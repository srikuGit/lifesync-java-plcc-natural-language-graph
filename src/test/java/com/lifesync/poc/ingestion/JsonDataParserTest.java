package com.lifesync.poc.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.domain.Models.*;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JsonDataParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonDataParser parser = new JsonDataParser(mapper);

    @Test
    void demoJsonLoadsJoinsAndIncludesSupportedAndUnsupportedGoals() throws Exception {
        List<Customer> customers = parser.readCustomers(mapper.readTree(new File("data/input/customers.json")));
        List<Goal> goals = parser.readGoals(mapper.readTree(new File("data/input/goals.json")));
        List<Merchant> merchants = parser.readMerchants(mapper.readTree(new File("data/input/merchants.json")));
        assertEquals(50, customers.size());
        assertEquals(150, goals.size());
        assertEquals(52, merchants.size());
        Set<String> ecns = customers.stream().map(Customer::ecn).collect(Collectors.toSet());
        assertTrue(goals.stream().allMatch(g -> ecns.contains(g.ecn())));
        assertTrue(goals.stream().anyMatch(g -> g.rawCategory().equals("VEHICLES")));
        assertTrue(goals.stream().anyMatch(g -> g.rawCategory().equals("APPLIANCES")));
        assertNull(customers.get(0).latitude()); // Resolved by LocationService after ingestion.
        assertTrue(customers.stream().allMatch(c -> c.ecn().matches("[0-9]{12}")));
        assertTrue(customers.stream().noneMatch(c -> c.name().contains("Demo")));
        assertNull(merchants.get(0).longitude());
    }

    @Test
    void onlyAllowListedMerchantFieldsAreRetained() throws Exception {
        List<Merchant> merchants = parser.readMerchants(mapper.readTree(new File("data/input/merchants.json")));
        String json = mapper.writeValueAsString(merchants);
        assertFalse(json.contains("accountNumber"));
        assertFalse(json.contains("routingNumber"));
        assertFalse(json.contains("businessSsn"));
        assertFalse(json.contains("bankName"));
    }

    @Test
    void goalFingerprintsChangeWithGoalContent() throws Exception {
        var node = mapper.readTree("""
            [{"ecn":"E1","goalId":"G1","data":{"attributes":[{"type":"BIG_PURCHASE",
             "customName":"Buy a refrigerator","targetAmount":100,"currency":"USD",
             "endDate":"01/01/2028","additionalFields":[{"type":"BIG_PURCHASE_TYPE","value":"APPLIANCES"}]}]}}]
            """);
        String original = parser.readGoals(node).getFirst().fingerprint();
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.get(0).path("data").path("attributes").get(0))
                .put("customName", "Buy a freezer");
        assertNotEquals(original, parser.readGoals(node).getFirst().fingerprint());
    }
}
