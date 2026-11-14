package com.lifesync.poc.api;

import com.lifesync.poc.graph.GraphRepository;
import com.lifesync.poc.service.RecommendationOrchestrator;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RecommendationApi {
    private final GraphRepository graph;
    private final RecommendationOrchestrator orchestrator;
    private final String embeddingModel;
    public RecommendationApi(GraphRepository graph, RecommendationOrchestrator orchestrator,
                             @Value("${lifesync.vector-model:nomic-embed-text}") String embeddingModel) {
        this.graph = graph;
        this.orchestrator = orchestrator;
        this.embeddingModel = embeddingModel;
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP", "service", "DEMO bank demo"); }

    @GetMapping("/vectors/status")
    public Object vectorStatus() { return graph.vectorStats(embeddingModel); }

    @GetMapping("/recommendations/{goalId}")
    public ResponseEntity<?> recommendation(@PathVariable String goalId) {
        return graph.goal(goalId).<ResponseEntity<?>>map(goal -> ResponseEntity.ok(Map.of(
                "goal", goal, "merchants", graph.recommendedMerchants(goalId),
                "generation", graph.recommendationMetadata(goalId).orElse(
                        new com.lifesync.poc.domain.Models.RecommendationMetadata("NOT_GENERATED", "NOT_GENERATED")),
                "emailPreviewReady", graph.preview(goalId).isPresent()))).orElseGet(
                () -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Goal not found")));
    }

    @PostMapping("/recommendations/generate")
    public Object generate(@RequestParam String goalId) {
        return orchestrator.processOne(goalId);
    }
}
