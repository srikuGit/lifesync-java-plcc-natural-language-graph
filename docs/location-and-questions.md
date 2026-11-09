> Current UI: no product dropdown. View goals retrieves all products and renders factual content without an LLM call. Fifty fixture customers each have four product goals. Customer and provider addresses are distinct. Questions use POST /experience/question and a customer-checked, five-minute session snapshot, so no new MCP retrieval occurs. Optional introductions use POST /experience/explain. Both update a server-rendered fragment asynchronously; model work is bounded to two concurrent tasks with a 15-second timeout. The existing GET question links remain compatible but new interactive submissions do not reload the page.

# Address-based location and questions over MCP

## Location flow

`ProductDemoIngestion` (and optional legacy ingestion) loads address-only fixtures.
`LocationIngestion`, ordered after both, looks up each existing customer and store
address using `LocationService`. It persists coordinates on Customer/Store nodes,
plus `address`, `matchedAddress`, `addressHash`, `geocodeProvider`, `geocodedAt` and
`geocodeStatus`. Merchant ECNs are also persisted with a uniqueness constraint.
Changed addresses have different cache keys. Repeated addresses share one lookup;
successes survive restarts in the existing mounted `data/cache` directory. Failures
are retried next startup. Missing, invalid or unavailable results clear coordinates
and yield `UNRESOLVED`, so stale/fabricated points cannot drive recommendations.
Distances remain Haversine straight-line miles, not driving directions or ETA.

Provider defaults to the US Census one-line address API, benchmark
`Public_AR_Current`. `GEOCODING_URL` can select a compatible endpoint; do not put
credentials in that URL. The default uses no key and does not send names or ECNs.
The configurable cache directory is `GEOCODING_CACHE_DIR`. Corporate proxies may
require standard JVM proxy/CA configuration. Do not disable TLS verification.
Geocoder matching does not prove residency or merchant occupancy.

Fixtures deliberately use public civic addresses as illustrative locations for
fictional people and businesses. They are not scraped customer information.
Names contain no Demo/Synthetic placeholders. ECNs are unique random 12-digit
strings, generated with a fixed seed for reproducible fixtures (not authentication
or production identifiers). `scripts/refresh-fixture-identities.py` regenerates
identities, removes embedded coordinates, and keeps customer-goal ECNs consistent.
The first three product customers share IDs/ECNs/names with the legacy fixtures;
product goals add home purchase, refinancing and auto financing to their PLCC goals
when legacy ingestion is enabled. Product fixture account statuses are consistent
across both sources. Existing obsolete C-DEMO-* database records are not deleted
or merged automatically; this preserves local work. Use C001–C003 for current data.

## Question flow

1. `CustomerExperienceService` calls `GraphMcpClient` once to load the scoped graph.
2. `GraphQuestionService` gives Ollama only that customer's goal IDs, descriptions,
   product/category metadata and provider counts, together with the question.
   Names, ECNs, exact addresses and personalized-offer audience IDs are excluded.
3. Ollama returns a JSON query plan, not Cypher or trusted financial prose.
4. Java validates every goal ID, field, numeric bound, sort and result limit;
   applies the plan to the existing MCP response; calculates counts; and renders
   exact retrieved providers/offers. No second MCP call is made for this question.

Supported plan fields: `goalIds`, `sort` (DISTANCE, APR, DISCOUNT, FEES),
`maxDistanceMiles`, `maxApr`, `minDiscountPercent`, `offersOnly` and `limit` (1–5).
Loan APR/fee/discount comparisons must stay within a single product. APR is not a
substitute for total cost across loan terms. Invalid plans, unavailable models and
oversized prompt inputs explicitly fall back to showing the original scoped graph.
The UI never claims it answered a question when interpretation failed.

Examples: “Show home refinance goals”, “Which auto provider has the lowest APR
within 10 miles?”, “Show shopping providers with discounts of at least 15%”,
“How many providers have offers for my car goal?” Counts refer to displayed goal/
provider/offer matches. MCP returns at most five nearby providers and three offers
per provider; this is not an exhaustive database query. Questions requiring more
facts need a broader permitted retrieval capability, not invented answers.

`/graph-search` redirects to `/experience` with the customer and question preserved.
Customer IDs are lookup keys, not authorization. These changes do not add a real
login or grant the model access to arbitrary customers or Cypher execution.

## Verification and limits

Run `gradle test bootJar`, then `python scripts/smoke-products.py` against a running
app after geocoding succeeds (or set `LIFESYNC_SMOKE_URL` for a separate test app).
Tests cover provider JSON conversion, persistent cache reuse, invalid/unmatched
responses, missing-provider behavior, query bounds, foreign IDs, financial sorting
and filtering, counts, prompt identity exclusion, and fixture joins.

In this cloud session the live Census endpoint was denied by outbound policy;
its hostname was added to the environment configuration draft. Live geocoding is
not claimed verified. End-to-end persistence and questions were exercised against
an isolated Neo4j database with controlled Census/Ollama HTTP responses. Those test
coordinates are held in a separate test cache, never the application's real cache.
