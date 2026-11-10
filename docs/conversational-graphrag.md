# Conversational GraphRAG assistant

The customer ID entry now retrieves only `CustomerProfile`: explicit first name,
last name, city/state and PLCC status. It performs no goal/merchant retrieval and
no model call. The UI shows profile details and a conversational chat window.
Names are structured in the fixtures and graph; historical names are not guessed
by splitting an arbitrary full name.

## On-demand goals and details

* “Show my goals” uses MCP `list_customer_goals` and renders basic goal cards for
  every product. No merchant or offer data is included in this response.
* “Show shops near me” or “Where can I get discounts?” uses MCP
  `find_plcc_merchants`. Only the current customer's PLCC goals may be selected;
  only ACTIVE holders can receive merchant/offer details.
* Home purchase, refinancing and auto-loan goals stay visible as basic goals.
  Their providers/offers are suppressed by the MCP projection itself, not just
  hidden by the template.
* Follow-ups can refine the previous PLCC query: “Only within five miles”, “At
  least fifteen percent discount”, or “Explain that offer”. Unchanged filters,
  order, offer-only selection and limit are preserved. Exact offer terms remain
  Java-rendered facts. Generated explanations cannot change them.
* Non-holders can explicitly request representative contact through the existing
  single consent panel. Chat never creates consent or applications by itself.

MCP also retains `get_customer_subgraph` for the independent campaign workflow.
It now always returns non-PLCC goals without provider details. The underlying
lending/auto product graph remains unchanged and available for administrative
Neo4j inspection.

## Bounded Java tool calling

`ConversationPlanner` asks Ollama for one JSON action constrained to GOALS,
MERCHANTS, OFFERS, EXPLAIN or HELP. The schema uses the known goal-ID allow-list;
Java validates every ID, argument, field, limit and numeric bound before dispatch.
The model cannot choose a customer ID, run Cypher, send email, or mutate consent.

The Java dispatcher may fetch a basic goal snapshot, revalidate cached PLCC
eligibility, and invoke the merchant tool. Its control flow is bounded to at most
three logical MCP tool calls per turn (currently at most two along normal paths),
one model plan, at most 200 goals, and five nearby matching providers per goal.
It does not run an open-ended agent loop or retry until the model succeeds. Spring AI automatic retry is set to one attempt; the customer can explicitly retry a failed turn.
Ollama work uses the configured `AI_TIMEOUT_SECONDS` deadline (60 default, configurable 5–60). The larger default allows CPU cold-start and the conversational context budget.
HTTP tool calls have their own connection/read timeouts. Campaign jobs use a
separate executor and do not occupy conversational request workers; Ollama CPU
capacity is still shared.

Only goal/product metadata, recent customer-session dialogue and filtered prior
PLCC facts enter the model prompt. Profile names/ECNs, exact addresses/coordinates
and personalized-offer audience IDs are not mechanically added. Chat text is user
input and may itself contain personal information, so do not treat model prompts
as anonymized data. The model's structured action is interpreted by Java, and UI
strings use escaped text rendering.

The conversational workflow uses the chat model and curated graph relationships; legacy vector backfill is off by default (`VECTOR_ENABLED=false`) so it does not compete for Ollama resources. Enable it explicitly only for the optional legacy vector workflow.

## Conversation state and security boundary

History is session-only and bounded to six exchanges. Changing customer creates a
new conversation and clears old consent/tool state. Sending messages requires the
matching customer, conversation ID and session token. Concurrent turns are
rejected; New chat clears history/cache atomically when idle. Goal snapshots last
five minutes and expired snapshots reset prior filters/context. Cached merchant
contexts are revalidated against current account status before explanation;
revoked details are redacted from stored history and the returned UI history.

This remains a fictional local demo: a supplied customer ID is not authentication.
Shared/production use needs authenticated customer identity and admin authorization
for campaign routes. Model/transport failures produce an explicit chat fallback
rather than automatically displaying goals or inventing an answer.

## Run and validate

From Windows CMD:

```bat
docker compose up -d --build
docker compose exec ollama ollama pull qwen2.5:1.5b
```

Open the app, enter C001, then ask “Show my goals”, “Where can I get shopping
discounts?”, and “Only within five miles”. C002 demonstrates non-holder consent.
Use **Campaign previews** to manually generate email previews for ACTIVE holders.
There is no actual email sending. `CAMPAIGN_AI_ENABLED=false` uses factual campaign
introductions without a model call. See [manual campaigns](manual-campaigns.md).

Run `gradle test bootJar` and `python scripts/smoke-products.py` after address
geocoding succeeds. Browser tests in the cloud instance use isolated Neo4j and
controlled Census/Ollama responses; those checks validate wiring, disclosure,
follow-up handling and campaign workflow, not real-model intent accuracy or live
geocoding availability. No test coordinates should enter the real app cache.
