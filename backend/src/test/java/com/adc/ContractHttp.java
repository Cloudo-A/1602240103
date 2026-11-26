package com.adc;

import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@TestPropertySource("classpath:auth-test.properties")
abstract class ContractHttp {
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    final HttpClient client = HttpClient.newHttpClient();

    String token(String username) throws Exception {
        return api("POST", "/api/auth/login", null,
            Map.of("username", username, "password", "123456"), 200).get("token").asString();
    }
    JsonNode api(String method, String path, String token, Object body, int expectedStatus) throws Exception {
        return api(method, path, token, body, expectedStatus, null);
    }
    JsonNode api(String method, String path, String token, Object body, int expectedStatus, String requestId) throws Exception {
        String payload = body instanceof String raw ? raw : body == null ? null : json.writeValueAsString(body);
        var response = send(method, path, token, payload, requestId);
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(expectedStatus);
        return json.readTree(response.body());
    }
    HttpResponse<String> send(String method, String path, String token, String body, String requestId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (requestId != null) request.header("X-Request-Id", requestId);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
