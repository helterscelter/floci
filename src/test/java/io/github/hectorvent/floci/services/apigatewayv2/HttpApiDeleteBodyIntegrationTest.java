package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * A DELETE request's body reaches the Lambda integration. Each of the three execute entry points
 * ({@code /execute-api}, {@code /_aws/execute-api} and {@code /restapis/.../_user_request_}) took
 * no body for DELETE and dispatched it with none, so a payload format 2.0 event carried
 * {@code body: null} for a DELETE that sent one, while POST, PUT and PATCH carried theirs.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiDeleteBodyIntegrationTest {

    private static final String FUNCTION_NAME = "httpv2-delete-body-fn";
    private static final String JSON_BODY = "{\"grant\":\"g-123\"}";

    private static String apiId;

    @Test
    @Order(1)
    void setupLambdaAndHttpApi() throws Exception {
        String zip = WebSocketTestSupport.createLambdaZip("""
                exports.handler = async (event) => ({
                    statusCode: 200,
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        method: event.requestContext ? event.requestContext.http.method : null,
                        body: event.body === undefined ? null : event.body,
                        isBase64Encoded: event.isBase64Encoded
                    })
                });
                """);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(FUNCTION_NAME, zip))
                .when().post("/2015-03-31/functions")
                .then().statusCode(201);

        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"delete-body-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);

        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST","payloadFormatVersion":"2.0"}
                        """.formatted(FUNCTION_NAME))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"DELETE /grants/current","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/2015-03-31/functions/" + FUNCTION_NAME + "/invocations")
                .then().statusCode(200);
    }

    @Test
    @Order(10)
    void deleteBodyReachesTheLambdaEventThroughExecuteApi() {
        given()
                .contentType(ContentType.JSON)
                .body(JSON_BODY)
                .when().delete("/execute-api/" + apiId + "/test/grants/current")
                .then()
                .statusCode(200)
                .body("method", equalTo("DELETE"))
                .body("body", equalTo(JSON_BODY))
                .body("isBase64Encoded", equalTo(false));
    }

    @Test
    @Order(11)
    void deleteBodyReachesTheLambdaEventThroughAwsExecuteApi() {
        given()
                .contentType(ContentType.JSON)
                .body(JSON_BODY)
                .when().delete("/_aws/execute-api/" + apiId + "/test/grants/current")
                .then()
                .statusCode(200)
                .body("method", equalTo("DELETE"))
                .body("body", equalTo(JSON_BODY))
                .body("isBase64Encoded", equalTo(false));
    }

    @Test
    @Order(12)
    void deleteBodyReachesTheLambdaEventThroughUserRequest() {
        given()
                .contentType(ContentType.JSON)
                .body(JSON_BODY)
                .when().delete("/restapis/" + apiId + "/test/_user_request_/grants/current")
                .then()
                .statusCode(200)
                .body("method", equalTo("DELETE"))
                .body("body", equalTo(JSON_BODY))
                .body("isBase64Encoded", equalTo(false));
    }

    @Test
    @Order(13)
    void deleteWithoutBodyStillCarriesNullBody() {
        given()
                .when().delete("/execute-api/" + apiId + "/test/grants/current")
                .then()
                .statusCode(200)
                .body("method", equalTo("DELETE"))
                .body("body", nullValue())
                .body("isBase64Encoded", equalTo(false));
    }

    @Test
    @Order(999)
    void cleanup() {
        if (apiId != null) {
            given().when().delete("/v2/apis/" + apiId);
        }
        given().when().delete("/2015-03-31/functions/" + FUNCTION_NAME);
    }
}
