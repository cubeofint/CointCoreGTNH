package coint.client.chat;

import java.lang.reflect.Field;

/** Follow the pack's existing Hodgepodge history/input limits, including when those tweaks are disabled. */
public final class ChatCompatibility {

    private static final Field LONGER_HISTORY = setting("longerChat");
    private static final Field HISTORY_LENGTH = setting("chatLength");
    private static final Field LONGER_INPUT = setting("longerSentMessages");

    private ChatCompatibility() {}

    private static Field setting(String name) {
        try {
            return Class.forName("com.mitchej123.hodgepodge.config.TweaksConfig")
                .getField(name);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static boolean enabled(Field field) {
        try {
            return field != null && field.getBoolean(null);
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    public static int historyLimit() {
        if (enabled(LONGER_HISTORY) && HISTORY_LENGTH != null) {
            try {
                return Math.max(100, Math.min(32767, HISTORY_LENGTH.getInt(null)));
            } catch (IllegalAccessException ignored) {}
        }
        return 100;
    }

    public static int inputLimit() {
        return enabled(LONGER_INPUT) ? 256 : 100;
    }
}
