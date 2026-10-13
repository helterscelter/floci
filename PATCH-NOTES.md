# Patch notes: HTTP API route priority

This branch is Floci `2.1.0` plus one fix. The image Cramageddon runs is built from
`cramageddon/2.1.0-patched`, which carries this fix and the payload 2.0 cookies fix
(`fix/http-api-payload-2-cookies`).

## What was wrong

For an HTTP API, `ApiGatewayV2Service.findMatchingRoute` looked for an exact route key, then returned
the first route whose template matched, in the route store's scan order, then `$default`. The scan
order is not creation order. So, among several matching routes:

- a greedy `ANY /a/{proxy+}` could take a request meant for `ANY /a/b/{x}/c/{proxy+}`;
- a greedy route could take a request that a route without a greedy variable matches fully
  (`GET /{proxy+}` over `ANY /items/{id}`);
- `ANY /pets/{id}` could take a `GET` that `GET /pets/{id}` defines;
- `GET /pets/{kind}/{id}` could take a request for `GET /pets/dog/{id}`.

AWS documents the opposite:

> After selecting a stage, API Gateway selects a route. API Gateway selects the route with the
> most-specific match, using the following priorities:
>
> 1. Full match for a route and method.
> 2. Match for a route and method with a greedy path variable (`{proxy+}`).
> 3. The `$default` route.

and, of `ANY`: "The ANY method matches all methods that you haven't defined for a route."

Source: "Working with routes for HTTP APIs", section "Routing API requests", with its example table
(routes `GET /pets/dog/1`, `GET /pets/dog/{id}`, `GET /pets/{proxy+}`, `ANY /{proxy+}`, `$default`),
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-routes.html.

Measured on a client's stack: with `ANY /v1/admin/{proxy+}` and
`ANY /v1/admin/schools/{schoolId}/banks/{proxy+}` on different functions,
`GET /v1/admin/schools/<id>/banks/<bankId>/draft` reached the `/v1/admin/{proxy+}` function.

### On the tie-breaks

The page states the three tiers, the example table and the `ANY` rule. It does not state in words how
two greedy routes, or two routes with path variables at different positions, are ordered against
each other; "most-specific match" and the example table (a literal `1` over `{id}`, `/pets/{proxy+}`
over `/{proxy+}`) are the evidence for ranking a literal segment over a variable and a longer literal
prefix over a shorter one. That is not a measurement against AWS.

## The fix

`src/main/java/io/github/hectorvent/floci/services/apigatewayv2/ApiGatewayV2Service.java`:

- `findMatchingRoute` ranks every matching route and returns the most specific; `$default` only when
  nothing else matches.
- `routeSpecificity` is the rank: a route without a greedy variable before one with it; then segment by
  segment from the left, literal before path variable before greedy variable; then a named method
  before `ANY` on the same path.
- The exact-match pass is gone: a fully literal route key ranks first on its own.

Matching itself (`routeKeyMatchesPath`) is unchanged. `findMatchingRoute` is the only route selection
the HTTP API request path uses (`ApiGatewayExecuteController`'s v2 dispatch); WebSocket APIs select by
`findRouteByKey`, and REST APIs match resources in `ApiGatewayService`, neither changed here.

## Tests

`src/test/java/io/github/hectorvent/floci/services/apigatewayv2/HttpApiRoutePriorityTest.java`,
21 cases. The route list is handed to `findMatchingRoute` in a fixed order, and each case runs with the
routes as written and reversed, since a selection that follows store order fails one of the two:

- AWS's example table, its four requests, in both orders;
- `ANY /a/{proxy+}` and `ANY /a/b/{x}/c/{proxy+}`: the longer greedy prefix wins where it matches;
- `ANY /v1/admin/{proxy+}`, `ANY /v1/admin/schools/{schoolId}/banks/{proxy+}` and
  `ANY /v1/admin/schools/{schoolId}/banks`: each request reaches its own route;
- a route without a greedy variable over a greedy one; `GET` over `ANY` on one path, with `POST` still
  reaching `ANY`; a literal segment over a variable;
- `$default` when nothing else matches; no route and no `$default` selects nothing; a method mismatch
  is not a match.

Revert-check: against unpatched `2.1.0` the class runs 21 tests with 7 failures, each the order in
which the less specific route comes first; with the fix, 21 of 21 pass.

Run:

    ./mvnw test -Dtest=HttpApiRoutePriorityTest

The API Gateway packages (`-Dtest=**/services/apigateway/**,**/services/apigatewayv2/**`), run in a
JDK 25 build container with the Docker socket mounted: 1244 tests (1223 at `2.1.0` plus the 21 new),
with no failures, errors or skips.

## Upstream

- Upstream `main` (`a017c94ec`, fetched 2026-10-13) has the same first-match `findMatchingRoute`, and
  a search of its pull requests and issues for HTTP API route selection found none that changes it.
- The fix commit merges onto `main` without conflict (a three-way merge from `2.1.0`).
