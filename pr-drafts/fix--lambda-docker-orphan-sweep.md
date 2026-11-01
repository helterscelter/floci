PR title: `fix(lambda): remove function containers a previous run left on the Docker daemon`

## Summary

A Lambda function container that is running when Floci is removed or killed without a graceful stop
(`docker rm -f`, a `docker compose up` recreate, SIGKILL, OOM) stays on the daemon. A new Floci never
adopts it and never removes it, so each such restart leaves one container per warm function behind.
The Kubernetes launcher already sweeps its orphaned pods at startup
(`KubernetesPodLauncher.sweepOrphansOnce`); the Docker launcher had no equivalent. This PR adds one,
scoped to the containers this Floci deployment owns.

## Type of change

- [x] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## AWS Compatibility

No AWS API behaviour changes. This is Floci's own container lifecycle: no request, response or error
shape is touched.

### The incorrect behaviour

A graceful stop drains the `WarmPool` and removes the containers. A stop that skips it does not, and
nothing else looks for them afterwards: `EmulatorLifecycle` sweeps only IAM sessions, and the
`WarmPool` is in memory. The leftover container can never be reused anyway, because its runtime
polls a Runtime API port that was bound by the process that died.

### Reproduction

1. Run Floci with the Docker executor and invoke a function so a warm container exists.
2. `docker rm -f <floci container>` (or recreate it with `docker compose up`).
3. `docker ps -a` still lists the `floci-aws-<function>-<id>` container, and it is still there after
   the new Floci has started and served invocations.

I have not re-run these steps against a real daemon for this PR; the tests below use a mocked Docker
client.

## Root cause

`ContainerLauncher` named and labelled its containers (`io.floci.service=lambda`, resource id,
account, region) but stamped nothing that says which deployment or which process run created them,
and nothing listed them again after the process that created them was gone.

## The fix

- `ContainerStorageHelper`: a new `LAMBDA_RUN_LABEL` (`io.floci.lambda.run`), reserved so
  `extra-labels` cannot set it.
- `ContainerLauncher`:
  - Every Lambda container now also carries `io.floci.owner` (the existing
    `ContainerStorageHelper.ownerIdentity`: the API port, behind the resource namespace) and the
    run label. They are applied after the user's `docker-flags`, so a `--label` cannot take them away.
  - `removeLeftoverContainers(runId)` lists containers with the Lambda service label and this
    deployment's owner label (current and legacy label spellings, as the ECS sweep does) and removes
    those that also have a name under this instance's `container-name-prefix` (and resource
    namespace) and a run label other than the current one.
  - `sweepLeftoversOnce()` runs it at the top of `launch`, until one sweep has removed everything it
    found. A launch proceeds after a failed sweep; the leftovers belong to another run, so they
    cannot collide with the new container.
- `EmulatorConfig` / `application.yml`: `floci.services.lambda.reconcile-containers-on-startup`
  (`FLOCI_SERVICES_LAMBDA_RECONCILE_CONTAINERS_ON_STARTUP`), default `true`, named after the existing
  ECS and EC2 settings. The Kubernetes sweep has no switch and is always on, so the default matches.
  The test `application.yml` turns it off so tests never sweep a CI daemon's containers.
- Docs: `docs/services/lambda.md` (a note and the setting row), `docs/configuration/docker.md` and
  `docs/configuration/environment-variables.md` (the setting row).

Ownership, so a sweep never touches another Floci sharing the daemon: a container is removed only if
it has the Lambda service label, the owner label equal to this deployment's identity, and a name under
this instance's prefix. Another Floci with a different port, resource namespace or
`container-name-prefix` is therefore left alone, as is anything without the owner label (including
containers created before this change, which carry none), and the current run's own containers.
Two instances that share a daemon with the same port, no namespace and the same prefix are
indistinguishable and would remove each other's containers; the setting turns the sweep off for them.

## Tests added

`ContainerLauncherLeftoverSweepTest` (new, 10 tests, Docker client mocked; the mocked listing returns
every container regardless of the label filter, so the sweep's own checks are what is exercised):

- own leftovers are removed, running and exited alike;
- the current run's warm container is not removed;
- another instance's containers (different port, different namespace identity, different prefix) are
  not removed;
- containers with no owner label, with null labels, or of another service are not removed;
- a configured prefix and resource namespace are honoured;
- a container that will not go is reported as failure and the rest are still removed; a daemon that
  cannot be listed is reported as failure;
- the sweep runs once after a success, is retried after a failure, and does nothing with the setting
  off.

`ContainerLauncherTest`: the container label assertion now includes the owner and run labels; a new
case checks that `--label io.floci.owner=...` / `--label io.floci.lambda.run=...` in `docker-flags`
cannot replace them; a new case checks that `launch` lists leftovers before creating the first
container and does not list again on the next launch.

```sh
./mvnw test -Dtest='ContainerLauncher*Test,LambdaDockerFlagsTest,ContainerStorageHelper*Test,LambdaRuntimeLauncherProducerTest,WarmPoolTest,LambdaServiceTest'
```

Revert-check: with the sweep methods stubbed out (config and labels in place), the new class runs 10
tests with 7 failures. With the fix, the run above is 0 failures, 0 errors, 0 skipped. The same tests
pass when the change is cherry-picked onto the `2.3.0` tag (the docs hunk of `lambda.md` conflicts
there; the code applies cleanly).

## Compatibility

- Default behaviour changes: with the Docker executor, Floci now removes its own earlier Lambda
  containers once at the first launch. They were unusable already.
- Lambda containers created by an earlier Floci carry no owner label and are not removed by the first
  Floci that has this change; every container created from then on is covered.
- `floci.services.lambda.reconcile-containers-on-startup: false` restores the previous behaviour.
- No effect on the Kubernetes executor.

## Checklist

- [ ] `./mvnw test` passes locally: the affected unit test classes pass (see above); the full suite
  and the Docker-backed integration tests are left to CI.
- [x] New or updated unit tests added
- [x] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
