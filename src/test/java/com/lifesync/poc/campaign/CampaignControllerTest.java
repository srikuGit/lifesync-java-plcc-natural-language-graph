package com.lifesync.poc.campaign;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CampaignControllerTest {
    final ManualCampaignService campaigns=mock(ManualCampaignService.class);
    MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new CampaignController(campaigns)).setViewResolvers((name,locale)->name.startsWith("redirect:")?new org.springframework.web.servlet.view.RedirectView(name.substring(9)):new org.springframework.web.servlet.view.json.MappingJackson2JsonView()).build();}
    @Test void getOnlyShowsRunsAndDoesNotGenerateAnything() throws Exception {
        mvc.perform(get("/campaigns")).andExpect(status().isOk()).andExpect(view().name("campaign-list"));
        verify(campaigns,never()).start(anyInt());
    }
    @Test void explicitPostWithSessionTokenStartsOneManualRun() throws Exception {
        var session=new MockHttpSession();session.setAttribute(CampaignController.TOKEN,"valid-token");
        when(campaigns.start(25)).thenReturn("R1");
        mvc.perform(post("/campaigns").session(session).param("token","valid-token").param("limit","25"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/campaigns/R1"));
        verify(campaigns).start(25);org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(CampaignController.TOKEN));
    }
    @Test void invalidTokenAndSendRouteCannotStartOrDeliverCampaign() throws Exception {
        var session=new MockHttpSession();session.setAttribute(CampaignController.TOKEN,"valid-token");
        mvc.perform(post("/campaigns").session(session).param("token","wrong")).andExpect(status().isForbidden());
        mvc.perform(post("/campaigns/send")).andExpect(status().isMethodNotAllowed());verifyNoInteractions(campaigns);
    }
}
