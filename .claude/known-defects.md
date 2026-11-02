# Known defects (fork-local)

This is the fork owner's local defect list, kept off upstream PRs. Each entry names a defect of this fork, when it was found, and how to fix it.

## `make docs-check` fails on the expired `bedrock-agentcore-control` deferred entry

- Found: 2026-11-02
- Symptom: `make docs-check` fails with "the Service Matrix in docs/services/index.md is out of sync".
- Cause: the deferred entry `bedrock-agentcore-control` in `tools/docs/service_matrix.yaml` expired on 2026-09-15. It was documented by floci-io/floci#2436 ("open at time of writing").
- Fix: renew the expiry in `tools/docs/service_matrix.yaml`, or add the row to `docs/services/index.md`.
- Status: `make docs-test` passes (54 tests).
- Scope, checked 2026-11-02: the expired entries are on `fix/http-api-payload-2-cookies` (6a23a4e68). On `main` (ae75a653d) `deferred` is empty, `bedrock-agentcore-control` resolves through an alias to `bedrock-agentcore`, and `make docs-check` exits 0.
