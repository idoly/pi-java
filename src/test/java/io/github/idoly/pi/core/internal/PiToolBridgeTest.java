package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.idoly.pi.core.tool.PiTool;
import io.github.idoly.pi.core.tool.PiToolParameter;
import io.github.idoly.pi.core.tool.PiToolRegistrationException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiToolBridgeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void invokesAnnotatedJavaMethodThroughGeneratedExtensionBridge() throws Exception {
        PiToolBridge bridge = PiToolBridge.open(List.of(new BuildTools()));
        Path extensionFile = bridge.extensionFile();
        try {
            assertTrue(Files.readString(extensionFile).contains("pi.registerTool"));
            assertTrue(Files.readString(extensionFile).contains("buildId"));

            String baseUrl = bridge.environment().get("PI_JAVA_TOOL_BASE_URL");
            String token = bridge.environment().get("PI_JAVA_TOOL_TOKEN");
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/tools/query_build_status"))
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"buildId\":\"build-7\"}"))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = mapper.readTree(response.body());

            assertEquals(200, response.statusCode());
            assertEquals("build-7", body.path("result").path("buildId").asText());
            assertEquals("SUCCESS", body.path("result").path("status").asText());
        } finally {
            bridge.close();
        }
        assertFalse(Files.exists(extensionFile));
    }

    @Test
    void rejectsUnauthorizedInvocation() throws Exception {
        try (PiToolBridge bridge = PiToolBridge.open(List.of(new BuildTools()))) {
            String baseUrl = bridge.environment().get("PI_JAVA_TOOL_BASE_URL");
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/tools/query_build_status"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
        }
    }

    @Test
    void rejectsInvalidSignaturesBeforeStartingPi() {
        assertThrows(PiToolRegistrationException.class, () ->
                PiToolBridge.open(List.of(new DuplicateBuildTools(), new BuildTools())));
        assertThrows(PiToolRegistrationException.class, () ->
                PiToolBridge.open(List.of(new OptionalPrimitiveTool())));
        assertThrows(PiToolRegistrationException.class, () ->
                PiToolBridge.open(List.of(new NonStringMapKeyTool())));
        assertThrows(PiToolRegistrationException.class, () ->
                PiToolBridge.open(List.of(new AsyncTool())));
        assertThrows(PiToolRegistrationException.class, () ->
                PiToolBridge.open(List.of(new BeanTool())));
    }

    @Test
    void validatesArgumentsAndSanitizesBusinessExceptions() throws Exception {
        try (PiToolBridge bridge = PiToolBridge.open(List.of(new FailureTools()))) {
            HttpResponse<String> unknownArgumentResponse = invoke(
                    bridge, "always_fails", "{\"unexpected\":true}");
            assertEquals(400, unknownArgumentResponse.statusCode());
            assertEquals("Unknown argument: unexpected",
                    mapper.readTree(unknownArgumentResponse.body()).path("error").asText());

            HttpResponse<String> businessFailureResponse = invoke(
                    bridge, "always_fails", "{}");
            assertEquals(500, businessFailureResponse.statusCode());
            assertEquals("Java Tool execution failed",
                    mapper.readTree(businessFailureResponse.body()).path("error").asText());
            assertFalse(businessFailureResponse.body().contains("database-password"));
        }
    }

    private static HttpResponse<String> invoke(
            PiToolBridge bridge,
            String toolName,
            String argumentsJson
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        bridge.environment().get("PI_JAVA_TOOL_BASE_URL") + "/tools/" + toolName))
                .header("content-type", "application/json")
                .header("authorization", "Bearer "
                        + bridge.environment().get("PI_JAVA_TOOL_TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(argumentsJson))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    static final class BuildTools {
        @PiTool(
                name = "query_build_status",
                label = "Build status",
                description = "Query a build from the Java application"
        )
        public BuildStatus query(
                @PiToolParameter(name = "buildId", description = "Build identifier") String buildId
        ) {
            return new BuildStatus(buildId, "SUCCESS");
        }
    }

    static final class DuplicateBuildTools {
        @PiTool(name = "query_build_status", description = "Duplicate")
        public String query(@PiToolParameter(name = "buildId") String buildId) {
            return buildId;
        }
    }

    static final class OptionalPrimitiveTool {
        @PiTool(name = "optional_primitive", description = "Invalid optional primitive")
        public int invoke(@PiToolParameter(name = "count", required = false) int count) {
            return count;
        }
    }

    static final class NonStringMapKeyTool {
        @PiTool(name = "non_string_map", description = "Invalid map key")
        public int invoke(@PiToolParameter(name = "values") Map<Integer, String> values) {
            return values.size();
        }
    }

    static final class AsyncTool {
        @PiTool(name = "async_tool", description = "Unsupported asynchronous result")
        public CompletionStage<String> invoke() {
            return CompletableFuture.completedFuture("done");
        }
    }

    static final class BeanTool {
        @PiTool(name = "bean_tool", description = "Unsupported Bean parameter")
        public String invoke(@PiToolParameter(name = "input") BeanInput input) {
            return input.value;
        }
    }

    static final class BeanInput {
        public String value;
    }

    static final class FailureTools {
        @PiTool(name = "always_fails", description = "Always fails")
        public String invoke() {
            throw new IllegalStateException("database-password");
        }
    }

    record BuildStatus(String buildId, String status) {
    }
}
