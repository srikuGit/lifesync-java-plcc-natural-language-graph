package com.lifesync.poc.service;

import com.lifesync.poc.service.RetrievalDecisionService.Evidence;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalDecisionServiceTest {
    private final RetrievalDecisionService service = new RetrievalDecisionService();

    @Test void multiCategoryRequiresHybrid() {
        var evidence = new Evidence(List.of("ELECTRONICS","HOME_FURNISHINGS"),.95,
                "SEMANTIC",7,9,true);
        var plan = service.plan(evidence);
        assertEquals("HYBRID", plan.strategy());
        assertEquals("JAVA_POLICY", plan.source());
        assertTrue(plan.reason().contains("MULTI_CATEGORY"));
    }
    @Test void lowConfidenceAndSmallGraphRequireHybrid() {
        var evidence = new Evidence(List.of("ELECTRONICS"),.60,"SEMANTIC",2,3,true);
        assertEquals("HYBRID", service.plan(evidence).strategy());
        assertTrue(service.plan(evidence).reason().contains("LOW_CONFIDENCE"));
        assertTrue(service.plan(evidence).reason().contains("FEW_GRAPH_MERCHANTS"));
    }
    @Test void clearHighConfidenceGoalUsesGraph() {
        var evidence = new Evidence(List.of("APPLIANCES"),.95,"SEMANTIC",6,8,true);
        assertEquals("GRAPH", service.plan(evidence).strategy());
    }
    @Test void vectorDisabledUsesGraphRegardlessOfCategories() {
        var evidence = new Evidence(List.of("ELECTRONICS","HOME_FURNISHINGS"),.60,
                "SEMANTIC",6,8,false);
        assertEquals("GRAPH", service.plan(evidence).strategy());
    }
    @Test void noGraphMerchantsCannotBeIntroducedByVectors() {
        var evidence = new Evidence(List.of("ELECTRONICS"),.60,"SEMANTIC",0,0,true);
        assertEquals("GRAPH", service.plan(evidence).strategy());
        assertFalse(service.evaluate(evidence,0,1).retry());
    }
    @Test void boundedRefinementIsEntirelyJavaBased() {
        var evidence = new Evidence(List.of("APPLIANCES"),.68,"SEMANTIC",6,8,true);
        var first = service.evaluate(evidence,1,1);
        assertTrue(first.retry());
        assertEquals("JAVA_POLICY", first.source());
        assertTrue(first.reason().contains("requiredMatches=3"));
        assertFalse(service.evaluate(evidence,1,2).retry());
        assertFalse(service.evaluate(evidence,3,1).retry());
    }
    @Test void invalidInputsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new Evidence(List.of("APPLIANCES"),1.2,"SEMANTIC",2,2,true));
        assertThrows(IllegalArgumentException.class, () ->
            service.evaluate(new Evidence(List.of("APPLIANCES"),.8,"SEMANTIC",2,2,true),-1,1));
    }
}
