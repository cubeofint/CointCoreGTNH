package coint.commands;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * Tracks the last DM contact for each player so that {@code /reply} knows who to address.
 *
 * <p>
 * When player A messages player B, both A→B and B→A entries are recorded so that either party
 * can use {@code /r} to continue the conversation.
 *
 * <p>
 * Uses {@link ConcurrentHashMap} because entries may be read/written from different threads
 * (Netty IO thread vs. server tick thread).
 */
public final class ReplyTracker {

    private static final Map<String, String> LAST_CONTACT = new ConcurrentHashMap<>();

    private ReplyTracker() {}

    /**
     * Records a two-way contact: {@code senderName}→{@code targetName} and the reverse.
     * Call this after a whisper has been successfully delivered.
     */
    public static void record(String senderName, String targetName) {
        LAST_CONTACT.put(senderName, targetName);
        LAST_CONTACT.put(targetName, senderName);
    }

    /**
     * Returns the name of the last player who was in a DM conversation with {@code playerName},
     * or {@code null} if no conversation has been recorded yet.
     */
    @Nullable
    public static String getReplyTarget(String playerName) {
        return LAST_CONTACT.get(playerName);
    }

    /**
     * Removes the reply entry for {@code playerName}. Call on player disconnect to free memory
     * and avoid stale entries.
     */
    public static void remove(String playerName) {
        LAST_CONTACT.remove(playerName);
    }
}
