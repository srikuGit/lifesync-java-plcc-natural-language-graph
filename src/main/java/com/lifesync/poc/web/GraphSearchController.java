package com.lifesync.poc.web;

import com.lifesync.poc.domain.Models.Customer;
import com.lifesync.poc.domain.Models.GoalRow;
import com.lifesync.poc.domain.Models.MerchantMatch;
import com.lifesync.poc.graph.GraphRepository;
import com.lifesync.poc.graph.GraphRepository.Candidate;
import com.lifesync.poc.service.DistanceService;
import com.lifesync.poc.service.NaturalLanguageGraphSearchService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Query a CUSTOMER-SCOPED graph via allow-listed Java operations; never run LLM Cypher. */
@Controller
public class GraphSearchController {
    private final GraphRepository graph;
    private final NaturalLanguageGraphSearchService queries;
    private final DistanceService distances;

    public GraphSearchController(GraphRepository graph, NaturalLanguageGraphSearchService queries,
                                 DistanceService distances) {
        this.graph = graph;
        this.queries = queries;
        this.distances = distances;
    }

    public record GoalResult(GoalRow goal, List<MerchantMatch> merchants, String matchSource) {}

    @GetMapping("/graph-search")
    public String search(@RequestParam(required = false) String customerId,
                         @RequestParam(required = false) String question, Model model) {
        model.addAttribute("customerIdInput", customerId == null ? "" : customerId);
        model.addAttribute("questionInput", question == null ? "" : question);
        model.addAttribute("examples", graph.demoCustomers(5));
        if (customerId == null || customerId.isBlank()) return "graph-search";
        String id = customerId.strip();
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
            model.addAttribute("error", "Enter a valid customer ID (letters, digits, - or _).");
            return "graph-search";
        }
        Optional<Customer> found = graph.customerById(id);
        if (found.isEmpty()) {
            model.addAttribute("error", "No demo customer was found for that customer ID.");
            return "graph-search";
        }
        Customer customer = found.get();
        model.addAttribute("customer", customer);
        List<GoalRow> all = graph.goalsForCustomer(customer.ecn());
        model.addAttribute("allGoals", all);
        NaturalLanguageGraphSearchService.SearchPlan plan;
        try {
            plan = queries.interpret(question, all);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
            return "graph-search";
        }
        model.addAttribute("plan", plan);
        Map<String,GoalRow> owned = new LinkedHashMap<>();
        all.forEach(g -> owned.put(g.goalId(), g));
        boolean showStores = "STORES".equals(plan.mode());
        List<GoalResult> results = plan.goalIds().stream()
                .filter(owned::containsKey)
                .map(goalId -> {
                    GoalRow goal = owned.get(goalId);
                    if (!showStores) return new GoalResult(goal, List.of(), "GOALS_ONLY");
                    // Security: non-holders and unverified customers must not see merchant info.
                    if (!customer.plccHolder()) return new GoalResult(goal, List.of(), "ACCOUNT_RESTRICTED");
                    List<MerchantMatch> saved = graph.recommendedMerchants(goalId);
                    if (!saved.isEmpty()) return new GoalResult(goal, saved, "SAVED_RECOMMENDATIONS");
                    // Allow exploration before the scheduler has completed this goal.
                    // Only active, PLCC-supported category graph candidates can appear here.
                    if (customer.latitude() == null || customer.longitude() == null)
                        return new GoalResult(goal, List.of(), "NO_CUSTOMER_LOCATION");
                    var dedup = new LinkedHashMap<String,Candidate>();
                    for (Candidate c : graph.candidateMerchants(goalId)) {
                        if (c.latitude() != null && c.longitude() != null)
                            dedup.putIfAbsent(c.storeId(), c);
                    }
                    var closest = dedup.values().stream()
                            .sorted(Comparator.comparingDouble(c -> distances.miles(
                                    customer.latitude(), customer.longitude(), c.latitude(), c.longitude())))
                            .limit(5).toList();
                    List<MerchantMatch> candidates = java.util.stream.IntStream.range(0, closest.size())
                            .mapToObj(i -> {
                                Candidate c = closest.get(i);
                                return new MerchantMatch(c.merchantId(), c.storeId(), c.name(),
                                        c.category(), c.description(), c.city(), c.state(), c.addressLine(),
                                        distances.miles(customer.latitude(), customer.longitude(),
                                                c.latitude(), c.longitude()), i + 1,
                                        "Graph category match; preliminary candidate, not a finalized recommendation.", null);
                            }).toList();
                    return new GoalResult(goal, candidates, "LIVE_GRAPH_CANDIDATES");
                }).toList();
        model.addAttribute("results", results);
        model.addAttribute("merchantDisclosureAllowed", customer.plccHolder());
        return "graph-search";
    }
}
