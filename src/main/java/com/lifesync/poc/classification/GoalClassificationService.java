package com.lifesync.poc.classification;

import com.lifesync.poc.domain.Models.Goal;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;

/** Semantic-only goal classification. No keyword cues or chat-model inference. */
@Service
public class GoalClassificationService {
    public static final String VERSION = SemanticCategoryClassifier.VERSION;
    private final SemanticCategoryClassifier semantic;

    public GoalClassificationService(SemanticCategoryClassifier semantic) { this.semantic = semantic; }

    public record Classification(List<String> categories, double confidence, String source) {
        public Classification {
            categories = List.copyOf(categories);
            if (!categories.stream().allMatch(CategoryCatalog::supported))
                throw new IllegalArgumentException("Unknown PLCC category");
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)
                throw new IllegalArgumentException("Invalid classification confidence");
        }
        public String primary() { return categories.isEmpty() ? "UNSUPPORTED" : categories.getFirst(); }
    }

    public String classify(Goal goal) { return classifyAll(goal).primary(); }

    public Classification classifyAll(Goal goal) {
        // The supplied category is context, not an exact-match shortcut. All labels must
        // be justified by embedding similarity to taxonomy descriptions.
        String text = "Customer purchase goal: " + Objects.toString(goal.description(), "")
                + ". Source goal category: " + Objects.toString(goal.rawCategory(), "");
        SemanticCategoryClassifier.Result result = semantic.classify(text, true, 3);
        return new Classification(result.categories(), result.confidence(),
                result.categories().isEmpty() ? "SEMANTIC_NO_PLCC_MATCH" : "SEMANTIC_EMBEDDING");
    }
}
