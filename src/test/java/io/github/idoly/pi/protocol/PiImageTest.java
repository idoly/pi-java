package io.github.idoly.pi.protocol;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PiImageTest {
    @Test
    void rejectsMissingContentAndMimeTypeAtInputBoundary() {
        for (String invalid : Arrays.asList(null, "", " \n\t")) {
            assertThrows(IllegalArgumentException.class, () -> new PiImage(invalid, "image/png"));
            assertThrows(IllegalArgumentException.class, () -> new PiImage("YWJj", invalid));
        }
    }
}
