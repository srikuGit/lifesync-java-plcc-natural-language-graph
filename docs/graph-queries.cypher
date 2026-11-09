// Current multi-product catalog: PLCC, home purchase, refinancing and auto loans.
// :PLCC is the legacy card master; :Product is the shared product catalog.
MATCH (p:Product)-[r:SUPPORTS_CATEGORY]->(cat:Category)
RETURN p,r,cat;

// Count categories by product to verify ingestion.
MATCH (p:Product)-[:SUPPORTS_CATEGORY]->(cat:Category)
RETURN p.productId AS product,p.name AS name,collect(cat.categoryId) AS categories
ORDER BY product;

// All goal/product/category paths for one customer (no merchant disclosure).
MATCH path=(c:Customer {customerId:'C001'})-[:HAS_GOAL]->(g:Goal)
      -[:CLASSIFIED_AS]->(cat:Category)<-[:SUPPORTS_CATEGORY]-(p:Product)
WHERE p.productId=coalesce(g.productId,'PLCC')
RETURN path;

// Legacy queries below intentionally show only parts of the graph.

// Open http://localhost:7474 and paste one query at a time.

// 1. Customer -> Goal -> Category <- Merchant -> Store
MATCH (c:Customer)-[cg:HAS_GOAL]->(g:Goal)-[gc:CLASSIFIED_AS]->(cat:Category)
      <-[mc:OFFERS_CATEGORY]-(m:Merchant)-[ms:HAS_STORE]->(s:Store)
RETURN c,cg,g,gc,cat,mc,m,ms,s LIMIT 40;

// 2. All nodes connected to one example goal
MATCH (c:Customer)-[cg:HAS_GOAL]->(g:Goal {goalId:'G001'})-[gc:CLASSIFIED_AS]->(cat:Category)
      <-[mc:OFFERS_CATEGORY]-(m:Merchant)-[ms:HAS_STORE]->(s:Store)
RETURN c,cg,g,gc,cat,mc,m,ms,s LIMIT 40;

// 3. Graph nodes for saved recommendations
MATCH (g:Goal {goalId:'G001'})-[gr:HAS_RECOMMENDATION]->(r:Recommendation)
      -[re:RECOMMENDS]->(s:Store)<-[ms:HAS_STORE]-(m:Merchant)
RETURN g,gr,r,re,s,ms,m LIMIT 10;

// 4. Preview statuses (no actual email sent)
MATCH (g:Goal)-[:HAS_RECOMMENDATION]->(r:Recommendation)
      -[:HAS_EMAIL_PREVIEW]->(p:EmailPreview)
RETURN g.goalId,r.merchantCount,p.status,p.generatedAt LIMIT 30;

// 5. Unsupported goals
MATCH (g:Goal {status:'UNSUPPORTED'})
RETURN g.goalId,g.description,g.rawCategory;

// ------- New in GraphRAG Vector edition -------
// Show vector index health (ONLINE once populated)
SHOW VECTOR INDEXES;

// Show merchants with valid cached vector embeddings
MATCH (m:Merchant) WHERE m.embedding IS NOT NULL
RETURN m.merchantId AS id, m.category AS category,
       size(m.embedding) AS dimensions, m.vectorModel AS model LIMIT 20;

// ANN search demonstration: replace $vector with one stored goal vector
MATCH (g:Goal {goalId:'G001'}) WHERE g.embedding IS NOT NULL
WITH g, g.embedding AS queryVector
CALL db.index.vector.queryNodes('merchant_embedding_index', 25, queryVector)
YIELD node, score
WITH g, node AS m, score
MATCH (g)-[:CLASSIFIED_AS]->(cat:Category)<-[:OFFERS_CATEGORY]-(m)
WHERE m.active = true
RETURN g.goalId AS goalId, m.merchantId AS merchantId,
       m.name AS merchant, cat.name AS category, score
ORDER BY score DESC LIMIT 20;

// Graph-grounded LLM and semantic evidence persisted with recommendations
MATCH (g:Goal)-[:HAS_RECOMMENDATION]->(r:Recommendation)-[rel:RECOMMENDS]->(s:Store)
RETURN g.goalId AS goalId, r.retrievalMode AS retrieval,
       r.explanationSource AS LLM, rel.rank AS rank,
       s.storeId AS store, rel.semanticScore AS vectorScore,
       rel.distanceMiles AS straightLineMiles
ORDER BY goalId, rank LIMIT 30;

// PLCC master and categories. Other categories can exist WITHOUT a PLCC relationship.
MATCH (p:PLCC)-[pc:SUPPORTS_CATEGORY]->(cat:Category) RETURN p,pc,cat;

// Multi-category merchant graph.
MATCH (m:Merchant)-[r:OFFERS_CATEGORY]->(cat:Category) RETURN m,r,cat LIMIT 80;

// GraphRAG eligible paths only (PLCC category filter).
MATCH (c:Customer)-[cg:HAS_GOAL]->(g:Goal)-[gc:CLASSIFIED_AS]->(cat:Category)
      <-[pc:SUPPORTS_CATEGORY]-(plcc:PLCC)
MATCH (m:Merchant {active:true})-[mc:OFFERS_CATEGORY]->(cat)
MATCH (m)-[ms:HAS_STORE]->(s:Store)
RETURN c,cg,g,gc,cat,pc,plcc,m,mc,ms,s LIMIT 40;

// Recommended stores linked to both goal and customer for verified active holders.
MATCH (c:Customer {plccAccountStatus:'ACTIVE'})-[:HAS_GOAL]->(g:Goal)
      -[rs:MATCHED_STORE]->(s:Store) RETURN c,g,rs,s LIMIT 30;

// Offers Flow records should NEVER be in Neo4j.
