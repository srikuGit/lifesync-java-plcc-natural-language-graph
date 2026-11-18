package com.lifesync.poc.campaign;

import com.lifesync.poc.campaign.CampaignModels.PreviewPage;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.WebUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Local administrative campaign and individual email workflow. */
@Controller
public class CampaignController {
    static final String TOKEN="manualCampaignStartToken";
    private final ManualCampaignService campaigns;
    private final CampaignEmailDelivery delivery;
    private static final String SEND_TOKEN = "campaignEmailSendToken";
    private record SendToken(String value, String runId, String customerId) {}

    public CampaignController(ManualCampaignService campaigns, CampaignEmailDelivery delivery) {
        this.campaigns = campaigns;
        this.delivery = delivery;
    }
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
    @GetMapping({"/campaigns/{runId}/previews/{customerId}", "/experience/{customerId}/email"})
    public String preview(@PathVariable(required = false) String runId, @PathVariable String customerId,
            Model model, HttpSession session) {
        model.addAttribute("runId", runId);
        model.addAttribute("customerId", customerId);
        try {
            var page = loadPreview(runId, customerId);
            model.addAttribute("page", page);
            if (!page.graph().goals().isEmpty()) {
                String token = UUID.randomUUID().toString();
                synchronized (WebUtils.getSessionMutex(session)) {
                    session.setAttribute(SEND_TOKEN, new SendToken(token, runId, customerId));
                }
                model.addAttribute("sendToken", token);
                model.addAttribute("recipient", delivery.recipient());
            }
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            model.addAttribute("previewError", ex.getMessage());
        } catch (Exception ex) {
            model.addAttribute("previewError", "Current graph and offer facts are unavailable. Please retry later.");
        }
        return "campaign-preview";
    }

    @PostMapping({"/campaigns/{runId}/previews/{customerId}/send", "/experience/{customerId}/email/send"})
    public String send(@PathVariable(required = false) String runId, @PathVariable String customerId,
            @RequestParam String token, HttpSession session, RedirectAttributes redirect) {
        synchronized (WebUtils.getSessionMutex(session)) {
            if (!(session.getAttribute(SEND_TOKEN) instanceof SendToken saved)
                    || !Objects.equals(saved.runId(), runId) || !saved.customerId().equals(customerId)
                    || token.length() > 100 || !MessageDigest.isEqual(
                        saved.value().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Reopen the email preview before sending.");
            }
            session.removeAttribute(SEND_TOKEN);
        }

        try {
            var page = loadPreview(runId, customerId);
            if (page.graph().goals().isEmpty()) {
                redirect.addFlashAttribute("emailError", "No eligible goals are available to email.");
                return redirectToPreview(runId, customerId, redirect);
            }
            delivery.send(page);
        } catch (Exception ex) {
            redirect.addFlashAttribute("emailError", "Could not send the email. Check the preview and try again.");
            return redirectToPreview(runId, customerId, redirect);
        }

        redirect.addFlashAttribute("emailNotice", "Email is sent to " + delivery.recipient() + ".");
        if (runId == null) {
            redirect.addAttribute("customerId", customerId);
            return "redirect:/experience#customer-profile";
        }
        redirect.addAttribute("runId", runId);
        return "redirect:/campaigns/{runId}";
    }

    private PreviewPage loadPreview(String runId, String customerId) {
        return runId == null ? campaigns.profilePreview(customerId) : campaigns.preview(runId, customerId);
    }

    private String redirectToPreview(String runId, String customerId, RedirectAttributes redirect) {
        redirect.addAttribute("customerId", customerId);
        if (runId == null) return "redirect:/experience/{customerId}/email";
        redirect.addAttribute("runId", runId);
        return "redirect:/campaigns/{runId}/previews/{customerId}";
    }
}
