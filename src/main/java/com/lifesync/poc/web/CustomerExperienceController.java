package com.lifesync.poc.web;

import com.lifesync.poc.product.CustomerExperienceService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.NoSuchElementException;

@Controller
public class CustomerExperienceController {
    private final CustomerExperienceService experience;
    public CustomerExperienceController(CustomerExperienceService experience) { this.experience=experience; }
    @GetMapping({"/","/login","/experience"})
    public String page(@RequestParam(required=false) String customerId,
                       @RequestParam(defaultValue="") String productId, Model model) {
        model.addAttribute("customerIdInput",customerId==null?"":customerId);
        model.addAttribute("productIdInput",productId);
        if (customerId != null && !customerId.isBlank()) {
            try { model.addAttribute("page",experience.load(customerId.strip(),productId)); }
            catch (IllegalArgumentException | NoSuchElementException ex) { model.addAttribute("error",ex.getMessage()); }
            catch (Exception ex) { model.addAttribute("error","The customer graph is temporarily unavailable. Please retry."); }
        }
        return "experience";
    }
}
