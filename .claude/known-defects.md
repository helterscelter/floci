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
- Status: not needed for the sharded run. The heap is fixed at 6g in `pom.xml`, and 6g is enough for each of upstream's four CI shards (floci-io/floci#2506): checked 2026-11-04, no shard ran out of heap. Only a single-JVM run of the whole suite needs more; the Fix above covers that case if it is ever wanted.
- Where the suite runs: the owner decided on 2026-11-04 that floci test work runs on boat.dev VMs, without sbx, as the four shards.

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
- Checked 2026-11-04 against plain Docker: the sharded suite under sbx at 6g failed in 12 classes. The same classes were run on a boat.dev VM with plain Docker 29.7.0 (8 vCPU, 16 GB), using fork main `ae75a653d`:
  - These 10 pass on plain Docker, so their failures are specific to the nested daemon: `EcrTlsDataPlaneDockerIntegrationTest`, `EcrIntegrationTest`, `EcrRegistryPortConflictDockerIntegrationTest`, `EksStorageClassDockerIntegrationTest`, `EksIrsaDockerIntegrationTest`, `Ec2VolumeBlockDeviceDockerIntegrationTest`, `CloudFormationNamedResourceIdenticalUpdateIntegrationTest`, `LambdaExecutionRoleDockerIntegrationTest`, `RedshiftSpectrumIntegrationTest` and `RdsAwsIntegrationTest`. `RdsAwsIntegrationTest` is flaky; it also passed once under sbx.
  - `EksRegistryHostsDockerIntegrationTest` also fails on plain Docker, on fork main and on upstream main: "readlink /etc/containerd/certs.d must succeed ==> expected: <0> but was: <1>".
  - `EksRoute53DnsDockerIntegrationTest` is undetermined. It self-skips on the VM with "Embedded DNS server must have a bound IP for Route 53 DNS integration test".
- Upstream main `dbbf229` on the same VM is not all green. Shard 4 passes. Shards 1 to 3 fail 5 tests:
  - `PartitionedFileStorageJournalTest` and an unreadable-file case in `CodeArtifactAssetPersistenceTest`. These are probably caused by the VM, where `boat exec` runs with `CAP_DAC_READ_SEARCH`, so a file the test made unreadable can still be read. Not confirmed.
  - `EmrHiveStepsDockerIntegrationTest` and `RdsTemplateDatabaseRestoreIntegrationTest`: timeouts.
  - `EksRegistryHostsDockerIntegrationTest`, as above.
- Running the suite on a boat.dev VM needs two firewall rules first, `iptables -I INPUT -i docker0 -j ACCEPT` and `iptables -I INPUT -i br+ -j ACCEPT`. Without them the VM's ufw drops traffic from containers to the host, and 19 more tests fail. On that VM each shard takes 20 to 35 minutes.
