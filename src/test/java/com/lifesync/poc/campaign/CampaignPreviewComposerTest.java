package com.lifesync.poc.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.product.CustomerSubgraph;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CampaignPreviewComposerTest {
    @SuppressWarnings("unchecked") final ObjectProvider<ChatModel> models=mock(ObjectProvider.class);
    final ChatModel chat=mock(ChatModel.class);
    final CustomerSubgraph graph=new CustomerSubgraph("PRIVATE-ID","Private Name",List.of(new CustomerSubgraph.Goal("G1","Buy a desk","PLCC","Shopping",List.of("HOME_FURNISHINGS"),"MATCHED",List.of())));
    ChatResponse response(String text){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
    @Test void optionalCopyUsesOnlyGoalTopicsAndIsBoundedPlainText() {
        when(models.getIfAvailable()).thenReturn(chat);
        when(chat.call(any(Prompt.class))).thenReturn(response("{\"introduction\":\"Explore nearby merchants related to your home office plans.\"}"));
        var composer=new CampaignPreviewComposer(models,new ObjectMapper(),true,5);
        try {
            var copy=composer.compose(graph);assertEquals("GRAPH_GROUNDED_LLM",copy.source());
            var capture=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(chat).call(capture.capture());
            assertFalse(capture.getValue().toString().contains("PRIVATE-ID"));assertFalse(capture.getValue().toString().contains("Private Name"));
            assertTrue(capture.getValue().toString().contains("Buy a desk"));
        } finally {composer.close();}
    }
    @Test void disabledOrInventedFinancialCopyUsesFactualFallback() {
        var disabled=new CampaignPreviewComposer(models,new ObjectMapper(),false,5);
        try {assertEquals("FACTUAL_TEMPLATE",disabled.compose(graph).source());verifyNoInteractions(models);} finally {disabled.close();}
        when(models.getIfAvailable()).thenReturn(chat);
        when(chat.call(any(Prompt.class))).thenReturn(response("{\"introduction\":\"You are approved for a guaranteed 50% discount.\"}"));
        var composer=new CampaignPreviewComposer(models,new ObjectMapper(),true,5);
        try {assertEquals("FACTUAL_TEMPLATE",composer.compose(graph).source());} finally {composer.close();}
    }
}
