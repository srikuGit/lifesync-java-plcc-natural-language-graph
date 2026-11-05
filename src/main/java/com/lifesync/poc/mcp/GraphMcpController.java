package com.lifesync.poc.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.product.CustomerSubgraphService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

/** Stateless MCP Streamable HTTP (JSON response mode), protocol version 2025-03-26. */
@RestController
public class GraphMcpController {
    public static final String PROTOCOL = "2025-03-26";
    private final CustomerSubgraphService graph;
    private final ObjectMapper mapper;
    public GraphMcpController(CustomerSubgraphService graph, ObjectMapper mapper) {
        this.graph=graph; this.mapper=mapper;
    }
    @PostMapping(value="/mcp",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handle(@RequestBody JsonNode request, HttpServletRequest http) {
        String origin = http.getHeader("Origin");
        if (origin != null && !sameOrigin(origin,http)) return ResponseEntity.status(403).build();
        String protocol = http.getHeader("MCP-Protocol-Version");
        if (protocol != null && !PROTOCOL.equals(protocol))
            return ResponseEntity.badRequest().body(error(request.get("id"),-32600,"Unsupported protocol version"));
        if (!request.isObject() || !"2.0".equals(request.path("jsonrpc").asText())
                || !request.path("method").isTextual())
            return ResponseEntity.ok(error(request.get("id"),-32600,"Invalid JSON-RPC request"));
        JsonNode id = request.get("id");
        String method = request.path("method").asText();
        if (id == null) {
            return method.startsWith("notifications/")
                    ? ResponseEntity.accepted().build()
                    : ResponseEntity.ok(error(null,-32600,"Request ID required"));
        }
        if (!(id.isTextual() || id.isNumber() || id.isNull()))
            return ResponseEntity.ok(error(null,-32600,"Invalid request ID"));
        try {
            Object result = switch (method) {
                case "initialize" -> Map.of("protocolVersion",PROTOCOL,
                    "capabilities",Map.of("tools",Map.of("listChanged",false)),
                    "serverInfo",Map.of("name","lifesync-customer-graph","version","0.9.0"),
                    "instructions","Synthetic local demo. Customer ID is a lookup key, not authorization. Read-only customer-scoped tools.");
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools",List.of(Map.of(
                    "name","get_customer_subgraph",
                    "description","Retrieve one customer's goals, product/category edges, five nearest eligible providers per goal, and current synthetic offers. Optional product filter. No Cypher input.",
                    "inputSchema",Map.of("type","object",
                        "properties",Map.of("customerId",Map.of("type","string","pattern","^[A-Za-z0-9_-]{1,64}$"),
                            "productId",Map.of("type","string","enum",List.of("PLCC","HOME_PURCHASE","HOME_REFINANCE","AUTO_LOAN"))),
                        "required",List.of("customerId"),"additionalProperties",false),
                    "annotations",Map.of("readOnlyHint",true,"destructiveHint",false,"idempotentHint",true,"openWorldHint",false))));
                case "tools/call" -> call(request.path("params"));
                default -> null;
            };
            return ResponseEntity.ok(result == null ? error(id,-32601,"Method not found")
                : Map.of("jsonrpc","2.0","id",id,"result",result));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.ok(error(id,-32602,ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.ok(error(id,-32603,"Graph service unavailable"));
        }
    }
    private Object call(JsonNode params) throws Exception {
        if (!"get_customer_subgraph".equals(params.path("name").asText()))
            throw new IllegalArgumentException("Unknown tool");
        JsonNode args = params.path("arguments");
        if (!args.isObject() || !args.path("customerId").isTextual()
                || (args.has("productId") && !args.get("productId").isTextual()))
            throw new IllegalArgumentException("customerId must be a string; productId is optional.");
        var names = args.fieldNames();
        while (names.hasNext()) if (!Set.of("customerId","productId").contains(names.next()))
            throw new IllegalArgumentException("Unexpected argument");
        try {
            Object subgraph = graph.load(args.path("customerId").asText(),args.path("productId").asText(""));
            return Map.of("content",List.of(Map.of("type","text","text",mapper.writeValueAsString(subgraph))),
                "isError",false);
        } catch (NoSuchElementException ex) {
            return Map.of("content",List.of(Map.of("type","text","text","No demo customer found.")),"isError",true);
        }
    }
    private static Map<String,Object> error(JsonNode id, int code, String message) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("jsonrpc","2.0"); result.put("id",id);
        result.put("error",Map.of("code",code,"message",message));
        return result;
    }
    private static boolean sameOrigin(String origin, HttpServletRequest request) {
        try {
            URI uri=URI.create(origin);
            int port=uri.getPort()<0 ? ("https".equals(uri.getScheme())?443:80) : uri.getPort();
            return request.getScheme().equals(uri.getScheme())
                && request.getServerName().equalsIgnoreCase(uri.getHost()) && request.getServerPort()==port;
        } catch (Exception ex) { return false; }
    }
}
