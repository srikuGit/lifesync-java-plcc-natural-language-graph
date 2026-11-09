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
        when(chat.call(anyString())).thenReturn("{\"goalIds\":[\"G1\"],\"sort\":\"APR\",\"limit\":1}");
        var answer=service.answer(graph(),"Which auto provider has the lowest APR?");
        assertEquals("Far",answer.graph().goals().getFirst().providers().getFirst().name());
        var capture=org.mockito.ArgumentCaptor.forClass(String.class);verify(chat).call(capture.capture());
        assertFalse(capture.getValue().contains("PRIVATE-ID"));assertFalse(capture.getValue().contains("Private Person"));
        when(chat.call(anyString())).thenReturn("{\"goalIds\":[\"FOREIGN\"]}");
        assertEquals("GRAPH_FALLBACK",service.answer(graph(),"List goals").source());
    }
}
