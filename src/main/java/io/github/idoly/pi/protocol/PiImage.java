package io.github.idoly.pi.protocol;

import java.util.Objects;

/**
 * Base64-encoded image content sent with a prompt.
 *
 * @param data base64 image data without a data URL prefix
 * @param mimeType image media type, for example {@code image/png}
 */
public record PiImage(String data, String mimeType) {
    public PiImage {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(mimeType, "mimeType");
    }

}
