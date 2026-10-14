# Patch notes: the request body on DELETE

This branch is Floci `2.1.0` plus one fix. The image Cramageddon runs is built from
`cramageddon/2.1.0-patched`, which carries this fix with the payload 2.0 cookies fix
(`fix/http-api-payload-2-cookies`) and the HTTP API route priority fix (`fix/http-api-route-priority`).

## What was wrong

A request reaches an API through one of three execute entry points:

- `ApiGatewayExecuteController`, `/execute-api/{apiId}/{stageName}/...`;
- `ApiGatewayAwsExecuteController`, `/_aws/execute-api/{apiId}/{stageName}/...`;
- `ApiGatewayUserRequestController`, `/restapis/{apiId}/{stageName}/_user_request_/...`.

In each, the `POST`, `PUT`, `PATCH` and `OPTIONS` handlers take the request entity as `byte[] body`
and pass it to `dispatch`. The `DELETE` handler takes no entity and calls `dispatch("DELETE", ..., null)`.
So a `DELETE` that sends a body reaches its integration without one. With an HTTP API Lambda
integration on payload format 2.0, the event carries `"body": null`, whatever the client sent.

Measured on a client's stack: `DELETE /v1/game-grants/current` with the JSON body `{"grant":"..."}`
reached the function with `body: null`, and the function answered 400 "Request body is required".
The client's own handler tests, which build the event with the body, pass for the same route.

AWS: the payload format 2.0 event has a `body` field ("Working with AWS Lambda proxy integrations for
HTTP APIs", https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html).
The page names no method whose body is left out. That is a reading of the page; I have not measured a
`DELETE` with a body against a real API Gateway.

## The fix

The `DELETE` handler of each of the three controllers takes `byte[] body` and passes it to
`dispatch`, as the other methods' handlers do:

- `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayExecuteController.java`
- `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayAwsExecuteController.java`
- `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayUserRequestController.java`

A `DELETE` with no body is unchanged: JAX-RS hands the handler an empty array, and the event builders
treat an empty body as absent, so the event still carries `"body": null`. A WebSocket
`DELETE @connections/{id}` on `/execute-api` still goes to `handleDeleteConnection` before dispatch.

Not changed here: `HttpProxyInvoker` sends an HTTP_PROXY integration's `DELETE` with
`HttpRequest.Builder.DELETE()`, which has no body, so an HTTP_PROXY backend still receives no body on
`DELETE`.

## Tests

`src/test/java/io/github/hectorvent/floci/services/apigatewayv2/HttpApiDeleteBodyIntegrationTest.java`,
6 cases. It deploys an HTTP API with a `DELETE /grants/current` route on a payload 2.0 Lambda
integration whose function echoes `event.body`, then:

- sends a JSON body through `/execute-api`, `/_aws/execute-api` and `/restapis/.../_user_request_`,
  and expects that body in the event, not base64-encoded;
- sends a `DELETE` with no body and expects `"body": null`.

The class starts a real Lambda container, so it needs Docker.

Revert-check: without the change to the three controllers, the class runs 6 tests with 3 failures,
each a body arriving as `null`; with it, 6 of 6 pass.

Run:

    ./mvnw test -Dtest=HttpApiDeleteBodyIntegrationTest

These runs are in a JDK 25 build container with the Docker socket mounted.

## Upstream

- Upstream `main` (`c4a809f47`, fetched 2026-10-14) has the same three `DELETE` handlers.
- The fix commit does not merge onto `main` cleanly: `main` changed the WebSocket check at the top of
  `ApiGatewayExecuteController.handleDelete`, and the three-way merge conflicts on that method. The
  change itself (take `byte[] body`, pass it to `dispatch`) applies to `main`'s method by hand.
