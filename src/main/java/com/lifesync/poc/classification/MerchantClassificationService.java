package com.lifesync.poc.classification;

import com.lifesync.poc.domain.Models.Merchant;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Semantic-only merchant taxonomy classification, including non-PLCC categories. */
@Service
public class MerchantClassificationService {
    private final SemanticCategoryClassifier semantic;

    public MerchantClassificationService(SemanticCategoryClassifier semantic) { this.semantic = semantic; }

    public String classify(Merchant merchant) {
        return classifyAll(merchant).stream().filter(CategoryCatalog::supported)
                .findFirst().orElse("UNSUPPORTED");
    }

    public List<String> classifyAll(Merchant merchant) {
        if (!merchant.active()) return List.of();
        // Business eligibility exclusion, NOT a category keyword classification rule.
        // A repair-only merchant must not be represented as a product retailer.
        String description = Objects.toString(merchant.description(), "").toLowerCase(Locale.ROOT);
        if (description.contains("repair only") || description.contains("rental only")
                || description.contains("services only") || description.contains("not for sale"))
            return List.of();
        String text = "Merchant retail inventory and products sold. Business: "
                + Objects.toString(merchant.name(), "")
                + ". Industry: " + Objects.toString(merchant.industry(), "")
                + ". Description: " + Objects.toString(merchant.description(), "")
                + ". Source category: " + Objects.toString(merchant.rawCategory(), "");
        return semantic.classify(text, false, 4).categories();
    }
}
