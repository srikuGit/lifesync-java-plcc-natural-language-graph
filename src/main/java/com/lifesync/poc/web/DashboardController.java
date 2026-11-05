package com.lifesync.poc.web;

import com.lifesync.poc.domain.Models.Customer;
import com.lifesync.poc.graph.GraphRepository;
import com.lifesync.poc.service.RecommendationOrchestrator;
import com.lifesync.poc.service.VectorBackfillService;
import com.lifesync.poc.service.EmailPreviewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class DashboardController {
    private final GraphRepository graph;
    private final RecommendationOrchestrator orchestrator;
    private final VectorBackfillService vectorBackfill;
    private final String embeddingModel;
    private final EmailPreviewService emails;
    public DashboardController(GraphRepository graph, RecommendationOrchestrator orchestrator,
                               VectorBackfillService vectorBackfill, EmailPreviewService emails,
                               @Value("${lifesync.vector-model:nomic-embed-text}") String embeddingModel) {
        this.graph = graph;
        this.orchestrator = orchestrator;
        this.vectorBackfill = vectorBackfill;
        this.embeddingModel = embeddingModel;
        this.emails=emails;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("counts", graph.counts());
        model.addAttribute("vectors", graph.vectorStats(embeddingModel));
        model.addAttribute("goals", graph.dashboardGoals(150));
        return "dashboard";
    }

    @PostMapping("/process/next")
    public String processBatch(RedirectAttributes redirect) {
        var results = orchestrator.processNextBatch();
        redirect.addFlashAttribute("notice", "Processed " + results.size() + " pending goals."
                + (results.isEmpty() ? " No pending goals available or a batch is already running." : ""));
        return "redirect:/";
    }

    @PostMapping("/vectors/backfill")
    public String backfill(RedirectAttributes redirect) {
        int count = vectorBackfill.backfill(5);
        redirect.addFlashAttribute("notice", "Embedded " + count + " eligible merchants."
                + (count == 0 ? " They may already be indexed, or Ollama may be unavailable." : ""));
        return "redirect:/";
    }

    @PostMapping("/goals/{goalId}/requeue")
    public String requeue(@PathVariable String goalId, RedirectAttributes redirect) {
        boolean queued = graph.requeueForDemo(goalId);
        redirect.addFlashAttribute("notice", queued ? "Goal queued for regeneration."
                : "Goal could not be queued while processing.");
        return "redirect:/goals/" + goalId;
    }

    @GetMapping("/customers")
    public String customers(@RequestParam(required = false) String ecn, Model model) {
        List<Customer> demos = graph.demoCustomers(5);
        model.addAttribute("demoCustomers", demos);
        if (ecn != null && !ecn.isBlank()) {
            graph.customerByEcn(ecn.trim()).ifPresentOrElse(customer -> {
                model.addAttribute("customer", customer);
                model.addAttribute("goals", graph.goalsForCustomer(customer.ecn()));
            }, () -> model.addAttribute("error", "No demo customer found for that ECN."));
        }
        return "customers";
    }

    @GetMapping("/goals/{goalId}")
    public String goal(@PathVariable String goalId, Model model) {
        var goal = graph.goal(goalId);
        if (goal.isEmpty()) return "redirect:/customers";
        model.addAttribute("goal", goal.get());
        model.addAttribute("goalClassification", graph.goalCategoryEvidence(goalId));
        model.addAttribute("merchants", graph.recommendedMerchants(goalId));
        graph.recommendationMetadata(goalId).ifPresent(meta -> model.addAttribute("retrieval", meta));
        return "goal";
    }

    @PostMapping("/goals/{goalId}/generate")
    public String generate(@PathVariable String goalId, RedirectAttributes redirect) {
        var result = orchestrator.processOne(goalId);
        redirect.addFlashAttribute("notice", "Goal " + result.goalId() + ": " + result.status());
        return "redirect:/goals/" + goalId;
    }

    @GetMapping("/emails/{goalId}")
    public String email(@PathVariable String goalId, Model model) {
        var preview = graph.preview(goalId);
        if (preview.isEmpty()) return "redirect:/goals/" + goalId;
        model.addAttribute("preview", emails.renderLatest(goalId));
        return "email-preview";
    }

    @GetMapping("/graph/{goalId}")
    public String graph(@PathVariable String goalId, Model model) {
        model.addAttribute("goalId", goalId);
        var goal=graph.goal(goalId);
        if(goal.isEmpty()) return "redirect:/customers";
        String safe=goalId.replaceAll("[^A-Za-z0-9_-]", "");
        String cypher;
        if(goal.get().plccHolder()) {
            cypher="MATCH (c:Customer {plccAccountStatus:'ACTIVE'})-[r1:HAS_GOAL]->"
                +"(g:Goal {goalId:'"+safe+"'})-[r2:CLASSIFIED_AS]->(cat:Category) "
                +"<-[r3:SUPPORTS_CATEGORY]-(p:PLCC) "
                +"MATCH (m:Merchant)-[r4:OFFERS_CATEGORY]->(cat) "
                +"MATCH (m)-[r5:HAS_STORE]->(s:Store) "
                +"RETURN c,r1,g,r2,cat,r3,p,r4,m,r5,s LIMIT 30;";
        } else {
            // Never surface merchant nodes or stores on a prospect's graph page.
            cypher="MATCH (c:Customer)-[r1:HAS_GOAL]->(g:Goal {goalId:'"+safe
                +"'})-[r2:CLASSIFIED_AS]->(cat:Category) "
                +"<-[r3:SUPPORTS_CATEGORY]-(p:PLCC) RETURN c,r1,g,r2,cat,r3,p LIMIT 30;";
        }
        model.addAttribute("cypher",cypher);
        return "graph";
    }
}
