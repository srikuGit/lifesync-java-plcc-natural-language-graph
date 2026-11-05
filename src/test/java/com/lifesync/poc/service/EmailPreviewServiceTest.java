package com.lifesync.poc.service;

import com.lifesync.poc.consent.ConsentLinkService;
import com.lifesync.poc.domain.Models.*;
import com.lifesync.poc.graph.GraphRepository;
import com.lifesync.poc.offers.OffersClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailPreviewServiceTest {
    private final GraphRepository graph=mock(GraphRepository.class);
    private final OffersClient offers=mock(OffersClient.class);
    private final ConsentLinkService links=new ConsentLinkService("unit-test-key","http://localhost:8080");
    private final TemplateEngine engine=mock(TemplateEngine.class);
    private final EmailPreviewService service=new EmailPreviewService(engine,graph,offers,links,"Demo Bank");
    @Test void prospectEmailHasNoMerchantInformationOrOffersCall() {
        when(graph.goal("G1")).thenReturn(Optional.of(goal("NONE")));
        when(graph.preview("G1")).thenReturn(Optional.of(new Preview("EP-G1","G1","Invite","now","")));
        when(engine.process(eq("email/plcc-invitation"),any(Context.class))).thenReturn("<html>Invite only</html>");
        assertEquals("<html>Invite only</html>",service.renderLatest("G1").html());
        ArgumentCaptor<Context> captor=ArgumentCaptor.forClass(Context.class);
        verify(engine).process(eq("email/plcc-invitation"),captor.capture());
        assertNull(captor.getValue().getVariable("merchants"));
        assertNotNull(captor.getValue().getVariable("consentUrl"));
        verifyNoInteractions(offers);
        verify(graph,never()).recommendedMerchants(anyString());
    }
    @Test void holderLoadsOffersOnEveryPreviewRequest() {
        when(graph.goal("G1")).thenReturn(Optional.of(goal("ACTIVE")));
        when(graph.preview("G1")).thenReturn(Optional.of(new Preview("EP-G1","G1","Holder","now","")));
        MerchantMatch m=new MerchantMatch("M001","M001-S1","Store","APPLIANCES","Sells appliances",
                "Seattle","WA","Demo Way",1.2,1,"Matched",0.8);
        when(graph.recommendedMerchants("G1")).thenReturn(List.of(m));
        when(engine.process(eq("email/recommendation-email"),any(Context.class))).thenReturn("<html>Offers</html>");
        service.renderLatest("G1");service.renderLatest("G1");
        verify(offers,times(2)).currentOffers("C001",List.of("M001"));
    }
    private GoalDetail goal(String status) {
        return new GoalDetail("G1","Buy appliance","APPLIANCES","READY",
                "ECN001","Demo","Seattle",47.6,-122.3,"fp",status,"C001");
    }
}
