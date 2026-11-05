package com.lifesync.poc.domain;

import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Deliberately small, allow-listed DTOs; no account number, routing number or SSN fields. */
public final class Models {
    private Models() {}

    public record Customer(String customerId, String ecn, String name,
                           String city, String state, String postalCode,
                           Double latitude, Double longitude, String plccAccountStatus) {
        public Customer(String customerId, String ecn, String name, String city,
                        String state, String postalCode, Double latitude, Double longitude) {
            this(customerId, ecn, name, city, state, postalCode, latitude, longitude, "UNKNOWN");
        }
        public boolean plccHolder() { return "ACTIVE".equals(plccAccountStatus); }
    }

    public record Goal(String goalId, String ecn, String description,
                       String goalType, String rawCategory, double targetAmount,
                       String currency, String endDate, String fingerprint) {}

    public record Merchant(String merchantId, String name, String industry,
                           String description, String rawCategory, boolean active,
                           String city, String state, String postalCode, String addressLine,
                           Double latitude, Double longitude, List<String> salesChannels) {}

    public record MerchantMatch(String merchantId, String storeId, String name,
                                String category, String description, String city,
                                String state, String addressLine,
                                double distanceMiles, int rank, String explanation,
                                Double semanticScore) {
        /** Synthetic addresses are not verified storefronts: show the approximate CITY area. */
        public String googleMapsUrl() {
            String cityQuery = city + ", " + state;
            return "https://www.google.com/maps/search/?api=1&query="
                    + URLEncoder.encode(cityQuery, StandardCharsets.UTF_8);
        }
    }

    public record GoalRow(String goalId, String description, String category, String status,
                          String customerEcn, String customerName, String city,
                          String previewId, String failureReason) {}

    public record GoalDetail(String goalId, String description, String category,
                             String status, String customerEcn, String customerName,
                             String customerCity, Double customerLat, Double customerLon,
                             String fingerprint, String plccAccountStatus, String customerId) {
        public GoalDetail(String goalId, String description, String category,
                          String status, String customerEcn, String customerName,
                          String customerCity, Double customerLat, Double customerLon,
                          String fingerprint) {
            this(goalId, description, category, status, customerEcn, customerName,
                 customerCity, customerLat, customerLon, fingerprint, "UNKNOWN", "");
        }
        public boolean plccHolder() { return "ACTIVE".equals(plccAccountStatus); }
        public boolean plccNonHolder() { return "NONE".equals(plccAccountStatus); }
    }

    public record Preview(String previewId, String goalId, String subject,
                          String generatedAt, String html) {}

    public record Counts(long customers, long goals, long merchants,
                         long ready, long pending, long processing,
                         long unsupported, long failed, long noMatches) {}

    public record VectorStats(long activeMerchants, long embeddedMerchants, long embeddedGoals,
                              String indexState) {}

    public record RecommendationMetadata(String retrievalMode, String explanationSource,
                                         String decisionPlan, String decisionEvaluation,int searchAttempts) {
        public RecommendationMetadata(String retrievalMode, String explanationSource) {
            this(retrievalMode, explanationSource,"NOT_GENERATED","NOT_GENERATED",0);
        }
    }

    public record ProcessingResult(String goalId, String status, int merchantCount, String message) {}

    /** Offers are ephemeral REST data, never Neo4j nodes or properties. */
    public record Offer(String offerId, String audience, String customerId, String merchantId,
                        String title, String description, String status,
                        String validFrom, String validUntil) {}
    public record OfferResponse(List<Offer> offers) {}
    public record OfferedMerchant(MerchantMatch merchant, List<Offer> offers) {}
}
