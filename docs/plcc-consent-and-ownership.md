# PLCC-only customer experience, consent and goal ownership

The UI requests `get_customer_subgraph` with `productId=PLCC` and filters the
response again before saving its question snapshot. Introductions and question
plans therefore receive only PLCC facts. Product dropdowns and lending examples
are not shown. The graph and MCP tools retain all four product types; diagnostic
Neo4j catalog queries are still available for administrators.

## Representative contact consent

Customers with non-ACTIVE PLCC status see a voluntary information/contact prompt
instead of the former account-required wording. No merchants are disclosed and
UNKNOWN status is not treated as proof that someone lacks an account.

An unchecked checkbox states the permission explicitly. A POST to
`/experience/plcc-contact` verifies the checkbox, a single-use session token,
the matching loaded customer and snapshot age. The database rechecks that the
account is not ACTIVE before recording the request. Missing permission, invalid
tokens, stale snapshots and a different customer do not save consent.

The saved graph is:

```
(Customer)-[:REQUESTED_CONTACT]->(PlccContactRequest)
```

The request has a unique `requestId`, productId=PLCC, status=REQUESTED,
consentGranted=true, consent text, version, consentedAt, createdAt and UI source.
Repeated explicit requests reuse the same customer/product request node.
Submission neither changes account status nor creates an application. No real
outreach integration is invoked; representatives or a separately authorized
workflow would need to process the queue. Customer-ID lookup remains a demo,
not production verified authentication or marketing-consent infrastructure.
The older signed email-preview consent route remains separate for compatibility.

## Ownership correction

A goal may have exactly one incoming customer `HAS_GOAL` relationship. Shared
merchant/category/product nodes across different goals are expected; shared
goal ownership is not.

Previously, MERGE created a new customer-goal edge without removing the old
identity's edge. Fixtures now validate unique goal definitions and remove
incorrect edges using the source customerId. Legacy intake removes incorrect
edges using the goal's source ECN. Both persist `ownerCustomerId`.

`GoalOwnershipRepair` runs after ingestion and before location updates. It first restores known legacy goal identities from the checked-in source files,
even with legacy semantic ingestion disabled. It then uses
the unique customer ECN matching each goal's source ECN to remove stale owners,
ensure the correct edge and clean stale derived RECOMMENDED_STORE edges. Location
updates no longer overwrite goal ECNs from arbitrary incoming relationships.
Remaining multi-owner goals without an authoritative source fail validation
rather than selecting an arbitrary owner. No customer, goal, product or database
volume is deleted.

Read-only verification in Neo4j Browser (expect no rows):

```cypher
MATCH (c:Customer)-[:HAS_GOAL]->(g:Goal)
WITH g, collect(DISTINCT c.customerId) AS owners
WHERE size(owners) > 1
RETURN g.goalId, owners;
```

Inspect canonical ownership:

```cypher
MATCH (c:Customer)-[:HAS_GOAL]->(g:Goal)
RETURN g.goalId, c.customerId, g.ownerCustomerId, g.ecn = c.ecn AS sourceMatches
LIMIT 50;
```

Restart after rebuilding to apply the idempotent repair. Do not clear volumes.
Live isolated checks deliberately created shared ownership for a fixture goal
and an older unseeded goal; startup removed both conflicts and restored their
correct, distinct customer owners. Browser tests verified PLCC-only rendering,
unchecked consent, explicit persistence and active-holder behavior. External
geocoding/model calls in these checks used controlled test services.
