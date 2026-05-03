package org.example;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Cursor for keyset pagination across SHARDS.
 *
 * The single-shard PageCursor encodes (createdAt, counterId) — one position in
 * one Postgres. With sharding, "position" is N positions, one per shard. Each
 * shard is independently advanced as the client pages forward.
 *
 * On the wire, this is a base64-encoded string of N pipe-separated parts:
 *
 *     <cursor0>|<cursor1>|<cursor2>
 *
 * where each <cursorI> is either empty (this shard is exhausted) or a single
 * PageCursor's encoded value (where to resume in this shard).
 */
public record ShardedPageCursor(List<Optional<PageCursor>> shardCursors) {

    private static final String SEP = "|";

    /** Build a cursor for the FIRST page — every shard starts at the beginning. */
    public static ShardedPageCursor firstPage(int shardCount) {
        List<Optional<PageCursor>> empty = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) empty.add(Optional.empty());
        return new ShardedPageCursor(empty);
    }

    /** Has every shard run out of rows? */
    public boolean isExhausted() {
        return shardCursors.stream().allMatch(Optional::isEmpty);
    }

    /** Encode as a URL-safe base64 string. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shardCursors.size(); i++) {
            if (i > 0) sb.append(SEP);
            // Empty marker for exhausted shards. Otherwise inline the inner
            // PageCursor's base64 (yes, base64 inside base64 — it's fine, the
            // outer encoder is whitespace/sep-safe).
            sb.append(shardCursors.get(i).map(PageCursor::encode).orElse(""));
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Decode a wire-format cursor. Throws IllegalArgumentException on malformed input. */
    public static ShardedPageCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            // -1 keeps trailing empty fields (an exhausted final shard).
            String[] parts = raw.split("\\" + SEP, -1);
            List<Optional<PageCursor>> shardCursors = new ArrayList<>();
            for (String part : parts) {
                shardCursors.add(part.isEmpty() ? Optional.empty() : Optional.of(PageCursor.decode(part)));
            }
            return new ShardedPageCursor(shardCursors);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cursor", e);
        }
    }
}
