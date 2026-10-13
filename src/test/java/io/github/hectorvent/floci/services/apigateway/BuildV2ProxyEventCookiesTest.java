package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Payload format 2.0 carries the request's cookies in a {@code cookies} array, not in a
 * {@code cookie} header, and turns each entry of a response's {@code cookies} array into its own
 * {@code Set-Cookie} header. See "Payload format differences" and the format 2.0 example in
 * https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html.
 * The Lambda authorizer receives the same 2.0 request shape (see the format 2.0 example in
 * https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html),
 * so every request case runs against both builders.
 */
class BuildV2ProxyEventCookiesTest {

    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private MultivaluedMap<String, String> requestHeaders;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() throws Exception {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        requestHeaders = new MultivaluedHashMap<>();
        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/echo"));

        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null);
    }

    enum Builder { INTEGRATION, AUTHORIZER }

    private JsonNode buildEvent(Builder builder) throws Exception {
        String json = switch (builder) {
            case INTEGRATION -> controller.buildV2ProxyEvent(
                    "GET", "/echo", "GET /echo",
                    "abc123", "us-east-1", "$default", headers, uriInfo, null, "req-1");
            case AUTHORIZER -> controller.buildRequestAuthorizerEventV2(
                    "GET", "/echo", "GET /echo",
                    "abc123", "$default", "us-east-1", headers, uriInfo);
        };
        return new ObjectMapper().readTree(json);
    }

    @ParameterizedTest
    @EnumSource(Builder.class)
    void cookieHeaderBecomesCookiesArrayAndLeavesHeaders(Builder builder) throws Exception {
        requestHeaders.add("Cookie", "a=1; b=2");
        requestHeaders.add("X-Trace", "kept");

        JsonNode event = buildEvent(builder);

        assertEquals("[\"a=1\",\"b=2\"]", String.valueOf(event.get("cookies")));
        assertFalse(event.get("headers").has("cookie"));
        assertEquals("kept", event.get("headers").get("x-trace").asText());
    }

    @ParameterizedTest
    @EnumSource(Builder.class)
    void everyCookieHeaderContributesToCookiesArray(Builder builder) throws Exception {
        requestHeaders.addAll("cookie", List.of("a=1; b=2", "c=3"));

        JsonNode event = buildEvent(builder);

        assertEquals("[\"a=1\",\"b=2\",\"c=3\"]", String.valueOf(event.get("cookies")));
        assertFalse(event.get("headers").has("cookie"));
    }

    @ParameterizedTest
    @EnumSource(Builder.class)
    void requestWithoutCookiesOmitsCookiesField(Builder builder) throws Exception {
        requestHeaders.add("X-Trace", "kept");

        assertFalse(buildEvent(builder).has("cookies"));
    }

    @Test
    void responseCookiesBecomeOneSetCookieHeaderEach() {
        InvokeResult result = new InvokeResult(
                200,
                null,
                """
                {
                  "statusCode": 200,
                  "cookies": ["a=1; Path=/; HttpOnly", "b=2; Path=/; Secure"],
                  "body": "{}"
                }
                """.getBytes(StandardCharsets.UTF_8),
                null,
                "req-1");

        try (Response response = controller.buildProxyResponse(result, true)) {
            assertEquals(
                    List.of("a=1; Path=/; HttpOnly", "b=2; Path=/; Secure"),
                    response.getStringHeaders().get(HttpHeaders.SET_COOKIE));
        }
    }
}
