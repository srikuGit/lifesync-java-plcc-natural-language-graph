package com.lifesync.poc.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.product.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GraphMcpControllerTest {
    private final CustomerSubgraphService graph=mock(CustomerSubgraphService.class);
    private MockMvc mvc;
    @BeforeEach void setup() { mvc=MockMvcBuilders.standaloneSetup(new GraphMcpController(graph,new ObjectMapper())).build(); }
    private ResultActions rpc(String json) throws Exception {
        return mvc.perform(post("/mcp").contentType("application/json").content(json));
    }
    @Test void initializesAndDiscoversReadOnlyTool() throws Exception {
        rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"));
        rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
            .andExpect(jsonPath("$.result.tools[0].name").value("get_customer_subgraph"))
            .andExpect(jsonPath("$.result.tools[0].annotations.readOnlyHint").value(true));
        rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").andExpect(status().isAccepted());
    }
    @Test void callsOnlyScopedToolAndReportsUnknownCustomers() throws Exception {
        when(graph.load("C1","AUTO_LOAN")).thenReturn(new CustomerSubgraph("C1","Demo",List.of()));
        rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_customer_subgraph\",\"arguments\":{\"customerId\":\"C1\",\"productId\":\"AUTO_LOAN\"}}}")
            .andExpect(jsonPath("$.result.isError").value(false));
        verify(graph).load("C1","AUTO_LOAN");
        when(graph.load("missing","")).thenThrow(new NoSuchElementException());
        rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_customer_subgraph\",\"arguments\":{\"customerId\":\"missing\"}}}")
            .andExpect(jsonPath("$.result.isError").value(true));
    }
    @Test void rejectsCypherUnexpectedArgumentsAndInvalidMethods() throws Exception {
        rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_customer_subgraph\",\"arguments\":{\"customerId\":\"C1\",\"cypher\":\"MATCH (n) RETURN n\"}}}")
            .andExpect(jsonPath("$.error.code").value(-32602));
        rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"execute_cypher\"}")
            .andExpect(jsonPath("$.error.code").value(-32601));
        verifyNoInteractions(graph);
    }
    @Test void rejectsForeignOriginAndUnsupportedProtocol() throws Exception {
        mvc.perform(post("/mcp").header("Origin","https://attacker.example").contentType("application/json")
            .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/mcp").header("MCP-Protocol-Version","invalid").contentType("application/json")
            .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/mcp")).andExpect(status().isMethodNotAllowed());
    }
    @Test void profileAndGoalToolsAreSeparateAndStrictlyValidateArguments() throws Exception {
        when(graph.profile("C1")).thenReturn(new CustomerProfile("C1","Priya","Shah","Seattle","WA","ACTIVE"));
        when(graph.listGoals("C1")).thenReturn(new CustomerSubgraph("C1","Priya Shah",List.of()));
        rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_customer_profile\",\"arguments\":{\"customerId\":\"C1\"}}}")
            .andExpect(jsonPath("$.result.isError").value(false));
        verify(graph).profile("C1");verify(graph,never()).listGoals(anyString());
        rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_customer_goals\",\"arguments\":{\"customerId\":\"C1\"}}}")
            .andExpect(jsonPath("$.result.isError").value(false));
        verify(graph).listGoals("C1");
        rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_customer_profile\",\"arguments\":{\"customerId\":\"C1\",\"goalIds\":[]}}}")
            .andExpect(jsonPath("$.error.code").value(-32602));
    }
    @Test void merchantToolParsesTypedFiltersAndRejectsCoercion() throws Exception {
        when(graph.findPlccMerchants("C1",List.of("G1"),5.,15.,2)).thenReturn(new CustomerSubgraph("C1","Person",List.of()));
        rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"find_plcc_merchants\",\"arguments\":{\"customerId\":\"C1\",\"goalIds\":[\"G1\"],\"maxDistanceMiles\":5,\"minDiscountPercent\":15,\"limit\":2}}}")
            .andExpect(jsonPath("$.result.isError").value(false));
        verify(graph).findPlccMerchants("C1",List.of("G1"),5.,15.,2);
        rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"find_plcc_merchants\",\"arguments\":{\"customerId\":\"C1\",\"maxDistanceMiles\":\"5\"}}}")
            .andExpect(jsonPath("$.error.code").value(-32602));
    }

}
