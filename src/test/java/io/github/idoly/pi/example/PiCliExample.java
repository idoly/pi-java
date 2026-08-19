package io.github.idoly.pi.example;

import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiResult;

import java.nio.file.Path;
import java.util.Arrays;

/** Minimal command-line example built on the public pi-java API. */
public final class PiCliExample {
    private PiCliExample() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: PiCliExample <workspace> <prompt...>");
            System.exit(2);
        }

        Path workspace = Path.of(args[0]);
        String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        try (PiClient client = PiClient.open(workspace)) {
            PiResult result = client.prompt(prompt)
                    .completion()
                    .toCompletableFuture()
                    .join();
            System.out.println(result.text());
        }
    }
}
