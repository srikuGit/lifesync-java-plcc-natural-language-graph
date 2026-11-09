package com.lifesync.poc.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.mcp.GraphMcpClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomerExperienceServiceTest {
    private final GraphMcpClient client=mock(GraphMcpClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatModel> models=mock(ObjectProvider.class);
    private final ChatModel chat=mock(ChatModel.class);
    private final CustomerSubgraph graph=new CustomerSubgraph("PRIVATE-ID","Private Name",List.of(
        new CustomerSubgraph.Goal("G1","Buy a home","PLCC","PLCC shopping",
            List.of("HOME_PURCHASE"),"MATCHED",List.of(
                new CustomerSubgraph.Provider("M1","S1","Demo Lender","Seattle","WA",1.,List.of(
                    new ProductOfferCatalog.Offer("O1","HOME_PURCHASE","M1","PRIVATE-ID","Demo offer","Illustrative terms",
                        "ACTIVE",java.time.LocalDate.of(2026,1,1),java.time.LocalDate.of(2030,1,1),null,6.,100.,360)))))));
    @Test void usesMcpAndOnlyCustomerSubgraphForPrompt() {
        when(client.customerGraph("PRIVATE-ID","PLCC")).thenReturn(graph);
        when(models.getIfAvailable()).thenReturn(chat);
        when(chat.call(anyString())).thenReturn("[{\"goalId\":\"G1\",\"text\":\"Explore nearby home lending providers for your purchase goal.\"}]");
        var service=new CustomerExperienceService(client,models,new ObjectMapper(),true);
        var page=service.compose(service.load("PRIVATE-ID","").graph());
        assertEquals("GRAPH_GROUNDED_LLM",page.source());
        assertEquals(graph,page.graph());
        verify(client).customerGraph("PRIVATE-ID","PLCC");
        var prompt=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chat).call(prompt.capture());
        assertFalse(prompt.getValue().contains("Private Name"));
        assertFalse(prompt.getValue().contains("PRIVATE-ID"));
        assertTrue(prompt.getValue().contains("Demo Lender"));
    }
    @Test void invalidForeignIdsInventedNumbersAndModelFailuresUseFacts() {
        when(models.getIfAvailable()).thenReturn(chat);
        var service=new CustomerExperienceService(client,models,new ObjectMapper(),true);
        for (String response:List.of(
                "[{\"goalId\":\"FOREIGN\",\"text\":\"A different customer's goal.\"}]",
                "[{\"goalId\":\"G1\",\"text\":\"Your rate is 1% guaranteed.\"}]",
                "not json")) {
            when(chat.call(anyString())).thenReturn(response);
            var page=service.compose(graph);
            assertEquals("FACTUAL_TEMPLATE",page.source());
            assertEquals(graph,page.graph());
        }
        when(chat.call(anyString())).thenThrow(new IllegalStateException("Unavailable"));
        assertEquals("FACTUAL_TEMPLATE",service.compose(graph).source());
    }
    @Test void initialLoadNeverWaitsForLlmEvenWhenEnabled() {
        when(client.customerGraph("PRIVATE-ID", "PLCC")).thenReturn(graph);
        var page=new CustomerExperienceService(client,models,new ObjectMapper(),true).load("PRIVATE-ID", "");
        assertEquals("FACTUAL_TEMPLATE",page.source());
        verifyNoInteractions(models);
    }
    @Test void uiRequestsOnlyPlccAndFiltersUnexpectedOtherProducts() {
        var foreign=new CustomerSubgraph.Goal("LOAN","Buy a home","HOME_PURCHASE","Home purchase",List.of("HOME_PURCHASE"),"MATCHED",List.of());
        when(client.customerGraph("PRIVATE-ID","PLCC")).thenReturn(new CustomerSubgraph("PRIVATE-ID","Person",List.of(graph.goals().getFirst(),foreign)));
        var result=new CustomerExperienceService(client,models,new ObjectMapper(),false).load("PRIVATE-ID","");
        assertEquals(1,result.graph().goals().size());
        assertEquals("PLCC",result.graph().goals().getFirst().productId());
        verify(client).customerGraph("PRIVATE-ID","PLCC");
    }
    @Test void disabledLlmStillLoadsMcpAndRendersAllGoals() {
        when(client.customerGraph("PRIVATE-ID","PLCC")).thenReturn(graph);
        var page=new CustomerExperienceService(client,models,new ObjectMapper(),false).load("PRIVATE-ID","");
        assertEquals(1,page.graph().goals().size());
        assertEquals("FACTUAL_TEMPLATE",page.source());
        verifyNoInteractions(models);
    }
}
