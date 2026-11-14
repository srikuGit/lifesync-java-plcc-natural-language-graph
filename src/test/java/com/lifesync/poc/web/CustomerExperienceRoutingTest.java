package com.lifesync.poc.web;

import com.lifesync.poc.conversation.*;
import com.lifesync.poc.consent.PlccContactService;
import com.lifesync.poc.product.CustomerProfile;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

class CustomerExperienceRoutingTest {
    @Test void renderedCustomerFormAndChatKeepDeploymentContextPath() throws Exception {
        var conversations=mock(ConversationService.class);
        var state=new ConversationState(new CustomerProfile("C001","Alex","Walker","Seattle","WA","ACTIVE"));
        when(conversations.start("C001")).thenReturn(state);
        var templates=new ClassLoaderTemplateResolver();
        templates.setPrefix("templates/");templates.setSuffix(".html");templates.setTemplateMode("HTML");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(templates);
        var views=new ThymeleafViewResolver();views.setTemplateEngine(engine);
        var mvc=MockMvcBuilders.standaloneSetup(new CustomerExperienceController(conversations,mock(PlccContactService.class)))
            .setViewResolvers(views).build();
        mvc.perform(get("/bank/").contextPath("/bank"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("action=\"/bank/experience\"")));
        mvc.perform(get("/bank/experience").contextPath("/bank").param("customerId","C001").param("fresh","true"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Alex Walker")))
            .andExpect(content().string(containsString("data-chat-url=\"/bank/experience/chat\"")))
            .andExpect(content().string(containsString("data-reset-url=\"/bank/experience/reset\"")))
            .andExpect(content().string(containsString("src=\"/bank/js/experience.js?v=6\"")))
            .andExpect(content().string(containsString("href=\"/bank/experience?fresh=true&amp;customerId=C001\"")));
        verify(conversations).start("C001");
    }
}
