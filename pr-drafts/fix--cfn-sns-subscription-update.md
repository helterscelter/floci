PR title: `fix(cloudformation): apply changed AWS::SNS::Subscription attributes on stack update`

## Summary

Updating an `AWS::SNS::Subscription`'s `FilterPolicy`, `FilterPolicyScope`, `RawMessageDelivery` or
`RedrivePolicy` through a CloudFormation stack update succeeds, keeps the subscription (same ARN) and
leaves the old values in place. This PR writes the template's values onto the kept subscription, and
resets a property the update dropped.

## Type of change

- [x] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## AWS Compatibility

### The AWS reference

`AWS::SNS::Subscription`, https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-sns-subscription.html
documents `FilterPolicy`, `FilterPolicyScope`, `RawMessageDelivery`, `RedrivePolicy` and
`DeliveryPolicy` as "Update requires: No interruption". `Endpoint`, `Protocol` and `TopicArn` are the
ones that replace the resource (already in `create-only-properties.json`).

### The incorrect behaviour

A stack with one subscription (`Protocol: sqs`, `FilterPolicyScope: MessageBody`,
`FilterPolicy: {detail-type: [a]}`) is deployed, then the same template with `[a, b]` is deployed
over it. Both deploys report success; `sns get-subscription-attributes` still answers
`{"detail-type":["a"]}`. `sns set-subscription-attributes` on the same subscription changes it.

### Root cause

`SnsCfnProvisioner.provisionSubscription` passes the attributes to `SnsService.subscribe`, which
returns the existing subscription for the same protocol and endpoint and ignores the attributes it
was given. On an update the provisioner never wrote them anywhere else.

### The fix

- `SnsCfnProvisioner`: when `ctx.isUpdate()`, each attribute the template declares is written with
  `setSubscriptionAttribute`. A property the previous template declared (`ctx.previouslyDeclared`)
  and this one dropped is reset: `RawMessageDelivery` to `false`, the others cleared. A property the
  stack never declared is left alone. `DeliveryPolicy`, previously ignored, is read like
  `RedrivePolicy` on create and update.
- `SnsService.clearSubscriptionAttribute`: removes an attribute so it reads as never set
  (`SetSubscriptionAttributes` can only store a value).

### Tests added

`SnsCfnProvisionerTest`: `anUpdateWritesTheChangedAttributesOntoTheExistingSubscription`,
`aCreateStillPassesTheAttributesToSubscribeOnly`, `anUpdateResetsAttributesTheTemplateDropped`.
`SnsServiceTest`: `clearSubscriptionAttribute_removesTheAttributeSoItReadsAsNeverSet`.

```sh
./mvnw test -Dtest='SnsCfnProvisionerTest,SnsServiceTest'
```

Revert-check: without the provisioner change, `SnsCfnProvisionerTest` runs 26 tests with 2 failures
(the two update cases). With it, `SnsCfnProvisionerTest` 26 of 26 and `SnsServiceTest` 100 of 100 pass.

### Compatibility

- Creates are unchanged apart from `DeliveryPolicy` now being stored.
- Not changed here: `SubscriptionRoleArn`, `Region` and `ReplayPolicy` are not read by the
  provisioner on create or update.

## Checklist

- [x] `./mvnw test` for the affected classes passes locally; the full suite is left to CI.
- [x] New unit tests added.
- [x] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
