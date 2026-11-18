# Manual PLCC email campaign previews

Open `/campaigns` and choose **Generate previews**. This starts one separate
background run for up to the requested number of ACTIVE PLCC customers (default
25, maximum 100, ordered by customer ID). It does not start on app boot or a
schedule. Delivery requires an explicit action from an individual preview.

The campaign worker is separate from conversational HTTP requests. It processes
one customer at a time, checks ACTIVE status before fetching facts and again
after generation, calls the current MCP customer graph with `productId=PLCC`,
and skips customers without PLCC goals or current eligible offers. The model
can only write a short generic introduction from PLCC goal topics. It cannot
choose recipients, change offer terms or decide account eligibility.

A single dedicated campaign worker and a separate bounded model executor prevent
unbounded jobs or model threads. `CAMPAIGN_AI_ENABLED=false` uses factual copy;
`CAMPAIGN_AI_TIMEOUT_SECONDS` controls a 5–60 second model deadline (default 30).
These settings are separate from chat configuration. The local Ollama server is
still a shared compute resource: running a campaign may make inference slower
for other callers even though chat web requests are not held by campaign work.

## Persistence and rendering

Neo4j stores `CampaignRun` and per-customer `CampaignPreview` metadata/status,
with a unique preview ID per run/customer. Run states are RUNNING, COMPLETED,
COMPLETED_WITH_FAILURES, FAILED and INTERRUPTED. Per-customer states distinguish READY,
FAILED, SKIPPED_INELIGIBLE, SKIPPED_NO_GOALS and SKIPPED_NO_OFFERS. Startup marks
unfinished work from an earlier instance as interrupted; it does not resume or
start a campaign automatically. Worker-level failures make a best-effort terminal FAILED transition and mark unfinished previews FAILED without overwriting completed runs. If the database is unavailable even for that transition, only non-sensitive failure metadata is logged and startup recovery handles the retained RUNNING state. Open the run page to see progress and refresh it.

Stored preview metadata includes subject, generated introduction, source, selected
goal IDs and timestamps. Rendered HTML and offer values are not stored in graph
nodes. Opening a preview checks the customer's ACTIVE account status again,
fetches current PLCC facts through MCP and revalidates offer dates/audiences. Only
goals selected during the run are shown. No model is called during GET rendering;
the UI labels generated prose versus current provider/offer facts. An ineligible
customer's preview is withheld, and missing/expired offers are never invented.
Every model and fixture string is escaped by Thymeleaf text rendering.

Manual POST uses a single-use session token. This is a local administrative demo,
not real administrator authentication. Before exposing it to shared users, add
an authenticated role boundary and appropriate communication permissions. Existing
representative-contact consent is not automatically email-marketing permission.
Generating previews does not create or change permission records.

## Sending to the demo inbox

Open a ready campaign preview, or select **View email with goals** on an active PLCC
customer profile. The profile preview uses a factual introduction and current PLCC
goals, merchants, and offers. Select **Send email** to deliver the message and return
to the originating profile or campaign run with a confirmation. The server checks
eligibility and refreshes offer facts before delivery.

With Docker Compose, open the Mailpit inbox at http://localhost:8025. Customer
fixtures have no email addresses, so the demo uses the displayed inbox address.
When running outside Compose, configure a test SMTP server with `DEMO_SMTP_HOST`
and `DEMO_SMTP_PORT` (defaults `localhost:1025`). The defaults for `DEMO_MAIL_FROM`
and `DEMO_MAIL_TO` are `bank@example.test` and `banker@example.test`.
