package com.lifesync.poc.web;

import com.lifesync.poc.product.CustomerExperienceService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.NoSuchElementException;

@Controller
public class CustomerExperienceController {
    private final CustomerExperienceService experience;
    private final com.lifesync.poc.product.GraphQuestionService questions;
    public CustomerExperienceController(CustomerExperienceService experience, com.lifesync.poc.product.GraphQuestionService questions) { this.experience=experience; this.questions=questions; }
    @GetMapping({"/","/login","/experience"})
    public String page(@RequestParam(required=false) String customerId,
                       @RequestParam(defaultValue="") String productId, @RequestParam(defaultValue="") String question, Model model) {
        model.addAttribute("customerIdInput",customerId==null?"":customerId);
        model.addAttribute("productIdInput",productId);
        model.addAttribute("questionInput",question);
        if (customerId != null && !customerId.isBlank()) {
            try {
                var page=experience.load(customerId.strip(),productId);
                model.addAttribute("page",page);
                if (!question.isBlank()) model.addAttribute("queryResult",questions.answer(page.graph(),question));
            }
            catch (IllegalArgumentException | NoSuchElementException ex) { model.addAttribute("error",ex.getMessage()); }
            catch (Exception ex) { model.addAttribute("error","The customer graph is temporarily unavailable. Please retry."); }
        }
        return "experience";
    }
}
