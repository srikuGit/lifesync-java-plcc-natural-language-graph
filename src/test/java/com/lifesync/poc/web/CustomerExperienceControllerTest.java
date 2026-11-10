package com.lifesync.poc.web;
import com.lifesync.poc.product.*;
import com.lifesync.poc.conversation.*;
import com.lifesync.poc.consent.PlccContactService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CustomerExperienceControllerTest {
    final ConversationService conversations=mock(ConversationService.class);
    final PlccContactService contacts=mock(PlccContactService.class);
    final ConversationState state=new ConversationState(new CustomerProfile("C001","Alex","Walker","Seattle","WA","ACTIVE"));
    MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new CustomerExperienceController(conversations,contacts))
        .setViewResolvers((name,locale)->new org.springframework.web.servlet.view.AbstractView(){protected void renderMergedOutputModel(Map<String,Object> model,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response){}}).build();}
    MockHttpSession session(){var s=new MockHttpSession();s.setAttribute(CustomerExperienceController.CONVERSATION,state);s.setAttribute(CustomerExperienceController.TOKEN,"token");return s;}
    String body(String customer){return "{\"customerId\":\""+customer+"\",\"question\":\"Show my goals\",\"conversationId\":\""+state.id()+"\",\"token\":\"token\"}";}
    @Test void idEntryLoadsOnlyProfileAndDoesNotRunQuestionOrConsentLookups() throws Exception {
        when(conversations.start("C001")).thenReturn(state);
        mvc.perform(get("/experience").param("customerId","C001").param("question","Show my goals"))
            .andExpect(status().isOk()).andExpect(model().attribute("profile",state.profile()))
            .andExpect(model().attribute("messages",List.of()));
        verify(conversations).start("C001");verify(conversations,never()).turn(any(),anyString());verifyNoInteractions(contacts);
    }
    @Test void validatedChatUsesTheCurrentSessionCustomer() throws Exception {
        var reply=new ConversationState.Reply("Your goals","MCP_GOALS",new CustomerSubgraph("C001","Alex",List.of()),false,false,1);
        when(conversations.turn(state,"Show my goals")).thenReturn(reply);
        mvc.perform(post("/experience/chat").session(session()).contentType("application/json").content(body("C001")))
            .andExpect(status().isOk()).andExpect(model().attribute("reply",reply));
        verify(conversations).turn(state,"Show my goals");
    }
    @Test void foreignCustomerMissingTokenOrOldConversationCannotRunTools() throws Exception {
        mvc.perform(post("/experience/chat").session(session()).contentType("application/json").content(body("C002"))).andExpect(status().isForbidden());
        mvc.perform(post("/experience/chat").session(session()).contentType("application/json").content(body("C001").replace("token\"}","wrong\"}"))).andExpect(status().isForbidden());
        verifyNoInteractions(conversations,contacts);
    }
    @Test void selectingAnotherCustomerClearsOldConsentAndConversation() throws Exception {
        var session=session();session.setAttribute("plccContactToken","old-token");
        var other=new ConversationState(new CustomerProfile("C002","Liam","Walker","Seattle","WA","NONE"));
        when(conversations.start("C002")).thenReturn(other);
        mvc.perform(get("/experience").session(session).param("customerId","C002"))
            .andExpect(model().attribute("profile",other.profile())).andExpect(model().attribute("messages",List.of()));
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute("plccContactToken"));
        org.junit.jupiter.api.Assertions.assertSame(other,session.getAttribute(CustomerExperienceController.CONVERSATION));
    }
    @Test void savedConsentStillSuppressesTheFormAfterMerchantRequest() throws Exception {
        var graph=new CustomerSubgraph("C001","Alex",List.of(new CustomerSubgraph.Goal("G1","Furniture","PLCC","PLCC",List.of(),"PLCC_ACCOUNT_RESTRICTED",List.of())));
        var reply=new ConversationState.Reply("Information","MCP_PLCC_RESULTS",graph,true,true,1);
        when(conversations.turn(state,"Show my goals")).thenReturn(reply);when(contacts.hasConsent("C001")).thenReturn(true);
        mvc.perform(post("/experience/chat").session(session()).contentType("application/json").content(body("C001")))
            .andExpect(model().attribute("contactSaved",true)).andExpect(model().attributeDoesNotExist("contactToken"));
    }
}
