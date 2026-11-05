package com.lifesync.poc.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.product.CustomerSubgraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.*;

/** The UI service consumes the same MCP contract available to other product applications. */
@Service
public class GraphMcpClient {
    private final RestClient rest;
    private final ObjectMapper mapper;
    public GraphMcpClient(ObjectMapper mapper,
            @Value("${lifesync.graph-mcp-url:http://127.0.0.1:8080/mcp}") String url) {
        this.mapper=mapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(15000);
        rest=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public CustomerSubgraph customerGraph(String customerId, String product) {
        // Stateless transport: negotiate capabilities for each independent exchange.
        rpc("initialize",Map.of("protocolVersion",GraphMcpController.PROTOCOL,
            "capabilities",Map.of(),"clientInfo",Map.of("name","lifesync-ui","version","0.9.0")));
        rest.post().contentType(MediaType.APPLICATION_JSON).body(Map.of("jsonrpc","2.0",
            "method","notifications/initialized")).retrieve().toBodilessEntity();
        Map<String,String> args = new HashMap<>();
        args.put("customerId",customerId);
        if (product != null && !product.isBlank()) args.put("productId",product);
        JsonNode result=rpc("tools/call",Map.of("name","get_customer_subgraph","arguments",args));
        if (result.path("isError").asBoolean()) throw new NoSuchElementException("No demo customer found.");
        try {
            CustomerSubgraph graph=mapper.readValue(result.path("content").path(0).path("text").asText(),
                CustomerSubgraph.class);
            if (!customerId.equals(graph.customerId())) throw new IllegalStateException("MCP customer scope mismatch");
            return graph;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Invalid MCP graph response",ex);
        }
    }
    private JsonNode rpc(String method, Object params) {
        String id=UUID.randomUUID().toString();
        JsonNode response=rest.post().contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON,MediaType.TEXT_EVENT_STREAM)
            .header("MCP-Protocol-Version",GraphMcpController.PROTOCOL)
            .body(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params))
            .retrieve().body(JsonNode.class);
        if (response == null || !id.equals(response.path("id").asText()) || response.has("error"))
            throw new IllegalStateException("Graph MCP request failed");
        return response.path("result");
    }
}
