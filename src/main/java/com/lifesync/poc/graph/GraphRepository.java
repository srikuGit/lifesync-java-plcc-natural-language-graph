package com.lifesync.poc.graph;

import com.lifesync.poc.domain.Models.*;
import com.lifesync.poc.classification.GoalClassificationService.Classification;
import com.lifesync.poc.classification.GoalClassificationService;
import com.lifesync.poc.service.EmbeddingText;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class GraphRepository {
    private final Driver driver;
    public GraphRepository(Driver driver) { this.driver = driver; }

    public void createSchema() {
        List<String> statements = List.of(
                "CREATE CONSTRAINT plcc_program_id IF NOT EXISTS FOR (n:PLCC) REQUIRE n.programId IS UNIQUE",
                "CREATE CONSTRAINT consent_request_id IF NOT EXISTS FOR (n:ConsentRequest) REQUIRE n.requestId IS UNIQUE",
                "CREATE CONSTRAINT customer_id IF NOT EXISTS FOR (n:Customer) REQUIRE n.customerId IS UNIQUE",
                "CREATE CONSTRAINT customer_ecn IF NOT EXISTS FOR (n:Customer) REQUIRE n.ecn IS UNIQUE",
                "CREATE CONSTRAINT goal_id IF NOT EXISTS FOR (n:Goal) REQUIRE n.goalId IS UNIQUE",
                "CREATE CONSTRAINT merchant_id IF NOT EXISTS FOR (n:Merchant) REQUIRE n.merchantId IS UNIQUE",
                "CREATE CONSTRAINT store_id IF NOT EXISTS FOR (n:Store) REQUIRE n.storeId IS UNIQUE",
                "CREATE CONSTRAINT category_id IF NOT EXISTS FOR (n:Category) REQUIRE n.categoryId IS UNIQUE",
                "CREATE CONSTRAINT recommendation_id IF NOT EXISTS FOR (n:Recommendation) REQUIRE n.recommendationId IS UNIQUE",
                "CREATE CONSTRAINT email_preview_id IF NOT EXISTS FOR (n:EmailPreview) REQUIRE n.previewId IS UNIQUE",
                "CREATE INDEX goal_status IF NOT EXISTS FOR (n:Goal) ON (n.status)",
                """
                    CREATE VECTOR INDEX merchant_embedding_index IF NOT EXISTS
                    FOR (m:Merchant) ON (m.embedding)
                    OPTIONS {indexConfig: {`vector.dimensions`: 768,
                                            `vector.similarity_function`: 'cosine'}}
                    """);
        try (Session session = driver.session()) {
            for (String query : statements) session.run(query).consume();
        }
    }

    public void upsertCustomers(List<Customer> customers) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (Customer c : customers) {
                    tx.run("""
                        MERGE (c:Customer {customerId:$id})
                        SET c.ecn=$ecn, c.name=$name, c.city=$city, c.state=$state,
                            c.postalCode=$postal, c.latitude=$lat, c.longitude=$lon,
                            c.plccAccountStatus=coalesce(c.plccAccountStatus,$plcc)
                        """, p("id", c.customerId(), "ecn", c.ecn(), "name", c.name(),
                            "city", c.city(), "state", c.state(), "postal", c.postalCode(),
                            "lat", c.latitude(), "lon", c.longitude(), "plcc", c.plccAccountStatus())).consume();
                }
                return null;
            });
        }
    }

    /** PLCC program categories are distinct from other categories in the shared taxonomy. */
    public void upsertPlccMaster(String id, String name, List<String> plccCodes,
                                 List<String> otherCodes) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (p:PLCC {programId:$id}) SET p.name=$name",
                       p("id",id,"name",name)).consume();
                tx.run("MATCH (p:PLCC {programId:$id})-[r:SUPPORTS_CATEGORY]->(:Category) DELETE r",
                       p("id",id)).consume();
                for (String code : plccCodes) {
                    if (!com.lifesync.poc.classification.CategoryCatalog.supported(code))
                        throw new IllegalArgumentException("Unexpected PLCC category: " + code);
                    tx.run("""
                        MATCH (p:PLCC {programId:$id})
                        MERGE (cat:Category {categoryId:$code})
                        SET cat.name=$name, cat.plccEligible=true
                        MERGE (p)-[:SUPPORTS_CATEGORY]->(cat)
                        """,p("id",id,"code",code,"name",code.replace('_',' '))).consume();
                }
                for (String code : otherCodes) {
                    if (plccCodes.contains(code)) continue;
                    tx.run("""
                        MERGE (cat:Category {categoryId:$code})
                        SET cat.name=$name, cat.plccEligible=false
                        """,p("code",code,"name",code.replace('_',' '))).consume();
                }
                return null;
            });
        }
    }

    /** Legacy API with one category, retained for compatibility. */
    public void upsertMerchants(List<Merchant> merchants, Map<String,String> categories) {
        Map<String,List<String>> multi = new HashMap<>();
        categories.forEach((id,cat) -> multi.put(id, "UNSUPPORTED".equals(cat)
                ? List.of() : List.of(cat)));
        upsertMerchantsMulti(merchants,multi);
    }

    public void upsertMerchantsMulti(List<Merchant> merchants, Map<String,List<String>> categories) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (Merchant m : merchants) {
                    List<String> codes = categories.getOrDefault(m.merchantId(),List.of());
                    String joined = String.join(",",codes);
                    String storeId=m.merchantId()+"-S1";
                    tx.run("""
                        MERGE (m:Merchant {merchantId:$id})
                        SET m.name=$name, m.industry=$industry, m.description=$description,
                            m.active=$active, m.salesChannels=$channels, m.category=$primary,
                            m.categories=$categories, m.vectorInputHash=$hash
                        MERGE (s:Store {storeId:$storeId})
                        SET s.city=$city,s.state=$state,s.postalCode=$postal,
                            s.addressLine=$address,s.latitude=$lat,s.longitude=$lon
                        MERGE (m)-[:HAS_STORE]->(s)
                        """,p("id",m.merchantId(),"name",m.name(),"industry",m.industry(),
                            "description",m.description(),"active",m.active(),
                            "channels",m.salesChannels(),"primary",codes.isEmpty()?"UNSUPPORTED":codes.getFirst(),
                            "categories",codes,
                            "hash",EmbeddingText.fingerprint(EmbeddingText.merchant(m.name(),m.industry(),joined,m.description())),
                            "storeId",storeId,"city",m.city(),"state",m.state(),"postal",m.postalCode(),
                            "address",m.addressLine(),"lat",m.latitude(),"lon",m.longitude())).consume();
                    tx.run("MATCH (m:Merchant {merchantId:$id})-[r:OFFERS_CATEGORY]->(:Category) DELETE r",
                            p("id",m.merchantId())).consume();
                    for(String cat:codes) {
                        // Only categories registered in the catalog can be connected.
                        tx.run("""
                            MATCH (m:Merchant {merchantId:$id}), (cat:Category {categoryId:$cat})
                            MERGE (m)-[:OFFERS_CATEGORY]->(cat)
                            """,p("id",m.merchantId(),"cat",cat)).consume();
                    }
                }
                return null;
            });
        }
    }

    /** Legacy single-label API for older integrations. New intake uses multi-label classification. */
    public void upsertGoals(List<Goal> goals, Map<String,String> categories) {
        Map<String,Classification> multi=new HashMap<>();
        categories.forEach((id,code)->multi.put(id,
                com.lifesync.poc.classification.CategoryCatalog.supported(code)
                    ? new Classification(List.of(code),0.90,"LEGACY_SINGLE_LABEL")
                    : new Classification(List.of(),0.0,"LEGACY_UNSUPPORTED")));
        upsertGoalsMulti(goals,multi);
    }

    /** Reclassifies existing goals when classifier version or category output changes.
     *  All category edges are replaced atomically; only PLCC-supported categories are linked. */
    public void upsertGoalsMulti(List<Goal> goals, Map<String,Classification> classifications) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                for (Goal g : goals) {
                    Classification classification=classifications.getOrDefault(g.goalId(),
                            new Classification(List.of(),0.0,"MISSING_CLASSIFICATION"));
                    List<String> categories=classification.categories();
                    String primary=classification.primary();
                    String classificationHash=EmbeddingText.fingerprint(g.fingerprint()+"|"
                            +GoalClassificationService.VERSION+"|"+String.join(",",categories)
                            +"|"+classification.confidence()+"|"+classification.source());
                    tx.run("""
                        MERGE (g:Goal {goalId:$goalId})
                        WITH g, (g.inputFingerprint IS NULL OR g.inputFingerprint <> $fp
                            OR g.classificationFingerprint IS NULL
                            OR g.classificationFingerprint <> $classFp) AS changed
                        SET g.status = CASE WHEN changed THEN 'PENDING' ELSE coalesce(g.status,'PENDING') END,
                            g.attempts = CASE WHEN changed THEN 0 ELSE coalesce(g.attempts,0) END,
                            g.nextRetryAt = CASE WHEN changed THEN 0 ELSE coalesce(g.nextRetryAt,0) END,
                            g.failureReason = CASE WHEN changed THEN '' ELSE coalesce(g.failureReason,'') END,
                            g.ecn=$ecn, g.description=$description, g.goalType=$type,
                            g.rawCategory=$rawCategory, g.category=$cat, g.categories=$categories,
                            g.classificationConfidence=$confidence, g.classificationSource=$source,
                            g.classificationFingerprint=$classFp,
                            g.targetAmount=$amount, g.currency=$currency,
                            g.endDate=$endDate, g.inputFingerprint=$fp
                        WITH g
                        MATCH (c:Customer {ecn:$ecn})
                        MERGE (c)-[:HAS_GOAL]->(g)
                        """, p("goalId", g.goalId(), "ecn", g.ecn(),
                            "description", g.description(), "type", g.goalType(),
                            "rawCategory", g.rawCategory(), "cat", primary,
                            "categories", categories, "confidence", classification.confidence(),
                            "source", classification.source(), "classFp", classificationHash,
                            "amount", g.targetAmount(), "currency", g.currency(),
                            "endDate", g.endDate(), "fp", g.fingerprint())).consume();
                    tx.run("MATCH (g:Goal {goalId:$goalId})-[r:CLASSIFIED_AS]->(:Category) DELETE r",
                            p("goalId",g.goalId())).consume();
                    for(String cat:categories) {
                        tx.run("""
                            MATCH (g:Goal {goalId:$goalId})
                            MATCH (:PLCC)-[:SUPPORTS_CATEGORY]->(cat:Category {categoryId:$cat})
                            MERGE (g)-[:CLASSIFIED_AS]->(cat)
                            """,p("goalId",g.goalId(),"cat",cat)).consume();
                    }
                }
                return null;
            });
        }
    }

    /** Java retrieval policy receives persisted goal classification evidence. */
    public GoalCategoryEvidence goalCategoryEvidence(String goalId) {
        try(Session session=driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (g:Goal {goalId:$id})
                RETURN coalesce(g.categories,[]) AS categories,
                       coalesce(g.classificationConfidence,0.0) AS confidence,
                       coalesce(g.classificationSource,'UNKNOWN') AS source,
                       coalesce(g.classificationFingerprint,g.inputFingerprint,'') AS classificationFp
                """,p("id",goalId)).list(r -> new GoalCategoryEvidence(
                    r.get("categories").asList(Value::asString),
                    r.get("confidence").asDouble(),
                    r.get("source").asString(),r.get("classificationFp").asString()))
                    .stream().findFirst().orElse(new GoalCategoryEvidence(List.of(),0.0,"UNKNOWN","")));
        }
    }

    public record GoalCategoryEvidence(List<String> categories,double confidence,String source,
                                       String classificationFingerprint) {
        public GoalCategoryEvidence {
            categories=List.copyOf(categories);
        }
        public GoalCategoryEvidence(List<String> categories,double confidence,String source) {
            this(categories,confidence,source,"");
        }
    }

    public void recoverInterruptedProcessing() {
        long cutoff = System.currentTimeMillis() - 5 * 60_000L;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (g:Goal {status:'PROCESSING'})
                    WHERE coalesce(g.processingStartedAt,0) < $cutoff
                    SET g.status='RETRY_PENDING', g.nextRetryAt=0,
                        g.failureReason='Interrupted processing recovered'
                    """, p("cutoff", cutoff)).consume();
                return null;
            });
        }
    }

    public List<String> claimPending(int max) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> tx.run("""
                MATCH (g:Goal)
                WHERE g.status='PENDING' OR (g.status='RETRY_PENDING' AND g.nextRetryAt <= $now)
                WITH g ORDER BY g.goalId LIMIT $count
                SET g.status='PROCESSING', g.attempts=coalesce(g.attempts,0)+1,
                    g.processingStartedAt=$now
                RETURN g.goalId AS id
                """, p("now", System.currentTimeMillis(), "count", max))
                    .list(r -> r.get("id").asString()));
        }
    }

    public boolean claimOne(String goalId) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> tx.run("""
                MATCH (g:Goal {goalId:$id})
                WHERE g.status='PENDING' OR (g.status='RETRY_PENDING' AND g.nextRetryAt <= $now)
                SET g.status='PROCESSING', g.attempts=coalesce(g.attempts,0)+1,
                    g.processingStartedAt=$now
                RETURN g.goalId AS id
                """, p("id", goalId, "now", System.currentTimeMillis())).hasNext());
        }
    }

    public Optional<GoalDetail> goal(String goalId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (c:Customer)-[:HAS_GOAL]->(g:Goal {goalId:$id})
                RETURN g.goalId AS id, g.description AS description, g.category AS category,
                       g.status AS status, c.ecn AS ecn, c.name AS name, c.city AS city,
                       c.latitude AS lat, c.longitude AS lon, g.inputFingerprint AS fp,
                       c.plccAccountStatus AS plccStatus, c.customerId AS customerId
                """, p("id", goalId)).list(r -> new GoalDetail(
                    s(r, "id"), s(r, "description"), s(r, "category"), s(r, "status"),
                    s(r, "ecn"), s(r, "name"), s(r, "city"), d(r, "lat"), d(r, "lon"),
                    s(r, "fp"), s(r,"plccStatus"),s(r,"customerId"))).stream().findFirst());
        }
    }

    public record Candidate(String merchantId, String storeId, String name, String category,
                            String description, String city, String state, String addressLine,
                            Double latitude, Double longitude) {}

    public List<Candidate> candidateMerchants(String goalId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (:Customer)-[:HAS_GOAL]->(g:Goal {goalId:$id})-[:CLASSIFIED_AS]->(cat:Category)
                      <-[:SUPPORTS_CATEGORY]-(:PLCC)
                MATCH (m:Merchant {active:true})-[:OFFERS_CATEGORY]->(cat)
                MATCH (m)-[:HAS_STORE]->(s:Store)
                RETURN DISTINCT m.merchantId AS id, s.storeId AS storeId, m.name AS name,
                       cat.categoryId AS category, m.description AS description, s.city AS city,
                       s.state AS state, s.addressLine AS address, s.latitude AS lat, s.longitude AS lon
                ORDER BY id, storeId, category
                """, p("id", goalId)).list(r -> new Candidate(
                    s(r, "id"), s(r, "storeId"), s(r, "name"), s(r, "category"),
                    s(r, "description"), s(r, "city"), s(r, "state"), s(r, "address"),
                    d(r, "lat"), d(r, "lon"))));
        }
    }

    /** Save graph facts and email PREVIEW METADATA only. Offers and rendered HTML never enter Neo4j. */
    public void completeHolder(String goalId, String fingerprint, String intro,
                         List<MerchantMatch> matches, String subject,
                         String retrievalMode, String explanationSource,
                         String plan, String evaluation, int attempts) {
        try(Session session=driver.session()) {
            session.executeWrite(tx -> {
                String recommendationId="REC-"+goalId;
                tx.run("""
                    MATCH (c:Customer {plccAccountStatus:'ACTIVE'})-[:HAS_GOAL]->(g:Goal {goalId:$id})
                    MERGE (r:Recommendation {recommendationId:$recId})
                    SET r.generatedAt=datetime(),r.fingerprint=$fp,r.merchantCount=$count,
                        r.introduction=$intro,r.retrievalMode=$retrieval,
                        r.explanationSource=$explanation,r.decisionPlan=$plan,
                        r.decisionEvaluation=$evaluation,r.searchAttempts=$attempts
                    REMOVE r.agentPlan, r.agentEvaluation, r.agentAttempts
                    MERGE (g)-[:HAS_RECOMMENDATION]->(r)
                    MERGE (ep:EmailPreview {previewId:$previewId})
                    SET ep.subject=$subject,ep.generatedAt=datetime(),ep.status='READY',
                        ep.kind='HOLDER_RECOMMENDATIONS'
                    REMOVE ep.html
                    MERGE (r)-[:HAS_EMAIL_PREVIEW]->(ep)
                    SET g.status='READY',g.failureReason='',g.processedFingerprint=$fp,
                        g.processingStartedAt=0
                    """,p("id",goalId,"recId",recommendationId,"fp",fingerprint,
                        "count",matches.size(),"intro",intro,"retrieval",retrievalMode,
                        "explanation",explanationSource,"plan",plan,"evaluation",evaluation,
                        "attempts",attempts,"previewId","EP-"+goalId,"subject",subject)).consume();
                tx.run("""
                    MATCH (r:Recommendation {recommendationId:$recId})-[rel:RECOMMENDS]->(:Store) DELETE rel
                    """,p("recId",recommendationId)).consume();
                tx.run("""
                    MATCH (g:Goal {goalId:$id})-[rel:MATCHED_STORE]->(:Store) DELETE rel
                    """,p("id",goalId)).consume();
                tx.run("""
                    MATCH (c:Customer)-[:HAS_GOAL]->(g:Goal {goalId:$id})
                    MATCH (c)-[rel:RECOMMENDED_STORE {goalId:$id}]->(:Store)
                    DELETE rel
                    """,p("id",goalId)).consume();
                for(MerchantMatch m:matches) {
                    tx.run("""
                        MATCH (c:Customer {plccAccountStatus:'ACTIVE'})-[:HAS_GOAL]->(g:Goal {goalId:$goalId})
                        MATCH (g)-[:HAS_RECOMMENDATION]->(r:Recommendation)
                        MATCH (s:Store {storeId:$storeId})
                        MERGE (r)-[rel:RECOMMENDS]->(s)
                        SET rel.rank=$rank,rel.distanceMiles=$distance,
                            rel.explanation=$explanation,rel.semanticScore=$score,
                            rel.category=$category
                        MERGE (g)-[gr:MATCHED_STORE]->(s)
                        SET gr.rank=$rank,gr.distanceMiles=$distance
                        MERGE (c)-[cr:RECOMMENDED_STORE {goalId:$goalId}]->(s)
                        SET cr.rank=$rank
                        """,p("goalId",goalId,"storeId",m.storeId(),"rank",m.rank(),
                            "distance",m.distanceMiles(),"explanation",m.explanation(),
                            "category",m.category(),"score",m.semanticScore())).consume();
                }
                return null;
            });
        }
    }

    public void completeInvitation(String goalId, String fingerprint, String subject,
                                   String category) {
        try(Session session=driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (c:Customer {plccAccountStatus:'NONE'})-[:HAS_GOAL]->(g:Goal {goalId:$id})
                    MERGE (r:Recommendation {recommendationId:$recId})
                    SET r.generatedAt=datetime(),r.fingerprint=$fp,r.merchantCount=0,
                        r.introduction='',r.retrievalMode='PLCC_CATEGORY_MATCH',
                        r.explanationSource='TEMPLATE',r.decisionPlan='INVITE_ONLY',
                        r.decisionEvaluation='CATEGORY_MATCH',r.searchAttempts=0
                    REMOVE r.agentPlan, r.agentEvaluation, r.agentAttempts
                    MERGE (g)-[:HAS_RECOMMENDATION]->(r)
                    MERGE (ep:EmailPreview {previewId:$previewId})
                    SET ep.subject=$subject,ep.generatedAt=datetime(),ep.status='READY',
                        ep.kind='PLCC_INVITATION'
                    REMOVE ep.html
                    MERGE (r)-[:HAS_EMAIL_PREVIEW]->(ep)
                    SET g.status='READY',g.failureReason='',g.processedFingerprint=$fp,
                        g.processingStartedAt=0
                    """,p("id",goalId,"recId","REC-"+goalId,"previewId","EP-"+goalId,
                        "fp",fingerprint,"subject",subject)).consume();
                // Prevent any earlier holder recommendation links surviving an account change.
                tx.run("""
                    MATCH (g:Goal {goalId:$id})-[r:MATCHED_STORE]->(:Store) DELETE r
                    """,p("id",goalId)).consume();
                tx.run("""
                    MATCH (c:Customer)-[r:RECOMMENDED_STORE {goalId:$id}]->(:Store) DELETE r
                    """,p("id",goalId)).consume();
                tx.run("""
                    MATCH (r:Recommendation {recommendationId:$recId})-[rel:RECOMMENDS]->(:Store)
                    DELETE rel
                    """,p("recId","REC-"+goalId)).consume();
                return null;
            });
        }
    }

    public void markTerminal(String goalId, String status, String reason) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (g:Goal {goalId:$id})
                    SET g.status=$status, g.failureReason=$reason,
                        g.processedFingerprint=g.inputFingerprint, g.processingStartedAt=0
                    """, p("id", goalId, "status", status, "reason", reason)).consume();
                return null;
            });
        }
    }

    public void markFailure(String goalId, String reason) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (g:Goal {goalId:$id})
                    SET g.status = CASE WHEN coalesce(g.attempts,0) >= 3 THEN 'FAILED' ELSE 'RETRY_PENDING' END,
                        g.failureReason=$reason,
                        g.nextRetryAt=$nextRetry, g.processingStartedAt=0
                    """, p("id", goalId, "reason", reason,
                        "nextRetry", System.currentTimeMillis() + 60000L)).consume();
                return null;
            });
        }
    }

    public List<Customer> demoCustomers(int limit) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (c:Customer) RETURN c.customerId AS id, c.ecn AS ecn,
                c.name AS name, c.city AS city, c.state AS state, c.postalCode AS postal,
                c.latitude AS lat, c.longitude AS lon, c.plccAccountStatus AS plcc
                ORDER BY c.customerId LIMIT $limit
                """, p("limit", limit)).list(r -> new Customer(s(r,"id"),s(r,"ecn"),
                    s(r,"name"),s(r,"city"),s(r,"state"),s(r,"postal"),
                    d(r,"lat"),d(r,"lon"),s(r,"plcc"))));
        }
    }

    public Optional<Customer> customerByEcn(String ecn) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (c:Customer {ecn:$ecn}) RETURN c.customerId AS id, c.ecn AS ecn,
                c.name AS name, c.city AS city, c.state AS state, c.postalCode AS postal,
                c.latitude AS lat, c.longitude AS lon, c.plccAccountStatus AS plcc
                """, p("ecn", ecn)).list(r -> new Customer(s(r,"id"),s(r,"ecn"),
                    s(r,"name"),s(r,"city"),s(r,"state"),s(r,"postal"),
                    d(r,"lat"),d(r,"lon"),s(r,"plcc"))).stream().findFirst());
        }
    }

    /** Customer-ID lookup for the natural-language graph search demo. */
    public Optional<Customer> customerById(String customerId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (c:Customer {customerId:$id}) RETURN c.customerId AS id, c.ecn AS ecn,
                c.name AS name, c.city AS city, c.state AS state, c.postalCode AS postal,
                c.latitude AS lat, c.longitude AS lon, c.plccAccountStatus AS plcc
                """, p("id", customerId)).list(r -> new Customer(s(r,"id"),s(r,"ecn"),
                    s(r,"name"),s(r,"city"),s(r,"state"),s(r,"postal"),
                    d(r,"lat"),d(r,"lon"),s(r,"plcc"))).stream().findFirst());
        }
    }

    public List<GoalRow> goalsForCustomer(String ecn) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (c:Customer {ecn:$ecn})-[:HAS_GOAL]->(g:Goal)
                OPTIONAL MATCH (g)-[:HAS_RECOMMENDATION]->(:Recommendation)-[:HAS_EMAIL_PREVIEW]->(ep:EmailPreview)
                RETURN g.goalId AS id, g.description AS description, g.category AS category,
                  g.status AS status, c.ecn AS ecn, c.name AS name, c.city AS city,
                  CASE WHEN g.status='READY' THEN ep.previewId ELSE null END AS previewId,
                  g.failureReason AS failure
                ORDER BY g.goalId
                """, p("ecn", ecn)).list(this::toGoalRow));
        }
    }

    public List<GoalRow> dashboardGoals(int limit) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (c:Customer)-[:HAS_GOAL]->(g:Goal)
                OPTIONAL MATCH (g)-[:HAS_RECOMMENDATION]->(:Recommendation)-[:HAS_EMAIL_PREVIEW]->(ep:EmailPreview)
                RETURN g.goalId AS id, g.description AS description, g.category AS category,
                  g.status AS status, c.ecn AS ecn, c.name AS name, c.city AS city,
                  CASE WHEN g.status='READY' THEN ep.previewId ELSE null END AS previewId,
                  g.failureReason AS failure
                ORDER BY CASE g.status WHEN 'PROCESSING' THEN 0 WHEN 'READY' THEN 1
                            WHEN 'PENDING' THEN 2 ELSE 3 END, g.goalId
                LIMIT $limit
                """, p("limit", limit)).list(this::toGoalRow));
        }
    }

    private GoalRow toGoalRow(Record r) {
        return new GoalRow(s(r,"id"),s(r,"description"),s(r,"category"),
                s(r,"status"),s(r,"ecn"),s(r,"name"),s(r,"city"),
                s(r,"previewId"),s(r,"failure"));
    }

    public Optional<Preview> preview(String goalId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (g:Goal {goalId:$id})-[:HAS_RECOMMENDATION]->(:Recommendation)
                      -[:HAS_EMAIL_PREVIEW]->(ep:EmailPreview)
                WHERE g.status='READY'
                RETURN ep.previewId AS previewId, g.goalId AS goalId,
                       ep.subject AS subject, toString(ep.generatedAt) AS generatedAt,
                       '' AS html
                """, p("id",goalId)).list(r -> new Preview(
                    s(r,"previewId"),s(r,"goalId"),s(r,"subject"),
                    s(r,"generatedAt"),s(r,"html"))).stream().findFirst());
        }
    }

    public List<MerchantMatch> recommendedMerchants(String goalId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (c:Customer {plccAccountStatus:'ACTIVE'})-[:HAS_GOAL]->
                      (g:Goal {goalId:$id})-[:HAS_RECOMMENDATION]->(r:Recommendation)
                      -[rel:RECOMMENDS]->(s:Store)<-[:HAS_STORE]-(m:Merchant)
                RETURN m.merchantId AS merchantId, s.storeId AS storeId, m.name AS name,
                       coalesce(rel.category,m.category) AS category, m.description AS description, s.city AS city,
                       s.state AS state, s.addressLine AS address,
                       rel.distanceMiles AS distance, rel.rank AS rank,
                       rel.explanation AS explanation, rel.semanticScore AS score
                ORDER BY rel.rank
                """, p("id", goalId)).list(r -> new MerchantMatch(
                    s(r,"merchantId"),s(r,"storeId"),s(r,"name"),s(r,"category"),
                    s(r,"description"),s(r,"city"),s(r,"state"),s(r,"address"),
                    r.get("distance").asDouble(),r.get("rank").asInt(),s(r,"explanation"),
                    d(r, "score"))));
        }
    }

    public Counts counts() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Record r = tx.run("""
                    MATCH (g:Goal)
                    WITH count(g) AS goals,
                         count(CASE WHEN g.status='READY' THEN 1 END) AS ready,
                         count(CASE WHEN g.status='PENDING' OR g.status='RETRY_PENDING' THEN 1 END) AS pending,
                         count(CASE WHEN g.status='PROCESSING' THEN 1 END) AS processing,
                         count(CASE WHEN g.status='UNSUPPORTED' THEN 1 END) AS unsupported,
                         count(CASE WHEN g.status='FAILED' THEN 1 END) AS failed,
                         count(CASE WHEN g.status='NO_MATCH' OR g.status='NO_LOCATION' THEN 1 END) AS noMatches
                    RETURN goals, ready, pending, processing, unsupported, failed, noMatches,
                           COUNT { MATCH (:Customer) } AS customers,
                           COUNT { MATCH (:Merchant) } AS merchants
                    """).single();
                return new Counts(r.get("customers").asLong(),r.get("goals").asLong(),
                        r.get("merchants").asLong(),r.get("ready").asLong(),r.get("pending").asLong(),
                        r.get("processing").asLong(),r.get("unsupported").asLong(),
                        r.get("failed").asLong(),r.get("noMatches").asLong());
            });
        }
    }

    public record MerchantVectorSource(String merchantId, String name, String industry,
                                       String category, String description, String inputHash) {}

    /** Read only active, supported merchants whose content/model vector is missing or stale. */
    public List<MerchantVectorSource> merchantsNeedingEmbeddings(String model, int limit) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (m:Merchant {active:true})-[:OFFERS_CATEGORY]->(:Category)
                    <-[:SUPPORTS_CATEGORY]-(:PLCC)
                WHERE m.embedding IS NULL OR m.vectorFingerprint IS NULL
                    OR m.vectorFingerprint <> m.vectorInputHash
                    OR coalesce(m.vectorModel,'') <> $model
                RETURN m.merchantId AS id, m.name AS name, m.industry AS industry,
                    m.categories AS categories, m.description AS description,
                    m.vectorInputHash AS fp
                ORDER BY m.merchantId LIMIT $limit
                """, p("model", model, "limit", limit)).list(r -> new MerchantVectorSource(
                    s(r,"id"), s(r,"name"), s(r,"industry"), String.join(",", r.get("categories").asList(Value::asString)),
                    s(r,"description"), s(r,"fp"))));
        }
    }

    public void saveMerchantEmbedding(String id, String fingerprint, String model, List<Double> vector) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (m:Merchant {merchantId:$id}) WHERE m.vectorInputHash=$fp
                    CALL db.create.setNodeVectorProperty(m, 'embedding', $vector)
                    SET m.vectorFingerprint=$fp, m.vectorModel=$model
                    RETURN m.merchantId AS id
                    """, p("id", id, "fp", fingerprint, "model", model, "vector", vector)).consume();
                return null;
            });
        }
    }

    public Optional<List<Double>> cachedGoalEmbedding(String goalId, String fingerprint, String model) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (g:Goal {goalId:$id})
                WHERE coalesce(g.classificationFingerprint,g.inputFingerprint)=$fp AND g.vectorFingerprint=$fp
                      AND g.vectorModel=$model AND g.embedding IS NOT NULL
                RETURN g.embedding AS embedding
                """, p("id",goalId, "fp",fingerprint, "model",model))
                    .list(r -> r.get("embedding").asList(Value::asDouble)).stream().findFirst());
        }
    }

    public void saveGoalEmbedding(String goalId, String fingerprint, String model, List<Double> vector) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (g:Goal {goalId:$id})
                    WHERE coalesce(g.classificationFingerprint,g.inputFingerprint)=$fp
                    CALL db.create.setNodeVectorProperty(g, 'embedding', $vector)
                    SET g.vectorFingerprint=$fp, g.vectorModel=$model
                    RETURN g.goalId AS id
                    """, p("id", goalId, "fp", fingerprint, "model", model, "vector", vector)).consume();
                return null;
            });
        }
    }

    /** Neo4j ANN retrieval, validated against graph relationships before returning any result. */
    public Map<String, Double> vectorCandidateScores(String goalId, List<Double> vector, int k, String model) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Map<String, Double> result = new LinkedHashMap<>();
                for (Record record : tx.run("""
                    CALL db.index.vector.queryNodes('merchant_embedding_index', $k, $vector)
                    YIELD node, score
                    WITH node AS m, score
                    MATCH (m:Merchant {active:true})-[:OFFERS_CATEGORY]->(cat:Category)
                          <-[:CLASSIFIED_AS]-(g:Goal {goalId:$goalId})
                    MATCH (:PLCC)-[:SUPPORTS_CATEGORY]->(cat)
                    MATCH (m)-[:HAS_STORE]->(s:Store)
                    WHERE s.latitude IS NOT NULL AND s.longitude IS NOT NULL
                          AND m.vectorFingerprint = m.vectorInputHash
                          AND m.vectorModel=$model
                    RETURN DISTINCT m.merchantId AS id, score ORDER BY score DESC
                    """, p("goalId", goalId, "k", k, "vector", vector, "model", model)).list()) {
                    result.put(record.get("id").asString(), record.get("score").asDouble());
                }
                return result;
            });
        }
    }

    public VectorStats vectorStats(String model) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Record r = tx.run("""
                    MATCH (m:Merchant {active:true})-[:OFFERS_CATEGORY]->(:Category)
                         <-[:SUPPORTS_CATEGORY]-(:PLCC)
                    RETURN count(DISTINCT m) AS total,
                           count(DISTINCT CASE WHEN m.embedding IS NOT NULL
                             AND m.vectorFingerprint=m.vectorInputHash AND m.vectorModel=$model
                             THEN m END) AS indexed
                    """, p("model", model)).single();
                long goalVectors = tx.run("""
                    MATCH (g:Goal) WHERE g.embedding IS NOT NULL
                      AND g.vectorFingerprint=coalesce(g.classificationFingerprint,g.inputFingerprint)
                      AND g.vectorModel=$model
                    RETURN count(g) AS goals
                    """, p("model", model)).single().get("goals").asLong();
                String state = "UNKNOWN";
                try {
                    var states = tx.run("""
                        SHOW VECTOR INDEXES YIELD name, state
                        WHERE name='merchant_embedding_index' RETURN state
                        """).list(row -> row.get("state").asString());
                    if (!states.isEmpty()) state = states.getFirst();
                } catch (Exception ignored) { /* schema may still be starting */ }
                return new VectorStats(r.get("total").asLong(), r.get("indexed").asLong(),
                        goalVectors, state);
            });
        }
    }

    public Optional<RecommendationMetadata> recommendationMetadata(String goalId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (g:Goal {goalId:$id})-[:HAS_RECOMMENDATION]->(r:Recommendation)
                RETURN r.retrievalMode AS retrievalMode, r.explanationSource AS explanationSource,
                       coalesce(r.decisionPlan,r.agentPlan) AS plan,
                       coalesce(r.decisionEvaluation,r.agentEvaluation) AS evaluation,
                       coalesce(r.searchAttempts,r.agentAttempts) AS attempts
                """, p("id",goalId)).list(r -> new RecommendationMetadata(
                    s(r,"retrievalMode"),s(r,"explanationSource"),s(r,"plan"),s(r,"evaluation"),
                    r.get("attempts").isNull()?0:r.get("attempts").asInt())).stream().findFirst());
        }
    }

    /** Explicit demo-only reprocessing; stored preview is overwritten, never emailed. */
    public boolean requeueForDemo(String goalId) {
        try (Session session = driver.session()) {
            return session.executeWrite(tx -> tx.run("""
                MATCH (g:Goal {goalId:$id})
                WHERE g.status <> 'PROCESSING'
                SET g.status='PENDING', g.attempts=0, g.failureReason='', g.nextRetryAt=0
                RETURN g.goalId AS id
                """, p("id",goalId)).hasNext());
        }
    }

    /** Future account events may update status; records and previews must be reprocessed. */
    public void updatePlccAccountStatus(String customerId, String status) {
        if(!Set.of("ACTIVE","NONE","UNKNOWN").contains(status))
            throw new IllegalArgumentException("Invalid account status");
        try(Session session=driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (c:Customer {customerId:$id})
                    WITH c,coalesce(c.plccAccountStatus,'UNKNOWN') AS old
                    SET c.plccAccountStatus=$status
                    WITH c,old WHERE old<>$status
                    MATCH (c)-[:HAS_GOAL]->(g:Goal)
                    SET g.status='PENDING',g.attempts=0,g.nextRetryAt=0,
                        g.failureReason='',g.processingStartedAt=0
                    WITH c,collect(g.goalId) AS goalIds
                    MATCH (c)-[cr:RECOMMENDED_STORE]->(:Store)
                    WHERE cr.goalId IN goalIds DELETE cr
                    """,p("id",customerId,"status",status)).consume();
                tx.run("""
                    MATCH (c:Customer {customerId:$id})-[:HAS_GOAL]->(g:Goal)
                    MATCH (g)-[r:MATCHED_STORE]->(:Store) DELETE r
                    """,p("id",customerId)).consume();
                tx.run("""
                    MATCH (c:Customer {customerId:$id})-[:HAS_GOAL]->(g:Goal)
                    MATCH (g)-[:HAS_RECOMMENDATION]->(r:Recommendation)-[rel:RECOMMENDS]->(:Store)
                    DELETE rel
                    """,p("id",customerId)).consume();
                return null;
            });
        }
    }

    public void saveConsentRequest(String goalId) {
        try(Session session=driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (c:Customer {plccAccountStatus:'NONE'})-[:HAS_GOAL]->(g:Goal {goalId:$id})
                    MERGE (request:ConsentRequest {requestId:$reqId})
                    ON CREATE SET request.createdAt=datetime(),request.status='REQUESTED'
                    MERGE (g)-[:HAS_CONSENT_REQUEST]->(request)
                    """,p("id",goalId,"reqId","CR-"+goalId)).consume();
                return null;
            });
        }
    }

    public boolean hasPlccMerchantCategoryMatch(String goalId) {
        try(Session session=driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (:Goal {goalId:$id})-[:CLASSIFIED_AS]->(cat:Category)
                       <-[:SUPPORTS_CATEGORY]-(:PLCC)
                MATCH (m:Merchant {active:true})-[:OFFERS_CATEGORY]->(cat)
                RETURN m.merchantId AS id LIMIT 1
                """,p("id",goalId)).hasNext());
        }
    }

    public String recommendationIntroduction(String goalId) {
        try(Session session=driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (c:Customer {plccAccountStatus:'ACTIVE'})-[:HAS_GOAL]->
                     (g:Goal {goalId:$id})-[:HAS_RECOMMENDATION]->(r:Recommendation)
                RETURN r.introduction AS intro
                """,p("id",goalId)).list(r -> s(r,"intro")).stream().findFirst().orElse("") );
        }
    }

    private static String s(Record r, String key) {
        Value value = r.get(key);
        return value.isNull() ? "" : value.asString();
    }
    private static Double d(Record r, String key) {
        Value value = r.get(key);
        return value.isNull() ? null : value.asDouble();
    }
    private static Map<String,Object> p(Object... pairs) {
        Map<String,Object> result = new HashMap<>();
        for (int i=0; i<pairs.length; i+=2) result.put((String)pairs[i], pairs[i+1]);
        return result;
    }
}
