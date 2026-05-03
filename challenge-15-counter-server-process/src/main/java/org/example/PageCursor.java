package org.example;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque cursor for keyset pagination over counters.
 *
 * On the wire: a base64-encoded string. Internally: (createdAt, counterId) — the
 * composite ordering key that uniquely identifies a row's position in the
 * (created_at DESC, counter_id DESC) ordering.
 *
 * Opaque means: clients are not supposed to parse or construct cursors themselves.
 * They receive a cursor in one response and send it back in the next request. This
 * lets us change the internal encoding without breaking clients.
 */
public record PageCursor(long createdAt, String counterId) {

    private static final String SEP = "::";

    /** Encodes to a URL-safe base64 string suitable for use as a query parameter. */
    public String encode() {
        String raw = createdAt + SEP + counterId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes from a wire-format cursor. Throws IllegalArgumentException on malformed input. */
    public static PageCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int sep = raw.indexOf(SEP);
            if (sep < 0) throw new IllegalArgumentException("invalid cursor: missing separator");
            long createdAt = Long.parseLong(raw.substring(0, sep));
            String counterId = raw.substring(sep + SEP.length());
            if (counterId.isEmpty()) throw new IllegalArgumentException("invalid cursor: empty counterId");
            return new PageCursor(createdAt, counterId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cursor", e);
        }
    }
}
