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
                case "tools/list" -> Map.of("tools",toolDefinitions());
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
        String name=params.path("name").asText();
        Set<String> allowed=switch(name) {
            case "get_customer_subgraph" -> Set.of("customerId","productId");
            case "get_customer_profile","list_customer_goals" -> Set.of("customerId");
            case "find_plcc_merchants" -> Set.of("customerId","goalIds","maxDistanceMiles","minDiscountPercent","limit");
            default -> throw new IllegalArgumentException("Unknown tool");
        };
        JsonNode args=params.path("arguments");
        if(!args.isObject()||!args.path("customerId").isTextual()) throw new IllegalArgumentException("customerId must be a string.");
        var names=args.fieldNames();
        while(names.hasNext())if(!allowed.contains(names.next()))throw new IllegalArgumentException("Unexpected argument");
        String customer=args.path("customerId").asText();
        try {
            Object data=switch(name) {
                case "get_customer_profile" -> graph.profile(customer);
                case "list_customer_goals" -> graph.listGoals(customer);
                case "get_customer_subgraph" -> {
                    if(args.has("productId")&&!args.get("productId").isTextual())throw new IllegalArgumentException("productId must be a string.");
                    yield graph.load(customer,args.path("productId").asText(""));
                }
                default -> {
                    List<String> ids=new ArrayList<>();
                    if(args.has("goalIds")) {
                        if(!args.get("goalIds").isArray()||args.get("goalIds").size()>200)throw new IllegalArgumentException("Invalid goalIds.");
                        for(var id:args.get("goalIds")) {
                            if(!id.isTextual())throw new IllegalArgumentException("Goal IDs must be strings.");
                            ids.add(id.asText());
                        }
                    }
                    if(args.has("limit")&&(!args.get("limit").isIntegralNumber()||!args.get("limit").canConvertToInt()))
                        throw new IllegalArgumentException("limit must be an integer.");
                    yield graph.findPlccMerchants(customer,ids,number(args,"maxDistanceMiles"),number(args,"minDiscountPercent"),args.path("limit").asInt(5));
                }
            };
            return Map.of("content",List.of(Map.of("type","text","text",mapper.writeValueAsString(data))),"isError",false);
        } catch(NoSuchElementException ex) {
            return Map.of("content",List.of(Map.of("type","text","text","No customer found.")),"isError",true);
        }
    }
    private static Double number(JsonNode args,String field) {
        var value=args.get(field);
        if(value==null||value.isNull())return null;
        if(!value.isNumber()||!Double.isFinite(value.asDouble()))throw new IllegalArgumentException(field+" must be a finite number.");
        return value.asDouble();
    }
    private static List<Map<String,Object>> toolDefinitions() {
        var customer=Map.<String,Object>of("type","string","pattern","^[A-Za-z0-9_-]{1,64}$");
        return List.of(
            tool("get_customer_subgraph","Read one customer's goal projection. Non-PLCC goals never include providers or offers; PLCC details require an ACTIVE account.",
                Map.of("customerId",customer,"productId",Map.of("type","string","enum",List.of("PLCC","HOME_PURCHASE","HOME_REFINANCE","AUTO_LOAN")))),
            tool("get_customer_profile","Read customer header only: first/last name, city/state and PLCC account status. Does not retrieve goals.",Map.of("customerId",customer)),
            tool("list_customer_goals","List all of the customer's product goals without merchant or offer details.",Map.of("customerId",customer)),
            tool("find_plcc_merchants","Find up to five nearest merchants for this customer's PLCC goals, with current eligible offers. Other-product and foreign goal IDs are rejected; non-ACTIVE accounts receive no merchants. Empty goalIds selects all owned PLCC goals.",
                Map.of("customerId",customer,"goalIds",Map.of("type","array","maxItems",200,"uniqueItems",true,"items",Map.of("type","string","pattern","^[A-Za-z0-9_-]{1,80}$")),
                    "maxDistanceMiles",Map.of("type",List.of("number","null"),"minimum",0,"maximum",10000),
                    "minDiscountPercent",Map.of("type",List.of("number","null"),"minimum",0,"maximum",100),
                    "limit",Map.of("type","integer","minimum",1,"maximum",5)))
        );
    }
    private static Map<String,Object> tool(String name,String description,Map<String,Object> properties) {
        return Map.of("name",name,"description",description,
            "inputSchema",Map.of("type","object","properties",properties,"required",List.of("customerId"),"additionalProperties",false),
            "annotations",Map.of("readOnlyHint",true,"destructiveHint",false,"idempotentHint",true,"openWorldHint",false));
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
