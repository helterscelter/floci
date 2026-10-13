# Patch notes: Floci 2.1.0 patched for Cramageddon

This branch is Floci `2.1.0` plus two fixes, each cherry-picked from its own branch, where its patch
notes give the AWS reference, the tests and the upstream status in full. It is the branch the
Cramageddon image is built from; it is not for upstream.

| Fix | Branch | Upstream |
|---|---|---|
| HTTP API payload format 2.0: the request's cookies in the event's `cookies` array, and no `cookie` entry in `headers` | `fix/http-api-payload-2-cookies` | the `cookies` array is upstream #5152, in no release as of 2026-10-13; the omission of the `cookie` header is not upstream |
| HTTP API route selection: the most specific matching route, not the first in store order | `fix/http-api-route-priority` | not upstream as of 2026-10-13 |

## Payload 2.0 cookies

`ApiGatewayExecuteController`'s two 2.0 builders, the integration event and the Lambda authorizer
event, list every cookie-pair of every `Cookie` header in `cookies` and leave the `cookie` header out
of `headers`. AWS: "Format `2.0` includes a new `cookies` field. All cookie headers in the request are
combined with commas and added to the `cookies` field."
(https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html).
Tests: `BuildV2ProxyEventCookiesTest`, 7 cases.

## Route priority

`ApiGatewayV2Service.findMatchingRoute` ranks every matching route and returns the most specific: a
route without a greedy variable before one with it; then segment by segment from the left, literal
before path variable before greedy variable; then a named method before `ANY` on the same path;
`$default` only when nothing else matches. AWS: "API Gateway selects the route with the most-specific
match, using the following priorities: Full match for a route and method. Match for a route and
method with a greedy path variable (`{proxy+}`). The `$default` route." and "The ANY method matches
all methods that you haven't defined for a route."
(https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-routes.html, "Routing
API requests"). Tests: `HttpApiRoutePriorityTest`, 21 cases.

## Tests

    ./mvnw test -Dtest=BuildV2ProxyEventCookiesTest,HttpApiRoutePriorityTest

The API Gateway packages (`-Dtest=**/services/apigateway/**,**/services/apigatewayv2/**`) run in a
JDK 25 build container with the Docker socket mounted: 1251 tests (1223 at `2.1.0`, the 7 cookies
cases and the 21 route cases), with no failures, errors or skips.

## Image

Cramageddon builds the image from this branch's tip with its own `infra/scripts/floci-patch/build.sh`,
which exports the commit with `git archive` and builds it with upstream's `docker/Dockerfile` but for
`-Dmaven.test.skip=true` in place of `-DskipTests` (at `2.1.0` the tests need `sidecars/cedar`,
which the exported tree lacks).

## Retirement

The branch is retired when an upstream release carries both: the request `cookies` array without the
`cookie` header in `headers`, and most-specific HTTP API route selection.
