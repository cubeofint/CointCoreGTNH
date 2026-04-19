package coint.debug;

import java.util.HashMap;
import java.util.Map;

public final class BackpackSecurityInspector {

    private static final Object LOCK = new Object();
    private static final Map<String, Session> UID_TO_SESSION = new HashMap<>();
    private static final Map<String, String> PLAYER_TO_UID = new HashMap<>();

    private BackpackSecurityInspector() {}

    public static void onContainerAccess(String player, String uid) {
        if (!BackpackSecurityAudit.ENABLED) return;
        if (uid == null || uid.isEmpty() || "null".equals(uid) || "unknown".equals(uid)) {
            BackpackSecurityAudit.logEvent("ANOMALY_UID_MISSING", "player=" + player + " stage=container_access");
            return;
        }
        synchronized (LOCK) {
            Session current = UID_TO_SESSION.get(uid);
            if (current != null && !current.player.equals(player)) {
                BackpackSecurityAudit.logEvent(
                    "ANOMALY_UID_SHARED_ACTIVE",
                    "uid=" + uid
                        + " owner="
                        + current.player
                        + " contender="
                        + player
                        + " lastEvent="
                        + current.lastEvent);
            }
            UID_TO_SESSION.put(uid, new Session(player, "container_access"));
            PLAYER_TO_UID.put(player, uid);
        }
    }

    public static void onContainerClosed(String player, String uid) {
        if (!BackpackSecurityAudit.ENABLED) return;
        synchronized (LOCK) {
            Session current = UID_TO_SESSION.get(uid);
            if (current != null && current.player.equals(player)) {
                UID_TO_SESSION.remove(uid);
            }
            String mapped = PLAYER_TO_UID.get(player);
            if (uid != null && uid.equals(mapped)) {
                PLAYER_TO_UID.remove(player);
            }
        }
    }

    public static void onClick(String player, String uid, int slot, int button, int mode, String source) {
        if (!BackpackSecurityAudit.ENABLED) return;
        synchronized (LOCK) {
            Session current = UID_TO_SESSION.get(uid);
            if (current != null && !current.player.equals(player)) {
                BackpackSecurityAudit.logEvent(
                    "ANOMALY_CLICK_CROSS_SESSION",
                    "uid=" + uid
                        + " source="
                        + source
                        + " owner="
                        + current.player
                        + " actor="
                        + player
                        + " slot="
                        + slot
                        + " button="
                        + button
                        + " mode="
                        + mode);
            }
            UID_TO_SESSION.put(uid, new Session(player, source + ":slot=" + slot + ",mode=" + mode));
            PLAYER_TO_UID.put(player, uid);
        }
    }

    public static void onDirectDrop(String player, String uid, String source, String stack) {
        if (!BackpackSecurityAudit.ENABLED) return;
        if (uid == null || uid.isEmpty() || "null".equals(uid) || "unknown".equals(uid)) return;
        synchronized (LOCK) {
            Session current = UID_TO_SESSION.get(uid);
            if (current != null && current.player.equals(player)) {
                BackpackSecurityAudit.logEvent(
                    "ANOMALY_DIRECT_DROP_WHILE_OPEN",
                    "uid=" + uid + " player=" + player + " source=" + source + " stack=" + stack);
            }
        }
    }

    private static final class Session {

        private final String player;
        private final String lastEvent;

        private Session(String player, String lastEvent) {
            this.player = player;
            this.lastEvent = lastEvent;
        }
    }
}
