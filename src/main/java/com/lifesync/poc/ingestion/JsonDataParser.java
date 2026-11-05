package com.lifesync.poc.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.domain.Models.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class JsonDataParser {
    private final ObjectMapper mapper;
    public JsonDataParser(ObjectMapper mapper) { this.mapper = mapper; }

    public List<Customer> readCustomers(JsonNode root) {
        requireArray(root, "customers");
        List<Customer> customers = new ArrayList<>();
        for (JsonNode entry : root) {
            JsonNode data = entry.path("data").path("attributes");
            JsonNode names = data.path("name").path("fullName");
            JsonNode current = null;
            for (JsonNode usage : data.path("addressUsageList")) {
                if (usage.path("currentIndicator").asBoolean(false)
                        && "CUSTOMER_RESIDENCE".equals(usage.path("addressUsageCode").asText())) {
                    current = usage.path("address");
                    break;
                }
            }
            if (current == null) current = mapper.createObjectNode();
            JsonNode loc = data.path("approximateLocation");
            customers.add(new Customer(required(data, "customerId"), required(data, "ecn"),
                    names.isArray() && !names.isEmpty() ? names.get(0).asText() : "Demo Customer",
                    str(current, "city"), str(current, "stateCode"), str(current, "postalCode"),
                    latitude(loc), longitude(loc), accountStatus(data)));
        }
        return customers;
    }

    public List<Goal> readGoals(JsonNode root) {
        requireArray(root, "goals");
        List<Goal> goals = new ArrayList<>();
        for (JsonNode entry : root) {
            JsonNode attrs = entry.path("data").path("attributes");
            if (!attrs.isArray() || attrs.isEmpty()) throw new IllegalArgumentException("Goal missing attributes");
            JsonNode data = attrs.get(0);
            String category = "";
            for (JsonNode f : data.path("additionalFields")) {
                if ("BIG_PURCHASE_TYPE".equals(str(f, "type"))) {
                    category = str(f, "value");
                    break;
                }
            }
            String goalId = required(entry, "goalId");
            String ecn = required(entry, "ecn");
            String description = required(data, "customName");
            String goalType = str(data, "type");
            double targetAmount = data.path("targetAmount").asDouble(0);
            String currency = str(data, "currency");
            String endDate = str(data, "endDate");
            String fingerprint = sha256(String.join("|", goalId, ecn, description, goalType,
                    category, String.valueOf(targetAmount), currency, endDate));
            goals.add(new Goal(goalId, ecn, description, goalType, category,
                    targetAmount, currency, endDate, fingerprint));
        }
        return goals;
    }

    public List<Merchant> readMerchants(JsonNode root) {
        requireArray(root, "merchants");
        List<Merchant> merchants = new ArrayList<>();
        for (JsonNode entry : root) {
            JsonNode loc = entry.path("approximateLocation");
            List<String> channels = new ArrayList<>();
            for (JsonNode channel : entry.path("salesChannels")) channels.add(channel.asText());
            // Never copy bankName, accountNumber, routingNumber, businessSsn or other financial fields.
            merchants.add(new Merchant(required(entry, "merchantId"), str(entry, "dbaName"),
                    str(entry, "industryOptionText"), str(entry, "businessDescription"),
                    str(entry, "merchantCategory"), entry.path("active").asBoolean(false),
                    str(entry, "businessCity"), str(entry, "businessState"),
                    str(entry, "businessZipCode"), str(entry, "businessAddressLine1"),
                    latitude(loc), longitude(loc), List.copyOf(channels)));
        }
        return merchants;
    }

    /** UNKNOWN never means eligible for PLCC promotion; only explicit NONE is a prospect. */
    private static String accountStatus(JsonNode attributes) {
        String status = str(attributes, "plccAccountStatus").toUpperCase(java.util.Locale.ROOT);
        return switch (status) {
            case "ACTIVE", "NONE" -> status;
            default -> "UNKNOWN";
        };
    }

    private static void requireArray(JsonNode n, String kind) {
        if (!n.isArray()) throw new IllegalArgumentException(kind + " JSON root must be an array");
    }
    private static String required(JsonNode n, String field) {
        String value = str(n, field);
        if (value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }
    private static String str(JsonNode n, String field) {
        return n.path(field).isTextual() ? n.path(field).asText().trim() : "";
    }
    private static Double latitude(JsonNode location) { return coordinate(location, "latitude", -90, 90); }
    private static Double longitude(JsonNode location) { return coordinate(location, "longitude", -180, 180); }
    private static Double coordinate(JsonNode n, String key, double min, double max) {
        JsonNode value = n.path(key);
        if (!value.isNumber()) return null;
        double d = value.asDouble();
        return Double.isFinite(d) && d >= min && d <= max ? d : null;
    }
    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
