PR title: `fix(apigatewayv2): select the most specific HTTP API route`

## Summary

For an HTTP API, Floci sends a request to the first route whose template matches, in the order the
route store happens to scan, so a greedy route such as `ANY /a/{proxy+}` can take a request that a
more specific route such as `ANY /a/b/{x}/c/{proxy+}` matches. AWS selects the most specific matching
route. This PR ranks every matching route and returns the most specific; `$default` is still the
last resort. Matching itself, WebSocket route selection and REST API resource matching are unchanged.

## Type of change

- [x] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## AWS Compatibility

### The AWS reference

"Working with routes for HTTP APIs", section "Routing API requests":
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-routes.html

> After selecting a stage, API Gateway selects a route. API Gateway selects the route with the
> most-specific match, using the following priorities:
>
> 1. Full match for a route and method.
> 2. Match for a route and method with a greedy path variable (`{proxy+}`).
> 3. The `$default` route.

and, from the same page's example table: "The ANY method matches all methods that you haven't
defined for a route." The table's routes are `GET /pets/dog/1`, `GET /pets/dog/{id}`,
`GET /pets/{proxy+}`, `ANY /{proxy+}` and `$default`, and its requests `GET /pets/dog/1`,
`GET /pets/dog/2`, `GET /pets/cat/1` and `POST /test/5` select the first four in turn.

To be precise about the evidence: the page gives the three tiers, the `ANY` rule and the table. It
does not say in words how two greedy routes, or two routes with variables at different positions,
rank against each other. "Most-specific match" and the table (`1` over `{id}`, `/pets/{proxy+}` over
`/{proxy+}`) are the basis for ranking a literal segment over a variable and a longer literal prefix
over a shorter one. I have not measured those tie-breaks against a real API Gateway.

### The incorrect behaviour

`ApiGatewayV2Service.findMatchingRoute` tries an exact route key, then returns the first route whose
template matches, then `$default`. The routes come from `routeStore.scan`, whose order is not
creation order, so which of several matching routes wins is arbitrary. Seen on a stack with
`ANY /v1/admin/{proxy+}` and `ANY /v1/admin/schools/{schoolId}/banks/{proxy+}` on different
functions: `GET /v1/admin/schools/<id>/banks/<bankId>/draft` reached the `/v1/admin/{proxy+}`
function.

### The fix

`src/main/java/io/github/hectorvent/floci/services/apigatewayv2/ApiGatewayV2Service.java`:

- `findMatchingRoute` ranks every route that `routeKeyMatchesPath` accepts and returns the lowest
  rank; `$default` only when nothing else matches. The separate exact-match pass is gone, since a
  fully literal route ranks first anyway.
- `routeSpecificity` is the rank: a route without a greedy variable before one with it; then segment
  by segment from the left, literal before path variable before greedy variable; then a route naming
  the request's method before `ANY` on the same path.

### Tests added

`src/test/java/io/github/hectorvent/floci/services/apigatewayv2/HttpApiRoutePriorityTest.java`, 21
cases. The routes are handed to `findMatchingRoute` in a fixed order, and each case runs with them as
written and reversed, since a selection that follows store order fails one of the two: AWS's example
table; a longer greedy prefix over a shorter one; a route without a greedy variable over a greedy
one; a named method over `ANY`; a literal segment over a variable; `$default` and no match.

```sh
./mvnw test -Dtest=HttpApiRoutePriorityTest
```

Revert-check: without the change to `findMatchingRoute`, the class runs 21 tests with 7 failures,
each the order in which the less specific route comes first. With it, 21 of 21 pass.

Wider run: `-Dtest=**/services/apigateway/**,**/services/apigatewayv2/**` in a JDK 25 build
container with `/var/run/docker.sock` mounted, so the tests started real Lambda containers: 1244
tests, 0 failures, 0 errors, 0 skipped.

These runs are on the change applied to `2.1.0`. The commit merges onto `main` (`a017c94ec`) without
conflict, and `findMatchingRoute` there is the same as at `2.1.0`; the runs have not been repeated on
`main`, and should be before this is opened.

### Compatibility

- An API whose routes overlap now reaches the route AWS would choose. An API whose routes do not
  overlap selects exactly what it did before.
- WebSocket APIs select by `findRouteByKey` and are unchanged. REST APIs match resources in
  `ApiGatewayService` and are unchanged.

## Checklist

- [ ] `./mvnw test` passes locally: the API Gateway packages were run with Docker as described above
  and all pass. The full suite, outside those packages, is left to CI.
- [x] New or updated integration test added: a new unit test class, `HttpApiRoutePriorityTest`.
- [x] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
