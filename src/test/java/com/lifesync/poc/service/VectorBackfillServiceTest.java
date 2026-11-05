package com.lifesync.poc.service;

import com.lifesync.poc.graph.GraphRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VectorBackfillServiceTest {
    private final GraphRepository graph = mock(GraphRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);

    @Test void backfillsMissingMerchantVectorAndStoresFingerprint() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(graph.merchantsNeedingEmbeddings("nomic-embed-text", 5)).thenReturn(List.of(
                new GraphRepository.MerchantVectorSource("M1", "Demo Store", "Appliances",
                        "APPLIANCES", "Sells refrigerators", "sha256")));
        when(model.embed(anyString())).thenReturn(new float[768]);
        var service = new VectorBackfillService(graph, provider, true, "nomic-embed-text", 5);
        assertEquals(1, service.backfill(5));
        verify(graph).saveMerchantEmbedding(eq("M1"), eq("sha256"), eq("nomic-embed-text"),
                argThat(v -> v.size() == 768));
    }

    @Test void disabledBackfillCallsNoModels() {
        var service = new VectorBackfillService(graph, provider, false, "nomic-embed-text", 5);
        assertEquals(0, service.backfill(5));
        verifyNoInteractions(provider, graph);
    }
}
