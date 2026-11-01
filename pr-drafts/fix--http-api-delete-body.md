PR title: `fix(apigateway): pass the request body through on DELETE`

## Summary

A request reaches an API through `/execute-api`, `/_aws/execute-api` or
`/restapis/{apiId}/{stageName}/_user_request_`. In all three controllers the `POST`, `PUT`, `PATCH` and
`OPTIONS` handlers pass the request body to `dispatch`, but the `DELETE` handler takes no body and
dispatches `null`. So a `DELETE` that sends a body reaches its integration without one: with an HTTP
API Lambda integration on payload format 2.0, the event carries `"body": null`. This PR has each
`DELETE` handler take the body and pass it on, as the other methods' handlers do.

## Type of change

- [x] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## AWS Compatibility

### The AWS reference

"Working with AWS Lambda proxy integrations for HTTP APIs", the payload format 2.0 event:
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html

The event has a `body` field, and the page names no method whose body is left out. To be precise about
the evidence: that is a reading of the page. I have not measured a `DELETE` with a body against a real
API Gateway.

### The incorrect behaviour

`ApiGatewayExecuteController.handleDelete`, `ApiGatewayAwsExecuteController.handleDelete` and
`ApiGatewayUserRequestController.handleDelete` each end in `dispatch("DELETE", ..., null)`. Seen on a
stack with an HTTP API route `DELETE /v1/game-grants/current` on a payload 2.0 Lambda integration
whose function reads a JSON body: the request sent `{"grant":"..."}`, the function received
`"body": null` and answered 400. A `POST` body to the same function arrived.

### The fix

In each of
`src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayExecuteController.java`,
`ApiGatewayAwsExecuteController.java` and `ApiGatewayUserRequestController.java`, `handleDelete` takes
`byte[] body` and passes it to `dispatch`.

- A `DELETE` without a body is unchanged: JAX-RS hands the handler an empty array, and the event
  builders treat an empty body as absent, so the event still carries `"body": null`.
- A WebSocket `DELETE @connections/{id}` on `/execute-api` still goes to `handleDeleteConnection`
  before dispatch.
- Not changed: `HttpProxyInvoker` sends an HTTP_PROXY integration's `DELETE` with
  `HttpRequest.Builder.DELETE()`, which carries no body, so an HTTP_PROXY backend still receives none.

### Tests added

`src/test/java/io/github/hectorvent/floci/services/apigatewayv2/HttpApiDeleteBodyIntegrationTest.java`,
6 cases. It deploys an HTTP API with `DELETE /grants/current` on a payload 2.0 Lambda integration
whose function echoes `event.body`, sends a JSON body through each of the three entry points and
expects it in the event, not base64-encoded, and sends a `DELETE` with no body and expects
`"body": null`. It starts a real Lambda container, so it needs Docker.

```sh
./mvnw test -Dtest=HttpApiDeleteBodyIntegrationTest
```

Revert-check: without the change to the three controllers, the class runs 6 tests with 3 failures,
each a body arriving as `null`. With it, 6 of 6 pass.

Wider run: `-Dtest=**/services/apigateway/**,**/services/apigatewayv2/**` in a JDK 25 build container
with `/var/run/docker.sock` mounted, on `2.1.0` with this change and two other fixes of mine (the
payload 2.0 request cookies and HTTP API route priority): 1257 tests, 0 failures, 0 errors, 0 skipped.

These runs are on the change applied to `2.1.0`. On `main` (`c4a809f47`, fetched 2026-10-14) the three
`DELETE` handlers are the same, but `ApiGatewayExecuteController.handleDelete` has a different
WebSocket check at its top, so the commit does not merge onto `main` cleanly; the change applies to
that method by hand. The runs have not been repeated on `main`, and should be before this is opened.

### Compatibility

- A `DELETE` with a body now reaches its Lambda or AWS integration with that body. A `DELETE` without
  one produces the same event as before.
- `GET`, `POST`, `PUT`, `PATCH` and `OPTIONS` are unchanged.

## Checklist

- [ ] `./mvnw test` passes locally: the API Gateway packages were run with Docker as described above
  and all pass. The full suite, outside those packages, is left to CI.
- [x] New or updated integration test added: a new integration test class,
  `HttpApiDeleteBodyIntegrationTest`.
- [x] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
