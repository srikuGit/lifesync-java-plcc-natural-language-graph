package com.lifesync.poc.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.graph.GraphRepository;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.*;

/** Curated synthetic product/category relationships; never inferred lending eligibility. */
@Component
@Order(1)
public class ProductDemoIngestion implements ApplicationRunner {
    private final Driver driver;
    private final ObjectMapper mapper;
    private final GraphRepository graph;
    private final Path input;
    public ProductDemoIngestion(Driver driver, ObjectMapper mapper, GraphRepository graph,
            @Value("${lifesync.input-dir:./data/input}") String inputDir) {
        this.driver=driver; this.mapper=mapper; this.graph=graph; this.input=Path.of(inputDir,"products-demo.json");
    }
    public record Fixture(List<Map<String,Object>> products, List<Map<String,Object>> customers,
                          List<Map<String,Object>> goals, List<Map<String,Object>> merchants) {}
    @Override public void run(ApplicationArguments args) throws Exception {
        Fixture fixture = mapper.readValue(input.toFile(),Fixture.class);
        graph.createSchema();
        try (var session = driver.session()) {
            session.run("CREATE CONSTRAINT product_id IF NOT EXISTS FOR (p:Product) REQUIRE p.productId IS UNIQUE").consume();
            session.executeWrite(tx -> {
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
                    MATCH (p:Product {productId:row.productId})-[:SUPPORTS_CATEGORY]->(cat:Category {categoryId:row.category})
                    MERGE (g:Goal {goalId:row.goalId})
                    SET g.description=row.description,g.productId=row.productId,g.ecn=c.ecn,
                        g.category=row.category,g.categories=[row.category],g.goalType=row.productId,
                        g.status='PRODUCT_DEMO',g.classificationSource='CURATED_SYNTHETIC_FIXTURE'
                    MERGE (c)-[:HAS_GOAL]->(g)
                    MERGE (g)-[:FOR_PRODUCT]->(p)
                    MERGE (g)-[:CLASSIFIED_AS]->(cat)
                    """,Map.of("rows",fixture.goals())).consume();
                tx.run("""
                    UNWIND $rows AS row
                    MATCH (p:Product {productId:row.productId})-[:SUPPORTS_CATEGORY]->(cat:Category {categoryId:row.category})
                    MERGE (m:Merchant {merchantId:row.merchantId})
                    SET m.name=row.name,m.description=row.description,m.active=row.active,
                        m.category=row.category,m.categories=[row.category]
                    MERGE (s:Store {storeId:row.storeId})
                    SET s.city=row.city,s.state=row.state,s.latitude=row.latitude,s.longitude=row.longitude,
                        s.addressLine='Synthetic demo location'
                    MERGE (m)-[:HAS_STORE]->(s)
                    MERGE (m)-[:OFFERS_CATEGORY]->(cat)
                    MERGE (m)-[:SERVES_PRODUCT]->(p)
                    """,Map.of("rows",fixture.merchants())).consume();
                return null;
            });
        }
    }
}
