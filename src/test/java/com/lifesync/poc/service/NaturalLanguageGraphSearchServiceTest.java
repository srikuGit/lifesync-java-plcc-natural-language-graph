package com.lifesync.poc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.domain.Models.GoalRow;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NaturalLanguageGraphSearchServiceTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatModel> chatProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<EmbeddingModel> embeddingProvider = mock(ObjectProvider.class);
    private final List<GoalRow> scoped = List.of(
            new GoalRow("G001", "Furnish my home office", "HOME_FURNISHINGS", "READY",
                    "SECRET-ECN", "Private Name", "Seattle", "", ""),
            new GoalRow("G002", "Buy a refrigerator", "APPLIANCES", "READY",
                    "SECRET-ECN", "Private Name", "Seattle", "", ""));

    private NaturalLanguageGraphSearchService service(boolean chatEnabled) {
        return new NaturalLanguageGraphSearchService(chatProvider, embeddingProvider,
                new ObjectMapper(), chatEnabled);
    }

    @Test void emptyQuestionShowsCustomerScopedGoalsAndStoresWithoutLlm() {
        var result = service(true).interpret("", scoped);
        assertEquals("STORES", result.mode());
        assertEquals(List.of("G001", "G002"), result.goalIds());
        verifyNoInteractions(chatProvider, embeddingProvider);
    }

    @Test void llmCanInterpretTypoButCannotInventMerchantFacts() {
        ChatModel chat=mock(ChatModel.class);
        when(chatProvider.getIfAvailable()).thenReturn(chat);
        when(chat.call(anyString())).thenReturn("{\"mode\":\"STORES\",\"goalIds\":[\"G001\"]}");
        var result=service(true).interpret("shoping for my hom offce", scoped);
        assertEquals("STORES", result.mode());
        assertEquals(List.of("G001"), result.goalIds());
        var prompt=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chat).call(prompt.capture());
        assertFalse(prompt.getValue().contains("SECRET-ECN"));
        assertFalse(prompt.getValue().contains("Private Name"));
        assertFalse(prompt.getValue().contains("Seattle"));
    }

    @Test void rejectsGoalIdsNotBelongingToCustomer() {
        ChatModel chat=mock(ChatModel.class);
        when(chatProvider.getIfAvailable()).thenReturn(chat);
        when(chat.call(anyString())).thenReturn("{\"mode\":\"STORES\",\"goalIds\":[\"G999\"]}");
        // Without embeddings available, fall back to displaying scoped goals, not G999.
        var result=service(true).interpret("show stores", scoped);
        assertEquals("GOALS", result.mode());
        assertEquals(List.of("G001", "G002"), result.goalIds());
    }

    @Test void overlyLongQuestionIsRejectedBeforeLlmCall() {
        assertThrows(IllegalArgumentException.class,
                () -> service(true).interpret("x".repeat(501), scoped));
        verifyNoInteractions(chatProvider);
    }

    @Test void cosineSimilarityProducesExpectedValues() {
        assertEquals(1.0, NaturalLanguageGraphSearchService.cosine(new float[]{1,0}, new float[]{2,0}), 1e-9);
        assertEquals(0.0, NaturalLanguageGraphSearchService.cosine(new float[]{1,0}, new float[]{0,1}), 1e-9);
    }
}
