PR title: `fix(apigateway): pass the request body through on DELETE`

## Summary

In the three execute controllers (`/execute-api`, `/_aws/execute-api`,
`/restapis/{apiId}/{stageName}/_user_request_`) the `POST`, `PUT`, `PATCH` and `OPTIONS` handlers pass
the request body to `dispatch`, but the `DELETE` handler takes no body and dispatches `null`. A
`DELETE` that sends a body therefore reaches its integration without one (an HTTP API Lambda
integration at payload format 2.0 gets `"body": null`). Each `DELETE` handler now takes the body and
passes it on.

Branch: `fix/http-api-delete-body-main`, one commit on `origin/main` (`5544a119d`).

## Type of change

- [x] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## AWS Compatibility

The payload format 2.0 event on "Working with AWS Lambda proxy integrations for HTTP APIs" has a
`body` field and names no method whose body is left out:
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html
That is a reading of the page; a `DELETE` with a body has not been measured against a real API
Gateway. The incorrect behaviour and the compatibility notes are those of
`../delete-body/PR-BODY.md`.

### The fix

`ApiGatewayAwsExecuteController` and `ApiGatewayUserRequestController` took the `2.1.0` change
unchanged. In `ApiGatewayExecuteController.handleDelete` the context differs on `main`
(`isWebSocketConnectionRequest(apiId, proxy)` and `@Blocking`), so the change was applied by hand: the
method gains `byte[] body` after `@PathParam("proxy")` and passes it to `dispatch`. The WebSocket
`DELETE @connections/{id}` check still runs first. A `DELETE` without a body is unchanged.

### Tests added

`src/test/java/io/github/hectorvent/floci/services/apigatewayv2/HttpApiDeleteBodyIntegrationTest.java`,
6 cases, through each of the three entry points, plus a `DELETE` with no body. It starts a real Lambda
container, so it needs Docker.

```sh
./mvnw test -Dtest=HttpApiDeleteBodyIntegrationTest
```

Not run on this branch: no Docker was available. It compiles, and
`ApiGatewayExecuteControllerTest` (41 tests, 0 failures) passes with the change. The class ran 6 of 6
on `2.1.0` with the change, and 3 of 6 failed without it.

## Checklist

- [ ] `./mvnw test` passes locally: the integration class still has to be run with Docker.
- [x] New or updated integration test added: `HttpApiDeleteBodyIntegrationTest`.
- [x] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
