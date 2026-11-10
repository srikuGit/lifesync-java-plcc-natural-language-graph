package com.lifesync.poc.product;

import com.lifesync.poc.domain.Models.Customer;
import com.lifesync.poc.graph.GraphRepository.Candidate;
import com.lifesync.poc.service.DistanceService;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomerSubgraphServiceTest {
    private final ProductGraphRepository graph=mock(ProductGraphRepository.class);
    private final ProductOfferCatalog offers=mock(ProductOfferCatalog.class);
    private final CustomerSubgraphService service=new CustomerSubgraphService(graph,offers,new DistanceService());
    private void customer(String status,Double lat) {
        when(graph.customer("C1")).thenReturn(Optional.of(new Customer("C1","secret","Person","Seattle","WA","x",lat,-122.,status)));
    }
    private ProductGraphRepository.GoalFact goal(String id,String product) {
        return new ProductGraphRepository.GoalFact(id,"Shopping",product,product,List.of("HOME_FURNISHINGS"));
    }
    @Test void profileLookupDoesNotRetrieveGoalsOrOffers() {
        var profile=new CustomerProfile("C1","Priya","Shah","Seattle","WA","ACTIVE");
        when(graph.profile("C1")).thenReturn(Optional.of(profile));
        assertEquals(profile,service.profile("C1"));
        verify(graph).profile("C1");verifyNoMoreInteractions(graph);verifyNoInteractions(offers);
    }
    @Test void listingAllProductsDoesNotQueryAnyProviderOrOfferFeed() {
        customer("ACTIVE",47.);
        when(graph.goals("C1","")).thenReturn(List.of(goal("G1","PLCC"),goal("G2","AUTO_LOAN")));
        var result=service.listGoals("C1");
        assertEquals(2,result.goals().size());
        assertTrue(result.goals().stream().allMatch(g->g.providers().isEmpty()&&"GOAL_ONLY".equals(g.status())));
        verify(graph,never()).candidates(anyString(),anyString(),anyString());verifyNoInteractions(offers);
    }
    @Test void nonHolderAndLoanGoalsNeverRevealProviders() {
        customer("NONE",47.);
        when(graph.goals("C1","")).thenReturn(List.of(goal("G1","PLCC"),goal("G2","AUTO_LOAN")));
        var result=service.load("C1","");
        assertEquals("PLCC_ACCOUNT_RESTRICTED",result.goals().getFirst().status());
        assertEquals("GOAL_ONLY",result.goals().get(1).status());
        verify(graph,never()).candidates(anyString(),anyString(),anyString());verifyNoInteractions(offers);
    }
    @Test void activeHolderStillReceivesOnlyBasicLendingGoals() {
        customer("ACTIVE",47.);
        when(graph.goals("C1","HOME_PURCHASE")).thenReturn(List.of(goal("G2","HOME_PURCHASE")));
        assertEquals("GOAL_ONLY",service.load("C1","HOME_PURCHASE").goals().getFirst().status());
        verify(graph,never()).candidates(anyString(),anyString(),anyString());verifyNoInteractions(offers);
    }
    @Test void scopesDeduplicatesAndRanksPlccProvidersByDistance() {
        customer("ACTIVE",47.);
        when(graph.goals("C1","PLCC")).thenReturn(List.of(goal("G1","PLCC")));
        Candidate near=new Candidate("near","S1","Near","HOME_FURNISHINGS","","Seattle","WA","",47.01,-122.);
        Candidate far=new Candidate("far","S2","Far","HOME_FURNISHINGS","","Seattle","WA","",48.,-122.);
        when(graph.candidates("C1","G1","PLCC")).thenReturn(List.of(far,near,near));
        when(offers.snapshot()).thenReturn(List.of());
        var providers=service.load("C1","PLCC").goals().getFirst().providers();
        assertEquals(List.of("near","far"),providers.stream().map(CustomerSubgraph.Provider::merchantId).toList());
    }
    @Test void filtersDistanceAndDiscountOnlyForOwnedPlccGoals() {
        customer("ACTIVE",47.);
        when(graph.goals("C1","")).thenReturn(List.of(goal("G1","PLCC"),goal("G2","AUTO_LOAN")));
        var near=new Candidate("near","S1","Near","HOME_FURNISHINGS","","Seattle","WA","",47.01,-122.);
        var far=new Candidate("far","S2","Far","HOME_FURNISHINGS","","Seattle","WA","",47.02,-122.);
        when(graph.candidates("C1","G1","PLCC")).thenReturn(List.of(near,far));
        when(offers.snapshot()).thenReturn(List.of(offer("near",10),offer("far",20)));
        var result=service.findPlccMerchants("C1",List.of("G1"),5.,15.,1);
        assertEquals(1,result.goals().size());
        assertEquals("far",result.goals().getFirst().providers().getFirst().merchantId());
        assertEquals(20.,result.goals().getFirst().providers().getFirst().offers().getFirst().discountPercent());
        assertThrows(IllegalArgumentException.class,()->service.findPlccMerchants("C1",List.of("FOREIGN"),null,null,5));
        assertThrows(IllegalArgumentException.class,()->service.findPlccMerchants("C1",List.of("G2"),null,null,5));
    }
    private ProductOfferCatalog.Offer offer(String merchant,double discount) {
        return new ProductOfferCatalog.Offer("O-"+merchant,"PLCC",merchant,null,"Discount","Terms","ACTIVE",
            LocalDate.now().minusDays(1),LocalDate.now().plusDays(1),discount,null,null,null);
    }
    @Test void missingLocationAndInvalidRequestsDoNotQueryCandidates() {
        customer("ACTIVE",null);
        when(graph.goals("C1","")).thenReturn(List.of(goal("G1","PLCC")));
        assertEquals("NO_CUSTOMER_LOCATION",service.load("C1","").goals().getFirst().status());
        verify(graph,never()).candidates(anyString(),anyString(),anyString());
        assertThrows(IllegalArgumentException.class,()->service.load("' MATCH (n)",""));
        assertThrows(IllegalArgumentException.class,()->service.load("C1","UNKNOWN"));
        assertThrows(IllegalArgumentException.class,()->service.findPlccMerchants("C1",List.of(),Double.NaN,null,5));
        assertThrows(IllegalArgumentException.class,()->service.findPlccMerchants("C1",List.of(),null,-1.,5));
        assertThrows(IllegalArgumentException.class,()->service.findPlccMerchants("C1",List.of(),null,null,6));
    }
}
