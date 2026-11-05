package com.lifesync.poc.product;

import java.util.List;

/** Bounded, customer-scoped graph projection. No account identifiers or exact coordinates. */
public record CustomerSubgraph(String customerId, String displayName, List<Goal> goals) {
    public record Goal(String goalId, String description, String productId, String productName,
                       List<String> categories, String status, List<Provider> providers) {}
    public record Provider(String merchantId, String storeId, String name, String city, String state,
                           double distanceMiles, List<ProductOfferCatalog.Offer> offers) {}
}
