package com.lifesync.poc.service;

import com.lifesync.poc.domain.Models.GoalDetail;
import com.lifesync.poc.graph.GraphRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticRetrievalServiceTest {
    private final GraphRepository graph = mock(GraphRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
    private final GoalDetail goal = new GoalDetail("G1", "Buy a refrigerator", "APPLIANCES",
            "PROCESSING", "ECN-1", "A Customer", "Seattle", 47.60, -122.33, "fingerprint");

    @Test void usesCachedVectorsAndGraphFilteredNeo4jSearch() {
        List<Double> vector = java.util.Collections.nCopies(768, 0.01);
        when(graph.goalCategoryEvidence("G1")).thenReturn(new GraphRepository.GoalCategoryEvidence(
                List.of("APPLIANCES","ELECTRONICS"),0.82,"EXPLICIT_AND_TEXT","fingerprint"));
        when(graph.cachedGoalEmbedding("G1", "fingerprint", "nomic-embed-text"))
                .thenReturn(Optional.of(vector));
        when(graph.vectorCandidateScores("G1", vector, 100, "nomic-embed-text")).thenReturn(Map.of("M1", 0.88));
        var semantic = new SemanticRetrievalService(graph, provider, true, "nomic-embed-text");
        assertEquals(Map.of("M1", 0.88), semantic.findSimilarEligibleMerchants(goal));
        verifyNoInteractions(provider);
    }

    @Test void embedsGoalOnceAndSavesVector() {
        when(graph.goalCategoryEvidence("G1")).thenReturn(new GraphRepository.GoalCategoryEvidence(
                List.of("APPLIANCES","ELECTRONICS"),0.82,"EXPLICIT_AND_TEXT","reclassified"));
        float[] embedding = new float[768];
        embedding[0] = 0.8f;
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.embed(anyString())).thenReturn(embedding);
        var semantic = new SemanticRetrievalService(graph, provider, true, "nomic-embed-text");
        assertEquals(Map.of(), semantic.findSimilarEligibleMerchants(goal));
        verify(graph).saveGoalEmbedding(eq("G1"), eq("reclassified"),
                eq("nomic-embed-text"), argThat(v -> v.size() == 768));
        verify(graph).vectorCandidateScores(eq("G1"), anyList(), eq(100), eq("nomic-embed-text"));
        verify(model).embed(org.mockito.ArgumentMatchers.<String>argThat(
                text -> text != null && text.contains("APPLIANCES, ELECTRONICS")));
    }

    @Test void gracefullyFallsBackOnModelFailure() {
        when(graph.goalCategoryEvidence("G1")).thenReturn(new GraphRepository.GoalCategoryEvidence(
                List.of("APPLIANCES"),0.95,"EXPLICIT_AND_TEXT","fingerprint"));
        when(provider.getIfAvailable()).thenThrow(new IllegalStateException("Ollama unavailable"));
        var semantic = new SemanticRetrievalService(graph, provider, true, "nomic-embed-text");
        assertTrue(semantic.findSimilarEligibleMerchants(goal).isEmpty());
    }

    @Test void rejectsWrongEmbeddingDimensions() {
        assertThrows(IllegalArgumentException.class, () -> SemanticRetrievalService.doubles(new float[384]));
        assertThrows(IllegalArgumentException.class, () -> SemanticRetrievalService.doubles(new float[] { Float.NaN }));
    }
}
