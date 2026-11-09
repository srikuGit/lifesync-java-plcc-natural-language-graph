package com.lifesync.poc.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GraphQuestionServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    @SuppressWarnings("unchecked") final ObjectProvider<ChatModel> models=mock(ObjectProvider.class);
    final GraphQuestionService service=new GraphQuestionService(models,mapper,true);
    org.springframework.ai.chat.model.ChatResponse response(String json) {
        return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
            new org.springframework.ai.chat.messages.AssistantMessage(json))));
    }
    CustomerSubgraph.Provider provider(String name,double distance,double apr) {
        return new CustomerSubgraph.Provider(name,name,name,"Seattle","WA",distance,List.of(
            new ProductOfferCatalog.Offer(name,"AUTO_LOAN",name,"PRIVATE-ID","Offer","Terms","ACTIVE",
                LocalDate.of(2026,1,1),LocalDate.of(2030,1,1),null,apr,500.,60)));
    }
    CustomerSubgraph graph() {return new CustomerSubgraph("PRIVATE-ID","Private Person",List.of(
        new CustomerSubgraph.Goal("G1","Buy a car","AUTO_LOAN","Auto loans",List.of("AUTO_LOAN"),"MATCHED",
            List.of(provider("Nearby",1,7),provider("LowerRate",3,5),provider("Far",20,4)))));}
    @Test void appliesDistanceRateFiltersComparisonAndCountWithoutRetrieval() throws Exception {
        var result=service.execute(graph(),mapper.readTree("""
            {"goalIds":["G1"],"sort":"APR","maxDistanceMiles":10,"maxApr":6,"offersOnly":true,"limit":5}
            """));
        assertEquals(1,result.providerCount());assertEquals(1,result.goalCount());
        assertEquals("LowerRate",result.graph().goals().getFirst().providers().getFirst().name());
    }
    @Test void rejectsForeignGoalsNegativeFiltersAndUnsupportedQueryFields() throws Exception {
        for(String json:List.of("{\"goalIds\":[\"FOREIGN\"]}","{\"goalIds\":[\"G1\"],\"maxApr\":-1}",
            "{\"goalIds\":[\"G1\"],\"cypher\":\"MATCH (n)\"}","{\"goalIds\":[\"G1\"],\"limit\":999999999999}")) {
            var plan=mapper.readTree(json);assertThrows(IllegalArgumentException.class,()->service.execute(graph(),plan));
        }
    }
    @Test void llmSeesOnlyScopedMetadataAndFailureKeepsOriginalGraph() {
        var chat=mock(ChatModel.class);when(models.getIfAvailable()).thenReturn(chat);
        when(chat.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response("{\"goalIds\":[\"G1\"],\"sort\":\"APR\",\"limit\":1}"));
        var answer=service.answer(graph(),"Which auto provider has the lowest APR?");
        assertEquals("Far",answer.graph().goals().getFirst().providers().getFirst().name());
        var capture=org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);verify(chat).call(capture.capture());
        assertFalse(capture.getValue().toString().contains("PRIVATE-ID"));assertFalse(capture.getValue().toString().contains("Private Person"));
        when(chat.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response("{\"goalIds\":[\"FOREIGN\"]}"));
        assertEquals("GRAPH_FALLBACK",service.answer(graph(),"List goals").source());
    }
    @Test void structuredFormatConstrainsGoalIdsAndSortValues() {
        var schema=service.schema(graph());
        assertEquals(false,schema.get("additionalProperties"));
        String json=mapper.valueToTree(schema).toString();
        assertTrue(json.contains("G1"));assertFalse(json.contains("PRIVATE-ID"));
        assertTrue(json.contains("DISCOUNT"));
    }
    @Test void acceptsFencedJsonButRejectsTrailingObjectsAndDuplicateFields() throws Exception {
        assertEquals("G1",service.parsePlan("```json\n{\"goalIds\":[\"G1\"]}\n```").path("goalIds").get(0).asText());
        assertThrows(Exception.class,()->service.parsePlan("{\"goalIds\":[]} {\"goalIds\":[\"FOREIGN\"]}"));
        assertThrows(Exception.class,()->service.parsePlan("{\"goalIds\":[],\"goalIds\":[\"FOREIGN\"]}"));
    }
    @Test void reportsDistinctDisabledMissingModelAndTimeoutCauses() {
        var disabled=new GraphQuestionService(models,mapper,false);
        assertTrue(disabled.answer(graph(),"Show goals").message().contains("AI_DISABLED"));
        var chat=mock(ChatModel.class);when(models.getIfAvailable()).thenReturn(chat);
        when(chat.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenThrow(new IllegalStateException("model 'qwen' not found"));
        assertTrue(service.answer(graph(),"Show goals").message().contains("MODEL_NOT_FOUND"));
        when(chat.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenThrow(new RuntimeException(new java.util.concurrent.TimeoutException()));
        assertTrue(service.answer(graph(),"Show goals").message().contains("MODEL_TIMEOUT"));
    }

}
