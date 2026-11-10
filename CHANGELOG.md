## Unreleased — Conversational GraphRAG and manual campaign previews

- Show structured customer profile details first; retrieve goals through MCP only after a chat request.
- List all product goals without merchants/offers, and disclose PLCC details only on explicit requests for eligible ACTIVE holders.
- Add schema-constrained conversational actions, bounded Java/MCP dispatch, session-isolated follow-ups and cached-detail eligibility rechecks.
- Add separate manual ACTIVE-holder email preview campaigns with persisted run statuses, bounded background generation and current-fact rendering; no email delivery.
- Add profile/goal/merchant MCP tools, structured fixture names, updated smoke checks, browser validation and regression tests.

## v0.9.0

- Add home purchase, home refinancing and auto financing alongside PLCC, with curated synthetic product/customer/goal/provider fixtures and an external synthetic offer feed.
- Add a customer-ID entry and comparison screen, product filtering and switching customers on the same page. Move legacy automation to `/automation`; legacy ingestion and scheduler are opt-in.
- Expose customer-scoped graph retrieval as a read-only, stateless MCP Streamable HTTP tool, consumed by the UI service and compatible with the official MCP Python client.
- Render nearest-provider and per-product offer comparisons from Java facts. Generate optional customer-subgraph-grounded UI introductions with factual fallback; exclude customer identifiers from prompts.
- Add unit and live smoke coverage for scope, account restrictions, offer ranking/validity, MCP transport and content fallback.

# Changelog

## 0.7.0 — Deterministic Java retrieval and chat LLM for email text only

- Removed `AgenticDecisionLoop`, its LLM prompt path, `AGENTIC_LLM_ENABLED`, and agent-specific tests.
- Added `RetrievalDecisionService` for deterministic GRAPH/HYBRID choice and one bounded vector refinement attempt; kept PLCC and account eligibility guardrails.
- Renamed `RecommendationExplanationAgent` to `EmailExplanationService` to reflect its actual grounded email-copy responsibility. It is the only chat-model caller.
- Retained embedding-based goal/merchant classification, optional graph-constrained vector retrieval and Haversine top-five ranking.
- Persisted Java-only decision metadata as `decisionPlan`, `decisionEvaluation` and `searchAttempts`; migrated old fields when reprocessing, with legacy read fallback.
- Updated UI, docs, and tests for the Java-only decision workflow.

## 0.6.0 — Semantic classification as ordinary Spring services

- Renamed GoalClassificationAgent → GoalClassificationService and MerchantClassificationAgent → MerchantClassificationService, replacing their Spring `@Component` annotations with `@Service`.
- Moved semantic classification code into `com.lifesync.poc.classification` and updated all production references and test class names.
- Classification remains embedding-only, with no LLM fallback; the retrieval-planning agent and separate explanation generator remain unchanged.
- Kept semantic taxonomy version constant so renames alone do not trigger data reprocessing or invalidate embedding caches.


## 0.5.0 — Semantic category classification

- Replaced both rule-based category classifiers with `SemanticCategoryClassifier` using `nomic-embed-text` cosine similarity against 13 descriptive category vectors.
- Preserved multi-label goal/merchant classification, external-category competition, graph-enforced PLCC eligibility, and repair-only merchant business exclusions.
- Added versioned, content-hashed disk caching under `data/cache/category-classification`, plus first-run progress logging.
- Removed chat-model classification flag; semantic embedding model is now mandatory even if merchant vector search is disabled.
- Added deterministic semantic classifier tests (multi-category, non-PLCC rejection, cache reuse, fail-closed).
- Preserved v0.4.2 Neo4j void-procedure fix.


## 0.4.2 — Neo4j void vector procedure fix

- Fixed both merchant and goal vector persistence queries: `db.create.setNodeVectorProperty` returns no result columns, so the queries must not use `YIELD node`.
- Kept the matched `m` / `g` variable in `SET` and `RETURN`, preserving vector fingerprint/model metadata and incremental backfill behavior.
- Added regression tests checking both Cypher write queries against this failure mode.
- No data reset or Neo4j volume deletion required; the next backfill attempt can resume after redeployment.


## 0.4.0 — Multi-label goal classification and evidence-driven retrieval agent

- Classified each goal into **multiple** allow-listed PLCC categories using explicit goal category plus description cues (optional Ollama assistance), returning ordered labels, confidence and source.
- Migrated goals to multiple Neo4j `CLASSIFIED_AS` relationships; deleted the premature single-category eligibility rejection.
- Added classification/version fingerprinting and automatic startup requeue of goals whose classification changes; refreshed semantic embedding cache and prompt to include all goal categories.
- Agent planning now consumes measured confidence, category count, unique eligible merchant/store counts and vector configuration. Enforced multi-category / low-confidence / low-candidate HYBRID safeguards even when LLM disagrees.
- Updated evaluation with distinct graph-eligible semantic match counts, explicit evidence thresholds, and bounded retries.
- Exposed classification evidence, planner thresholds, metrics, overrides and evaluations in the goal UI and stored recommendation metadata.
- Prioritized the classified goal category order when de-duplicating merchants with several matching categories.
- Updated demo goal G001 to describe furniture plus electronics and added targeted agent, classifier and orchestrator tests; corrected merchant JSON count assertion from 50 to 52.
- Retained strict PLCC holder/prospect separation, current Offers REST retrieval and Google Maps behavior.

## 0.3.0 — PLCC, Offers Flow and bounded agents

- Introduced an explicit PLCC master node with category whitelist, plus other-business categories outside PLCC.
- Merchant classification now yields multiple product categories using the business description.
- Enriched synthetic customer fixture with verified-for-demo ACTIVE/NONE/UNKNOWN PLCC status.
- Implemented holder-only recommendation path and non-holder PLCC invitation/permission path with strict separation.
- Added future account status ingestion interface, reprocessing and relationship cleanup.
- Added plan/retrieve/evaluate/refine agent loop (maximum two retrieval attempts).
- Added active semantic filtering under mandatory PLCC graph category constraints.
- Added REST Offers Flow client, current-date and customer/merchant targeting checks, and changing mock offers endpoint.
- Changed preview persistence to metadata only so offer data and rendered HTML are never persisted to Neo4j.
- Added 24h signed consent links and explicit checkbox requests (`ConsentRequest` graph node); no email delivery.
- Kept approximate Google Maps locations in holder-only email previews.
- Added dedicated unit tests for PLCC routing, offer targeting, consent and dynamic preview behavior.

## 0.2.0 — GraphRAG and vector retrieval

- Incremental merchant embeddings, goal embedding cache, Neo4j vector search and grounded merchant explanations.

## 0.1.0 — Base POC

- Synthetic JSON ingestion, Neo4j graph, scheduler, distance-ranked merchant results and HTML email previews.

### v0.8.0 — Natural-language graph exploration
- Added `/graph-search` customer-ID lookup, goal listing and free-text Q&A routing.
- Local Ollama chat interprets misspelled requests and selects allow-listed goal IDs; optional embedding fallback.
- Neo4j queries remain fixed and parameterized, scoped to the selected demo customer; PLCC account disclosure restrictions maintained.
- Displays stored recommendations, or explicitly labeled preliminary PLCC-eligible graph candidates for unprocessed goals, by distance.
- Added `NL_QUERY_LLM_ENABLED` and unit tests for goal allow-list validation and no-PII LLM prompts.
