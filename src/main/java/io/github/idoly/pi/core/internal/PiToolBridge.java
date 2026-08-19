package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.idoly.pi.core.tool.PiTool;
import io.github.idoly.pi.core.tool.PiToolRegistrationException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class PiToolBridge implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final String BASE_URL_ENV = "PI_JAVA_TOOL_BASE_URL";
    private static final String TOKEN_ENV = "PI_JAVA_TOOL_TOKEN";

    private final ObjectMapper mapper;
    private final Map<String, PiToolDefinition> tools;
    private final String token;
    private final HttpServer server;
    private final Path extensionFile;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PiToolBridge(
            ObjectMapper mapper,
            Map<String, PiToolDefinition> tools,
            String token,
            HttpServer server,
            Path extensionFile
    ) {
        this.mapper = mapper;
        this.tools = tools;
        this.token = token;
        this.server = server;
        this.extensionFile = extensionFile;
    }

    static PiToolBridge open(List<Object> toolInstances) {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, PiToolDefinition> tools = discoverTools(toolInstances, mapper);
        if (tools.isEmpty()) {
            throw new PiToolRegistrationException(
                    "Tool instances do not contain any public @PiTool methods");
        }

        HttpServer server = null;
        Path extensionFile = null;
        try {
            String token = generateToken();
            server = HttpServer.create(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setExecutor(PiThreads.executor());
            extensionFile = createExtensionFile(tools, mapper);
            PiToolBridge bridge = new PiToolBridge(
                    mapper, tools, token, server, extensionFile);
            server.createContext("/tools/", bridge::handleRequest);
            server.start();
            return bridge;
        } catch (Exception cause) {
            if (server != null) {
                server.stop(0);
            }
            deleteQuietly(extensionFile);
            if (cause instanceof PiToolRegistrationException registrationFailure) {
                throw registrationFailure;
            }
            throw new PiToolRegistrationException(
                    "Failed to start embedded Java Tool bridge", cause);
        }
    }

    Path extensionFile() {
        return extensionFile;
    }

    Map<String, String> environment() {
        return Map.of(
                BASE_URL_ENV, "http://127.0.0.1:" + server.getAddress().getPort(),
                TOKEN_ENV, token);
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Only POST is allowed");
                return;
            }
            String authorizationHeader =
                    exchange.getRequestHeaders().getFirst("Authorization");
            String providedToken = authorizationHeader != null
                    && authorizationHeader.startsWith("Bearer ")
                    ? authorizationHeader.substring(7) : "";
            if (!MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8),
                    providedToken.getBytes(StandardCharsets.UTF_8))) {
                sendError(exchange, 401, "Unauthorized");
                return;
            }

            String requestPath = exchange.getRequestURI().getRawPath();
            String toolName = requestPath.substring("/tools/".length());
            PiToolDefinition tool = tools.get(toolName);
            if (tool == null) {
                sendError(exchange, 404, "Unknown Tool");
                return;
            }

            byte[] requestBody;
            try {
                requestBody = readRequestBody(exchange.getRequestBody());
            } catch (PiToolCallException failure) {
                sendError(exchange, 413, failure.getMessage());
                return;
            }
            JsonNode toolArguments;
            try {
                toolArguments = mapper.readTree(requestBody);
            } catch (Exception ignored) {
                sendError(exchange, 400, "Invalid JSON arguments");
                return;
            }
            try {
                JsonNode toolResult = tool.invoke(toolArguments, mapper);
                ObjectNode payload = mapper.createObjectNode();
                payload.set("result", toolResult);
                sendJson(exchange, 200, payload);
            } catch (PiToolCallException failure) {
                sendError(exchange, 400, failure.getMessage());
            } catch (Exception ignored) {
                sendError(exchange, 500, "Java Tool execution failed");
            }
        }
    }

    private void sendError(
            HttpExchange exchange,
            int statusCode,
            String message
    ) throws IOException {
        sendJson(exchange, statusCode, mapper.createObjectNode().put("error", message));
    }

    private void sendJson(HttpExchange exchange, int statusCode, JsonNode payload) throws IOException {
        byte[] body = mapper.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
    }

    private static byte[] readRequestBody(InputStream inputStream)
            throws IOException, PiToolCallException {
        byte[] body = inputStream.readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new PiToolCallException("Tool request exceeds " + MAX_REQUEST_BYTES + " bytes");
        }
        return body;
    }

    private static Map<String, PiToolDefinition> discoverTools(List<Object> toolInstances, ObjectMapper mapper) {
        Map<String, PiToolDefinition> discoveredTools = new TreeMap<>();
        for (Object toolInstance : toolInstances) {
            for (java.lang.reflect.Method method : toolInstance.getClass().getMethods()) {
                if (!method.isAnnotationPresent(PiTool.class)) {
                    continue;
                }
                PiToolDefinition tool = PiToolDefinition.create(toolInstance, method, mapper);
                PiToolDefinition previous = discoveredTools.putIfAbsent(tool.name(), tool);
                if (previous != null) {
                    throw new PiToolRegistrationException(
                            "Duplicate @PiTool name: " + tool.name());
                }
            }
        }
        return Collections.unmodifiableMap(discoveredTools);
    }

    private static Path createExtensionFile(
            Map<String, PiToolDefinition> tools,
            ObjectMapper mapper
    ) throws IOException {
        ArrayNode serializedTools = mapper.createArrayNode();
        for (PiToolDefinition tool : tools.values()) {
            ObjectNode serializedTool = serializedTools.addObject();
            serializedTool.put("name", tool.name());
            serializedTool.put("label", tool.label());
            serializedTool.put("description", tool.description());
            serializedTool.set("parameters", tool.schema());
        }
        String toolsJson = mapper.writeValueAsString(serializedTools);
        String sourceCode = """
                import { Unsafe } from "typebox";

                const tools = %s;

                export default function (pi: any) {
                  for (const definition of tools) {
                    pi.registerTool({
                      name: definition.name,
                      label: definition.label,
                      description: definition.description,
                      parameters: Unsafe(definition.parameters),
                      async execute(_toolCallId: string, params: unknown, signal?: AbortSignal) {
                        const baseUrl = process.env.%s;
                        const token = process.env.%s;
                        if (!baseUrl || !token) {
                          throw new Error("Java Tool bridge is not configured");
                        }
                        const response = await fetch(
                          `${baseUrl}/tools/${encodeURIComponent(definition.name)}`,
                          {
                            method: "POST",
                            signal,
                            headers: {
                              "content-type": "application/json",
                              "authorization": `Bearer ${token}`
                            },
                            body: JSON.stringify(params)
                          }
                        );
                        const payload = await response.json();
                        if (!response.ok) {
                          throw new Error(payload.error || `Java Tool failed (${response.status})`);
                        }
                        const text = typeof payload.result === "string"
                          ? payload.result
                          : JSON.stringify(payload.result);
                        return {
                          content: [{ type: "text", text }],
                          details: payload.result
                        };
                      }
                    });
                  }
                }
                """.formatted(toolsJson, BASE_URL_ENV, TOKEN_ENV);
        Path extensionFile = Files.createTempFile("pi-java-tools-", ".ts");
        Files.writeString(extensionFile, sourceCode, StandardCharsets.UTF_8);
        return extensionFile.toRealPath();
    }

    private static String generateToken() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        deleteQuietly(extensionFile);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
