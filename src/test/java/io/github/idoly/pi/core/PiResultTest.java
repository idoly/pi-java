package io.github.idoly.pi.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiResultTest {
    @Test
    void incompleteAndUnknownStopReasonsAreNotSuccessful() {
        assertFalse(result(null, false).successful());
        for (String reason : List.of("pending", "error", "length", "deferred", "aborted", "future_reason")) {
            assertFalse(result(reason, false).successful(), reason);
        }
    }

    @Test
    void successfulTerminalReasonsStillRespectSessionAbort() {
        for (String reason : List.of("stop", "toolUse")) {
            assertTrue(result(reason, false).successful(), reason);
            assertFalse(result(reason, true).successful(), reason);
        }
    }

    @Test
    void handledInputNeedsNoAssistantButMustNotBeAborted() {
        assertTrue(new PiResult("", List.of(), false, "handled", null, null).successful());
        assertFalse(new PiResult("", List.of(), true, "handled", null, null).successful());
    }

    private static PiResult result(String reason, boolean aborted) {
        return new PiResult("text", List.of(), aborted, "started", reason, null);
    }
}
