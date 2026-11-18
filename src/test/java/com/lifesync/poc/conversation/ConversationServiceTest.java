package com.lifesync.poc.conversation;
import com.lifesync.poc.product.*;
import com.lifesync.poc.mcp.GraphMcpClient;
import org.junit.jupiter.api.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ConversationServiceTest {
    final GraphMcpClient mcp=mock(GraphMcpClient.class);
    final ConversationPlanner planner=mock(ConversationPlanner.class);
    final ConversationService service=new ConversationService(mcp,planner);
    final CustomerProfile profile=new CustomerProfile("C1","Alex","Walker","Seattle","WA","ACTIVE");
    final CustomerSubgraph.Goal plcc=new CustomerSubgraph.Goal("G1","Furniture","PLCC","PLCC",List.of("HOME_FURNISHINGS"),"GOAL_ONLY",List.of());
    final CustomerSubgraph.Goal loan=new CustomerSubgraph.Goal("G2","Buy a home","HOME_PURCHASE","Home lending",List.of("HOME_PURCHASE"),"GOAL_ONLY",List.of());
    final CustomerSubgraph goals=new CustomerSubgraph("C1","Alex Walker",List.of(plcc,loan));
    @BeforeEach void setup(){when(mcp.customerProfile("C1")).thenReturn(profile);when(mcp.customerGoals("C1")).thenReturn(goals);}
    ConversationPlanner.Plan plan(String action,List<String> ids){return new ConversationPlanner.Plan(action,ids,false,null,null,"DISTANCE",false,5,"");}
    CustomerSubgraph details(){
        var offer=new ProductOfferCatalog.Offer("O1","PLCC","M1",null,"Discount","Terms","ACTIVE",LocalDate.of(2026,1,1),LocalDate.of(2030,1,1),20.,null,null,null);
        var provider=new CustomerSubgraph.Provider("M1","S1","Cedar Furnishings","Seattle","WA",2.,List.of(offer));
        return new CustomerSubgraph("C1","Alex Walker",List.of(new CustomerSubgraph.Goal("G1","Furniture","PLCC","PLCC",plcc.categories(),"MATCHED",List.of(provider))));
    }
    @Test void startingCustomerFetchesProfileOnly(){assertEquals(profile,service.start("C1").profile());verify(mcp,never()).customerGoals(anyString());verifyNoInteractions(planner);}
    @Test void showGoalsListsAllProductsWithoutMerchantsOrOffers() throws Exception {
        var state=service.start("C1");when(planner.plan(eq(state),eq(goals),anyString())).thenReturn(plan("GOALS",List.of("G1","G2")));
        var reply=service.turn(state,"Show my goals");assertEquals(2,reply.graph().goals().size());assertFalse(reply.showProviders());
        assertTrue(reply.graph().goals().stream().allMatch(g->g.providers().isEmpty()));assertEquals(1,reply.toolCalls());
        verify(mcp,never()).plccMerchants(anyString(),anyList(),any(),any(),anyInt());
    }
    @Test void whereToBuyFurnitureFindsShopsWithoutTheModel() {
        var furnishings=new CustomerSubgraph.Goal("G1","Redo the living room","PLCC","PLCC",
            List.of("HOME_FURNISHINGS"),"GOAL_ONLY",List.of());
        var electronics=new CustomerSubgraph.Goal("G3","New television","PLCC","PLCC",
            List.of("ELECTRONICS"),"GOAL_ONLY",List.of());
        when(mcp.customerGoals("C1")).thenReturn(new CustomerSubgraph("C1","Alex Walker",
            List.of(furnishings,loan,electronics)));
        when(mcp.plccMerchants("C1",List.of("G1"),null,null,5)).thenReturn(details());
        var offlinePlanner=new ConversationPlanner(null,new com.fasterxml.jackson.databind.ObjectMapper(),false);
        var assistant=new ConversationService(mcp,offlinePlanner);

        var reply=assistant.turn(assistant.start("C1"),"where can i buy furniture");

        assertEquals("MCP_PLCC_RESULTS",reply.source());
        assertTrue(reply.showProviders());
        assertEquals("Cedar Furnishings",reply.graph().goals().getFirst().providers().getFirst().name());
        verify(mcp).plccMerchants("C1",List.of("G1"),null,null,5);
    }
    @Test void nearbyShopsWithExplicitRadiusSearchesAllPlccGoalsWithoutTheModel() {
        var planner=new ConversationPlanner(null,new com.fasterxml.jackson.databind.ObjectMapper(),false);
        var assistant=new ConversationService(mcp,planner);
        var state=assistant.start("C1");
        when(mcp.plccMerchants("C1",List.of("G1"),Math.nextDown(5d),null,5)).thenReturn(details());

        var reply=assistant.turn(state,"shops near me less than 5 miles");

        assertEquals("MCP_PLCC_RESULTS",reply.source());
        assertEquals("Cedar Furnishings",reply.graph().goals().getFirst().providers().getFirst().name());
        verify(mcp).plccMerchants("C1",List.of("G1"),Math.nextDown(5d),null,5);
    }
    @Test void merchantRequestCannotRetrieveLoanProviders() throws Exception {
        var state=service.start("C1");when(planner.plan(eq(state),eq(goals),anyString())).thenReturn(plan("MERCHANTS",List.of("G2")));
        var reply=service.turn(state,"Find lenders for my home goal");assertEquals("PRODUCT_POLICY",reply.source());
        verify(mcp,never()).plccMerchants(anyString(),anyList(),any(),any(),anyInt());
    }
    @Test void followupPreservesOfferFiltersAndSortAndUsesBoundedTools() throws Exception {
        var state=service.start("C1");when(mcp.plccMerchants(eq("C1"),eq(List.of("G1")),any(),any(),eq(5))).thenReturn(details());
        when(planner.plan(eq(state),eq(goals),eq("Top one shop with discounts"))).thenReturn(new ConversationPlanner.Plan("OFFERS",List.of("G1"),false,null,15.,"DISCOUNT",true,1,""));
        assertEquals(2,service.turn(state,"Top one shop with discounts").toolCalls());
        when(planner.plan(eq(state),eq(goals),eq("Within five miles"))).thenReturn(new ConversationPlanner.Plan("MERCHANTS",List.of(),true,5.,null,null,null,null,""));
        var result=service.turn(state,"Within five miles");assertTrue(result.toolCalls()<=3);
        verify(mcp).plccMerchants("C1",List.of("G1"),5.,15.,5);
        assertEquals("DISCOUNT",state.previous.sort());assertTrue(state.previous.offersOnly());assertEquals(1,state.previous.limit());
    }
    @Test void generalShopRequestIgnoresUnrequestedModelLimitAndNamedGoalResetsPreviousFilters() throws Exception {
        var state=service.start("C1");
        var first=details().goals().getFirst().providers().getFirst();
        var providers=List.of(first,new CustomerSubgraph.Provider("M2","S2","Second shop","Seattle","WA",3.,first.offers()),
            new CustomerSubgraph.Provider("M3","S3","Third shop","Seattle","WA",4.,first.offers()));
        var found=new CustomerSubgraph("C1","Alex Walker",List.of(new CustomerSubgraph.Goal("G1","Furniture","PLCC","PLCC",plcc.categories(),"MATCHED",providers)));
        when(mcp.plccMerchants(eq("C1"),eq(List.of("G1")),any(),any(),eq(5))).thenReturn(found);
        String question="Shops that can help me with Furniture";
        when(planner.plan(eq(state),eq(goals),eq(question))).thenReturn(
            new ConversationPlanner.Plan("MERCHANTS",List.of("G1"),false,null,null,"DISTANCE",false,1,""),
            new ConversationPlanner.Plan("MERCHANTS",List.of("G1"),true,null,null,null,null,3,""));
        assertEquals(3,service.turn(state,question).graph().goals().getFirst().providers().size());
        state.previous=new ConversationPlanner.Plan("OFFERS",List.of("G1"),false,1.,20.,"DISCOUNT",true,1,"");
        assertEquals(3,service.turn(state,question).graph().goals().getFirst().providers().size());
        verify(mcp,times(2)).plccMerchants("C1",List.of("G1"),null,null,5);
        assertEquals(5,state.previous.limit());assertNull(state.previous.maxDistanceMiles());assertFalse(state.previous.offersOnly());
    }
    @Test void discountsButtonAfterAllGoalsFetchesOnlyPlccOffersWithoutModelPlanning() {
        var state=service.start("C1");
        service.turn(state,"Show my goals","GOALS");
        when(mcp.plccMerchants("C1",List.of("G1"),null,0d,5)).thenReturn(details());
        var reply=service.turn(state,"Where can I get shopping discounts?","OFFERS");
        assertEquals("MCP_PLCC_RESULTS",reply.source());assertEquals(1,reply.graph().goals().size());
        assertEquals("G1",reply.graph().goals().getFirst().goalId());
        assertFalse(reply.graph().goals().getFirst().providers().isEmpty());
        verify(mcp).plccMerchants("C1",List.of("G1"),null,0d,5);verifyNoInteractions(planner);
    }
    @Test void shortcutRejectsUnknownActionBeforeCallingTools() {
        assertThrows(IllegalArgumentException.class,()->service.turn(new ConversationState(profile),"Discounts","RUN_CYPHER"));
        verifyNoInteractions(mcp,planner);
    }
    @Test void revokedAccountRedactsCachedDetailsAndExplanations() throws Exception {
        var state=service.start("C1");state.goals=goals;state.retrievedAt=System.currentTimeMillis();state.lastResult=details();
        state.add("Shops",new ConversationState.Reply("Cedar Furnishings is nearby","MCP_PLCC_RESULTS",details(),true,false,1));
        when(mcp.customerProfile("C1")).thenReturn(new CustomerProfile("C1","Alex","Walker","Seattle","WA","NONE"));
        when(planner.plan(eq(state),eq(goals),anyString())).thenReturn(new ConversationPlanner.Plan("EXPLAIN",List.of(),false,null,null,null,null,null,"Cedar Furnishings offers furniture."));
        var reply=service.turn(state,"Explain that");assertEquals("ACCOUNT_RESTRICTED",reply.source());assertTrue(reply.showConsent());
        assertTrue(reply.graph().goals().getFirst().providers().isEmpty());assertTrue(state.messages().stream().filter(m->m.role().equals("assistant")).noneMatch(m->m.text().contains("Cedar")));
    }
    @Test void newCustomerStateNeverSharesHistoryOrGoalCache(){var a=service.start("C1");a.add("Private question",new ConversationState.Reply("Reply","GRAPH",null,false,false,0));
        when(mcp.customerProfile("C2")).thenReturn(new CustomerProfile("C2","Liam","Walker","Seattle","WA","NONE"));
        var b=service.start("C2");assertTrue(b.messages().isEmpty());assertNull(b.goals);assertNotEquals(a.id(),b.id());}
}
