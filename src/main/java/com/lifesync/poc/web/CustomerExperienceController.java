package com.lifesync.poc.web;

import com.lifesync.poc.conversation.*;
import com.lifesync.poc.product.*;
import com.lifesync.poc.consent.PlccContactService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Controller
public class CustomerExperienceController {
    static final String SNAPSHOT="customerGraphSnapshot";
    static final String CONVERSATION="customerConversation";
    static final String TOKEN="conversationToken";
    record Snapshot(CustomerSubgraph graph,long loadedAt) {}
    public record Question(String customerId,String question,String conversationId,String token) {}
    private final ConversationService conversations;
    private final PlccContactService contacts;
    public CustomerExperienceController(ConversationService conversations,PlccContactService contacts){this.conversations=conversations;this.contacts=contacts;}
    @GetMapping({"/","/login","/experience"})
    public String page(@RequestParam(required=false) String customerId,@RequestParam(defaultValue="") String question,
                       Model model,HttpSession session){
        model.addAttribute("customerIdInput",customerId==null?"":customerId);
        model.addAttribute("questionInput",question);
        if(customerId!=null&&!customerId.isBlank()) {
            try {
                ConversationState state=(ConversationState)session.getAttribute(CONVERSATION);
                if(state==null||!state.profile().customerId().equals(customerId.strip())){
                    state=conversations.start(customerId.strip());
                    session.setAttribute(CONVERSATION,state);session.setAttribute(TOKEN,UUID.randomUUID().toString());
                    session.removeAttribute(SNAPSHOT);session.removeAttribute("plccContactToken");
                } else conversations.refreshProfile(state);
                model.addAttribute("conversation",state);model.addAttribute("profile",state.profile());
                model.addAttribute("messages",state.messages());model.addAttribute("chatToken",session.getAttribute(TOKEN));
                consent(state.lastReply(),model,session);
            } catch(IllegalArgumentException|NoSuchElementException ex){model.addAttribute("error",ex.getMessage());}
            catch(Exception ex){model.addAttribute("error","Customer details are temporarily unavailable. Please retry.");}
        }
        return "experience";
    }
    @PostMapping(value={"/experience/chat","/experience/question"},consumes="application/json")
    public String chat(@RequestBody Question request,Model model,HttpSession session){
        ConversationState state=validated(request,session);
        ConversationState.Reply reply;
        try{reply=conversations.turn(state,request.question());}
        catch(IllegalArgumentException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,ex.getMessage());}
        catch(IllegalStateException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,ex.getMessage());}
        if(session.getAttribute(CONVERSATION)!=state)throw new ResponseStatusException(HttpStatus.CONFLICT,"Customer selection changed. Reload this page.");
        model.addAttribute("reply",reply);model.addAttribute("messages",state.messages());consent(reply,model,session);
        return "fragments/conversation :: turn";
    }
    @PostMapping(value="/experience/reset",consumes="application/json")
    @ResponseBody public Map<String,String> reset(@RequestBody Question request,HttpSession session){
        var state=validated(request,session);
        if(!state.resetIfIdle())throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for the current reply before starting a new chat.");
        session.removeAttribute(SNAPSHOT);session.removeAttribute("plccContactToken");
        return Map.of("status","cleared");
    }
    private ConversationState validated(Question q,HttpSession session){
        Object value=session.getAttribute(CONVERSATION);String expected=(String)session.getAttribute(TOKEN);
        if(!(value instanceof ConversationState state)||q.customerId()==null||!q.customerId().equals(state.profile().customerId())
            ||q.conversationId()==null||!q.conversationId().equals(state.id())||q.token()==null||expected==null||q.token().length()>100
            ||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),q.token().getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Reload the selected customer before sending a message.");
        return state;
    }
    private void consent(ConversationState.Reply reply,Model model,HttpSession session){
        if(reply==null||!reply.showConsent()||reply.graph()==null)return;
        session.setAttribute(SNAPSHOT,new Snapshot(reply.graph(),System.currentTimeMillis()));
        boolean saved=contacts.hasConsent(reply.graph().customerId());model.addAttribute("contactSaved",saved);
        if(saved)session.removeAttribute("plccContactToken");
        else {String token=UUID.randomUUID().toString();session.setAttribute("plccContactToken",token);
            model.addAttribute("contactToken",token);model.addAttribute("contactCustomerId",reply.graph().customerId());
            model.addAttribute("consentText",PlccContactService.CONSENT_TEXT);}
    }
}
