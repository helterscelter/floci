# Fork notes

This branch holds notes about the fork's branches, not code. It shares no history with
`main` and is never merged or sent upstream.

## Pull request drafts (`pr-drafts/`)

A description for each fix, written for a pull request against `floci-io/floci`. None has
been opened. Each file is named after its branch, with `/` written as `--`. The first line of
each file is the pull request's title.

The six current drafts belong to branches based on upstream `main` after `2.3.0`. They are
the six fixes `integration/2.3.0-patched` carries:

| Draft | Branch | Base | Fix |
|---|---|---|---|
| `fix--http-api-route-priority-main.md` | `fix/http-api-route-priority-main` | `2.3.0`+25 | HTTP API: the most specific matching route |
| `fix--http-api-delete-body-main.md` | `fix/http-api-delete-body-main` | `2.3.0`+25 | The request body passed through on `DELETE` |
| `fix--http-api-v2-cookie-header-omitted.md` | `fix/http-api-v2-cookie-header-omitted` | `2.3.0`+25 | No `cookie` entry in the 2.0 event's `headers` |
| `fix--cfn-sns-subscription-update.md` | `fix/cfn-sns-subscription-update` | `2.3.0`+24 | `AWS::SNS::Subscription` attribute changes applied on stack update |
| `fix--lambda-sqs-esm-concurrency.md` | `fix/lambda-sqs-esm-concurrency` | `2.3.0`+25 | SQS event-source batches run concurrently, bounded by `MaximumConcurrency` |
| `fix--lambda-docker-orphan-sweep.md` | `fix/lambda-docker-orphan-sweep` | `2.3.0`+26 | Lambda containers a previous run left on the Docker daemon are removed |

Three drafts belong to earlier versions of the same fixes, on older bases. The current
draft replaces each:

| Draft | Branch | Base | Replaced by |
|---|---|---|---|
| `fix--http-api-route-priority.md` | `fix/http-api-route-priority` | `2.1.0` | `fix--http-api-route-priority-main.md` |
| `fix--http-api-delete-body.md` | `fix/http-api-delete-body` | `2.1.0` | `fix--http-api-delete-body-main.md` |
| `fix--http-api-payload-2-cookies-main.md` | `fix/http-api-payload-2-cookies-main` | `2.2.0`+229 | `fix--http-api-v2-cookie-header-omitted.md` |

## Integration branches (`integration/`)

`integration/2.3.0-patched.md` records how `integration/2.3.0-patched` was built: tag `2.3.0`
plus the six fixes, each cherry-picked from its own branch, with each commit's source branch
and SHA, the one conflict and how it was resolved, and the test run. That branch is the
source of the `cramageddon/floci-patched:2.3.0-1e46066bf554` image; the decision to run it is
recorded in Cramageddon's ADR-0041.
