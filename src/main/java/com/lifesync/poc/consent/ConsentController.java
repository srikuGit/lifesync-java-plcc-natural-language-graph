package com.lifesync.poc.consent;

import com.lifesync.poc.graph.GraphRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ConsentController {
    private final ConsentLinkService links;
    private final GraphRepository graph;
    public ConsentController(ConsentLinkService links,GraphRepository graph) {
        this.links=links;this.graph=graph;
    }
    @GetMapping("/consent")
    public String form(@RequestParam String token,Model model) {
        String goalId=links.validatedGoalId(token);
        if(goalId==null || graph.goal(goalId).filter(g->g.plccNonHolder() && "READY".equals(g.status())).isEmpty())
            return "consent-invalid";
        model.addAttribute("token",token);
        return "consent";
    }
    @PostMapping("/consent")
    public String submit(@RequestParam String token,
                         @RequestParam(name="permission",defaultValue="false") boolean permission) {
        String goalId=links.validatedGoalId(token);
        if(!permission || goalId==null || graph.goal(goalId)
                .filter(g->g.plccNonHolder() && "READY".equals(g.status())).isEmpty())
            return "consent-invalid";
        graph.saveConsentRequest(goalId);
        return "consent-success";
    }
}
