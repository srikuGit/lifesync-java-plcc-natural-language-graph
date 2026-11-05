package com.lifesync.poc.service;

import com.lifesync.poc.graph.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/** Incremental, idempotent embedding backfill to avoid exhausting an 8 GB laptop. */
@Service
public class VectorBackfillService {
    private static final Logger log = LoggerFactory.getLogger(VectorBackfillService.class);
    private final GraphRepository graph;
    private final ObjectProvider<EmbeddingModel> models;
    private final boolean enabled;
    private final String modelName;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public VectorBackfillService(GraphRepository graph, ObjectProvider<EmbeddingModel> models,
                                 @Value("${lifesync.vector-enabled:true}") boolean enabled,
                                 @Value("${lifesync.vector-model:nomic-embed-text}") String modelName,
                                 @Value("${lifesync.vector-backfill-batch-size:5}") int batchSize) {
        this.graph = graph;
        this.models = models;
        this.enabled = enabled;
        this.modelName = modelName;
        this.batchSize = Math.max(1, Math.min(20, batchSize));
    }

    @Scheduled(initialDelay = 12000, fixedDelayString = "${lifesync.vector-backfill-delay-ms:45000}")
    public void scheduledBackfill() { if (enabled) backfill(batchSize); }

    public int backfill(int requestedLimit) {
        if (!enabled || !running.compareAndSet(false, true)) return 0;
        try {
            EmbeddingModel model = models.getIfAvailable();
            if (model == null) return 0;
            int completed = 0;
            for (GraphRepository.MerchantVectorSource m : graph.merchantsNeedingEmbeddings(
                    modelName, Math.min(Math.max(1, requestedLimit), 20))) {
                try {
                    String text = EmbeddingText.merchant(m.name(), m.industry(), m.category(), m.description());
                    graph.saveMerchantEmbedding(m.merchantId(), m.inputHash(), modelName,
                            SemanticRetrievalService.doubles(model.embed(text)));
                    completed++;
                } catch (Exception e) {
                    log.warn("Embedding backfill stopped at merchant {}: {}", m.merchantId(), e.getMessage());
                    break; // avoid repeated model calls if Ollama is down
                }
            }
            if (completed > 0) log.info("Indexed {} merchant vectors in Neo4j", completed);
            return completed;
        } catch (Exception e) {
            log.warn("Embedding backfill skipped: {}", e.getMessage());
            return 0;
        } finally {
            running.set(false);
        }
    }
}
