package com.lifesync.poc.web;
import com.lifesync.poc.product.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CustomerExperienceControllerTest {
    final CustomerExperienceService experience=mock(CustomerExperienceService.class);
    final GraphQuestionService questions=mock(GraphQuestionService.class);
    final CustomerSubgraph graph=new CustomerSubgraph("C001","Alex",List.of());
    final CustomerExperienceService.Page page=new CustomerExperienceService.Page(graph,Map.of(),"FACTUAL_TEMPLATE");
    MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new CustomerExperienceController(experience,questions)).setViewResolvers((name,locale)->new org.springframework.web.servlet.view.json.MappingJackson2JsonView()).build();}
    @Test void initialPageLoadsAllProductsWithoutModelOrQuestionWork() throws Exception {
        when(experience.load("C001","")).thenReturn(page);
        mvc.perform(get("/experience").param("customerId","C001").param("productId","PLCC"))
            .andExpect(status().isOk()).andExpect(view().name("experience"));
        verify(experience).load("C001","");verify(experience,never()).compose(any());verifyNoInteractions(questions);
    }
    @Test void questionReusesLoadedSnapshotWithoutAnotherMcpRetrieval() throws Exception {
        var session=new MockHttpSession();
        session.setAttribute(CustomerExperienceController.SNAPSHOT,new CustomerExperienceController.Snapshot(graph,System.currentTimeMillis()));
        when(experience.factual(graph)).thenReturn(page);
        when(questions.answer(graph,"Show home lending goals")).thenReturn(new GraphQuestionService.Answer(graph,"Results","MCP_GRAPH_QUERY",0,0,0));
        mvc.perform(post("/experience/question").session(session).contentType("application/json")
            .content("{\"customerId\":\"C001\",\"question\":\"Show home lending goals\"}"))
            .andExpect(status().isOk()).andExpect(view().name("fragments/customer-goals :: results"));
        verify(experience,never()).load(anyString(),anyString());
        verify(questions).answer(graph,"Show home lending goals");
    }
    @Test void mismatchedCustomerAndExpiredSnapshotsCannotBeQueried() throws Exception {
        var session=new MockHttpSession();
        session.setAttribute(CustomerExperienceController.SNAPSHOT,new CustomerExperienceController.Snapshot(graph,System.currentTimeMillis()));
        mvc.perform(post("/experience/question").session(session).contentType("application/json")
            .content("{\"customerId\":\"C002\",\"question\":\"Show goals\"}")).andExpect(status().isConflict());
        session.setAttribute(CustomerExperienceController.SNAPSHOT,new CustomerExperienceController.Snapshot(graph,0));
        mvc.perform(post("/experience/question").session(session).contentType("application/json")
            .content("{\"customerId\":\"C001\",\"question\":\"Show goals\"}")).andExpect(status().isConflict());
        verifyNoInteractions(questions,experience);
    }
}
