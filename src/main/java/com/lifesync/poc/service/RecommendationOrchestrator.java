package com.lifesync.poc.service;

import com.lifesync.poc.classification.CategoryCatalog;
import com.lifesync.poc.domain.Models.*;
import com.lifesync.poc.graph.GraphRepository;
import com.lifesync.poc.graph.GraphRepository.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/** Java-only retrieval orchestration. The chat LLM only drafts email wording after selection. */
@Service
public class RecommendationOrchestrator {
    private static final Logger log=LoggerFactory.getLogger(RecommendationOrchestrator.class);
    private final GraphRepository graph;
    private final DistanceService distance;
    private final EmailPreviewService emails;
    private final EmailExplanationService explanations;
    private final SemanticRetrievalService semantic;
    private final RetrievalDecisionService decisions;
    private final boolean schedulerEnabled;
    private final int batchSize;
    private final AtomicBoolean batchRunning=new AtomicBoolean();
    private volatile boolean appReady;

    public RecommendationOrchestrator(GraphRepository graph,DistanceService distance,
                  EmailPreviewService emails,EmailExplanationService explanations,
                  SemanticRetrievalService semantic,RetrievalDecisionService decisions,
                  @Value("${lifesync.scheduler-enabled:true}") boolean schedulerEnabled,
                  @Value("${lifesync.scheduler-batch-size:1}") int batchSize) {
        this.graph=graph;this.distance=distance;this.emails=emails;
        this.explanations=explanations;this.semantic=semantic;this.decisions=decisions;
        this.schedulerEnabled=schedulerEnabled;
        this.batchSize=Math.max(1,Math.min(batchSize,10));
    }
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {appReady=true;}
    @Scheduled(initialDelay=60000,fixedDelayString="${lifesync.scheduler-delay-ms:90000}")
    public void scheduledBatch() {if(schedulerEnabled && appReady) processNextBatch();}
    public List<ProcessingResult> processNextBatch() {
        if(!batchRunning.compareAndSet(false,true)) return List.of();
        try {
            graph.recoverInterruptedProcessing();
            return graph.claimPending(batchSize).stream().map(this::processClaimed).toList();
        } finally {batchRunning.set(false);}
    }
    public ProcessingResult processOne(String goalId) {
        if(!graph.claimOne(goalId)) {
            String status=graph.goal(goalId).map(GoalDetail::status).orElse("NOT_FOUND");
            return new ProcessingResult(goalId,status,0,"Not pending; no duplicate preview generated");
        }
        return processClaimed(goalId);
    }
    private ProcessingResult processClaimed(String goalId) {
        try {
            GoalDetail goal=graph.goal(goalId).orElseThrow(
                ()->new IllegalStateException("Customer-goal relationship missing"));
            if(!graph.hasPlccMerchantCategoryMatch(goalId)) {
                graph.markTerminal(goalId,"UNSUPPORTED","No matched PLCC program category with active merchant");
                return new ProcessingResult(goalId,"UNSUPPORTED",0,"No supported PLCC category match");
            }
            // STRICT business gate: UNKNOWN is not a prospect. Do not derive account ownership from AI.
            if(goal.plccNonHolder()) {
                graph.completeInvitation(goalId,goal.fingerprint(),emails.subjectInvitation(goal),goal.category());
                return new ProcessingResult(goalId,"READY",0,"PLCC invitation preview, no merchant disclosure");
            }
            if(!goal.plccHolder()) {
                graph.markTerminal(goalId,"ACCOUNT_UNVERIFIED","PLCC account status not verified");
                return new ProcessingResult(goalId,"ACCOUNT_UNVERIFIED",0,"No marketing preview generated");
            }
            if(goal.customerLat()==null || goal.customerLon()==null) {
                graph.markTerminal(goalId,"NO_LOCATION","Customer coordinates absent");
                return new ProcessingResult(goalId,"NO_LOCATION",0,"Customer location missing");
            }
            List<Candidate> candidates=graph.candidateMerchants(goalId);
            if(candidates.isEmpty()) {
                graph.markTerminal(goalId,"NO_MATCH","No active PLCC eligible merchants");
                return new ProcessingResult(goalId,"NO_MATCH",0,"No eligible merchants");
            }
            // Plan using the persisted multi-label classification and measured graph evidence.
            var classified=graph.goalCategoryEvidence(goalId);
            int uniqueMerchants=(int)candidates.stream().map(Candidate::merchantId).distinct().count();
            int uniqueStores=(int)candidates.stream().map(Candidate::storeId).distinct().count();
            var evidence=new RetrievalDecisionService.Evidence(classified.categories(),classified.confidence(),
                    classified.source(),uniqueMerchants,uniqueStores,semantic.isEnabled());
            // Java-only PLAN -> graph/vector RETRIEVE -> Java EVALUATE -> optional REFINE.
            var plan=decisions.plan(evidence);
            Map<String,Double> scores=new HashMap<>();
            if(plan.strategy().equals("HYBRID")) scores.putAll(semantic.findSimilarEligibleMerchants(goal));
            long strongCount=candidates.stream().map(Candidate::merchantId).distinct()
                    .filter(id->scores.getOrDefault(id,0.0)>=0.42d).count();
            var evaluation=plan.strategy().equals("HYBRID")
                    ? decisions.evaluate(evidence,(int)strongCount,1)
                    : new RetrievalDecisionService.Assessment(false,"GRAPH_ONLY_NO_VECTOR_EVALUATION","JAVA_POLICY");
            int attempts=1;
            if(plan.strategy().equals("HYBRID") && evaluation.retry()) {
                attempts=2;
                semantic.refineWithAlternateQuery(goal).forEach((id,score)->scores.merge(id,score,Math::max));
                int refinedStrong=(int)candidates.stream().map(Candidate::merchantId).distinct()
                        .filter(id->scores.getOrDefault(id,0.0)>=0.42d).count();
                evaluation=decisions.evaluate(evidence,refinedStrong,2);
            }
            // Semantic filtering only when sufficiently supported; category membership is always mandatory.
            List<Candidate> vectorEligible=candidates.stream()
                    .filter(c->scores.getOrDefault(c.merchantId(),0.0)>=0.42d).toList();
            boolean hybrid=plan.strategy().equals("HYBRID") && !vectorEligible.isEmpty();
            List<Candidate> eligible=hybrid?vectorEligible:candidates;
            // One store may serve several matched categories. Prefer the goal's most
            // relevant category (explicit primary first), not alphabetical Cypher order.
            Map<String,Candidate> byStore=new LinkedHashMap<>();
            eligible.forEach(c->byStore.merge(c.storeId(),c,(prior,next)->
                    categoryPosition(evidence.categories(),next.category())
                        < categoryPosition(evidence.categories(),prior.category()) ? next : prior));
            List<CandidateDistance> nearest=byStore.values().stream()
                    .filter(c->c.latitude()!=null && c.longitude()!=null)
                    .map(c->new CandidateDistance(c,distance.miles(goal.customerLat(),goal.customerLon(),
                            c.latitude(),c.longitude())))
                    .sorted(Comparator.comparingDouble(CandidateDistance::miles)
                            .thenComparing(cd->cd.candidate().storeId()))
                    .limit(5).toList();
            if(nearest.isEmpty()) {
                graph.markTerminal(goalId,"NO_LOCATION","Eligible stores have no coordinates");
                return new ProcessingResult(goalId,"NO_LOCATION",0,"Merchant locations missing");
            }
            List<MerchantMatch> matches=IntStream.range(0,nearest.size()).mapToObj(i->{
                Candidate c=nearest.get(i).candidate();
                String explanation="PLCC category " + CategoryCatalog.display(c.category())
                    + "; proximity based on demo coordinates.";
                return new MerchantMatch(c.merchantId(),c.storeId(),c.name(),c.category(),
                    c.description(),c.city(),c.state(),c.addressLine(),
                    Math.round(nearest.get(i).miles()*10d)/10d,i+1,explanation,
                    scores.get(c.merchantId()));
            }).toList();
            var explained=explanations.explain(goal,matches,evidence.categories());
            String retrievalMode=hybrid?"PLCC_GRAPH_AND_VECTOR":
                    (plan.strategy().equals("GRAPH")?"PLCC_GRAPH_ONLY":"PLCC_GRAPH_FALLBACK");
            graph.completeHolder(goalId,goal.fingerprint(),explained.introduction(),
                    explained.matches(),emails.subjectHolder(goal),retrievalMode,explained.source(),
                    plan.strategy()+" / "+plan.source()+" / "+plan.reason(),
                    evaluation.reason()+" / "+evaluation.source(),attempts);
            log.info("Goal {} prepared holder preview with {} stores via {}",goalId,matches.size(),retrievalMode);
            return new ProcessingResult(goalId,"READY",matches.size(),"Holder email preview ready");
        } catch(Exception ex) {
            log.error("PLCC recommendation failed for {}",goalId,ex);
            try {graph.markFailure(goalId,ex.getClass().getSimpleName());}
            catch(Exception err){log.error("Could not update goal status",err);}
            return new ProcessingResult(goalId,"FAILED_OR_RETRY_PENDING",0,"Processing failed");
        }
    }
    private static int categoryPosition(List<String> orderedCategories,String category) {
        int index=orderedCategories.indexOf(category);
        return index<0 ? Integer.MAX_VALUE : index;
    }
    private record CandidateDistance(Candidate candidate,double miles) {}
}
