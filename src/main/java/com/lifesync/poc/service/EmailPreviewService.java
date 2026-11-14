package com.lifesync.poc.service;

import com.lifesync.poc.consent.ConsentLinkService;
import com.lifesync.poc.domain.Models.*;
import com.lifesync.poc.graph.GraphRepository;
import com.lifesync.poc.offers.OffersClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Preview is rendered on demand; fresh offers are NEVER written to Neo4j. */
@Service
public class EmailPreviewService {
    private final TemplateEngine templates;
    private final GraphRepository graph;
    private final OffersClient offers;
    private final ConsentLinkService consent;
    private final String bankName;
    public EmailPreviewService(TemplateEngine templates,GraphRepository graph,
            OffersClient offers,ConsentLinkService consent,
            @Value("${lifesync.demo-bank-name:DEMO bank}") String bankName) {
        this.templates=templates;this.graph=graph;this.offers=offers;this.consent=consent;
        this.bankName=bankName;
    }
    public String subjectHolder(GoalDetail g) {
        return "Explore nearby merchants for your DEMO bank goal";
    }
    public String subjectInvitation(GoalDetail g) {
        return "Explore PLCC options for your savings goal — demo invitation";
    }
    public Preview renderLatest(String goalId) {
        GoalDetail goal=graph.goal(goalId).orElseThrow(()->new IllegalArgumentException("Goal not found"));
        Preview saved=graph.preview(goalId).orElseThrow(()->new IllegalArgumentException("Preview not ready"));
        Context context=new Context();
        context.setVariable("bankName",bankName);
        context.setVariable("customerName",goal.customerName());
        context.setVariable("goalDescription",goal.description());
        String html;
        if(goal.plccHolder()) {
            List<MerchantMatch> merchants=graph.recommendedMerchants(goalId);
            if(merchants.isEmpty()) throw new IllegalStateException("Holder recommendation has no stores");
            List<String> ids=merchants.stream().map(MerchantMatch::merchantId).distinct().toList();
            List<Offer> latest=offers.currentOffers(goal.customerId(),ids);
            Map<String,List<Offer>> byMerchant=latest.stream().collect(Collectors.groupingBy(Offer::merchantId));
            List<OfferedMerchant> withOffers=merchants.stream()
                    .map(m->new OfferedMerchant(m,byMerchant.getOrDefault(m.merchantId(),List.of()))).toList();
            context.setVariable("introduction",graph.recommendationIntroduction(goalId));
            context.setVariable("merchants",withOffers);
            html=templates.process("email/recommendation-email",context);
        } else if(goal.plccNonHolder()) {
            context.setVariable("consentUrl",consent.generate(goalId));
            html=templates.process("email/plcc-invitation",context);
        } else throw new IllegalStateException("Account status unknown; no email permitted");
        return new Preview(saved.previewId(),saved.goalId(),saved.subject(),saved.generatedAt(),html);
    }
}
