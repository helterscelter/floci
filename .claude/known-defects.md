# Known defects (fork-local)

This is the fork owner's local defect list, kept off upstream PRs. Each entry names a defect of this fork, when it was found, and how to fix it.

## `make docs-check` fails on the expired `bedrock-agentcore-control` deferred entry

- Found: 2026-11-02
- Symptom: `make docs-check` fails with "the Service Matrix in docs/services/index.md is out of sync".
- Cause: the deferred entry `bedrock-agentcore-control` in `tools/docs/service_matrix.yaml` expired on 2026-09-15. It was documented by floci-io/floci#2436 ("open at time of writing").
- Fix: renew the expiry in `tools/docs/service_matrix.yaml`, or add the row to `docs/services/index.md`.
- Status: `make docs-test` passes (54 tests).
- Scope, checked 2026-11-02: the expired entries are on `fix/http-api-payload-2-cookies` (6a23a4e68). On `main` (ae75a653d) `deferred` is empty, `bedrock-agentcore-control` resolves through an alias to `bedrock-agentcore`, and `make docs-check` exits 0.

## The surefire heap is hard-coded and too small for test discovery on a 32-core machine

- Found: 2026-11-02
- Symptom: `./mvnw test` runs out of heap, `java.lang.OutOfMemoryError: Java heap space`, in Quarkus `ArcProcessor#generateResources` during test discovery, so 0 tests run. With a 10g heap the same suite runs 21480 tests.
- Cause: `pom.xml` sets the surefire heap in explicit plugin configuration, `<configuration><argLine>-Xmx6g</argLine>`. Explicit plugin configuration wins over the `-DargLine` user property, so `-DargLine=-Xmx10g` has no effect.
- Fix: make the heap a Maven property with a default, so a run can raise it on the command line. Define `<surefire.heap>-Xmx6g</surefire.heap>` under `<properties>` and set `<argLine>${surefire.heap}</argLine>` in the surefire configuration, then run with `-Dsurefire.heap=-Xmx10g`. (Maven does not accept shell-style `${name:-default}` defaults, so the default lives in the property definition.)
- Status: open. The heap is still fixed at 6g in `pom.xml` as recorded on 2026-11-02.

## The full suite in an isolated container with a nested Docker daemon fails four integration tests that depend on how Floci addresses containers

- Found: 2026-11-02
- Symptom: the full suite, run inside an isolated container whose Docker daemon is nested, finishes with 21480 tests run, 4 failures, 3 errors and 11 skipped. The failing tests are:
  - `DocDbIntegrationTest` (4): "MongoDB backend for cluster my-docdb-cluster did not become ready on 172.17.0.6:27017 within 60000ms";
  - `EcrIntegrationTest.deleteRepositoryForce`: "Backing registry unreachable";
  - `LambdaExecutionRoleDockerIntegrationTest.runtimeUsesExecutionRoleCredentialsForSdkCalls`: the Lambda container gets `connect ECONNREFUSED 127.0.0.1:4587`, because it is told to reach Floci at loopback, which from a sibling container is not Floci;
  - `JsonataEvaluatorTest.expensiveButLegitimateExpressionsStayWithinTheShippedBounds`: "Expression evaluation timeout", a timing bound under suite load.
- Cause: the first three depend on how Floci addresses the containers it starts (its Docker networking and hostname configuration, AGENTS.md "Configuration Rules"), not on Floci logic. The Jsonata test is a wall-clock bound that the suite load pushes past.
- Fix: none recorded. Environment-dependent: check the container's networking (whether Floci is reached at the address it advertises to sibling containers, not at loopback) and the DocDb endpoint's reachability before treating the first three as defects. Re-run the Jsonata test on an idle machine before treating its timeout as a defect.
- Status: open as an environment note. Not a Floci defect as recorded on 2026-11-02.
