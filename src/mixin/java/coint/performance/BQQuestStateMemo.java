package coint.performance;

import java.util.IdentityHashMap;
import java.util.UUID;

/**
 * Per-call memo for {@code QuestCache.updateCache}. That method walks the whole quest database (~3.5k quests in GTNH)
 * for a player and asks {@code isUnlocked}/{@code isComplete} for every quest and again for its prerequisites while
 * deciding visibility. Nothing changes quest state during the walk, so each answer is computed once per quest.
 *
 * <p>
 * Only active on the thread that started the walk and only for the player being walked.
 */
public final class BQQuestStateMemo {

    private static Thread owner;
    private static UUID player;
    private static int depth;
    private static final IdentityHashMap<Object, Boolean> UNLOCKED = new IdentityHashMap<>(4096);
    private static final IdentityHashMap<Object, Boolean> COMPLETE = new IdentityHashMap<>(4096);

    private BQQuestStateMemo() {}

    public static void begin(UUID uuid) {
        Thread current = Thread.currentThread();
        if (depth > 0 && owner != current) {
            return; // another thread is walking; leave it alone and run uncached
        }
        if (depth == 0) {
            owner = current;
            player = uuid;
            UNLOCKED.clear();
            COMPLETE.clear();
        }
        depth++;
    }

    public static void end() {
        if (owner != Thread.currentThread() || depth == 0) {
            return;
        }
        if (--depth == 0) {
            owner = null;
            player = null;
            UNLOCKED.clear();
            COMPLETE.clear();
        }
    }

    private static boolean active(UUID uuid) {
        return depth > 0 && owner == Thread.currentThread() && uuid != null && uuid.equals(player);
    }

    public static Boolean getUnlocked(Object quest, UUID uuid) {
        return active(uuid) ? UNLOCKED.get(quest) : null;
    }

    public static void putUnlocked(Object quest, UUID uuid, boolean value) {
        if (active(uuid)) {
            UNLOCKED.put(quest, value);
        }
    }

    public static Boolean getComplete(Object quest, UUID uuid) {
        return active(uuid) ? COMPLETE.get(quest) : null;
    }

    public static void putComplete(Object quest, UUID uuid, boolean value) {
        if (active(uuid)) {
            COMPLETE.put(quest, value);
        }
    }
}
