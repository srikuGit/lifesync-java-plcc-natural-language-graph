package com.lifesync.poc.ingestion;

import org.neo4j.driver.Driver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Repair historical identity migrations using the goal's source ECN, never an arbitrary first owner. */
@Component
@Order(2)
public class GoalOwnershipRepair implements ApplicationRunner {
    private final Driver driver;
    private final ObjectMapper mapper;
    private final Path input;
    public GoalOwnershipRepair(Driver driver,ObjectMapper mapper,@Value("${lifesync.input-dir:./data/input}") String inputDir){
        this.driver=driver;this.mapper=mapper;this.input=Path.of(inputDir);
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        // Refresh canonical identity for existing legacy goals even when semantic ingestion is disabled.
        Map<String,String> identities=new HashMap<>();
        for(var c:mapper.readTree(input.resolve("customers.json").toFile())) {
            var a=c.path("data").path("attributes");
            identities.put(a.path("ecn").asText(),a.path("customerId").asText());
        }
        List<Map<String,String>> sources=new ArrayList<>();
        for(var g:mapper.readTree(input.resolve("goals.json").toFile())) {
            String customer=identities.get(g.path("ecn").asText());
            if(customer==null) throw new IllegalArgumentException("Goal source has no customer identity");
            sources.add(Map.of("goal",g.path("goalId").asText(),"customer",customer));
        }
        try(var session=driver.session()) {
            session.executeWrite(tx->{
                tx.run("""
                    UNWIND $sources AS row
                    MATCH (owner:Customer {customerId:row.customer}),(g:Goal {goalId:row.goal})
                    SET g.ecn=owner.ecn
                    """,Map.of("sources",sources)).consume();
                tx.run("""
                    MATCH (g:Goal), (owner:Customer)
                    WHERE g.ecn=owner.ecn
                    OPTIONAL MATCH (wrong:Customer)-[rel:HAS_GOAL]->(g)
                    WHERE wrong <> owner
                    DELETE rel
                    WITH DISTINCT g,owner
                    SET g.ownerCustomerId=owner.customerId
                    MERGE (owner)-[:HAS_GOAL]->(g)
                    """).consume();
                tx.run("""
                    MATCH (wrong:Customer)-[r:RECOMMENDED_STORE]->()
                    MATCH (g:Goal {goalId:r.goalId})
                    WHERE g.ownerCustomerId IS NOT NULL AND wrong.customerId <> g.ownerCustomerId
                    DELETE r
                    """).consume();
                var conflicts=tx.run("""
                    MATCH (c:Customer)-[:HAS_GOAL]->(g:Goal)
                    WITH g,count(DISTINCT c) AS owners WHERE owners>1
                    RETURN g.goalId AS id LIMIT 10
                    """).list(r->r.get("id").asString());
                if(!conflicts.isEmpty()) throw new IllegalStateException("Ambiguous goal ownership requires source correction: "+conflicts);
                return null;
            });
        }
    }
}
