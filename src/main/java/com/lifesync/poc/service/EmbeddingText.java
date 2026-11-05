package com.lifesync.poc.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable, allow-listed text used for semantic search; no customer identity or banking data. */
public final class EmbeddingText {
    private EmbeddingText() {}

    public static String merchant(String name, String industry, String category, String description) {
        return "search_document: Merchant: " + trim(name, 100) + "\nProduct category: " + trim(category, 70)
                + "\nIndustry: " + trim(industry, 100) + "\nMerchandise: " + trim(description, 450);
    }

    public static String goal(String description, String category) {
        return "search_query: Customer purchase goal: " + trim(description, 400) + "\nProduct category: " + trim(category, 70);
    }

    public static String trim(String value, int limit) {
        if (value == null) return "";
        String safe = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").trim();
        return safe.substring(0, Math.min(safe.length(), limit));
    }

    public static String fingerprint(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
