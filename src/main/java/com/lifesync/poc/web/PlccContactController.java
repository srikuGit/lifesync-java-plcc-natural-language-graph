package com.lifesync.poc.web;

import com.lifesync.poc.consent.PlccContactService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Controller
public class PlccContactController {
    private final PlccContactService contacts;
    public PlccContactController(PlccContactService contacts){this.contacts=contacts;}
    @PostMapping("/experience/plcc-contact")
    public String submit(@RequestParam String customerId,@RequestParam String token,
            @RequestParam(defaultValue="false") boolean permission,HttpSession session,RedirectAttributes redirect) {
        Object value=session.getAttribute(CustomerExperienceController.SNAPSHOT);
        String expected=(String)session.getAttribute("plccContactToken");
        if(!(value instanceof CustomerExperienceController.Snapshot saved) || expected==null || token.length()>100
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))
                || !customerId.equals(saved.graph().customerId()) || System.currentTimeMillis()-saved.loadedAt()>300_000
                || saved.graph().goals().stream().noneMatch(g->"PLCC_ACCOUNT_RESTRICTED".equals(g.status())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Reload this customer's goals before submitting consent.");
        if(!permission) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Please explicitly agree before requesting contact.");
        if(!contacts.request(customerId)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Account status changed; reload your goals.");
        session.removeAttribute("plccContactToken");
        redirect.addFlashAttribute("consentNotice","Your consent has been saved and a PLCC information contact request recorded. No actual call is initiated by this demonstration.");
        return "redirect:/experience?customerId="+java.net.URLEncoder.encode(customerId,StandardCharsets.UTF_8);
    }
}
