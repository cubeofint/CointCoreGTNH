package coint.tasks;

import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.event.ServerChatEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Suppresses vanilla "joined the game" and "left the game" messages.
 * Listening to chat events and filtering out system messages.
 */
public class ChatMessageFilter {

    private static final Logger LOG = LogManager.getLogger(ChatMessageFilter.class);

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        // Check if this is a system message (from server, not from a player)
        if (event.component instanceof ChatComponentTranslation) {
            @SuppressWarnings({ "cast", "RedundantCast" })
            ChatComponentTranslation trans = (ChatComponentTranslation) event.component;
            String key = trans.getKey();

            // Block join and leave messages
            if ("multiplayer.player.joined".equals(key) || "multiplayer.player.left".equals(key)) {
                event.setCanceled(true);
                LOG.debug("[CointCore] Suppressed vanilla player join/leave message");
            }
        }
    }
}
