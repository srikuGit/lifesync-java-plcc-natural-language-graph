package com.lifesync.poc.mcp;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphMcpClientTest {
    final ObjectMapper mapper=new ObjectMapper();
    final List<JsonNode> calls=Collections.synchronizedList(new ArrayList<>());
    HttpServer server;
    GraphMcpClient client;
    volatile String responseCustomer="C1";
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp",exchange->{
            JsonNode request=mapper.readTree(exchange.getRequestBody());
            if(!request.has("id")){exchange.sendResponseHeaders(202,-1);exchange.close();return;}
            Object result=Map.of();
            if("tools/call".equals(request.path("method").asText())) {
                calls.add(request.path("params"));
                Object content="get_customer_profile".equals(request.path("params").path("name").asText())
                    ? Map.of("customerId",responseCustomer,"firstName","Priya","lastName","Shah","city","Seattle","state","WA","plccAccountStatus","ACTIVE")
                    : Map.of("customerId",responseCustomer,"displayName","Priya Shah","goals",List.of());
                result=Map.of("content",List.of(Map.of("type","text","text",mapper.writeValueAsString(content))),"isError",false);
            }
            byte[] body=mapper.writeValueAsBytes(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();client=new GraphMcpClient(mapper,"http://127.0.0.1:"+server.getAddress().getPort()+"/mcp");
    }
    @AfterEach void close(){server.stop(0);}
    @Test void usesDistinctProfileGoalAndMerchantToolsWithServerSuppliedIdentity() {
        assertEquals("Priya",client.customerProfile("C1").firstName());
        assertEquals(1,calls.size());assertEquals("get_customer_profile",calls.getFirst().path("name").asText());
        client.customerGoals("C1");
        assertEquals("list_customer_goals",calls.get(1).path("name").asText());
        client.plccMerchants("C1",List.of("G1"),5.,15.,3);
        var args=calls.get(2).path("arguments");
        assertEquals("find_plcc_merchants",calls.get(2).path("name").asText());
        assertEquals("C1",args.path("customerId").asText());assertEquals("G1",args.path("goalIds").get(0).asText());
        assertEquals(5.,args.path("maxDistanceMiles").asDouble());assertEquals(15.,args.path("minDiscountPercent").asDouble());
    }
    @Test void rejectsAResponseForAnotherCustomer() {
        responseCustomer="FOREIGN";
        assertThrows(IllegalStateException.class,()->client.customerProfile("C1"));
        assertThrows(IllegalStateException.class,()->client.customerGoals("C1"));
    }
}
