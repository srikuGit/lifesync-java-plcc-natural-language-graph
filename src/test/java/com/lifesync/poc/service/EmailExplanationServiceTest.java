package com.lifesync.poc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.domain.Models.GoalDetail;
import com.lifesync.poc.domain.Models.MerchantMatch;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailExplanationServiceTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    private final GoalDetail goal = new GoalDetail("G1", "Buy a refrigerator", "APPLIANCES",
            "PROCESSING", "SECRET-ECN", "Private Customer", "Seattle", 47.6, -122.3, "fp");
    private final List<MerchantMatch> matches = List.of(new MerchantMatch("M1", "S1", "Demo Appliances",
            "APPLIANCES", "Sells refrigerators", "Seattle", "WA", "1 Demo St",
            2.0, 1, "Eligible appliances retailer", 0.9));

    @Test void feedsOnlyGraphFactsToLlmAndKeepsMerchantOrder() {
        ChatModel chat = mock(ChatModel.class);
        when(provider.getIfAvailable()).thenReturn(chat);
        when(chat.call(anyString())).thenReturn("""
                {"introduction":"Explore nearby shops related to your refrigerator goal.",
                 "merchants":[{"merchantId":"M1","reason":"Its description lists refrigerators for sale."}]}
                """);
        var service = new EmailExplanationService(provider, new ObjectMapper(), true);
        var result = service.explain(goal, matches);
        assertEquals("GRAPH_GROUNDED_LLM", result.source());
        assertEquals(0.9, result.matches().getFirst().semanticScore());
        assertEquals("M1", result.matches().getFirst().merchantId());
        var capture = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chat).call(capture.capture());
        assertFalse(capture.getValue().contains("SECRET-ECN"));
        assertFalse(capture.getValue().contains("Private Customer"));
    }

    @Test void multiCategoryGoalEvidenceIsPassedToLlm() {
        ChatModel chat=mock(ChatModel.class);
        when(provider.getIfAvailable()).thenReturn(chat);
        when(chat.call(anyString())).thenReturn("""
                {"introduction":"Explore nearby shops for your upcoming purchase.",
                 "merchants":[{"merchantId":"M1","reason":"The listed merchant sells refrigerators."}]}
                """);
        var service=new EmailExplanationService(provider,new ObjectMapper(),true);
        service.explain(goal,matches,List.of("APPLIANCES","ELECTRONICS"));
        var capture=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chat).call(capture.capture());
        assertTrue(capture.getValue().contains("goalCategories"));
        assertTrue(capture.getValue().contains("APPLIANCES"));
        assertTrue(capture.getValue().contains("ELECTRONICS"));
    }

    @Test void rejectsUnverifiedMerchantIdsAndFallsBack() {
        ChatModel chat = mock(ChatModel.class);
        when(provider.getIfAvailable()).thenReturn(chat);
        when(chat.call(anyString())).thenReturn("""
                {"introduction":"Explore nearby shops related to your refrigerator goal.",
                 "merchants":[{"merchantId":"FAKE","reason":"Guaranteed next day free delivery."}]}
                """);
        var service = new EmailExplanationService(provider, new ObjectMapper(), true);
        var result = service.explain(goal, matches);
        assertEquals("DETERMINISTIC_FALLBACK", result.source());
        assertEquals(matches, result.matches());
    }
}
