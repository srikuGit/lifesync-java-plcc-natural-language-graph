> Historical reference for v0.4; current v0.7 behavior is documented in `../README.md` and `architecture.md`.

# Validation summary — v0.4.0

These checks ran in the code-generation environment:

1. Java 21 compiler successfully compiled the changed real source files: `GoalClassificationService`, `AgenticDecisionLoop`, `RecommendationExplanationAgent`, `GraphRepository`, `SemanticRetrievalService`, `RecommendationOrchestrator`, `DistanceService`, `EmbeddingText` and `Models`, using locally supplied stubs for external library interfaces. This is **not** a real-dependency Gradle compilation.
2. Executable Java smoke tests passed: multi-category classification; unsupported-goal handling; ambiguous-goal low confidence; LLM strategy override by explicit policy; deterministic fallback; semantic coverage/refinement bounds.
3. Executable graph-repository smoke test verified that two goal category relationships and a versioned fingerprint were written, and that Neo4j vector search Cypher retains the PLCC graph constraint. This test uses a fake Neo4j driver; it does **not** exercise a live Neo4j server.
4. Executable orchestrator smoke test verified: HYBRID policy override, distinct graph candidates, store-level deduplication, preferred goal category selection, deterministic distance ranking, and explainability metadata.
5. Parsed all five JSON fixtures and both YAML files; checked customers=50, goals=150, merchants=52.
6. ZIP integrity check and presence of required files passed.

Not run here: full `gradle clean test bootJar`; actual Spring Boot startup, Thymeleaf rendering, real Neo4j Cypher execution, or Ollama/Offers REST integration. Run `docker compose up --build -d` locally (Dockerfile executes the full tests during build) and review failures, if any, before presenting the demo.
