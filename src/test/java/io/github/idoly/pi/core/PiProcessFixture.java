package io.github.idoly.pi.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.idoly.pi.core.PiConnection.Request;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Controls a child JVM; SDK traffic crosses its stdin/stdout pipes. */
final class PiProcessFixture implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ServerSocket server;
    private final PiProcess process;
    private final BlockingQueue<Request> commands = new LinkedBlockingQueue<>();
    private final CompletableFuture<Socket> connected = new CompletableFuture<>();
    private volatile PrintWriter control;

    PiProcessFixture() { this(false); }

    PiProcessFixture(boolean holdOnClose) {
        try {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            server.setSoTimeout(10000);
            String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
            process = new PiProcess(new PiProcess.Config(List.of(java, "-cp", System.getProperty("java.class.path"),
                    PiProcessStub.class.getName(), "controlled", Integer.toString(server.getLocalPort()), Boolean.toString(holdOnClose)),
                    Path.of(System.getProperty("user.dir")), Map.of(), 256, 32 * 1024 * 1024, 500, null));
            Thread reader = new Thread(this::readCommands, "pi-test-control");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    PiProcess process() { return process; }

    PiClient openClient() {
        PiClient client = new PiClient(new PiConnection(process, 15000, "test"),
                PiConfig.builder().build(), ignored -> {});
        try {
            client.start().toCompletableFuture().join();
            awaitReady();
            return client;
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    void awaitReady() {
        try { connected.get(10, TimeUnit.SECONDS); }
        catch (Exception failure) { close(); throw new IllegalStateException("Test child did not connect", failure); }
    }

    Request awaitCommand(long timeoutSeconds) throws InterruptedException {
        Request command = commands.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (command == null) throw new AssertionError("Timed out waiting for outbound RPC command");
        return command;
    }

    synchronized void emitInbound(Object record) {
        ObjectNode message;
        if (record instanceof PiEvent event) message = (ObjectNode) event.raw();
        else if (record instanceof PiResponse response) {
            message = mapper.createObjectNode().put("type", "response").put("id", response.requestId())
                    .put("command", response.command()).put("success", response.success());
            if (response.data() != null) message.set("data", response.data());
            if (response.error() != null) message.put("error", response.error());
        } else throw new IllegalArgumentException("Unknown record");
        send(mapper.createObjectNode().set("emit", message));
    }

    synchronized void exitProcess(int code) { send(mapper.createObjectNode().put("exit", code)); }

    @Override public void close() {
        try { process.close(); }
        finally {
            if (connected.isDone() && !connected.isCompletedExceptionally()) {
                try { connected.join().close(); } catch (IOException ignored) {}
            }
            try { server.close(); } catch (IOException ignored) {}
        }
    }

    private void readCommands() {
        try (Socket socket = server.accept();
             BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            control = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            connected.complete(socket);
            String line;
            while ((line = input.readLine()) != null) {
                ObjectNode arguments = (ObjectNode) mapper.readTree(line);
                String id = arguments.path("id").asText();
                String type = arguments.path("type").asText();
                arguments.remove(List.of("id", "type"));
                commands.add(new Request(id, type, mapper.convertValue(arguments, new TypeReference<Map<String, Object>>() {})));
            }
        } catch (IOException failure) { connected.completeExceptionally(failure); }
        finally { try { server.close(); } catch (IOException ignored) {} }
    }

    private void send(ObjectNode message) {
        awaitReady();
        control.println(message);
        if (control.checkError()) throw new AssertionError("Failed to send test control record to child JVM");
    }
}
