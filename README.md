## Updating the running demo

The customer pages use **DEMO bank** branding and the chat is **Banker assistant**.
After pulling changes, rebuild the app image; restarting an existing image keeps its old pages and JavaScript:

```sh
git pull origin main
docker compose up -d --build --no-deps app
```

Reload the page and start a new conversation. The **Show my goals**, **Find discounts**,
and **Shops near me** buttons invoke explicit customer-scoped graph actions. Shopping
buttons select only PLCC goals and do not require model interpretation. Free-text
questions still use Ollama. Account restrictions and successful address geocoding
still determine whether nearby shops and offers can be displayed.

# Conversational GraphRAG and manual campaign previews

Enter a customer ID to see **first name, last name and a chat window**. No goals or offers are shown automatically. Ask **“Show my goals”** to list all product goals, then ask about shops/discounts for eligible PLCC merchant and offer details. Other product goals remain basic-only. Follow-up questions reuse bounded customer-session context.

A separate **Campaign previews** page starts a manual background job for **ACTIVE PLCC holders only**. It creates email previews and records progress. Bankers can send an individual preview from a campaign run or an active PLCC customer profile to the local Mailpit inbox at http://localhost:8025.

See [the conversational architecture and setup](docs/conversational-graphrag.md) and [manual campaign workflow](docs/manual-campaigns.md). These instructions supersede the historical UI descriptions below.

## Troubleshooting “Ask about my goals”

The graph lookup can work even when Ollama question interpretation is unavailable. For the default chat model, run in Windows CMD:

```bat
docker compose exec ollama ollama list
docker compose exec ollama ollama pull qwen2.5:1.5b
docker compose logs --tail=100 app ollama
```

If you changed `OLLAMA_CHAT_MODEL`, install that model instead. `NL_QUERY_LLM_ENABLED` controls questions independently of `UI_CONTENT_ENABLED`.

Question requests now use an Ollama JSON schema constrained to the loaded goal IDs and supported filters. Invalid output is still rejected. Failure messages and app logs distinguish AI_DISABLED, MODEL_NOT_FOUND, MODEL_TIMEOUT, MODEL_BUSY, INVALID_PLAN and OLLAMA_UNAVAILABLE; logs do not include questions or raw model output. Ollama keeps a loaded model for five minutes by default instead of unloading after five seconds. Model work defaults to a 30-second deadline; set `AI_TIMEOUT_SECONDS` (5–60 seconds) for your CPU, then recreate the app. Initial page loading still makes no model call.

## Current customer UI: PLCC only, opt-in contact and single goal ownership

The customer screen now requests **only PLCC data through MCP**, including its question and AI-explanation flows. Home purchase, refinancing and auto-loan products/goals remain in Neo4j and accessible through the MCP product filter; they are not shown in the customer UI.

For customers without an ACTIVE PLCC account, the screen offers an unchecked consent box to request representative contact about PLCC details. An explicit POST records a customer-linked `PlccContactRequest` with `REQUESTED` status, consent text/version and timestamp. It does not open an account, change account status, reveal restricted merchants, or initiate a real call in this demonstration.

The shared-goal issue was caused by obsolete `HAS_GOAL` edges left behind when fixture customer identities changed. Both ingestion paths now remove incorrect ownership edges before attaching the authoritative owner. Startup repairs existing relationships using source ECNs before geocoding. It does not delete customers, goals, products or volumes. Unknown ambiguous owners cause a clear startup error instead of guessing. See [ownership and consent details](docs/plcc-consent-and-ownership.md).

## Faster goals page and clearer product navigation

**View goals** now loads every product without a dropdown or LLM wait. All 50 fixture customers, C001–C050, have PLCC shopping, home purchase, refinancing, and auto-loan goals. Home purchase and refinancing are clearly labeled **Home lending**.

Customers and providers now use different address fixtures. Restart after rebuilding to geocode changed provider addresses; the cache keys change automatically. Distances are never inflated: exact co-locations display “Same mapped location”, and nonzero distances below 0.1 mile display “<0.1 mi”.

After loading a customer, use **Ask about these goals and offers** to narrow or compare the displayed results. Example buttons show home lending, auto-loan APR, and shopping-discount questions. Questions run asynchronously and reuse the session's loaded customer graph for up to five minutes; they do not reload MCP. **Refresh all goals and offers** obtains fresh data. Switching customers invalidates the previous snapshot. Optional **Explain all goals with AI** is separate from the initial load. AI work has a 30-second default server limit and bounded concurrency, with explicit fallback when unavailable. Live provider/model speed still depends on your environment.

## Address geocoding and MCP questions

The current customer IDs are **C001**, **C002**, and **C003**. Fictional people and merchants now have realistic names and reproducible random 12-digit ECNs; goal references use the same customer ECNs. Coordinate fields have been removed from source fixtures.

At startup `LocationService` resolves addresses through the **US Census geocoder** and `LocationIngestion` writes latitude/longitude, matched address, provider, timestamp, status and address fingerprint to Neo4j. Successful results are cached in `data/cache/geocoding`; unmatched or unavailable addresses remain unlocated. The public civic addresses are illustrative locations, not claims that these fictional people or businesses occupy them. The UI retains a fictional-data notice without putting “Demo” in names.

The customer screen now accepts questions such as “Which auto loan provider has the lowest APR within 10 miles?”, “Show home purchase providers with offers”, and “How many providers match my car goal?”. It retrieves the customer graph once through MCP, asks Ollama for a validated selection/filter/sort plan, and executes it in Java over that response. No duplicate MCP call or LLM-generated Cypher is needed. Counts and comparisons cover the bounded returned subset, not every provider in the database. `/graph-search` now redirects to this same MCP-backed experience.

Windows CMD:

```bat
set UI_CONTENT_ENABLED=false
set NL_QUERY_LLM_ENABLED=true
docker compose up -d --build
docker compose --profile models run --rm pull-models
```

`UI_CONTENT_ENABLED` controls introductions; `NL_QUERY_LLM_ENABLED` independently controls questions. Questions require the configured Ollama chat model. Without it, the screen explicitly shows the unfiltered retrieved graph. Geocoding requires outbound HTTPS to `geocoding.geo.census.gov`; configure `GEOCODING_URL` for a compatible alternative endpoint. No API key is needed. Allow the domain in managed cloud environment settings; preserve TLS verification. Restart after enabling access to retry unresolved addresses. Do not erase database volumes to refresh data.

See [location and query details](docs/location-and-questions.md).

# LifeSync v0.9 — Multi-product customer experience and graph MCP

The landing page now loads a customer's goals across **PLCC, home purchase, home refinancing, and auto loans**, then shows nearby merchants or lending providers with current synthetic offers. Enter `C-DEMO-001`, `C-DEMO-002`, or `C-DEMO-003` and switch customers on the same screen. This is a demo lookup, not authentication.

The UI service calls the read-only **MCP Streamable HTTP endpoint at `/mcp`** to retrieve a customer-scoped graph projection. Optional Ollama generation uses only that projection to write on-screen introductions; Java controls account gates, provider ranking, and exact offer terms. The new workflow does not generate email. The previous PLCC automation remains at `/automation`.

See **[Multi-product setup, architecture, MCP contract, and validation](docs/multi-product-mcp.md)** for current instructions. Curated fixtures start without AI models; enable LLM introductions after pulling the chat model. Legacy semantic ingestion and its scheduler are opt-in.

```sh
gradle test bootJar
docker compose up -d neo4j ollama
UI_CONTENT_ENABLED=false java -jar build/libs/lifesync-java-poc-0.9.0.jar
# In another shell:
python3 scripts/smoke-products.py
```

## Previous PLCC workflow reference (v0.7–v0.8)

The following describes the retained legacy workflow; v0.9 defaults and startup instructions above take precedence.

# LifeSync PLCC — Java-only retrieval + semantic classification + email LLM (v0.7.0)

Local Java 21 / Spring Boot / Neo4j Community / Spring AI / Ollama / Thymeleaf / Docker Compose proof of concept. Individual campaign emails are delivered to the demo inbox. All names, account statuses, goals, stores, and offers are synthetic demo fixtures.

## v0.7.0: exactly where AI is used

- `GoalClassificationService` and `MerchantClassificationService` call `SemanticCategoryClassifier`, which generates `nomic-embed-text` **embeddings** and compares cosine similarity against 13 static business category descriptions. **No chat-LLM, keyword rules, or fallback classification.** Static category *definitions* and similarity thresholds still exist.
- `RetrievalDecisionService` is a **plain Java** `@Service`, no `ChatModel` dependency. It deterministically chooses GRAPH or HYBRID and whether to retry vector retrieval. `RecommendationOrchestrator` applies it after Neo4j category eligibility checks and before selecting top five stores.
- `SemanticRetrievalService` optionally uses embeddings and Neo4j vector similarity; it is **not a chat-LLM/agent**. Results are constrained by PLCC-eligible graph candidates.
- `EmailExplanationService` is the **only** application class that calls Ollama **chat** (`ChatModel`). It uses retrieved, validated goal/merchant facts to compose the email *introduction* and one explanation per selected merchant, without altering offers, merchant eligibility, ranking, or recipients. This grounded wording is generated during recommendation processing and stored for subsequent email preview. No on-demand chat call when the email page is opened.
- `EmailPreviewService`/Thymeleaf renders holder or invitation HTML. For active PLCC holders, `OffersClient` fetches live offer data via REST at preview time; offers are not stored in Neo4j. Non-holders only see a permission/PLCC invitation; unknown status prevents a preview.

**There is no retrieval agent, chat-LLM classification, or configurable `AGENTIC_LLM_ENABLED` setting in v0.7.** The embedding model is still needed for semantic classification even when `VECTOR_ENABLED=false`.

## Java retrieval policy

For verified PLCC holders, Neo4j first returns active PLCC-eligible category-matched merchants and stores. Java then uses persisted classification evidence:

| Condition | Strategy |
|---|---|
| No graph-eligible merchants | No recommendations (fail closed) |
| `VECTOR_ENABLED=false` | GRAPH |
| >1 goal category, or confidence < 0.85, or <3 distinct graph merchants | HYBRID |
| Otherwise | GRAPH |

HYBRID means graph-constrained vector similarity on eligible merchants, with initial similarity acceptance `>= 0.42` (POC heuristic). When fewer than `min(3, graphMerchantCount)` distinct eligible merchants meet this cutoff, Java optionally makes **one** alternate-query vector search, max **two** attempts total. If no graph-eligible merchant receives a sufficiently strong vector score, use the graph-only candidates. Java deduplicates by store, calculates straight-line Haversine distance, selects nearest five, and persists rankings and trace metadata. Scores and thresholds are **uncalibrated demo values**, not accuracy or suitability guarantees.

### Persistent Neo4j graph

```
(Customer)-[:HAS_GOAL]->(Goal)-[:CLASSIFIED_AS]->(Category)
(Merchant)-[:OFFERS_CATEGORY]->(Category)
(Merchant)-[:HAS_STORE]->(Store)
(PLCC)-[:SUPPORTS_CATEGORY]->(Category)
(Goal)-[:HAS_RECOMMENDATION]->(Recommendation)-[:RECOMMENDS]->(Store)
(Customer)-[:RECOMMENDED_STORE]->(Store)
```

Holder recommendation metadata now uses `decisionPlan`, `decisionEvaluation`, `searchAttempts` in `Recommendation` nodes. Earlier `agentPlan`, `agentEvaluation`, `agentAttempts` are removed when an existing recommendation is reprocessed. Readers also support legacy fields until existing goals are refreshed. No offers or rendered email HTML are saved in Neo4j.

## Run locally

Prerequisites: Docker Engine/Desktop with Compose; 8 GB RAM minimum recommended for this local demo.

```bash
docker compose up -d --build
docker compose logs -f app
```

- Dashboard: http://localhost:8080
- Neo4j browser: http://localhost:7474 (`neo4j` / `localDemoPassword123` by default)
- Vector status: http://localhost:8080/api/vectors/status
- The first Ollama model download and first semantic classification can take time. Classification vectors are cached in `data/cache/category-classification`. Neo4j and Ollama use named Compose volumes.

### Useful options

```yaml
# compose.yaml: app.environment
LLM_EXPLANATIONS_ENABLED: ${LLM_EXPLANATIONS_ENABLED:-true}   # chat LLM for email text only
VECTOR_ENABLED: ${VECTOR_ENABLED:-true}                       # additional merchant vector retrieval
SEMANTIC_CLASSIFICATION_MIN_SCORE: ${SEMANTIC_CLASSIFICATION_MIN_SCORE:-0.57}
SEMANTIC_CLASSIFICATION_RELATIVE_MARGIN: ${SEMANTIC_CLASSIFICATION_RELATIVE_MARGIN:-0.09}
SCHEDULER_BATCH_SIZE: ${SCHEDULER_BATCH_SIZE:-1}
SCHEDULER_DELAY_MS: ${SCHEDULER_DELAY_MS:-90000}
```

The category semantic classifier **always** requires `nomic-embed-text`; `LLM_EXPLANATIONS_ENABLED=false` only disables chat-generated email wording, replacing it with deterministic copy. `AGENTIC_LLM_ENABLED` no longer exists. Leave `LLM_EXPLANATIONS_ENABLED=true` to match the requirement that the chat LLM is used exclusively for email wording.

To rebuild the app without clearing database or model volumes:

```bash
docker compose up -d --build --no-deps app
```

If you are upgrading from v0.6 with goals in `READY` status, **previously generated recommendations remain unchanged until manually requeued** using the goal page's requeue button. Do not use `docker compose down -v` unless you intentionally want to erase named volumes.

## Project code map

- `classification/GoalClassificationService.java` — semantic goal category classification.
- `classification/MerchantClassificationService.java` — semantic merchant category classification.
- `classification/SemanticCategoryClassifier.java` — embedding similarity + cache + thresholds.
- `service/RetrievalDecisionService.java` — Java-only GRAPH/HYBRID policy and bounded coverage evaluation.
- `service/SemanticRetrievalService.java` — constrained Neo4j vector candidate scores.
- `service/RecommendationOrchestrator.java` — account/PLCC gates, Java policy, distance ranking, persistence and email text generation.
- `service/EmailExplanationService.java` — only chat LLM consumer, for grounded email prose.
- `service/EmailPreviewService.java` — Thymeleaf previews, no actual send.
- `graph/GraphRepository.java` — Cypher retrieval, vector persistence, recommendation persistence and metadata.
- `offers/OffersClient.java` — runtime REST offer retrieval.

## Tests and verification

Run `gradle test` with a local Gradle installation, then `docker compose up -d --build` and verify the UI and Neo4j/Ollama startup. `RetrievalDecisionServiceTest` covers Java-only strategies, vector-disabled handling, empty graph candidate guardrail, and bounded refinement. `RecommendationOrchestratorTest` verifies Java-selected merchant retrieval, distance ordering, and holder/non-holder gates. `EmailExplanationServiceTest` verifies prompt grounding and safe fallback. No live financial data is used.

See `docs/architecture.md` for the updated processing flow. `docs/v0.4-architecture.md` and `docs/VALIDATION-v0.4.md` document **historical versions**; they are not authoritative for v0.7.

## v0.8: Natural-language graph search page

Open **http://localhost:8080/graph-search**. Enter a demo `customerId` (e.g. `C001`) to load **that customer's goals and eligible stores** automatically, then optionally ask a free-form question:

- `Show all my savings goals`
- `What stores can help me buy a refridgerator?`
- `Where can I shop for my hom offce?`
- `Which stores support all my goals?`

`NaturalLanguageGraphSearchService` asks the local Ollama **chat model** to classify the intent as `GOALS` or `STORES` and to select zero or more IDs from the **customer-scoped allow-list**. The prompt contains only goal IDs, descriptions and category labels—not names, ECNs, PLCC status or customer locations. Spellings and synonyms can be interpreted by the LLM. Java validates **every returned ID**; the model **never generates or executes Cypher**. The only executed queries are parameterized, predefined `GraphRepository` methods. The UI uses a precomputed Recommendation when present, otherwise active PLCC-eligible goal-category-to-merchant graph candidates sorted by approximate straight-line distance in Java. Before processing, these are explicitly labeled *preliminary candidates*, not generated recommendations.

`NL_QUERY_LLM_ENABLED=true` (default) turns on question interpretation. If the local chat LLM is unavailable or returns invalid JSON, the service tries embedding-based semantic matching of the known goal descriptions. If both are unavailable, it safely displays the customer's goals rather than pretending it found stores. The remaining chat-LLM usage is still `EmailExplanationService` for generated email wording. The request does **not** trigger email generation or send anything.

**Privacy/authorization:** This is an intentionally local, synthetic-data-only demo. Typing a customer ID is **not** authentication. For production, authenticate the requesting user, derive authorized customer IDs from their session/identity, implement access logging and rate limiting, and authorize every query. The demo withholds merchant/store information from non-holders and unverified PLCC accounts; it must never be published to the Internet as-is. Exact storefront location and travel times are not claimed.
