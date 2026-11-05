package com.lifesync.poc.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** Deterministic retrieval policy. Never asks an LLM to choose or evaluate merchants.
 *  PLCC eligibility remains a separate mandatory graph/business check. */
@Service
public class RetrievalDecisionService {
    public static final double HIGH_CONFIDENCE = 0.85;
    public static final int MIN_GRAPH_MERCHANTS = 3;
    public static final int MIN_SEMANTIC_MATCHES = 3;
    public static final int MAX_ATTEMPTS = 2;

    public record Evidence(List<String> categories, double confidence, String source,
                           int graphMerchantCount, int graphStoreCount, boolean vectorEnabled) {
        public Evidence {
            categories = List.copyOf(categories);
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)
                throw new IllegalArgumentException("Invalid classification confidence");
            if (graphMerchantCount < 0 || graphStoreCount < 0)
                throw new IllegalArgumentException("Negative graph candidate counts");
        }
        public String metrics() {
            return String.format(Locale.ROOT,
                    "categories=%s;confidence=%.2f;source=%s;graphMerchants=%d;graphStores=%d;vectorEnabled=%s",
                    String.join(",", categories), confidence, source, graphMerchantCount, graphStoreCount, vectorEnabled);
        }
        public String hybridTriggers() {
            StringBuilder reasons = new StringBuilder();
            if (categories.size() > 1) reasons.append("MULTI_CATEGORY,");
            if (confidence < HIGH_CONFIDENCE) reasons.append("LOW_CONFIDENCE,");
            if (graphMerchantCount < MIN_GRAPH_MERCHANTS) reasons.append("FEW_GRAPH_MERCHANTS,");
            return reasons.isEmpty() ? "NONE" : reasons.substring(0, reasons.length() - 1);
        }
    }
    public record Plan(String strategy, String reason, String source) {}
    public record Assessment(boolean retry, String reason, String source) {}

    public Plan plan(Evidence evidence) {
        if (evidence.graphMerchantCount() == 0)
            return new Plan("GRAPH", "NO_ELIGIBLE_GRAPH_MERCHANTS;" + evidence.metrics(), "JAVA_POLICY");
        if (!evidence.vectorEnabled())
            return new Plan("GRAPH", "VECTOR_DISABLED;" + evidence.metrics(), "JAVA_POLICY");
        String triggers = evidence.hybridTriggers();
        String strategy = triggers.equals("NONE") ? "GRAPH" : "HYBRID";
        return new Plan(strategy, "triggers=" + triggers + ";" + evidence.metrics(), "JAVA_POLICY");
    }

    /** Counts only vector-matched merchants that were independently eligible in the graph. */
    public Assessment evaluate(Evidence evidence, int semanticCount, int attempt) {
        if (semanticCount < 0) throw new IllegalArgumentException("Negative semantic count");
        if (attempt < 1 || attempt > MAX_ATTEMPTS)
            throw new IllegalArgumentException("Invalid retrieval attempt");
        int required = Math.min(MIN_SEMANTIC_MATCHES, evidence.graphMerchantCount());
        String metrics = String.format(Locale.ROOT,
                "graphMerchants=%d;semanticMatches=%d;requiredMatches=%d;attempt=%d/%d",
                evidence.graphMerchantCount(), semanticCount, required, attempt, MAX_ATTEMPTS);
        if (evidence.graphMerchantCount() == 0)
            return new Assessment(false, "NO_GRAPH_EVIDENCE;" + metrics, "JAVA_POLICY");
        if (!evidence.vectorEnabled())
            return new Assessment(false, "VECTOR_DISABLED;" + metrics, "JAVA_POLICY");
        if (semanticCount >= required)
            return new Assessment(false, "SEMANTIC_COVERAGE_SUFFICIENT;" + metrics, "JAVA_POLICY");
        if (attempt >= MAX_ATTEMPTS)
            return new Assessment(false, "MAX_ATTEMPTS_REACHED;" + metrics, "JAVA_POLICY");
        return new Assessment(true, "LOW_SEMANTIC_COVERAGE;" + metrics, "JAVA_POLICY");
    }
}
