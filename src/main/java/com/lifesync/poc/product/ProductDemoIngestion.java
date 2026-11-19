package com.lifesync.poc.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.graph.GraphRepository;
import com.lifesync.poc.classification.SemanticCategoryClassifier;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.*;

/** Loads synthetic product facts and classifies goal text with embeddings. */
@Component
@Order(1)
public class ProductDemoIngestion implements ApplicationRunner {
    private final Driver driver;
    private final ObjectMapper mapper;
    private final GraphRepository graph;
    private final Path input;
    private final SemanticCategoryClassifier classifier;
    public ProductDemoIngestion(Driver driver, ObjectMapper mapper, GraphRepository graph, SemanticCategoryClassifier classifier,
            @Value("${lifesync.input-dir:./data/input}") String inputDir) {
        this.driver=driver; this.mapper=mapper; this.graph=graph; this.classifier=classifier; this.input=Path.of(inputDir,"products-demo.json");
    }
    public record Fixture(List<Map<String,Object>> products, List<Map<String,Object>> customers,
                          List<Map<String,Object>> goals, List<Map<String,Object>> merchants) {}
    @Override public void run(ApplicationArguments args) throws Exception {
        Fixture fixture = mapper.readValue(input.toFile(),Fixture.class);
        validateOwnership(fixture);
        var classifiedGoals = classifyGoals(fixture);
        graph.createSchema();
        try (var session = driver.session()) {
            session.run("CREATE CONSTRAINT product_id IF NOT EXISTS FOR (p:Product) REQUIRE p.productId IS UNIQUE").consume();
            session.executeWrite(tx -> {
                tx.run("""
                    UNWIND $rows AS row
                    MATCH (old:Customer)-[r:HAS_GOAL]->(g:Goal {goalId:row.goalId})
                    WHERE old.customerId <> row.customerId
                    DELETE r
                    """,Map.of("rows",fixture.goals())).consume();
                tx.run("""
                    UNWIND $rows AS row
                    MERGE (p:Product {productId:row.productId}) SET p.name=row.name
                    WITH p,row UNWIND row.categories AS code
                    MERGE (cat:Category {categoryId:code}) SET cat.name=replace(code,'_',' ')
                    MERGE (p)-[:SUPPORTS_CATEGORY]->(cat)
                    """,Map.of("rows",fixture.products())).consume();
                // Include all legacy PLCC categories without changing their classification policy.
                tx.run("""
                    MATCH (:PLCC)-[:SUPPORTS_CATEGORY]->(cat:Category)
                    MATCH (p:Product {productId:'PLCC'})
                    MERGE (p)-[:SUPPORTS_CATEGORY]->(cat)
                    """).consume();
                tx.run("""
                    UNWIND $rows AS row
                    MERGE (c:Customer {customerId:row.customerId})
                    SET c += row
                    """,Map.of("rows",fixture.customers())).consume();
                tx.run("""
                    UNWIND $rows AS row
                    MATCH (c:Customer {customerId:row.customerId})
                    MATCH (p:Product {productId:row.productId})
                    MERGE (g:Goal {goalId:row.goalId})
                    SET g.description=row.description,g.productId=row.productId,g.ecn=c.ecn,
                        g.category=row.primaryCategory,g.categories=row.categories,g.goalType=row.productId,
                        g.classificationConfidence=row.confidence,
                        g.ownerCustomerId=c.customerId,
                        g.status='PRODUCT_DEMO',g.classificationSource=row.source
                    MERGE (c)-[:HAS_GOAL]->(g)
                    MERGE (g)-[:FOR_PRODUCT]->(p)
                    WITH g,p,row
                    OPTIONAL MATCH (g)-[old:CLASSIFIED_AS]->(:Category)
                    DELETE old
                    WITH DISTINCT g,p,row
                    UNWIND row.categories AS code
                    MATCH (p)-[:SUPPORTS_CATEGORY]->(cat:Category {categoryId:code})
                    MERGE (g)-[:CLASSIFIED_AS]->(cat)
                    """,Map.of("rows",classifiedGoals)).consume();
                tx.run("""
                    UNWIND $rows AS row
                    MATCH (p:Product {productId:row.productId})-[:SUPPORTS_CATEGORY]->(cat:Category {categoryId:row.category})
                    MERGE (m:Merchant {merchantId:row.merchantId})
                    SET m.ecn=row.ecn,m.name=row.name,m.description=row.description,m.active=row.active,
                        m.category=row.category,m.categories=[row.category]
                    MERGE (s:Store {storeId:row.storeId})
                    SET s.city=row.city,s.state=row.state,s.postalCode=row.postalCode,
                        s.addressLine=row.address
                    MERGE (m)-[:HAS_STORE]->(s)
                    MERGE (m)-[:OFFERS_CATEGORY]->(cat)
                    MERGE (m)-[:SERVES_PRODUCT]->(p)
                    """,Map.of("rows",fixture.merchants())).consume();
                return null;
            });
        }
    }
    List<Map<String,Object>> classifyGoals(Fixture fixture) {
        Map<Object,List<String>> categoriesByProduct = new HashMap<>();
        for (var product : fixture.products()) {
            var categories = ((List<?>) product.get("categories")).stream().map(String.class::cast).toList();
            categoriesByProduct.put(product.get("productId"), categories);
        }
        var rows = new ArrayList<Map<String,Object>>();
        for (var goal : fixture.goals()) {
            var allowed = categoriesByProduct.get(goal.get("productId"));
            if (allowed == null) throw new IllegalArgumentException("Unknown product for goal " + goal.get("goalId"));
            var result = classifier.classifyForProduct((String) goal.get("description"), allowed);
            var categories = result.categories();
            rows.add(Map.of("goalId", goal.get("goalId"), "customerId", goal.get("customerId"),
                    "productId", goal.get("productId"), "description", goal.get("description"),
                    "categories", categories, "primaryCategory", categories.isEmpty() ? "UNSUPPORTED" : categories.getFirst(),
                    "confidence", result.confidence(), "source",
                    categories.isEmpty() ? "SEMANTIC_NO_PRODUCT_MATCH" : "SEMANTIC_EMBEDDING"));
        }
        return rows;
    }

    static void validateOwnership(Fixture fixture) {
        Set<Object> ids=new HashSet<>();
        Set<Object> customers=new HashSet<>();
        fixture.customers().forEach(c->customers.add(c.get("customerId")));
        for(var goal:fixture.goals()) {
            if(goal.get("goalId")==null || !ids.add(goal.get("goalId")) || !customers.contains(goal.get("customerId")))
                throw new IllegalArgumentException("Each fixture goal must have one valid customer owner");
        }
    }

}
