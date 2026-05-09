# Noise Reduction Offline Evaluation

This directory stores deterministic offline evaluation assets for KB noise reduction.

- `*.yaml`: one evaluation case per file
- `fixtures/*`: source documents used by the cases
- `build/reports/knowledgebase/noise-reduction/`: generated shadow reports

The harness compares:

- baseline ranking over all chunks
- shadow ranking with `secondaryIndexCandidate=true` chunks filtered out

Phase 3 can only be considered ready when payload-heavy hits go down and protected technical anchors do not regress.
