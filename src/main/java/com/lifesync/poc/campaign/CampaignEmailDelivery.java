package com.lifesync.poc.campaign;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Service
public class CampaignEmailDelivery {
    private final JavaMailSender mail;
    private final SpringTemplateEngine templates;
    private final String from;
    private final String recipient;

    public CampaignEmailDelivery(JavaMailSender mail, SpringTemplateEngine templates,
            @Value("${lifesync.campaign.mail-from}") String from,
            @Value("${lifesync.campaign.mail-to}") String recipient) {
        this.mail = mail;
        this.templates = templates;
        this.from = from;
        this.recipient = recipient;
    }

    public String recipient() {
        return recipient;
    }

    public void send(CampaignModels.PreviewPage page) throws MessagingException {
        var context = new Context();
        context.setVariable("page", page);
        var message = mail.createMimeMessage();
        var helper = new MimeMessageHelper(message, "UTF-8");
        helper.setValidateAddresses(true);
        helper.setFrom(from);
        helper.setTo(recipient);
        helper.setSubject(page.preview().subject());
        helper.setText(templates.process("email/campaign-content", context), true);
        mail.send(message);
    }
}
