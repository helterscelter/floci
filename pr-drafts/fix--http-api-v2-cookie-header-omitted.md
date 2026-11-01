PR title: `fix(apigatewayv2): leave the cookie header out of the 2.0 event's headers`

## Summary

For an HTTP API Lambda integration (and Lambda authorizer) at payload format 2.0, Floci puts the
request's cookies in the event's `cookies` array, since #5152, and also repeats them as a `cookie`
entry in `headers`. AWS moves the cookies into `cookies`; its 2.0 event has no `cookie` header. This
change leaves `cookie` out of `headers` in both 2.0 builders. The `cookies` array, every other header
and payload format 1.0 are unchanged.

Branch: `fix/http-api-v2-cookie-header-omitted`, one commit on `origin/main` (`5544a119d`).

## Type of change

- [x] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## AWS Compatibility

"Working with AWS Lambda proxy integrations for HTTP APIs", "Payload format differences":
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html

> Format `2.0` includes a new `cookies` field. All cookie headers in the request are combined with
> commas and added to the `cookies` field.

The format 2.0 example event carries `"cookies": ["cookie1", "cookie2"]` beside a `headers` map with
no `cookie` entry; the Lambda authorizer 2.0 example has the same shape
(https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html).
The page does not say in words that `cookie` is removed from `headers`; that comes from the example
events, and from payload 2.0 adapters that rebuild a `Cookie` header from `event.cookies`. It has not
been measured against a real API Gateway. See `../PR-BODY.md` for the full write-up.

### Root cause

`ApiGatewayExecuteController.putV2CookiesHeadersAndQuery`, shared by `buildV2ProxyEvent` and
`buildRequestAuthorizerEventV2`, builds `cookies` through `CookieHeaders.cookiePairs` and then copies
every request header, `Cookie` included, into `headers`.

### The fix

- `core/common/CookieHeaders.java`: new `isCookieHeader(String)`, a case-insensitive match on
  `Cookie`, used by `cookiePairs` so the array and the skip recognise the same header.
- `services/apigateway/ApiGatewayExecuteController.java`: `putV2CookiesHeadersAndQuery` skips a header
  for which `isCookieHeader` is true when it writes `headers`.

### Tests added

`BuildV2ProxyEventMultiValueTest.cookieHeaderIsNotRepeatedInHeaders`, parameterised over both
builders: `Cookie: a=1; b=2` gives `cookies: ["a=1","b=2"]`, no `cookie` in `headers`, other headers
kept.

```sh
./mvnw test -Dtest=BuildV2ProxyEventMultiValueTest,CookieHeadersTest
```

Test-first: on `origin/main` the class runs 12 tests with 2 failures, the new case under each
builder (`expected: <false> but was: <true>`). With the change: `BuildV2ProxyEventMultiValueTest` 12 of
12 and `CookieHeadersTest` 2 of 2 pass.

### Compatibility

- A Lambda reading `event.headers.cookie` at payload 2.0 stops seeing it there, as on AWS.
- Payload 1.0 events, `HTTP_PROXY` forwarding and Lambda function URL events are unchanged.

## Checklist

- [ ] `./mvnw test` passes locally: the targeted classes pass; the full suite is left to CI.
- [x] New or updated test added: `cookieHeaderIsNotRepeatedInHeaders`.
- [x] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
