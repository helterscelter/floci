PR title: `fix(lambda): run SQS event-source batches concurrently, bounded by MaximumConcurrency`

## Summary

An SQS event-source mapping in Floci processes one batch at a time and starts the next only on a
later poll tick. `ScalingConfig.MaximumConcurrency` is accepted, stored and returned, and the poller
never reads it. This PR lets a mapping keep polling and invoking while batches are in flight, up to
`MaximumConcurrency` when the mapping sets it, and up to a new
`floci.services.lambda.sqs-default-max-concurrency` (default `10`) when it does not. FIFO ordering
per message group is unchanged.

## Type of change

- [x] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## AWS Compatibility

### The AWS reference

"Configuring scaling behavior for SQS event source mappings", in "Using Lambda with Amazon SQS":
https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-scaling.html

For standard queues, Lambda polls with five concurrent batches at first and scales up as messages
arrive, to at most 1,250 concurrent function invocations. `ScalingConfig.MaximumConcurrency`
(2 to 1,000) caps the number of concurrent invocations the mapping may use. For FIFO queues, Lambda
scales with the number of message groups, and messages of one group are delivered in order, one batch
at a time.

### The incorrect behaviour

`SqsEventSourcePoller` guards each mapping with a single `activePolls` flag. While a batch is being
received, invoked and deleted, every tick for that mapping is skipped. So a mapping runs at most one
batch at a time, and a new one at most once per `poll-interval-ms` (default 1000 ms). A burst across
many message groups queues behind one batch, and `MaximumConcurrency` has no effect.

### Reproduction

Expected from the code and the AWS documentation, not measured here: with a Lambda that sleeps two
seconds, an SQS FIFO event source with `BatchSize=1`, and ten messages sent to ten different
`MessageGroupId`s, `main` starts the invocations one after another, at least a poll interval apart,
while this branch starts them together, up to the concurrency cap. I have not run this against a real
AWS account or a Floci container.

The unit tests below model the same thing without Docker.

### Root cause

`pollAndInvoke` used `activePolls.putIfAbsent(uuid, TRUE)` to allow one poll per mapping, and held it
through the invocation and the deletes.

### The fix

`src/main/java/io/github/hectorvent/floci/services/lambda/SqsEventSourcePoller.java`:

- `activePolls` becomes `inFlightPolls`, a per-mapping count of polls from receive to delete. A poll
  starts only if the count is below the mapping's effective concurrency: `MaximumConcurrency` if set,
  else `sqs-default-max-concurrency`. A tick that finds every slot taken is skipped, as before.
- A tick whose poll receives a full batch starts the next poll immediately (if a slot is free), so a
  backlog fills the free slots within one tick instead of one slot per interval.
- The receive step, including the `MaximumBatchingWindowInSeconds` buffer, runs under a per-mapping lock.
  The buffer used to be protected by the single `activePolls` flag and still has one writer at a time.
  Filtering, invocation and deletes run outside the lock.
- Duplicate delivery is still prevented where it was before: a received message is hidden by its
  visibility timeout. On a FIFO queue `SqsService.receiveMessage` does not return a message from a
  group that already has a message in flight, so one group is processed in order, one batch at a time,
  while different groups run in parallel.

`EmulatorConfig`, `src/main/resources/application.yml`: the new key `sqs-default-max-concurrency`,
environment variable `FLOCI_SERVICES_LAMBDA_SQS_DEFAULT_MAX_CONCURRENCY`. The test `application.yml`
needs no entry: the annotation default applies.

Docs: `docs/services/lambda.md` (replaces the "Enforcement status" note under ScalingConfig, adds the
variable to the table), `docs/configuration/environment-variables.md`,
`docs/configuration/advanced/application-yml.md`.

### Tests added

All in `SqsEventSourcePollerTest`, no Docker:

- `secondBatchIsInvokedWhileTheFirstIsStillInFlight`
- `scalingConfigMaximumConcurrencyBoundsBatchesInFlight`: five polls with a cap of 2 give exactly 2
  invocations in flight
- `mappingWithoutScalingConfigIsBoundedByTheConfiguredDefault`
- `aFreedSlotIsUsedByTheNextPoll`
- `aTickThatReceivesAFullBatchFillsTheFreeSlots`

`awaitPollCompleted`, the existing helper that waited for a poll to finish by relying on the single
flag, now caps the mapping at one concurrent poll first, so it still waits the same way.

```sh
./mvnw test -Dtest=SqsEventSourcePollerTest
```

Revert-check: with `SqsEventSourcePoller.java` at `main`, the class runs 39 tests with 4 failures (the
first four new tests above; `aFreedSlotIsUsedByTheNextPoll` passes on both). With the change, 39 of 39
pass, three runs in a row. `SqsEventSourcePollerTest`, `LambdaServiceTest`,
`LambdaEventSourceMappingCfnProvisionerTest`, `EsmStoreFilterCriteriaTest` and
`EventSourceMappingSerializationTest` together: 271 tests, 0 failures. I did not run the Docker-backed
or full suite.

### Compatibility

- A mapping now invokes its function concurrently. A function that assumed one invocation at a time per
  mapping will see overlap locally, as it would on AWS. Set `ScalingConfig.MaximumConcurrency` to 2 (the
  AWS minimum) or `FLOCI_SERVICES_LAMBDA_SQS_DEFAULT_MAX_CONCURRENCY=1` to get the old behaviour back.
- The default of 10 is below AWS's 1,250 to bound local load. It is a config key, not a protocol value.
- Kinesis and DynamoDB Streams pollers are unchanged.
- Not changed: AWS starts at five pollers and ramps; Floci does not model the ramp.

## Checklist

- [ ] `./mvnw test` passes locally: the affected test classes were run as described above, without
  Docker; the full suite is left to CI.
- [x] New or updated unit test added
- [x] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
