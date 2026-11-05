package com.lifesync.poc.service;

import com.lifesync.poc.domain.Models.GoalDetail;
import com.lifesync.poc.graph.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** ANN retrieval in Neo4j, ALWAYS constrained by graph category eligibility in Cypher. */
@Service
public class SemanticRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(SemanticRetrievalService.class);
    public static final int DIMENSIONS = 768; // nomic-embed-text
    private final GraphRepository graph;
    private final ObjectProvider<EmbeddingModel> models;
    private final boolean enabled;
    private final String modelName;

    public SemanticRetrievalService(GraphRepository graph, ObjectProvider<EmbeddingModel> models,
                                    @Value("${lifesync.vector-enabled:true}") boolean enabled,
                                    @Value("${lifesync.vector-model:nomic-embed-text}") String modelName) {
        this.graph = graph;
        this.models = models;
        this.enabled = enabled;
        this.modelName = modelName;
    }

    public boolean isEnabled() { return enabled; }

    /** Empty map means graph-only fallback, NOT that no merchant is eligible. */
    public Map<String, Double> findSimilarEligibleMerchants(GoalDetail goal) {
        if (!enabled) return Map.of();
        try {
            var classified=graph.goalCategoryEvidence(goal.goalId());
            String cacheKey=classified.classificationFingerprint().isBlank()
                    ? goal.fingerprint() : classified.classificationFingerprint();
            String categories=classified.categories().isEmpty() ? goal.category()
                    : String.join(", ",classified.categories());
            var cached = graph.cachedGoalEmbedding(goal.goalId(), cacheKey, modelName);
            List<Double> vector;
            if (cached.isPresent()) {
                vector = cached.get();
            } else {
                EmbeddingModel model = models.getIfAvailable();
                if (model == null) return Map.of();
                String text = EmbeddingText.goal(goal.description(), categories);
                vector = doubles(model.embed(text));
                graph.saveGoalEmbedding(goal.goalId(), cacheKey, modelName, vector);
            }
            return graph.vectorCandidateScores(goal.goalId(), vector, 100, modelName);
        } catch (Exception e) {
            log.warn("Vector retrieval unavailable for goal {}: {}. Using graph-only matching.",
                    goal.goalId(), e.getMessage());
            return Map.of();
        }
    }

    /** One alternate semantic query, still checked by the exact PLCC graph relationships. */
    public Map<String,Double> refineWithAlternateQuery(GoalDetail goal) {
        if(!enabled) return Map.of();
        try {
            EmbeddingModel model=models.getIfAvailable();
            if(model==null) return Map.of();
            var classified=graph.goalCategoryEvidence(goal.goalId());
            String categories=classified.categories().isEmpty() ? goal.category()
                    : String.join(", ",classified.categories());
            String query="search_query: Stores selling goods useful for this purchase: "
                    + EmbeddingText.trim(goal.description(),320) + " in " + categories;
            return graph.vectorCandidateScores(goal.goalId(),doubles(model.embed(query)),100,modelName);
        } catch(Exception e) {
            log.warn("Alternate vector search unavailable for {}: {}",goal.goalId(),e.getClass().getSimpleName());
            return Map.of();
        }
    }

    public static List<Double> doubles(float[] values) {
        if (values == null || values.length != DIMENSIONS)
            throw new IllegalArgumentException("Expected 768-dimensional nomic-embed-text vector");
        List<Double> result = new ArrayList<>(values.length);
        for (float v : values) {
            if (!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite embedding");
            result.add((double) v);
        }
        return result;
    }
}
