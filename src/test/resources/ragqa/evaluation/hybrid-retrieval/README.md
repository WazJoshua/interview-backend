# Hybrid Retrieval Offline Fixtures

These fixtures drive deterministic offline evaluation for hybrid retrieval Phase 1.

- `manifest.yaml` lists the fixture files used by the test harness.
- Each fixture encodes one query case with:
  - `rawQuery`
  - `bucketTags`
  - `finalTopK`
  - `relevantChunkKeys`
  - `denseExpectedTopKeys`
  - `sparseExpectedTopKeys`
  - `fusedExpectedTopKeys`
  - `expectSparseOnlyRescue`
  - `expectCrossBranchMismatch`
  - `denseBranchRanks`
  - `sparseBranchRanks`
  - optional `denseBranchScores`
  - optional `sparseBranchScores`

The harness does not call external providers. It replays deterministic dense and sparse candidate sets and
uses the in-repo `RetrievalResultFusionService` to compute hybrid selection.
