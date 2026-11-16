package coint.client.chat;

import net.minecraft.util.IChatComponent;

import coint.chat.ChatChannel;

/** Client-only view of the channel attached to an original (unwrapped) history entry. */
public interface ChatHistoryTags {

    ChatChannel cointcore$channel(IChatComponent component);
}
