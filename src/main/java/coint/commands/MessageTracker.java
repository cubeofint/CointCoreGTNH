package coint.commands;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import io.netty.util.internal.ConcurrentSet;

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
public final class MessageTracker {

    private static final Map<String, String> LAST_CONTACT = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> IGNORE = new ConcurrentHashMap<>();

    private MessageTracker() {}

    /**
     * Records a two-way contact: {@code senderName}→{@code targetName} and the reverse.
     * Call this after a whisper has been successfully delivered.
     */
    public static void setReply(String senderName, String targetName) {
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

    public static void toggleIgnore(String sender, String target) {
        var i = IGNORE.get(sender);
        if (i == null) {
            i = new ConcurrentSet<>();
            i.add(target);
            return;
        }

        if (i.contains(target)) {
            i.remove(target);
            return;
        }

        i.add(target);
    }

    // true if ignore exist at any side
    public static boolean checkIgnore(String sender, String target) {
        boolean result = true;

        var i = IGNORE.get(sender);
        if (i == null) result = false;
        result = result && i.contains(target);

        i = IGNORE.get(target);
        if (i == null) result = false;
        result = result && i.contains(target);

        return result;
    }
}
