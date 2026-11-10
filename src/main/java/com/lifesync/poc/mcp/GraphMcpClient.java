package com.lifesync.poc.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.product.CustomerProfile;
import com.lifesync.poc.product.CustomerSubgraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.*;

/** Typed MCP transport. Callers supply the server-bound customer identity, never model-selected IDs. */
@Service
public class GraphMcpClient {
    private final RestClient rest;
    private final ObjectMapper mapper;
    public GraphMcpClient(ObjectMapper mapper,
            @Value("${lifesync.graph-mcp-url:http://127.0.0.1:8080/mcp}") String url) {
        this.mapper=mapper;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);factory.setReadTimeout(15000);
        rest=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public CustomerProfile customerProfile(String customerId) {
        var profile=read("get_customer_profile",Map.of("customerId",customerId),CustomerProfile.class);
        if(profile==null||!customerId.equals(profile.customerId()))throw new IllegalStateException("MCP customer scope mismatch");
        return profile;
    }
    public CustomerSubgraph customerGoals(String customerId) {
        return scoped(customerId,read("list_customer_goals",Map.of("customerId",customerId),CustomerSubgraph.class));
    }
    public CustomerSubgraph plccMerchants(String customerId,List<String> goalIds,Double maxDistanceMiles,
            Double minDiscountPercent,int limit) {
        Map<String,Object> args=new LinkedHashMap<>();
        args.put("customerId",customerId);args.put("goalIds",goalIds);args.put("limit",limit);
        if(maxDistanceMiles!=null)args.put("maxDistanceMiles",maxDistanceMiles);
        if(minDiscountPercent!=null)args.put("minDiscountPercent",minDiscountPercent);
        return scoped(customerId,read("find_plcc_merchants",args,CustomerSubgraph.class));
    }
    /** Retained for campaign and compatibility consumers; non-PLCC details are stripped server-side. */
    public CustomerSubgraph customerGraph(String customerId,String product) {
        Map<String,Object> args=new HashMap<>();args.put("customerId",customerId);
        if(product!=null&&!product.isBlank())args.put("productId",product);
        return scoped(customerId,read("get_customer_subgraph",args,CustomerSubgraph.class));
    }
    private CustomerSubgraph scoped(String customerId,CustomerSubgraph graph) {
        if(graph==null||!customerId.equals(graph.customerId()))throw new IllegalStateException("MCP customer scope mismatch");
        return graph;
    }
    private <T> T read(String name,Map<String,?> args,Class<T> type) {
        rpc("initialize",Map.of("protocolVersion",GraphMcpController.PROTOCOL,"capabilities",Map.of(),
            "clientInfo",Map.of("name","lifesync-ui","version","0.9.0")));
        rest.post().contentType(MediaType.APPLICATION_JSON).header("MCP-Protocol-Version",GraphMcpController.PROTOCOL)
            .body(Map.of("jsonrpc","2.0","method","notifications/initialized")).retrieve().toBodilessEntity();
        JsonNode result=rpc("tools/call",Map.of("name",name,"arguments",args));
        if(result.path("isError").asBoolean())throw new NoSuchElementException("No customer found.");
        try {return mapper.readValue(result.path("content").path(0).path("text").asText(),type);}
        catch(java.io.IOException ex){throw new IllegalStateException("Invalid MCP graph response",ex);}
    }
    private JsonNode rpc(String method,Object params) {
        String id=UUID.randomUUID().toString();
        JsonNode response=rest.post().contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON,MediaType.TEXT_EVENT_STREAM)
            .header("MCP-Protocol-Version",GraphMcpController.PROTOCOL)
            .body(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params))
            .retrieve().body(JsonNode.class);
        if(response==null||!id.equals(response.path("id").asText()))throw new IllegalStateException("Graph MCP request failed");
        if(response.has("error")) {
            if(response.path("error").path("code").asInt()==-32602)throw new IllegalArgumentException("Graph MCP rejected tool arguments");
            throw new IllegalStateException("Graph MCP request failed");
        }
        return response.path("result");
    }
}
