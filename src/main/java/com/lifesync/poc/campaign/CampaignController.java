package com.lifesync.poc.campaign;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Local administrative preview workflow; no send route exists. */
@Controller
public class CampaignController {
    static final String TOKEN="manualCampaignStartToken";
    private final ManualCampaignService campaigns;
    public CampaignController(ManualCampaignService campaigns){this.campaigns=campaigns;}
    @GetMapping("/campaigns")
    public String list(Model model,HttpSession session) {
        String token=UUID.randomUUID().toString();session.setAttribute(TOKEN,token);
        model.addAttribute("campaignToken",token);model.addAttribute("runs",campaigns.runs());model.addAttribute("busy",campaigns.busy());
        return "campaign-list";
    }
    @PostMapping("/campaigns")
    public String start(@RequestParam String token,@RequestParam(defaultValue="25") int limit,
            HttpSession session,RedirectAttributes redirect) {
        Object expected=session.getAttribute(TOKEN);
        if(!(expected instanceof String saved)||token.length()>100
            ||!MessageDigest.isEqual(saved.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Refresh the campaigns page before starting a run.");
        try {
            String run=campaigns.start(limit);session.removeAttribute(TOKEN);
            return "redirect:/campaigns/"+run;
        } catch(IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("notice",ex.getMessage());return "redirect:/campaigns";
        }
    }
    @GetMapping("/campaigns/{runId}")
    public String run(@PathVariable String runId,Model model) {
        model.addAttribute("run",campaigns.run(runId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Campaign run not found.")));
        model.addAttribute("previews",campaigns.previews(runId));return "campaign-run";
    }
    @GetMapping("/campaigns/{runId}/previews/{customerId}")
    public String preview(@PathVariable String runId,@PathVariable String customerId,Model model) {
        try {model.addAttribute("page",campaigns.preview(runId,customerId));}
        catch(NoSuchElementException ex){throw new ResponseStatusException(HttpStatus.NOT_FOUND,ex.getMessage());}
        catch(IllegalStateException ex){model.addAttribute("previewError",ex.getMessage());}
        catch(Exception ex){model.addAttribute("previewError","Current graph and offer facts are unavailable. Please retry later.");}
        model.addAttribute("runId",runId);return "campaign-preview";
    }
}
