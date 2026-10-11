package io.github.idoly.pi.protocol;

/**
 * Base64-encoded image content sent with a prompt.
 *
 * @param data base64 image data without a data URL prefix
 * @param mimeType image media type, for example {@code image/png}
 */
public record PiImage(String data, String mimeType) {
    public PiImage {
        if (data == null || data.isBlank()) throw new IllegalArgumentException("Image data must not be blank");
        if (mimeType == null || mimeType.isBlank()) throw new IllegalArgumentException("Image MIME type must not be blank");
    }

}
