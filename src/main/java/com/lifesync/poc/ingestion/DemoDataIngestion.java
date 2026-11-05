package com.lifesync.poc.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.classification.GoalClassificationService;
import com.lifesync.poc.classification.MerchantClassificationService;
import com.lifesync.poc.domain.Models.*;
import com.lifesync.poc.graph.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DemoDataIngestion implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataIngestion.class);
    private final String inputDir;
    private final ObjectMapper mapper;
    private final JsonDataParser parser;
    private final GraphRepository graph;
    private final GoalClassificationService goalClassificationService;
    private final MerchantClassificationService merchantClassificationService;

    public DemoDataIngestion(@Value("${lifesync.input-dir}") String inputDir,
                             ObjectMapper mapper, JsonDataParser parser,
                             GraphRepository graph, GoalClassificationService goalClassificationService,
                             MerchantClassificationService merchantClassificationService) {
        this.inputDir = inputDir;
        this.mapper = mapper;
        this.parser = parser;
        this.graph = graph;
        this.goalClassificationService = goalClassificationService;
        this.merchantClassificationService = merchantClassificationService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path root = Path.of(inputDir);
        for (String filename : List.of("customers.json", "goals.json", "merchants.json")) {
            if (!Files.isRegularFile(root.resolve(filename)))
                throw new IllegalStateException("Required demo input missing: " + root.resolve(filename));
        }
        JsonNode customersJson = mapper.readTree(root.resolve("customers.json").toFile());
        JsonNode goalsJson = mapper.readTree(root.resolve("goals.json").toFile());
        JsonNode merchantsJson = mapper.readTree(root.resolve("merchants.json").toFile());
        List<Customer> customers = parser.readCustomers(customersJson);
        List<Goal> goals = parser.readGoals(goalsJson);
        List<Merchant> merchants = parser.readMerchants(merchantsJson);

        Set<String> ecns = customers.stream().map(Customer::ecn).collect(Collectors.toSet());
        List<String> orphanGoals = goals.stream().filter(g -> !ecns.contains(g.ecn()))
                .map(Goal::goalId).toList();
        if (!orphanGoals.isEmpty()) throw new IllegalStateException("Goals missing customers: " + orphanGoals);

        // Semantic classification is performed at ingestion, not on every recommendation.
        // Progress logs make first-run Ollama embedding work visible on an 8 GB host.
        long started = System.currentTimeMillis();
        Map<String,GoalClassificationService.Classification> goalCats = new LinkedHashMap<>();
        for (Goal goal : goals) {
            goalCats.put(goal.goalId(), goalClassificationService.classifyAll(goal));
            if (goalCats.size() % 25 == 0)
                log.info("Semantic goal classification: {}/{} ({} ms)",
                        goalCats.size(), goals.size(), System.currentTimeMillis()-started);
        }
        Map<String,List<String>> merchantCats = new LinkedHashMap<>();
        for (Merchant merchant : merchants) {
            merchantCats.put(merchant.merchantId(), merchantClassificationService.classifyAll(merchant));
            if (merchantCats.size() % 10 == 0)
                log.info("Semantic merchant classification: {}/{} ({} ms)",
                        merchantCats.size(), merchants.size(), System.currentTimeMillis()-started);
        }
        graph.createSchema();
        JsonNode catalog = mapper.readTree(root.resolve("plcc-catalog.json").toFile());
        List<String> plccCategories = new ArrayList<>();
        catalog.path("categories").forEach(n -> plccCategories.add(n.asText()));
        List<String> externalCategories = new ArrayList<>();
        catalog.path("otherMerchantCategories").forEach(n -> externalCategories.add(n.asText()));
        graph.upsertPlccMaster(catalog.path("programId").asText("PLCC_DEMO"),
                catalog.path("programName").asText("PLCC demonstration"),
                plccCategories, externalCategories);
        graph.upsertCustomers(customers);
        graph.upsertMerchantsMulti(merchants, merchantCats);
        graph.upsertGoalsMulti(goals, goalCats);
        log.info("Ingested {} customers, {} goals, {} merchants into Neo4j (idempotently)",
                customers.size(), goals.size(), merchants.size());
    }
}
