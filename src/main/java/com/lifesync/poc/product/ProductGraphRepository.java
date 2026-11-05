package com.lifesync.poc.product;

import com.lifesync.poc.graph.GraphRepository;
import org.neo4j.driver.*;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Read-only application queries are predefined and always start at the requested customer. */
@Repository
public class ProductGraphRepository {
    private final Driver driver;
    private final GraphRepository customers;
    public ProductGraphRepository(Driver driver, GraphRepository customers) {
        this.driver = driver; this.customers = customers;
    }
    public record GoalFact(String id, String description, String product, String productName,
                           List<String> categories) {}
    public List<GoalFact> goals(String customerId, String productId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (:Customer {customerId:$customer})-[:HAS_GOAL]->(g:Goal)
                WITH g, coalesce(g.productId,'PLCC') AS product
                WHERE $product = '' OR product = $product
                OPTIONAL MATCH (p:Product {productId:product})
                RETURN g.goalId AS id, g.description AS description, product,
                    coalesce(p.name,'Private label credit card') AS name,
                    coalesce(g.categories,[]) AS categories
                ORDER BY id LIMIT 201
                """, Map.of("customer", customerId, "product", productId)).list(r -> new GoalFact(
                    r.get("id").asString(), r.get("description").asString(), r.get("product").asString(),
                    r.get("name").asString(), r.get("categories").asList(Value::asString))));
        }
    }
    public List<GraphRepository.Candidate> candidates(String customerId, String goalId, String productId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (c:Customer {customerId:$customer})-[:HAS_GOAL]->(g:Goal {goalId:$goal})
                      -[:CLASSIFIED_AS]->(cat:Category)<-[:SUPPORTS_CATEGORY]-(p:Product {productId:$product})
                WHERE coalesce(g.productId,'PLCC') = p.productId
                    AND (p.productId <> 'PLCC' OR c.plccAccountStatus = 'ACTIVE')
                MATCH (m:Merchant {active:true})-[:OFFERS_CATEGORY]->(cat)
                MATCH (m)-[:HAS_STORE]->(s:Store)
                WHERE s.latitude IS NOT NULL AND s.longitude IS NOT NULL
                RETURN DISTINCT m.merchantId AS id,s.storeId AS store,m.name AS name,
                    cat.categoryId AS category,m.description AS description,
                    s.city AS city,s.state AS state,s.addressLine AS address,
                    s.latitude AS lat,s.longitude AS lon
                ORDER BY id,store,category
                """, Map.of("customer",customerId,"goal",goalId,"product",productId))
                .list(r -> new GraphRepository.Candidate(r.get("id").asString(),r.get("store").asString(),
                    r.get("name").asString(),r.get("category").asString(),r.get("description").asString(""),
                    r.get("city").asString(""),r.get("state").asString(""),r.get("address").asString(""),
                    r.get("lat").asDouble(),r.get("lon").asDouble())));
        }
    }
    public Optional<com.lifesync.poc.domain.Models.Customer> customer(String id) {
        return customers.customerById(id);
    }
}
