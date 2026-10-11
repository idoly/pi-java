package io.github.idoly.pi.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PiProcessStub {
    private PiProcessStub() {
    }

    private static void runControlled(int port, boolean holdOnClose) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            PrintWriter commands = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            Thread stdin = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) commands.println(line);
                    if (!holdOnClose) System.exit(0);
                } catch (Exception failure) { System.exit(2); }
            }, "test-stdin");
            stdin.setDaemon(true); stdin.start();
            ObjectMapper mapper = new ObjectMapper();
            PrintWriter stdout = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
            try (BufferedReader control = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = control.readLine()) != null) {
                    JsonNode request = mapper.readTree(line);
                    if (request.has("exit")) System.exit(request.path("exit").asInt());
                    if (request.has("emit")) stdout.println(request.get("emit"));
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("controlled")) {
            runControlled(Integer.parseInt(args[1]), Boolean.parseBoolean(args[2]));
            return;
        }
        if (args.length > 0 && args[0].equals("sleep")) {
            System.out.println("{\"type\":\"ready\"}");
            System.out.flush();
            Thread.sleep(30000);
            return;
        }
        if (args.length > 0 && args[0].equals("bad-tree")) {
            String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    PiProcessStub.class.getName(), "sleep")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
            System.out.println("not-json");
            System.out.flush();
            Thread.sleep(30000);
            return;
        }
        if (args.length > 0 && args[0].equals("huge-stderr")) {
            System.err.println("x".repeat(100000));
            System.err.flush();
        }
        ObjectMapper mapper = new ObjectMapper();
        PrintWriter stdout = new PrintWriter(new OutputStreamWriter(
                System.out, StandardCharsets.UTF_8), true);
        PrintWriter stderr = new PrintWriter(new OutputStreamWriter(
                System.err, StandardCharsets.UTF_8), true);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode command = mapper.readTree(line);
                String response = mapper.createObjectNode()
                        .put("id", command.path("id").asText())
                        .put("type", "response")
                        .put("command", command.path("type").asText())
                        .put("success", true)
                        .set("data", mapper.createObjectNode().put("value", "a\u2028b\u2029c"))
                        .toString();
                if (args.length > 0 && args[0].equals("last-response")) {
                    stdout.println("{\"type\":\"pause\"}");
                    reader.readLine();
                    stdout.println(response);
                    return;
                }
                int split = response.length() / 2;
                stdout.print(response.substring(0, split));
                stdout.flush();
                Thread.sleep(5);
                stdout.println(response.substring(split));
                stdout.flush();
                stderr.println("handled " + command.path("type").asText());
            }
        }
    }
}
