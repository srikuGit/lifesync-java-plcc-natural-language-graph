package com.lifesync.poc.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.service.EmbeddingText;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Semantic multi-label classification. No keyword or exact-category classification rules.
 * Uses nomic-embed-text cosine similarity against a versioned business taxonomy.
 * All embeddings are cached on disk so restart does not re-embed 200 demo records.
 * Similarity thresholds are POC heuristics; tune against a labeled evaluation set. */
@Component
public class SemanticCategoryClassifier {
    public static final String VERSION = "semantic-taxonomy-v1";
    private static final Map<String, String> TAXONOMY = new LinkedHashMap<>();
    static {
        TAXONOMY.put("HOME_FURNISHINGS", "Furniture for furnishing a home, apartment, bedroom, living room or home office. Sofas, couches, beds, mattresses, desks, office chairs, tables, shelves and cabinets.");
        TAXONOMY.put("APPLIANCES", "Home and kitchen appliances for cooking, cleaning, laundry and refrigeration. Refrigerators, freezers, ovens, dishwashers, washing machines, dryers and microwaves.");
        TAXONOMY.put("ELECTRONICS", "Consumer electronics, computing and digital technology. Laptops, desktop computers, monitors, tablets, televisions, smartphones, headphones, cameras and gaming consoles.");
        TAXONOMY.put("HOME_IMPROVEMENT", "Home renovation, remodeling, building supplies and do-it-yourself improvements. Paint, hardware, power tools, fixtures, construction and repairs to the home.");
        TAXONOMY.put("FLOORING", "Residential and commercial floor coverings. Carpets, rugs, hardwood flooring, laminate, vinyl flooring, ceramic floor tiles and floor installation materials.");
        TAXONOMY.put("JEWELRY", "Fine jewelry and personal adornment. Engagement rings, wedding bands, necklaces, bracelets, gemstones, earrings and watches.");
        TAXONOMY.put("OUTDOOR_LIVING", "Outdoor home living and patio products. Garden furniture, outdoor seating, grills, backyard furnishings, patio sets and outdoor leisure equipment.");
        TAXONOMY.put("SPECIALTY_RETAIL", "Specialty shops selling hobby materials, arts and crafts supplies, collectibles, specialty goods and niche retail merchandise.");
        TAXONOMY.put("APPAREL", "Clothing, fashion and wearable items. Shoes, footwear, coats, dresses, shirts, pants, accessories and wardrobe purchases.");
        TAXONOMY.put("TRAVEL", "Travel services and vacation bookings. Flights, airline tickets, hotel reservations, cruises and tour packages.");
        TAXONOMY.put("AUTOMOTIVE", "Automobiles, cars, trucks and vehicles. Car dealerships, purchasing a car, automobile sales, vehicle ownership and motor vehicles.");
        TAXONOMY.put("SERVICES", "Services rather than retail merchandise. Repair-only businesses, equipment rental, installation-only, cleaning, consulting and maintenance labor.");
        TAXONOMY.put("GROCERIES", "Grocery stores, supermarkets, fresh produce, packaged foods, food staples, pantry supplies and household groceries.");
    }

    private final ObjectProvider<EmbeddingModel> models;
    private final ObjectMapper mapper;
    private final Path cacheDir;
    private final String modelName;
    private final double minimumScore;
    private final double relativeMargin;
    private final Map<String,float[]> inMemory = new ConcurrentHashMap<>();
    private volatile Map<String,float[]> categoryVectors;

    public SemanticCategoryClassifier(ObjectProvider<EmbeddingModel> models, ObjectMapper mapper,
            @Value("${lifesync.semantic-classification-cache-dir:./data/cache/category-classification}") String cacheDir,
            @Value("${lifesync.vector-model:nomic-embed-text}") String modelName,
            @Value("${lifesync.semantic-classification-min-score:0.57}") double minimumScore,
            @Value("${lifesync.semantic-classification-relative-margin:0.09}") double relativeMargin) {
        this.models = models;
        this.mapper = mapper;
        this.cacheDir = Path.of(cacheDir);
        this.modelName = modelName;
        if (minimumScore <= 0 || minimumScore >= 1 || relativeMargin < 0 || relativeMargin > 0.3)
            throw new IllegalArgumentException("Invalid semantic classification thresholds");
        this.minimumScore = minimumScore;
        this.relativeMargin = relativeMargin;
    }

    public record Match(String category, double score) {}
    public record Result(List<Match> matches, double confidence) {
        public Result { matches = List.copyOf(matches); }
        public List<String> categories() { return matches.stream().map(Match::category).toList(); }
    }

    /** category descriptions are compared to the *meaning* of the input, not to keyword matches. */
    public Result classify(String input, boolean plccOnly, int maxLabels) {
        if (input == null || input.isBlank()) return new Result(List.of(), 0.0);
        if (maxLabels < 1) throw new IllegalArgumentException("maxLabels must be positive");
        Map<String,float[]> catalog = taxonomyVectors();
        float[] query = embedding("search_query: " + EmbeddingText.trim(input, 800));
        List<Match> ranked = catalog.entrySet().stream()
                .map(e -> new Match(e.getKey(), cosine(query, e.getValue())))
                .sorted(Comparator.comparingDouble(Match::score).reversed()
                        .thenComparing(Match::category))
                .toList();
        double bestOverall = ranked.getFirst().score();
        // External categories compete in the same ranking. A vehicle or travel goal must
        // not be forced into an unrelated PLCC category just because PLCC-only is requested.
        if (bestOverall < minimumScore) return new Result(List.of(), 0.0);
        List<Match> chosen = ranked.stream()
                .filter(m -> m.score() >= minimumScore && m.score() >= bestOverall-relativeMargin)
                .filter(m -> !plccOnly || CategoryCatalog.supported(m.category()))
                .limit(maxLabels).toList();
        if (chosen.isEmpty()) return new Result(List.of(), 0.0);
        double second = ranked.size()>1 ? ranked.get(1).score() : 0.0;
        // Confidence reflects top-match separation and absolute score; not a probability.
        double confidence = Math.min(0.97, Math.max(0.45,
                0.64 + 0.22 * Math.min(1, Math.max(0, (bestOverall-second)/0.15))
                        + 0.10 * Math.min(1, (bestOverall-minimumScore)/(1-minimumScore))));
        if (chosen.size()>1) confidence = Math.min(confidence, 0.79);
        return new Result(chosen, confidence);
    }

    private Map<String,float[]> taxonomyVectors() {
        Map<String,float[]> ready = categoryVectors;
        if (ready != null) return ready;
        synchronized (this) {
            if (categoryVectors != null) return categoryVectors;
            Map<String,float[]> vectors = new LinkedHashMap<>();
            TAXONOMY.forEach((code, description) -> vectors.put(code,
                    embedding("search_document: Business category " + code.replace('_',' ')
                            + ". Products and services: " + description)));
            categoryVectors = Collections.unmodifiableMap(vectors);
            return categoryVectors;
        }
    }

    private float[] embedding(String text) {
        String key = EmbeddingText.fingerprint(VERSION + "|" + modelName + "|" + text);
        float[] cached = inMemory.get(key);
        if (cached != null) return cached;
        synchronized (inMemory) {
            cached = inMemory.get(key);
            if (cached != null) return cached;
            Path path = cacheDir.resolve(key + ".json");
            try {
                if (Files.isRegularFile(path)) {
                    float[] saved = mapper.readValue(path.toFile(), float[].class);
                    validate(saved);
                    inMemory.put(key, saved);
                    return saved;
                }
                EmbeddingModel model = models.getIfAvailable();
                if (model == null) throw new IllegalStateException("EmbeddingModel unavailable: semantic classification requires Ollama embeddings");
                float[] vector = model.embed(text);
                validate(vector);
                Files.createDirectories(cacheDir);
                Path temp = Files.createTempFile(cacheDir, "embedding-", ".tmp");
                try {
                    mapper.writeValue(temp.toFile(), vector);
                    try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException ex) {
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { Files.deleteIfExists(temp); }
                inMemory.put(key, vector);
                return vector;
            } catch (Exception ex) {
                throw new IllegalStateException("Semantic category embedding failed; check Ollama and cache permissions: " + ex.getMessage(), ex);
            }
        }
    }

    private static void validate(float[] vector) {
        if (vector == null || vector.length != 768)
            throw new IllegalArgumentException("Expected 768-dimensional nomic-embed-text embedding");
        for (float value : vector) if (!Float.isFinite(value))
            throw new IllegalArgumentException("Non-finite embedding value");
    }

    private static double cosine(float[] a, float[] b) {
        double dot=0, aa=0, bb=0;
        for (int i=0; i<a.length; i++) { dot+=a[i]*b[i]; aa+=a[i]*a[i]; bb+=b[i]*b[i]; }
        return aa==0 || bb==0 ? 0 : dot/Math.sqrt(aa*bb);
    }
}
