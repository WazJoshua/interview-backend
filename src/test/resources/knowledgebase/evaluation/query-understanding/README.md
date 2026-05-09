# Query Understanding Offline Fixtures

These fixtures drive deterministic offline evaluation for query understanding.

- `manifest.yaml` lists the fixture files used by the test harness.
- Each fixture encodes one query case with:
  - `rawQuery`
  - `expectedNormalizedQuery`
  - `rewriteCandidate`
  - `bucketTags`
  - `finalTopK`
  - `relevantChunkKeys`
  - `expectedPhase2TopKeys`
  - `expectedPhase3TopKeys`
  - `originalBranchRanks`
  - `rewriteBranchRanks`
  - optional `originalBranchScores`
  - optional `rewriteBranchScores`

The harness does not call external LLM or embedding providers.
