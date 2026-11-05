package com.lifesync.poc.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.domain.Models.Goal;
import com.lifesync.poc.domain.Models.Merchant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Deterministic fake embeddings test semantic ranking without an Ollama server. */
class ClassificationServiceTest {
    @TempDir Path cache;
    private GoalClassificationService goals;
    private MerchantClassificationService merchants;
    private EmbeddingModel model;
    private ObjectProvider<EmbeddingModel> provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        model = mock(EmbeddingModel.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.embed(anyString())).thenAnswer(call -> embeddingFor(call.getArgument(0)));
        var classifier = new SemanticCategoryClassifier(provider, new ObjectMapper(),
                cache.toString(), "test-model", 0.57, 0.09);
        goals = new GoalClassificationService(classifier);
        merchants = new MerchantClassificationService(classifier);
    }

    @Test void semanticMultiCategoryGoalAndMerchant() {
        Goal goal = goal("Buy a desk and laptop", "HOME_FURNISHINGS");
        assertEquals(List.of("ELECTRONICS", "HOME_FURNISHINGS"),
                goals.classifyAll(goal).categories().stream().sorted().toList());
        assertEquals("SEMANTIC_EMBEDDING", goals.classifyAll(goal).source());
        Merchant m = merchant(true,"Sells sofas and laptop accessories");
        assertTrue(merchants.classifyAll(m).containsAll(List.of("HOME_FURNISHINGS", "ELECTRONICS")));
    }

    @Test void externalCategoryDoesNotTurnIntoPlcc() {
        assertEquals("UNSUPPORTED", goals.classify(goal("Buy a car", "VEHICLES")));
        assertTrue(goals.classifyAll(goal("Buy a car", "VEHICLES")).categories().isEmpty());
        assertEquals(List.of("AUTOMOTIVE"), merchants.classifyAll(new Merchant("M3","Auto Dealer",
                "Automotive","Sells cars", "AUTOMOTIVE", true,"Seattle","WA","98101",
                "Lane",47.6,-122.3,List.of("IN_STORE"))));
    }

    @Test void semanticResultOverridesMisleadingRawCode() {
        assertEquals(List.of("APPLIANCES"), goals.classifyAll(goal("Buy a refrigerator", "VEHICLES")).categories());
    }

    @Test void businessExclusionsStillApply() {
        assertTrue(merchants.classifyAll(merchant(false,"Sells refrigerators")).isEmpty());
        assertTrue(merchants.classifyAll(merchant(true,"Refrigerator repair only")).isEmpty());
    }

    @Test void embeddingsCachedAcrossClassifierInstances() {
        Goal goal = goal("Buy a refrigerator", "APPLIANCES");
        goals.classifyAll(goal);
        int before = mockingDetails(model).getInvocations().size();
        var second = new GoalClassificationService(new SemanticCategoryClassifier(provider,
                new ObjectMapper(), cache.toString(), "test-model", 0.57, 0.09));
        assertEquals("APPLIANCES", second.classify(goal));
        assertEquals(before, mockingDetails(model).getInvocations().size(),
                "Same taxonomy and goal text should be served from disk cache");
    }

    @Test void failsClosedWhenEmbeddingModelUnavailable() {
        @SuppressWarnings("unchecked") ObjectProvider<EmbeddingModel> missing = mock(ObjectProvider.class);
        var service = new GoalClassificationService(new SemanticCategoryClassifier(missing,
                new ObjectMapper(), cache.resolve("missing").toString(), "test-model", 0.57, 0.09));
        assertThrows(IllegalStateException.class, () -> service.classifyAll(goal("Buy a sofa", "")));
    }

    private Goal goal(String description, String category) {
        return new Goal("G1", "ECN1", description, "BIG_PURCHASE", category,
                1000, "USD", "01/01/2028", "fp");
    }
    private Merchant merchant(boolean active, String description) {
        return new Merchant("M1", "Mixed Store", "Home Furnishings", description,
                "HOME_FURNISHINGS", active, "Seattle", "WA", "98101", "Lane",
                47.6,-122.3,List.of("IN_STORE"));
    }

    private static float[] embeddingFor(String raw) {
        String text = raw.toLowerCase(Locale.ROOT);
        float[] v = new float[768];
        // This is a fake embedding provider. Production classifier contains no keyword rules.
        if (text.startsWith("search_document: business category")) {
            for (int i=0;i<CODES.length;i++) {
                if (text.startsWith("search_document: business category " + CODES[i].toLowerCase(Locale.ROOT).replace('_',' '))) {
                    v[i]=1;
                    return v;
                }
            }
        }
        if (text.contains("desk") || text.contains("sofa") || text.contains("apartment")) v[0]=1;
        if (text.contains("refrigerator")) v[1]=1;
        if (text.contains("laptop")) v[2]=1;
        if (text.contains("car") && !text.contains("refrigerator")) v[10]=1;
        if (v[0]==0 && v[1]==0 && v[2]==0 && v[10]==0) v[12]=1;
        return v;
    }
    private static final String[] CODES = {"HOME_FURNISHINGS", "APPLIANCES", "ELECTRONICS",
            "HOME_IMPROVEMENT", "FLOORING", "JEWELRY", "OUTDOOR_LIVING", "SPECIALTY_RETAIL",
            "APPAREL", "TRAVEL", "AUTOMOTIVE", "SERVICES", "GROCERIES"};
}
