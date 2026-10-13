# Patch notes: HTTP API payload format 2.0 cookies

This branch is Floci `2.1.0` plus one fix, built locally as `floci-cramageddon:2.1.0-cookies`. It is
not for upstream as it stands: upstream `main` has moved on, and the submittable form is the branch
`fix/http-api-payload-2-cookies-main` (see "Upstream" below).

## What was wrong

For an HTTP API route with an `AWS_PROXY` Lambda integration at payload format `2.0`, Floci `2.1.0`
built the event with the request's `Cookie` header copied into `headers.cookie` and no `cookies`
field. AWS documents the opposite:

> Format `2.0` includes a new `cookies` field. All cookie headers in the request are combined with
> commas and added to the `cookies` field. In the response to the client, each cookie becomes a
> `set-cookie` header.

Source: "Working with AWS Lambda proxy integrations for HTTP APIs", section "Payload format
differences" and the format 2.0 example event,
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html.
The Lambda authorizer receives the same 2.0 request shape, with `cookies` in its example event:
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html.

A handler that reads `event.cookies` therefore saw no cookies through Floci.

The response side was already correct in `2.1.0`: `buildProxyResponse(result, true)` turns each entry
of a 2.0 response's `cookies` array into its own `Set-Cookie` header (upstream #2391).

### On leaving `cookie` out of `headers`

AWS's prose says the cookies are added to `cookies`. It does not say in words that the `cookie`
header is removed from `headers`. Its 2.0 example event lists `cookies` and a `headers` map with no
`cookie` entry, and Lambda adapters for payload 2.0 rebuild a `Cookie` header from `event.cookies`
(for example Hono's `aws-lambda` adapter, `getCookies` in `src/adapter/aws-lambda/handler.ts`), which
they would not need to do if the header were present. That is supporting evidence, not a
measurement against AWS. The fix follows it, and the PR body says so.

## The fix

`src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayExecuteController.java`:

- `putV2Cookies` lists every cookie-pair of every `Cookie` header, in request order, trimmed and
  without empty segments, in a `cookies` array, and leaves the field out when there are none.
- Both 2.0 builders, `buildV2ProxyEvent` (integration) and `buildRequestAuthorizerEventV2` (Lambda
  authorizer), call it and skip the `cookie` header when writing `headers`.
- `buildRequestAuthorizerEventV2` is package-private instead of private, like `buildV2ProxyEvent`,
  so the test can call it.

Nothing else changes: header and query-string values keep 2.1.0's first-value behaviour, and payload
format 1.0 events are untouched.

## Tests

`src/test/java/io/github/hectorvent/floci/services/apigateway/BuildV2ProxyEventCookiesTest.java`,
7 cases, the request cases parameterised over both builders:

- `Cookie: a=1; b=2` becomes `cookies: ["a=1","b=2"]`, with no `cookie` in `headers` and other
  headers still forwarded;
- two `Cookie` headers contribute to one array, in order;
- a request without cookies has no `cookies` field;
- a 2.0 response's `cookies` array becomes one `Set-Cookie` header per entry.

Revert-check: against unpatched `2.1.0` the class runs 7 tests with 4 failures (the four request
cases that carry a cookie, `expected: <["a=1","b=2"]> but was: <null>`); with the fix, 7 of 7 pass.
The no-cookie case and the response case pass in both, as they should.

Run:

    ./mvnw test -Dtest=BuildV2ProxyEventCookiesTest

The API Gateway packages (`-Dtest=**/services/apigateway/**,**/services/apigatewayv2/**`), run in a
build container with no Docker socket: 1223 tests unpatched, 1230 patched (the 7 new), with 41
failures and 41 errors in both. The 82 failing tests are identical in both runs; all are tests that
start Lambda containers (WebSocket, Lambda authorizer and binary-payload integration tests).

## Image

    sed "s/-DskipTests/-Dmaven.test.skip=true/" docker/Dockerfile > Dockerfile.jvm-notests
    docker build -f Dockerfile.jvm-notests --build-arg VERSION=2.1.0-cookies -t floci-cramageddon:2.1.0-cookies .

`docker/Dockerfile` at `2.1.0` does not build as is: it copies only `src/`, and `-DskipTests` still
compiles the tests, which need `sidecars/cedar` (`CedarSidecarServer`). Skipping test compilation is
the only change.

## End to end

A Lambda bundled from a client's real cookie code (`readRefreshCookie`, which reads `event.cookies`
only, and `refreshCookie`), on `nodejs22.x`, behind an HTTP API with an `AWS_PROXY` integration at
payload format `2.0` and a `POST /v1/auth/refresh` route, called with
`Cookie: cramageddon_refresh=abc%2Fdef; other=1`:

| | `floci/floci:2.1.0` | this branch |
|---|---|---|
| `event.cookies` | `null` | `["cramageddon_refresh=abc%2Fdef","other=1"]` |
| `event.headers.cookie` | `cramageddon_refresh=abc%2Fdef; other=1` | absent |
| refresh token read | `null` | `abc/def` |
| `Set-Cookie` headers from the response `cookies` | both | both |

## Upstream

- Request `cookies` array: upstream #5152 (`c6369b321`, merged 2026-10-07, after `2.2.0`; in no
  release yet) builds it on `main`, through `CookieHeaders.cookiePairs`, but still repeats the
  `cookie` header in `headers`.
- So this branch does not cherry-pick onto `main` (conflict in `ApiGatewayExecuteController.java`).
  The submittable change is `fix/http-api-payload-2-cookies-main`: only the omission of `cookie`
  from `headers`, with `CookieHeaders.isCookieHeader` and one parameterised case in
  `BuildV2ProxyEventMultiValueTest`.
- Not changed, same question: Lambda function URL events (`LambdaUrlInvocationController`, #5288)
  also build `cookies` and repeat the `cookie` header.
