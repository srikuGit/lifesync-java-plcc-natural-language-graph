package com.lifesync.poc.classification;

import java.util.Map;
import java.util.Set;

public final class CategoryCatalog {
    private CategoryCatalog() {}
    public static final Set<String> SUPPORTED = Set.of(
            "HOME_FURNISHINGS", "APPLIANCES", "ELECTRONICS", "HOME_IMPROVEMENT",
            "FLOORING", "JEWELRY", "OUTDOOR_LIVING", "SPECIALTY_RETAIL", "APPAREL");

    private static final Map<String, String> FRIENDLY = Map.of(
            "HOME_FURNISHINGS", "Home Furnishings", "APPLIANCES", "Appliances",
            "ELECTRONICS", "Electronics", "HOME_IMPROVEMENT", "Home Improvement",
            "FLOORING", "Flooring", "JEWELRY", "Jewelry", "OUTDOOR_LIVING", "Outdoor Living",
            "SPECIALTY_RETAIL", "Specialty Retail", "APPAREL", "Apparel");

    public static String normalize(String text) {
        return text == null ? "" : text.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }
    public static boolean supported(String category) { return SUPPORTED.contains(category); }
    public static String display(String category) { return FRIENDLY.getOrDefault(category, "Unsupported"); }
}
