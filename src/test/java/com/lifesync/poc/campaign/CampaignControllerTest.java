package com.lifesync.poc.campaign;

import com.lifesync.poc.product.CustomerSubgraph;
import org.junit.jupiter.api.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import java.util.Properties;
import static org.hamcrest.Matchers.containsString;
import java.util.List;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CampaignControllerTest {
    final ManualCampaignService campaigns=mock(ManualCampaignService.class);
    final CampaignEmailDelivery delivery=mock(CampaignEmailDelivery.class);
    MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new CampaignController(campaigns,delivery)).setViewResolvers((name,locale)->name.startsWith("redirect:")?new org.springframework.web.servlet.view.RedirectView(name.substring(9)):new org.springframework.web.servlet.view.json.MappingJackson2JsonView()).build();}
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
    @Test void invalidTokenAndMissingBulkSendRouteCannotStartOrDeliverCampaign() throws Exception {
        var session=new MockHttpSession();session.setAttribute(CampaignController.TOKEN,"valid-token");
        mvc.perform(post("/campaigns").session(session).param("token","wrong")).andExpect(status().isForbidden());
        mvc.perform(post("/campaigns/send")).andExpect(status().isMethodNotAllowed());verifyNoInteractions(campaigns);
    }

    @Test void profileSendReturnsToCustomerAndRejectsReplayedToken() throws Exception {
        var session=new MockHttpSession();
        var page=previewPage();
        when(campaigns.profilePreview("C001")).thenReturn(page);
        when(delivery.recipient()).thenReturn("banker@example.test");
        String token=openPreview("/experience/C001/email",session);
        mvc.perform(post("/experience/C002/email/send").session(session).param("token",token))
            .andExpect(status().isForbidden());
        mvc.perform(post("/experience/C001/email/send").session(session).param("token",token))
            .andExpect(redirectedUrl("/experience?customerId=C001#customer-profile"))
            .andExpect(flash().attribute("emailNotice","Email is sent to banker@example.test."));
        mvc.perform(post("/experience/C001/email/send").session(session).param("token",token))
            .andExpect(status().isForbidden());
        verify(delivery).send(page);
    }

    @Test void campaignSendReturnsToRunAndShowsFailureOnPreview() throws Exception {
        var session=new MockHttpSession();
        var page=previewPage();
        when(campaigns.preview("R1","C001")).thenReturn(page);
        when(delivery.recipient()).thenReturn("banker@example.test");
        doThrow(new MailSendException("Unavailable")).doNothing().when(delivery).send(page);
        String path="/campaigns/R1/previews/C001";
        String token=openPreview(path,session);
        mvc.perform(post(path+"/send").session(session).param("token",token))
            .andExpect(redirectedUrl(path)).andExpect(flash().attributeExists("emailError"));
        String retry=openPreview(path,session);
        mvc.perform(post(path+"/send").session(session).param("token",retry))
            .andExpect(redirectedUrl("/campaigns/R1")).andExpect(flash().attributeExists("emailNotice"));
    }

    @Test void previewAndMailRenderTheSameEscapedGoal() throws Exception {
        var resolver=new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        var engine=new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var views=new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        var mail=mock(JavaMailSender.class);
        var message=new MimeMessage(Session.getInstance(new Properties()));
        when(mail.createMimeMessage()).thenReturn(message);
        var sender=new CampaignEmailDelivery(mail,engine,"bank@example.test","banker@example.test");
        var page=previewPage();
        when(campaigns.profilePreview("C001")).thenReturn(page);
        when(campaigns.preview("R1","C001")).thenReturn(page);
        var rendered=MockMvcBuilders.standaloneSetup(new CampaignController(campaigns,sender))
            .setViewResolvers(views).build();
        rendered.perform(get("/experience/C001/email"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Shop &amp; compare")))
            .andExpect(content().string(containsString("action=\"/experience/C001/email/send\"")));
        rendered.perform(get("/campaigns/R1/previews/C001"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Shop &amp; compare")))
            .andExpect(content().string(containsString("action=\"/campaigns/R1/previews/C001/send\"")));
        sender.send(page);
        org.junit.jupiter.api.Assertions.assertTrue(message.getContent().toString().contains("Shop &amp; compare"));
        org.junit.jupiter.api.Assertions.assertEquals("banker@example.test",message.getAllRecipients()[0].toString());
        verify(mail).send(message);
    }

    private String openPreview(String path,MockHttpSession session) throws Exception {
        return (String) mvc.perform(get(path).session(session))
            .andExpect(view().name("campaign-preview")).andReturn().getModelAndView().getModel().get("sendToken");
    }

    private CampaignModels.PreviewPage previewPage() {
        var goal=new CustomerSubgraph.Goal("G1","Shop & compare","PLCC","PLCC",List.of(),"READY",List.of());
        var graph=new CustomerSubgraph("C001","Demo customer",List.of(goal));
        var preview=new CampaignModels.Preview("P1","R1","C001","READY","PLCC shopping goals",
            "Explore your options.","FACTUAL_TEMPLATE",List.of("G1"),"2026-11-18","");
        return new CampaignModels.PreviewPage(null,preview,graph);
    }
}
