package com.lifesync.poc.campaign;

import com.lifesync.poc.mcp.GraphMcpClient;
import com.lifesync.poc.product.CustomerSubgraph;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.lifesync.poc.campaign.CampaignModels.*;

/** Manual preview generation only, on a dedicated worker unrelated to chat request threads. */
@Service
@Order(20)
public class ManualCampaignService implements ApplicationRunner {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ManualCampaignService.class);
    private final CampaignRepository repository;
    private final GraphMcpClient mcp;
    private final CampaignPreviewComposer composer;
    private final AtomicBoolean busy=new AtomicBoolean();
    private boolean recovered;
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),
        task->{Thread t=new Thread(task,"manual-campaign");t.setDaemon(true);return t;});
    public ManualCampaignService(CampaignRepository repository,GraphMcpClient mcp,CampaignPreviewComposer composer) {
        this.repository=repository;this.mcp=mcp;this.composer=composer;
    }
    @Override public void run(ApplicationArguments args){recoverOnce();}
    private synchronized void recoverOnce() {
        if(!recovered){repository.recoverInterruptedRuns();recovered=true;}
    }
    public String start(int limit) {
        if(limit<1||limit>100)throw new IllegalArgumentException("Choose an audience limit from 1 to 100.");
        recoverOnce();
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("A campaign preview run is already in progress. Wait for it to finish.");
        String run=UUID.randomUUID().toString();
        try {
            List<String> audience=List.copyOf(new LinkedHashSet<>(repository.activeAudience(limit)));
            repository.createRun(run,audience);
            worker.submit(()->process(run,audience));
            return run;
        } catch(RuntimeException ex) {busy.set(false);throw ex;}
    }
    void process(String run,List<String> audience) {
        try {
            for(String customer:audience) {
                if(Thread.currentThread().isInterrupted())return;
                processCustomer(run,customer);
            }
            repository.finish(run);
        } catch(RuntimeException ex) {
            // Exception bodies may contain customer/network data; log only the run ID and exception class.
            log.warn("Campaign preview run failed: run={}, cause={}",run,ex.getClass().getSimpleName());
            try {
                repository.failRun(run,"Campaign execution stopped because a required service or persistence operation failed");
            } catch(RuntimeException persistenceFailure) {
                log.warn("Campaign failure status could not be persisted: run={}, cause={}",run,persistenceFailure.getClass().getSimpleName());
            }
        } finally {busy.set(false);}
    }
    void processCustomer(String run,String customer) {
        try {
            if(!repository.isActive(customer)) {repository.mark(run,customer,"SKIPPED_INELIGIBLE","PLCC account is no longer ACTIVE");return;}
            repository.mark(run,customer,"PROCESSING","");
            CustomerSubgraph graph=plccGraph(mcp.customerGraph(customer,"PLCC"),customer);
            if(graph.goals().isEmpty()){repository.mark(run,customer,"SKIPPED_NO_GOALS","No PLCC goals are available");return;}
            if(graph.goals().stream().anyMatch(g->"PLCC_ACCOUNT_RESTRICTED".equals(g.status()))) {
                repository.mark(run,customer,"SKIPPED_INELIGIBLE","MCP did not verify PLCC merchant access");return;
            }
            if(graph.goals().stream().flatMap(g->g.providers().stream()).allMatch(p->p.offers().isEmpty())) {
                repository.mark(run,customer,"SKIPPED_NO_OFFERS","No current eligible PLCC offers are available");return;
            }
            var copy=composer.compose(graph);
            // Eligibility can change while model generation is running.
            if(!repository.isActive(customer)){repository.mark(run,customer,"SKIPPED_INELIGIBLE","PLCC account changed during generation");return;}
            repository.ready(run,customer,copy.subject(),copy.introduction(),copy.source(),graph.goals().stream().map(CustomerSubgraph.Goal::goalId).toList());
        } catch(Exception ex) {
            repository.mark(run,customer,"FAILED","Preview generation failed; check graph/model service availability");
        }
    }
    public PreviewPage preview(String runId,String customerId) {
        Run run=repository.run(runId).orElseThrow(()->new NoSuchElementException("Campaign run not found."));
        Preview preview=repository.preview(runId,customerId).orElseThrow(()->new NoSuchElementException("Campaign preview not found."));
        if(!"READY".equals(preview.status()))throw new IllegalStateException("This customer preview is not ready.");
        CustomerSubgraph current=currentGraph(customerId);
        Set<String> selected=Set.copyOf(preview.goalIds());
        current=new CustomerSubgraph(current.customerId(),current.displayName(),current.goals().stream().filter(g->selected.contains(g.goalId())).toList());
        // No model call during rendering; offers are refreshed by MCP and never stored in campaign nodes.
        return new PreviewPage(run,preview,current);
    }
    public PreviewPage profilePreview(String customerId) {
        CustomerSubgraph graph=currentGraph(customerId);
        var copy=CampaignPreviewComposer.factualCopy();
        var preview=new Preview(null,null,customerId,"READY",copy.subject(),copy.introduction(),
            copy.source(),graph.goals().stream().map(CustomerSubgraph.Goal::goalId).toList(),null,"");
        return new PreviewPage(null,preview,graph);
    }
    private CustomerSubgraph currentGraph(String customerId) {
        if(!repository.isActive(customerId))throw new IllegalStateException("This customer no longer has an ACTIVE PLCC account. The preview is unavailable.");
        CustomerSubgraph current=plccGraph(mcp.customerGraph(customerId,"PLCC"),customerId);
        if(current.goals().stream().anyMatch(g->"PLCC_ACCOUNT_RESTRICTED".equals(g.status())))
            throw new IllegalStateException("PLCC merchant access is not currently verified. The preview is unavailable.");
        return current;
    }
    static CustomerSubgraph plccGraph(CustomerSubgraph graph,String customer) {
        if(graph==null||!customer.equals(graph.customerId()))throw new IllegalArgumentException("MCP customer scope mismatch");
        return new CustomerSubgraph(customer,graph.displayName(),graph.goals().stream().filter(g->"PLCC".equals(g.productId())).toList());
    }
    public List<Run> runs(){return repository.runs();}
    public Optional<Run> run(String id){return repository.run(id);}
    public List<Preview> previews(String id){return repository.previews(id);}
    public boolean busy(){return busy.get();}
    @PreDestroy public void close(){worker.shutdownNow();}
}
