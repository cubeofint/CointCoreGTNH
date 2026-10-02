package coint.client;

public final class ClientRestartState {

    private static volatile boolean active;
    private static volatile int secondsRemaining;
    private static volatile String phase = "";
    private static volatile long updatedAt;

    private ClientRestartState() {}

    public static void update(boolean activeNow, int seconds, String phaseNow) {
        active = activeNow;
        secondsRemaining = Math.max(0, seconds);
        phase = phaseNow == null ? "" : phaseNow;
        updatedAt = System.currentTimeMillis();
    }

    public static boolean isActive() {
        if (active && System.currentTimeMillis() - updatedAt > 5000L) {
            active = false;
        }
        return active;
    }

    public static int getSecondsRemaining() {
        return secondsRemaining;
    }

    public static String getPhase() {
        return phase;
    }
}
