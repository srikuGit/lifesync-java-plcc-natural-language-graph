package com.lifesync.poc.campaign;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.lifesync.poc.campaign.CampaignModels.*;

/** Campaign metadata only: no rendered HTML, offer values or delivery integration. */
@Repository
public class CampaignRepository {
    private final Driver driver;
    private volatile boolean schemaReady;
    public CampaignRepository(Driver driver){this.driver=driver;}
    public synchronized void ensureSchema() {
        if(schemaReady)return;
        try(var s=driver.session()) {
            s.run("CREATE CONSTRAINT campaign_run_id IF NOT EXISTS FOR (r:CampaignRun) REQUIRE r.runId IS UNIQUE").consume();
            s.run("CREATE CONSTRAINT campaign_preview_id IF NOT EXISTS FOR (p:CampaignPreview) REQUIRE p.previewId IS UNIQUE").consume();
        }
        schemaReady=true;
    }
    public void recoverInterruptedRuns() {
        ensureSchema();
        try(var s=driver.session()) {
            s.executeWrite(tx->{
                tx.run("""
                    MATCH (r:CampaignRun {status:'RUNNING'})-[:HAS_PREVIEW]->(p:CampaignPreview)
                    WHERE p.status IN ['PENDING','PROCESSING']
                    SET p.status='FAILED',p.reason='Interrupted by application restart'
                    """).consume();
                tx.run("""
                    MATCH (r:CampaignRun {status:'RUNNING'})
                    SET r.status='INTERRUPTED',r.finishedAt=datetime()
                    """).consume();return null;
            });
        }
    }
    public List<String> activeAudience(int limit) {
        try(var s=driver.session()) {
            return s.executeRead(tx->tx.run("""
                MATCH (c:Customer {plccAccountStatus:'ACTIVE'})
                RETURN c.customerId AS id ORDER BY id LIMIT $limit
                """,Map.of("limit",limit)).list(r->r.get("id").asString()));
        }
    }
    public boolean isActive(String customer) {
        try(var s=driver.session()) {
            return s.executeRead(tx->tx.run("MATCH (c:Customer {customerId:$id,plccAccountStatus:'ACTIVE'}) RETURN c.customerId LIMIT 1",Map.of("id",customer)).hasNext());
        }
    }
    public void createRun(String runId,List<String> customers) {
        ensureSchema();
        try(var s=driver.session()) {
            s.executeWrite(tx->{
                tx.run("""
                    CREATE (r:CampaignRun {runId:$run,status:'RUNNING',createdAt:datetime(),audienceCount:$count,
                        kind:'PLCC_PREVIEW_ONLY',trigger:'MANUAL'})
                    WITH r UNWIND $customers AS customer
                    CREATE (p:CampaignPreview {previewId:$run+':'+customer,runId:$run,customerId:customer,status:'PENDING'})
                    MERGE (r)-[:HAS_PREVIEW]->(p)
                    """,Map.of("run",runId,"count",customers.size(),"customers",customers)).consume();return null;
            });
        }
    }
    public void mark(String run,String customer,String status,String reason) {
        try(var s=driver.session()) {
            s.run("""
                MATCH (p:CampaignPreview {previewId:$id})
                SET p.status=$status,p.reason=$reason,p.updatedAt=datetime()
                """,Map.of("id",run+":"+customer,"status",status,"reason",reason)).consume();
        }
    }
    public void ready(String run,String customer,String subject,String introduction,String source,List<String> goals) {
        try(var s=driver.session()) {
            s.run("""
                MATCH (p:CampaignPreview {previewId:$id})
                SET p.status='READY',p.subject=$subject,p.introduction=$intro,p.source=$source,
                    p.goalIds=$goals,p.generatedAt=datetime(),p.reason=''
                """,Map.of("id",run+":"+customer,"subject",subject,"intro",introduction,"source",source,"goals",goals)).consume();
        }
    }
    public void finish(String run) {
        try(var s=driver.session()) {
            s.run("""
                MATCH (r:CampaignRun {runId:$run})
                OPTIONAL MATCH (r)-[:HAS_PREVIEW]->(p:CampaignPreview)
                WITH r,count(CASE WHEN p.status='FAILED' THEN 1 END) AS failures
                SET r.status=CASE WHEN failures>0 THEN 'COMPLETED_WITH_FAILURES' ELSE 'COMPLETED' END,
                    r.finishedAt=datetime()
                """,Map.of("run",run)).consume();
        }
    }
    /** Best effort terminal transition; completed/interrupted runs are never overwritten. */
    public void failRun(String run,String reason) {
        try(var s=driver.session()) {
            s.executeWrite(tx->{
                boolean changed=tx.run("""
                    MATCH (r:CampaignRun {runId:$run,status:'RUNNING'})
                    SET r.status='FAILED',r.finishedAt=datetime(),r.failureReason=$reason
                    RETURN r.runId
                    """,Map.of("run",run,"reason",reason)).hasNext();
                if(changed) tx.run("""
                    MATCH (:CampaignRun {runId:$run,status:'FAILED'})-[:HAS_PREVIEW]->(p:CampaignPreview)
                    WHERE p.status IN ['PENDING','PROCESSING']
                    SET p.status='FAILED',p.reason=$reason,p.updatedAt=datetime()
                    """,Map.of("run",run,"reason",reason)).consume();
                return null;
            });
        }
    }
    private static final String RUN_RETURN="""
        RETURN r.runId AS id,r.status AS status,toString(r.createdAt) AS created,
            coalesce(toString(r.finishedAt),'') AS finished,r.audienceCount AS audience,
            count(CASE WHEN p.status='READY' THEN 1 END) AS ready,
            count(CASE WHEN p.status STARTS WITH 'SKIPPED' THEN 1 END) AS skipped,
            count(CASE WHEN p.status='FAILED' THEN 1 END) AS failed
        """;
    public List<Run> runs() {
        try(var s=driver.session()) {
            return s.executeRead(tx->tx.run("MATCH (r:CampaignRun) OPTIONAL MATCH (r)-[:HAS_PREVIEW]->(p:CampaignPreview) "+RUN_RETURN+" ORDER BY created DESC LIMIT 20").list(this::run));
        }
    }
    public Optional<Run> run(String id) {
        try(var s=driver.session()) {
            return s.executeRead(tx->tx.run("MATCH (r:CampaignRun {runId:$run}) OPTIONAL MATCH (r)-[:HAS_PREVIEW]->(p:CampaignPreview) "+RUN_RETURN,Map.of("run",id)).list(this::run).stream().findFirst());
        }
    }
    private Run run(Record r) {return new Run(r.get("id").asString(),r.get("status").asString(),r.get("created").asString(),r.get("finished").asString(),r.get("audience").asLong(),r.get("ready").asLong(),r.get("skipped").asLong(),r.get("failed").asLong());}
    public List<Preview> previews(String runId) {
        try(var s=driver.session()) {
            return s.executeRead(tx->tx.run("MATCH (:CampaignRun {runId:$run})-[:HAS_PREVIEW]->(p:CampaignPreview) RETURN p ORDER BY p.customerId",Map.of("run",runId)).list(this::preview));
        }
    }
    public Optional<Preview> preview(String runId,String customerId) {
        try(var s=driver.session()) {
            return s.executeRead(tx->tx.run("MATCH (:CampaignRun {runId:$run})-[:HAS_PREVIEW]->(p:CampaignPreview {customerId:$customer}) RETURN p",Map.of("run",runId,"customer",customerId)).list(this::preview).stream().findFirst());
        }
    }
    private Preview preview(Record r) {
        Value p=r.get("p");
        return new Preview(p.get("previewId").asString(),p.get("runId").asString(),p.get("customerId").asString(),p.get("status").asString(),
            p.get("subject").asString(""),p.get("introduction").asString(""),p.get("source").asString(""),
            p.get("goalIds").isNull()?List.of():p.get("goalIds").asList(Value::asString),
            p.get("generatedAt").isNull()?"":p.get("generatedAt").asObject().toString(),p.get("reason").asString(""));
    }
}
