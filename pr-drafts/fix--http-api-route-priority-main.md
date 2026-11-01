PR title: `fix(apigatewayv2): select the most specific HTTP API route`

## Summary

For an HTTP API, Floci sends a request to the first route whose template matches, in the order the
route store happens to scan, so a greedy route such as `ANY /a/{proxy+}` can take a request that a
more specific route such as `ANY /a/b/{x}/c/{proxy+}` matches. AWS selects the most specific matching
route. This PR ranks every matching route and returns the most specific; `$default` is still the
last resort. Matching itself, WebSocket route selection and REST API resource matching are unchanged.

Branch: `fix/http-api-route-priority-main`, one commit on `origin/main` (`5544a119d`).

## Type of change

- [x] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## AWS Compatibility

The AWS reference, the incorrect behaviour, the fix and the compatibility notes are those of
`../route-priority/PR-BODY.md`; the commit is the same change. `findMatchingRoute` is unchanged on
`main` from `2.1.0`, so it applied with a line offset and no conflict.

"Working with routes for HTTP APIs", section "Routing API requests":
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-routes.html

> API Gateway selects the route with the most-specific match, using the following priorities:
> 1. Full match for a route and method. 2. Match for a route and method with a greedy path variable
> (`{proxy+}`). 3. The `$default` route.

The page does not say how two greedy routes rank against each other; the tie-breaks (literal before
variable before greedy, longer literal prefix first) follow from "most-specific" and its example
table, and have not been measured against a real API Gateway.

### The fix

`src/main/java/io/github/hectorvent/floci/services/apigatewayv2/ApiGatewayV2Service.java`:
`findMatchingRoute` ranks every route `routeKeyMatchesPath` accepts and returns the lowest rank, and
`$default` only when nothing else matches. `routeSpecificity` ranks a route without a greedy variable
first, then segment by segment from the left (literal, path variable, greedy variable), then a named
method before `ANY`.

### Tests added

`src/test/java/io/github/hectorvent/floci/services/apigatewayv2/HttpApiRoutePriorityTest.java`, 21
cases, each run with the routes in both orders.

```sh
./mvnw test -Dtest=HttpApiRoutePriorityTest
```

Result on this branch (JDK 25, no Docker needed): 21 tests, 0 failures, 0 errors, 0 skipped. The
revert-check (7 of 21 failing without the change) was run on `2.1.0`.

## Checklist

- [ ] `./mvnw test` passes locally: the targeted class passes; the full suite is left to CI.
- [x] New or updated test added: `HttpApiRoutePriorityTest`.
- [x] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
