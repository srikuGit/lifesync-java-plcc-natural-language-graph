package com.lifesync.poc.web;
import com.lifesync.poc.consent.PlccContactService;
import com.lifesync.poc.product.CustomerSubgraph;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PlccContactControllerTest {
    final PlccContactService contacts=mock(PlccContactService.class);
    MockMvc mvc;
    MockHttpSession session;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new PlccContactController(contacts)).build();
        session=new MockHttpSession();
        var graph=new CustomerSubgraph("C002","Person",List.of(new CustomerSubgraph.Goal("G1","Furniture","PLCC","PLCC",List.of("HOME_FURNISHINGS"),"PLCC_ACCOUNT_RESTRICTED",List.of())));
        session.setAttribute(CustomerExperienceController.SNAPSHOT,new CustomerExperienceController.Snapshot(graph,System.currentTimeMillis()));
        session.setAttribute("plccContactToken","valid-token");
    }
    @Test void recordsExplicitConsentAndConsumesToken() throws Exception {
        when(contacts.request("C002")).thenReturn(true);
        mvc.perform(post("/experience/plcc-contact").session(session).param("customerId","C002").param("token","valid-token").param("permission","true"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/experience?customerId=C002"));
        verify(contacts).request("C002");
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute("plccContactToken"));
    }
    @Test void missingPermissionDoesNotRecordConsent() throws Exception {
        mvc.perform(post("/experience/plcc-contact").session(session).param("customerId","C002").param("token","valid-token"))
            .andExpect(status().isBadRequest());verifyNoInteractions(contacts);
    }
    @Test void foreignCustomerOrInvalidTokenCannotRecordConsent() throws Exception {
        mvc.perform(post("/experience/plcc-contact").session(session).param("customerId","C001").param("token","valid-token").param("permission","true"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/experience/plcc-contact").session(session).param("customerId","C002").param("token","wrong").param("permission","true"))
            .andExpect(status().isForbidden());verifyNoInteractions(contacts);
    }
}
