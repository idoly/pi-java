package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.idoly.pi.core.PiActivity;
import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiClientState;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiClientConcurrencyTest {
    @Test
    void completesFiftyConcurrentClients() throws Exception {
        List<PiScriptedTransport> transports = new ArrayList<>();
        List<PiClient> clients = new ArrayList<>();
        List<PiActivity> activities = new ArrayList<>();
        try {
            for (int index = 0; index < 50; index++) {
                PiScriptedTransport transport = new PiScriptedTransport();
                PiClient client = PiClientFactory.createForTest(transport);
                transports.add(transport);
                clients.add(client);
                activities.add(client.prompt("prompt-" + index));
            }

            for (PiScriptedTransport transport : transports) {
                PiCommand command = transport.awaitCommand(Duration.ofSeconds(2));
                transport.emitInbound(success(command));
                transport.emitInbound(settled());
            }

            CompletableFuture<?>[] completions = activities.stream()
                    .map(PiActivity::completion)
                    .map(stage -> stage.toCompletableFuture())
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(completions).get(5, TimeUnit.SECONDS);

            assertEquals(50, clients.stream()
                    .filter(client -> client.state() == PiClientState.IDLE)
                    .count());
        } finally {
            clients.forEach(PiClient::close);
        }
    }

    private static PiResponse success(PiCommand command) {
        ObjectNode raw = JsonNodeFactory.instance.objectNode();
        return new PiResponse(command.id(), command.type(), true, raw, null, raw);
    }

    private static PiEvent settled() {
        ObjectNode raw = JsonNodeFactory.instance.objectNode().put("type", "agent_settled");
        return new PiEvent("agent_settled", raw);
    }
}
