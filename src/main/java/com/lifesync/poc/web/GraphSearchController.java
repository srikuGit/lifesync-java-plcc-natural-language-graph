package com.lifesync.poc.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

/** Existing bookmarks now use the customer-scoped MCP question flow. */
@Controller
public class GraphSearchController {
    @GetMapping("/graph-search")
    public String search(@RequestParam(defaultValue="") String customerId,
                         @RequestParam(defaultValue="") String question) {
        return "redirect:"+UriComponentsBuilder.fromPath("/experience").queryParam("customerId",customerId)
            .queryParam("question",question).build().encode().toUriString();
    }
}
