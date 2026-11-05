package com.lifesync.poc.product;

import com.lifesync.poc.domain.Models.Customer;
import com.lifesync.poc.graph.GraphRepository.Candidate;
import com.lifesync.poc.service.DistanceService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomerSubgraphServiceTest {
    private final ProductGraphRepository graph=mock(ProductGraphRepository.class);
    private final ProductOfferCatalog offers=mock(ProductOfferCatalog.class);
    private final CustomerSubgraphService service=new CustomerSubgraphService(graph,offers,new DistanceService());
    private void customer(String status, Double lat) {
        when(graph.customer("C1")).thenReturn(Optional.of(new Customer("C1","secret","Demo","Seattle","WA","x",lat,-122.,status)));
    }
    @Test void nonHolderCanExploreLoansButCannotSeePlccMerchants() {
        customer("NONE",47.);
        when(graph.goals("C1","")).thenReturn(List.of(
            new ProductGraphRepository.GoalFact("G1","Shopping","PLCC","PLCC",List.of("APPLIANCES")),
            new ProductGraphRepository.GoalFact("G2","Car","AUTO_LOAN","Auto",List.of("AUTO_LOAN"))));
        when(graph.candidates("C1","G2","AUTO_LOAN")).thenReturn(List.of());
        var result=service.load("C1","");
        assertEquals("PLCC_ACCOUNT_RESTRICTED",result.goals().getFirst().status());
        verify(graph,never()).candidates("C1","G1","PLCC");
        verify(graph).candidates("C1","G2","AUTO_LOAN");
    }
    @Test void scopesDeduplicatesAndRanksByDistance() {
        customer("ACTIVE",47.);
        when(graph.goals("C1","AUTO_LOAN")).thenReturn(List.of(
            new ProductGraphRepository.GoalFact("G2","Car","AUTO_LOAN","Auto",List.of("AUTO_LOAN"))));
        Candidate near=new Candidate("near","S1","Near","AUTO_LOAN","", "Seattle","WA","",47.01,-122.);
        Candidate far=new Candidate("far","S2","Far","AUTO_LOAN","", "Seattle","WA","",48.,-122.);
        when(graph.candidates("C1","G2","AUTO_LOAN")).thenReturn(List.of(far,near,near));
        when(offers.current(anyString(),anyString(),eq("C1"))).thenReturn(List.of());
        var providers=service.load("C1","AUTO_LOAN").goals().getFirst().providers();
        assertEquals(List.of("near","far"),providers.stream().map(CustomerSubgraph.Provider::merchantId).toList());
    }
    @Test void missingLocationAndInvalidRequestsDoNotQueryCandidates() {
        customer("ACTIVE",null);
        when(graph.goals("C1","")).thenReturn(List.of(
            new ProductGraphRepository.GoalFact("G2","Car","AUTO_LOAN","Auto",List.of("AUTO_LOAN"))));
        assertEquals("NO_CUSTOMER_LOCATION",service.load("C1","").goals().getFirst().status());
        verify(graph,never()).candidates(anyString(),anyString(),anyString());
        assertThrows(IllegalArgumentException.class,()->service.load("' MATCH (n)",""));
        assertThrows(IllegalArgumentException.class,()->service.load("C1","UNKNOWN"));
    }
}
