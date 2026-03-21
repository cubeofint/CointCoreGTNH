package coint.commands.temprank;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import coint.util.TimeUtil;

/**
 * Immutable record of a single temporary rank assignment.
 */
public class TempRankEntry {

    /** UUID of the player who received the rank. */
    public final UUID playerUuid;
    /** Last-known display name, stored for legibility in logs and /trank list. */
    public final String playerName;
    /** The SU rank id (e.g. {@code "helper"}). */
    public final String rankId;
    /**
     * Unix epoch in milliseconds when the rank expires.
     * {@code -1} means permanent (the entry is kept in storage so it can be
     * revoked with {@code /trank remove}, but it will never expire on its own).
     */
    public final long expiresAt;
    /** Name of the command sender who issued the rank. */
    public final String issuer;

    public TempRankEntry(UUID playerUuid, String playerName, String rankId, long expiresAt, String issuer) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.rankId = rankId;
        this.expiresAt = expiresAt;
        this.issuer = issuer;
    }

    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    /** Human-readable remaining time, or "навсегда" for permanent entries. */
    public String remainingTime() {
        if (expiresAt < 0) return "навсегда";
        long remaining = expiresAt - System.currentTimeMillis();
        if (remaining <= 0) return "истёк";
        return TimeUtil.formatDuration(remaining);
    }

    /** Formatted expiry date in GMT+3, or "навсегда". */
    public String expireDate() {
        if (expiresAt < 0) return "навсегда";
        ZonedDateTime zdt = Instant.ofEpochMilli(expiresAt)
            .atZone(TimeUtil.getZone());
        return zdt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
    }
}
