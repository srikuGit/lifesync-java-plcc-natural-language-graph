package com.lifesync.poc.service;

import com.lifesync.poc.domain.Models.*;
import com.lifesync.poc.graph.GraphRepository;
import com.lifesync.poc.graph.GraphRepository.Candidate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationOrchestratorTest {
    private final GraphRepository graph=mock(GraphRepository.class);
    private final EmailPreviewService emails=mock(EmailPreviewService.class);
    private final EmailExplanationService explanation=mock(EmailExplanationService.class);
    private final SemanticRetrievalService semantic=mock(SemanticRetrievalService.class);
    private final RetrievalDecisionService decisions=new RetrievalDecisionService();
    private final RecommendationOrchestrator system=new RecommendationOrchestrator(
            graph,new DistanceService(),emails,explanation,semantic,decisions,false,5);

    @Test
    @SuppressWarnings({"unchecked","rawtypes"})
    void plccHolderUsesJavaHybridPolicyAndRanksByDistance() {
        GoalDetail goal=goal("APPLIANCES","ACTIVE");
        when(graph.claimOne("G1")).thenReturn(true);
        when(graph.goal("G1")).thenReturn(Optional.of(goal));
        when(graph.hasPlccMerchantCategoryMatch("G1")).thenReturn(true);
        when(graph.candidateMerchants("G1")).thenReturn(List.of(
                merchant("M3",47.70),merchant("M2",47.62),merchant("M5",47.65),
                merchant("M4",47.64),merchant("M1",47.61),merchant("M6",47.66)));
        when(graph.goalCategoryEvidence("G1")).thenReturn(
                new GraphRepository.GoalCategoryEvidence(List.of("APPLIANCES"),0.72,"TEXT_CUES"));
        when(semantic.isEnabled()).thenReturn(true);
        when(semantic.findSimilarEligibleMerchants(goal)).thenReturn(Map.of(
            "M1",0.91,"M2",0.88,"M3",0.87,"M4",0.89,"M5",0.90,"M6",0.85));
        when(explanation.explain(eq(goal),anyList(),anyList())).thenAnswer(call ->
                new EmailExplanationService.GroundedResult("Welcome",call.getArgument(1),"LLM"));
        when(emails.subjectHolder(goal)).thenReturn("Holder Subject");
        ProcessingResult result=system.processOne("G1");
        assertEquals("READY",result.status());
        assertEquals(5,result.merchantCount());
        ArgumentCaptor<List<MerchantMatch>> captor=ArgumentCaptor.forClass((Class)List.class);
        verify(graph).completeHolder(eq("G1"),eq("fp"),eq("Welcome"),captor.capture(),
                eq("Holder Subject"),eq("PLCC_GRAPH_AND_VECTOR"),eq("LLM"),anyString(),anyString(),eq(1));
        assertEquals("M1",captor.getValue().getFirst().merchantId());
        for(int i=1;i<captor.getValue().size();i++)
            assertTrue(captor.getValue().get(i-1).distanceMiles() <= captor.getValue().get(i).distanceMiles());
    }

    @Test void multiCategoryGraphEvidenceTriggersJavaHybridPolicy() {
        GoalDetail goal=new GoalDetail("G1","Buy a desk and a laptop", "HOME_FURNISHINGS",
                "PROCESSING","ECN1","Demo Customer","Seattle",47.6,-122.33,"fp","ACTIVE","C001");
        when(graph.claimOne("G1")).thenReturn(true);
        when(graph.goal("G1")).thenReturn(Optional.of(goal));
        when(graph.hasPlccMerchantCategoryMatch("G1")).thenReturn(true);
        when(graph.goalCategoryEvidence("G1")).thenReturn(new GraphRepository.GoalCategoryEvidence(
                List.of("HOME_FURNISHINGS","ELECTRONICS"),0.78,"TEXT_CUES"));
        when(graph.candidateMerchants("G1")).thenReturn(List.of(
                new Candidate("M1","M1-S1","Furniture Shop","HOME_FURNISHINGS", "Desks",
                        "Seattle","WA","Demo Street",47.61,-122.33),
                new Candidate("M2","M2-S1","Computer Shop","ELECTRONICS", "Laptops",
                        "Seattle","WA","Demo Street",47.62,-122.33)));
        when(semantic.isEnabled()).thenReturn(true);
        when(explanation.explain(eq(goal),anyList(),anyList())).thenAnswer(call->
                new EmailExplanationService.GroundedResult("Welcome",call.getArgument(1),"RULE"));
        when(emails.subjectHolder(goal)).thenReturn("Subject");
        assertEquals("READY",system.processOne("G1").status());
        verify(graph).completeHolder(eq("G1"),eq("fp"),anyString(),anyList(),eq("Subject"),
                eq("PLCC_GRAPH_FALLBACK"),anyString(),anyString(),anyString(),eq(2));
        verify(semantic).findSimilarEligibleMerchants(eq(goal));
    }

    @Test void nonHolderGetsInvitationWithoutMerchantsVectorOrOffers() {
        GoalDetail goal=goal("APPLIANCES","NONE");
        when(graph.claimOne("G1")).thenReturn(true);
        when(graph.goal("G1")).thenReturn(Optional.of(goal));
        when(graph.hasPlccMerchantCategoryMatch("G1")).thenReturn(true);
        when(emails.subjectInvitation(goal)).thenReturn("Invitation");
        assertEquals("READY",system.processOne("G1").status());
        verify(graph).completeInvitation("G1","fp","Invitation","APPLIANCES");
        verify(graph,never()).candidateMerchants(anyString());
        verifyNoInteractions(semantic,explanation);
    }

    @Test void unknownStatusMustNotGetAnyEmail() {
        GoalDetail goal=goal("APPLIANCES","UNKNOWN");
        when(graph.claimOne("G1")).thenReturn(true);
        when(graph.goal("G1")).thenReturn(Optional.of(goal));
        when(graph.hasPlccMerchantCategoryMatch("G1")).thenReturn(true);
        assertEquals("ACCOUNT_UNVERIFIED",system.processOne("G1").status());
        verifyNoInteractions(emails,semantic,explanation);
    }

    @Test void unsupportedCategoryNeverInvitesAnyone() {
        when(graph.claimOne("G1")).thenReturn(true);
        when(graph.goal("G1")).thenReturn(Optional.of(goal("TRAVEL","NONE")));
        assertEquals("UNSUPPORTED",system.processOne("G1").status());
        verifyNoInteractions(emails,semantic,explanation);
    }

    private GoalDetail goal(String category,String status) {
        return new GoalDetail("G1","Buy a refrigerator",category,"PROCESSING",
                "ECN1","Demo Customer","Seattle",47.60,-122.33,"fp",status,"C001");
    }
    private Candidate merchant(String id,double latitude) {
        return new Candidate(id,id+"-S1","Store "+id,"APPLIANCES",
                "Sells refrigerators","Seattle","WA","Fictional Lane",latitude,-122.33);
    }
}
