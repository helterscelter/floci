package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Route selection for HTTP APIs: API Gateway picks the most specific matching route, whatever
 * order the routes were created in ("Working with routes for HTTP APIs", "Routing API requests",
 * https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-routes.html).
 *
 * <p>The route store's scan order is not creation order, so each case runs with the routes in
 * the order written and reversed: a selection that depends on store order fails one of the two.
 */
class HttpApiRoutePriorityTest {

    private static final String REGION = "us-east-1";
    private static final String API_ID = "api12345";

    private static Route route(String routeKey) {
        Route r = new Route();
        r.setRouteId("r-" + Integer.toHexString(routeKey.hashCode()));
        r.setRouteKey(routeKey);
        return r;
    }

    private static ApiGatewayV2Service serviceWithRoutes(List<String> routeKeys) {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        ApiGatewayV2Service service = spy(new ApiGatewayV2Service(storageFactory,
                mock(EmulatorConfig.class), mock(RegionResolver.class)));
        List<Route> routes = routeKeys.stream().map(HttpApiRoutePriorityTest::route).toList();
        doReturn(routes).when(service).getRoutes(REGION, API_ID);
        return service;
    }

    private static String select(List<String> routeKeys, String method, String path) {
        Route r = serviceWithRoutes(routeKeys).findMatchingRoute(REGION, API_ID, method, path);
        return r == null ? null : r.getRouteKey();
    }

    private static List<String> reversed(List<String> routeKeys) {
        List<String> copy = new ArrayList<>(routeKeys);
        Collections.reverse(copy);
        return copy;
    }

    /** The five routes and four requests of AWS's own example table. */
    private static final List<String> AWS_EXAMPLE_ROUTES = List.of(
            "GET /pets/dog/1",
            "GET /pets/dog/{id}",
            "GET /pets/{proxy+}",
            "ANY /{proxy+}",
            "$default");

    static Stream<Arguments> awsExample() {
        return Stream.of(
                Arguments.of("GET", "/pets/dog/1", "GET /pets/dog/1"),
                Arguments.of("GET", "/pets/dog/2", "GET /pets/dog/{id}"),
                Arguments.of("GET", "/pets/cat/1", "GET /pets/{proxy+}"),
                Arguments.of("POST", "/test/5", "ANY /{proxy+}"));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("awsExample")
    void awsExampleTableInCreationOrder(String method, String path, String expected) {
        assertEquals(expected, select(AWS_EXAMPLE_ROUTES, method, path));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("awsExample")
    void awsExampleTableInReverseOrder(String method, String path, String expected) {
        assertEquals(expected, select(reversed(AWS_EXAMPLE_ROUTES), method, path));
    }

    static Stream<Arguments> orders() {
        return Stream.of(Arguments.of("as written"), Arguments.of("reversed"));
    }

    private static List<String> inOrder(String order, String... routeKeys) {
        List<String> keys = List.of(routeKeys);
        return "reversed".equals(order) ? reversed(keys) : keys;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orders")
    void longerGreedyPrefixBeatsShorterGreedyPrefix(String order) {
        List<String> routes = inOrder(order, "ANY /a/{proxy+}", "ANY /a/b/{x}/c/{proxy+}");
        assertEquals("ANY /a/b/{x}/c/{proxy+}", select(routes, "GET", "/a/b/1/c/d/e"));
        assertEquals("ANY /a/{proxy+}", select(routes, "GET", "/a/b/1/d/e"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orders")
    void nestedGreedyRouteWinsOverItsParentsGreedyRoute(String order) {
        // The shape that sent a nested request to the parent's function.
        List<String> routes = inOrder(order,
                "ANY /v1/admin/{proxy+}",
                "ANY /v1/admin/schools/{schoolId}/banks/{proxy+}",
                "ANY /v1/admin/schools/{schoolId}/banks");
        assertEquals("ANY /v1/admin/schools/{schoolId}/banks/{proxy+}",
                select(routes, "GET", "/v1/admin/schools/s1/banks/b1/draft"));
        assertEquals("ANY /v1/admin/schools/{schoolId}/banks",
                select(routes, "GET", "/v1/admin/schools/s1/banks"));
        assertEquals("ANY /v1/admin/{proxy+}",
                select(routes, "GET", "/v1/admin/schools/s1/members"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orders")
    void routeWithoutGreedyVariableBeatsGreedyRoute(String order) {
        List<String> routes = inOrder(order, "GET /{proxy+}", "ANY /items/{id}");
        assertEquals("ANY /items/{id}", select(routes, "GET", "/items/7"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orders")
    void definedMethodBeatsAnyForTheSamePath(String order) {
        List<String> routes = inOrder(order, "ANY /pets/{id}", "GET /pets/{id}");
        assertEquals("GET /pets/{id}", select(routes, "GET", "/pets/3"));
        assertEquals("ANY /pets/{id}", select(routes, "POST", "/pets/3"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orders")
    void literalSegmentBeatsPathVariableAtTheSamePosition(String order) {
        List<String> routes = inOrder(order, "GET /pets/{kind}/{id}", "GET /pets/dog/{id}");
        assertEquals("GET /pets/dog/{id}", select(routes, "GET", "/pets/dog/4"));
        assertEquals("GET /pets/{kind}/{id}", select(routes, "GET", "/pets/cat/4"));
    }

    @Test
    void defaultRouteCatchesWhatNoOtherRouteMatches() {
        assertEquals("$default", select(List.of("$default", "GET /pets/{id}"), "GET", "/owners/1"));
    }

    @Test
    void noMatchAndNoDefaultRouteSelectsNothing() {
        assertNull(select(List.of("GET /pets/{id}"), "GET", "/owners/1"));
    }

    @Test
    void methodMismatchIsNotAMatch() {
        Route r = serviceWithRoutes(List.of("POST /pets/{id}", "ANY /{proxy+}"))
                .findMatchingRoute(REGION, API_ID, "GET", "/pets/1");
        assertNotNull(r);
        assertEquals("ANY /{proxy+}", r.getRouteKey());
    }
}
