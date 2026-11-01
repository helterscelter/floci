PR title: `fix(apigatewayv2): leave the cookie header out of the 2.0 event's headers`

## Summary

For an HTTP API Lambda integration (and HTTP API Lambda authorizer) at payload format 2.0, Floci puts
the request's cookies in the event's `cookies` array, since #5152, and also repeats them as a
`cookie` entry in `headers`. AWS moves the cookies into `cookies`, and its 2.0 event has no `cookie`
header. This PR leaves `cookie` out of `headers` in both 2.0 builders. The `cookies` array, every
other header, and payload format 1.0 are unchanged.

Follows #5152 (request `cookies` array) and #2391 (response `cookies` to `Set-Cookie`).

## Type of change

- [x] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## AWS Compatibility

### The AWS reference

"Working with AWS Lambda proxy integrations for HTTP APIs", section "Payload format differences":
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html

> Format `2.0` includes a new `cookies` field. All cookie headers in the request are combined with
> commas and added to the `cookies` field. In the response to the client, each cookie becomes a
> `set-cookie` header.

The format 2.0 example event on the same page carries `"cookies": ["cookie1", "cookie2"]` beside a
`headers` map with no `cookie` entry. The Lambda authorizer's 2.0 example has the same shape:
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html

To be precise about the evidence: the page does not say in words that the `cookie` header is removed
from `headers`. That comes from the example event, and it agrees with how payload 2.0 adapters are
written: they rebuild a `Cookie` header from `event.cookies` (for example Hono's AWS Lambda adapter,
`getCookies` in `src/adapter/aws-lambda/handler.ts`), which would be unnecessary if API Gateway
still sent the header. I have not measured this against a real API Gateway. If a maintainer has,
that measurement should decide it.

### The incorrect behaviour

Request side, before this PR: the event carries the cookies twice, once in `cookies` and once as
`headers.cookie`.

Response side: already correct. A 2.0 response's `cookies` entries each become a `Set-Cookie`
header (#2391), and the reproduction below shows it unchanged.

### Minimal reproduction

The Lambda (`index.mjs`, runtime `nodejs22.x`):

```js
export const handler = async (event) => ({
  statusCode: 200,
  headers: { "content-type": "application/json" },
  cookies: ["session=new; Path=/; HttpOnly", "theme=dark; Path=/"],
  body: JSON.stringify({
    cookies: event.cookies ?? null,
    cookieHeader: event.headers?.cookie ?? null,
  }),
});
```

The API, against Floci on port 4566:

```sh
zip fn.zip index.mjs
FN=$(aws --endpoint-url http://localhost:4566 lambda create-function --function-name probe \
  --runtime nodejs22.x --handler index.handler --role arn:aws:iam::000000000000:role/x \
  --zip-file fileb://fn.zip --query FunctionArn --output text)
API=$(aws --endpoint-url http://localhost:4566 apigatewayv2 create-api --name probe \
  --protocol-type HTTP --query ApiId --output text)
INT=$(aws --endpoint-url http://localhost:4566 apigatewayv2 create-integration --api-id "$API" \
  --integration-type AWS_PROXY --integration-uri "$FN" --integration-method POST \
  --payload-format-version 2.0 --query IntegrationId --output text)
aws --endpoint-url http://localhost:4566 apigatewayv2 create-route --api-id "$API" \
  --route-key 'POST /v1/auth/refresh' --target "integrations/$INT"
aws --endpoint-url http://localhost:4566 apigatewayv2 create-stage --api-id "$API" \
  --stage-name '$default' --auto-deploy
aws --endpoint-url http://localhost:4566 lambda add-permission --function-name probe \
  --statement-id apigw --action lambda:InvokeFunction --principal apigateway.amazonaws.com \
  --source-arn "arn:aws:execute-api:us-east-1:000000000000:$API/*/*"

HOST="$API.execute-api.localhost.floci.io"
curl -s -i --resolve "$HOST:4566:127.0.0.1" -X POST "http://$HOST:4566/v1/auth/refresh" \
  -H 'Cookie: session=abc; theme=light'
```

Before, `main` at `ae75a653d`:

```
HTTP/1.1 200 OK
Set-Cookie: session=new; Path=/; HttpOnly
Set-Cookie: theme=dark; Path=/

{"cookies":["session=abc","theme=light"],"cookieHeader":"session=abc; theme=light"}
```

After, this branch:

```
HTTP/1.1 200 OK
Set-Cookie: session=new; Path=/; HttpOnly
Set-Cookie: theme=dark; Path=/

{"cookies":["session=abc","theme=light"],"cookieHeader":null}
```

### Root cause

`ApiGatewayExecuteController.putV2CookiesHeadersAndQuery`, shared by `buildV2ProxyEvent` and
`buildRequestAuthorizerEventV2`, builds the `cookies` array from the `Cookie` headers through
`CookieHeaders.cookiePairs` and then copies every request header, `Cookie` included, into `headers`.

### The fix

- `src/main/java/io/github/hectorvent/floci/core/common/CookieHeaders.java`: a new
  `isCookieHeader(String)`, a case-insensitive match on `Cookie`. `cookiePairs` uses it, so the array
  and the skip recognise the same header.
- `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayExecuteController.java`:
  `putV2CookiesHeadersAndQuery` skips a header for which `isCookieHeader` is true when it writes
  `headers`. Both 2.0 builders go through it, so the integration event and the authorizer event
  change together.

### Tests added

`src/test/java/io/github/hectorvent/floci/services/apigateway/BuildV2ProxyEventMultiValueTest.java`
gains `cookieHeaderIsNotRepeatedInHeaders`, parameterised over both builders like the rest of the
class: `Cookie: a=1; b=2` gives `cookies: ["a=1","b=2"]`, no `cookie` in `headers`, and other
headers still present.

```sh
./mvnw test -Dtest=BuildV2ProxyEventMultiValueTest
```

Revert-check: without the change to `putV2CookiesHeadersAndQuery`, the class runs 12 tests with 2
failures, the new case under each builder (`expected: <false> but was: <true>` on
`headers.has("cookie")`). With it, 12 of 12 pass.

The response side is already covered by
`ApiGatewayExecuteControllerTest.projectsHttpApiV2CookiesAsRepeatedSetCookieHeadersAlongsideResponseHeaders`,
which passes unchanged.

Wider run, with Docker: the API Gateway and API Gateway v2 test classes (`ApiGateway*`, `BuildV2*`,
`ProxyEvent*`, `HttpApi*`, `HttpProxy*`, `WebSocket*`, `Jwt*`, `RouteAuthoriz*`, and the rest of the
`services/apigateway` and `services/apigatewayv2` classes), plus `CookieHeadersTest` and
`LambdaUrl*`. They ran in a JDK 25 build container with `/var/run/docker.sock` mounted, so the tests
started real Lambda containers: 1994 tests on `main` (`ae75a653d`), 1996 on this branch (the 2 new
cases), with 0 failures, 0 errors and 0 skipped in both.

### Compatibility

- A Lambda behind a Floci HTTP API that reads `event.headers.cookie` at payload 2.0 stops seeing the
  cookies there. It would not see them there on AWS either, so it now fails locally the way it
  would fail when deployed. `event.cookies` is unchanged.
- No test in the repository or in `compatibility-tests` asserts a `cookie` entry in a 2.0 event's
  `headers`.
- Payload format 1.0 events (`buildProxyEvent`, REST APIs and HTTP APIs at 1.0) keep their
  `headers` and `multiValueHeaders` exactly as they are, `Cookie` included, as AWS's 1.0 format
  has no `cookies` field.
- `HTTP_PROXY` integrations do not build a Lambda event and still forward the `Cookie` header to the
  backend.
- Not changed here: Lambda function URL events (`LambdaUrlInvocationController`, #5288) also build
  `cookies` and repeat the `cookie` header. Function URLs use the same 2.0 shape, so the same
  question applies. I left them out to keep this PR to API Gateway. Happy to follow up if you
  agree.

### End-to-end verification

- The reproduction above, run against JVM images built with `docker/Dockerfile` from `main`
  (`ae75a653d`) and from this branch: the outputs are the ones shown.
- The same change backported to `2.1.0`, where `cookies` was not built at all, was run with a
  Lambda whose refresh-token handling reads `event.cookies` only. On stock `2.1.0` the token read as
  `null`; on the patched build it read correctly, and both `Set-Cookie` headers from the response
  `cookies` array came back.

Note on building the image: `docker/Dockerfile` runs `mvn clean package -DskipTests`, which still
compiles the tests, and they need `sidecars/cedar`, which that Dockerfile does not copy. For these
local builds I used `-Dmaven.test.skip=true` instead. That is unrelated to this PR, and I have not
changed it here.

## Checklist

- [ ] `./mvnw test` passes locally: the affected test classes were run with Docker as described
  above and all pass (1996 tests, none failing or skipped). The full suite, outside those classes,
  is left to CI.
- [x] New or updated integration test added: a new unit case in `BuildV2ProxyEventMultiValueTest`,
  over both 2.0 builders.
- [x] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
