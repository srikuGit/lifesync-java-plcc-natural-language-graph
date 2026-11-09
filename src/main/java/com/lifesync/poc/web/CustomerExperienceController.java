package com.lifesync.poc.web;

import com.lifesync.poc.product.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.NoSuchElementException;

@Controller
public class CustomerExperienceController {
    private final CustomerExperienceService experience;
    private final GraphQuestionService questions;
    private final com.lifesync.poc.consent.PlccContactService contacts;
    static final String SNAPSHOT="customerGraphSnapshot";
    record Snapshot(CustomerSubgraph graph,long loadedAt) {}
    public record Question(String customerId,String question) {}
    public CustomerExperienceController(CustomerExperienceService experience,GraphQuestionService questions,com.lifesync.poc.consent.PlccContactService contacts) {
        this.experience=experience; this.questions=questions; this.contacts=contacts;
    }
    @GetMapping({"/","/login","/experience"})
    public String page(@RequestParam(required=false) String customerId,
                       @RequestParam(defaultValue="") String question,Model model,HttpSession session) {
        model.addAttribute("customerIdInput",customerId==null?"":customerId);
        model.addAttribute("questionInput",question);
        if (customerId!=null && !customerId.isBlank()) {
            session.removeAttribute(SNAPSHOT);
            try {
                // Load only PLCC for the customer UI. Initial rendering never waits for a model call.
                var page=experience.load(customerId.strip(),"PLCC");
                session.setAttribute(SNAPSHOT,new Snapshot(page.graph(),System.currentTimeMillis()));
                model.addAttribute("page",page);
                if(page.graph().goals().stream().anyMatch(g->"PLCC_ACCOUNT_RESTRICTED".equals(g.status()))) {
                    boolean saved=contacts.hasConsent(page.graph().customerId());
                    model.addAttribute("contactSaved",saved);
                    if(saved) session.removeAttribute("plccContactToken");
                    else {
                        String token=java.util.UUID.randomUUID().toString();
                        session.setAttribute("plccContactToken",token);
                        model.addAttribute("contactToken",token);
                        model.addAttribute("consentText",com.lifesync.poc.consent.PlccContactService.CONSENT_TEXT);
                    }
                }
                // Preserve old question links; interactive questions use the separate POST endpoint.
                if(!question.isBlank()) model.addAttribute("queryResult",questions.answer(page.graph(),question));
            } catch(IllegalArgumentException | NoSuchElementException ex) {model.addAttribute("error",ex.getMessage());}
            catch(Exception ex) {model.addAttribute("error","The customer graph is temporarily unavailable. Please retry.");}
        }
        return "experience";
    }
    @PostMapping(value="/experience/question",consumes="application/json")
    public String question(@RequestBody Question request,Model model,HttpSession session) {
        var graph=snapshot(request.customerId(),session);
        if(request.question()==null || request.question().isBlank() || request.question().length()>500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a question of up to 500 characters.");
        model.addAttribute("page",experience.factual(graph));
        model.addAttribute("queryResult",questions.answer(graph,request.question()));
        return "fragments/customer-goals :: results";
    }
    @PostMapping(value="/experience/explain",consumes="application/json")
    public String explain(@RequestBody Question request,Model model,HttpSession session) {
        model.addAttribute("page",experience.compose(snapshot(request.customerId(),session)));
        return "fragments/customer-goals :: results";
    }
    private CustomerSubgraph snapshot(String customer,HttpSession session) {
        Object value=session.getAttribute(SNAPSHOT);
        if(!(value instanceof Snapshot saved) || customer==null || !customer.equals(saved.graph().customerId())
                || System.currentTimeMillis()-saved.loadedAt()>300_000)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Reload this customer's goals before asking a question.");
        return saved.graph();
    }
}
