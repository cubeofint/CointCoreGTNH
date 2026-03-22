package coint.integration.nilcord;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayerMP;

import coint.CointCore;

/**
 * Reflection-based bridge to Nilcord's Discord integration.
 *
 * <p>
 * {@link net.minecraftforge.event.ServerChatEvent} is canceled by
 * {@link coint.commands.chat.ChatSplitHandler} before Nilcord's own
 * {@code MFEvents.onServerChat} handler can observe it (Nilcord registers with
 * the default {@code receiveCanceled = false}). This class bypasses the event
 * pipeline entirely and calls {@code EventListener.playerChatMessage()} directly
 * via reflection so that only <em>global</em> chat messages reach Discord.
 *
 * <p>
 * Nilcord is an optional dependency: if the mod is absent the bridge silently
 * does nothing. Resolution is lazy and cached after the first call.
 */
public final class NilcordBridge {

    /** Resolved {@code NilcordPremain.listener} (type {@code EventListener}), or {@code null}. */
    private static Object listener;

    /** Resolved {@code EventListener#playerChatMessage(EntityPlayerMP, String)}, or {@code null}. */
    private static Method playerChatMessage;

    /** Set to {@code true} after the first resolution attempt. */
    private static volatile boolean resolved = false;

    private NilcordBridge() {}

    /**
     * Forwards {@code message} to Discord via Nilcord.
     *
     * <p>
     * Must only be called for <em>global</em> chat messages (prefix already stripped).
     * No-op if Nilcord is not installed or its API has changed.
     *
     * @param sender  the player who sent the global message
     * @param message raw message text, <b>without</b> the global prefix ({@code !})
     */
    public static void forwardGlobalChat(EntityPlayerMP sender, String message) {
        if (!resolve()) return;
        try {
            playerChatMessage.invoke(listener, sender, message);
        } catch (Exception e) {
            CointCore.LOG.warn("[NilcordBridge] Failed to forward global chat to Discord: {}", e.getMessage());
        }
    }

    /**
     * Lazily resolves Nilcord's reflection targets on first use.
     *
     * @return {@code true} if Nilcord is available and targets were resolved
     */
    private static boolean resolve() {
        if (resolved) return listener != null;
        resolved = true;

        try {
            Class<?> premain = Class.forName("cc.unilock.nilcord.NilcordPremain");

            Field listenerField = premain.getDeclaredField("listener");
            listenerField.setAccessible(true);
            Object resolvedListener = listenerField.get(null);

            if (resolvedListener == null) {
                // Called too early — listener not yet assigned by FMLInitializationEvent.
                CointCore.LOG
                    .warn("[NilcordBridge] NilcordPremain.listener is null; Nilcord may not have initialised yet.");
                resolved = false; // allow retry on next chat message
                return false;
            }

            // Commit atomically only when everything resolved successfully
            playerChatMessage = resolvedListener.getClass()
                .getMethod("playerChatMessage", EntityPlayerMP.class, String.class);
            listener = resolvedListener;
            CointCore.LOG.info("[NilcordBridge] Nilcord Discord integration linked successfully.");

        } catch (ClassNotFoundException e) {
            // Nilcord not installed — expected, stay silent
        } catch (Exception e) {
            CointCore.LOG.warn("[NilcordBridge] Could not link Nilcord integration: {}", e.getMessage());
        }

        return listener != null;
    }
}
