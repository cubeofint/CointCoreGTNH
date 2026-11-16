package coint.mixin.minecraft;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.chat.ChatChannel;
import coint.client.chat.ClientChatChannels;

@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChatChannels implements coint.client.chat.ChatHistoryTags {

    @Shadow
    @Final
    private List<ChatLine> chatLines;

    @Shadow
    public abstract void deleteChatLine(int id);

    @Unique
    private final Map<IChatComponent, ChatChannel> cointcore$channels = new IdentityHashMap<>();

    @Inject(method = "func_146237_a", at = @At("HEAD"), cancellable = true)
    private void cointcore$filter(IChatComponent component, int id, int time, boolean refresh, CallbackInfo ci) {
        ChatChannel channel = refresh ? cointcore$channels.get(component) : ClientChatChannels.pending();
        if (!refresh) {
            if (channel != null) cointcore$channels.put(component, channel);
            else cointcore$channels.remove(component);
        }
        if (!ClientChatChannels.active() || ClientChatChannels.view()
            .shows(channel)) return;
        if (id != 0) deleteChatLine(id);
        // Save the complete original component: wrapping later must retain click/hover events and original timestamps.
        if (!refresh) {
            chatLines.add(0, new ChatLine(time, component, id));
            while (chatLines.size() > coint.client.chat.ChatCompatibility.historyLimit())
                chatLines.remove(chatLines.size() - 1);
            cointcore$prune();
        }
        ci.cancel();
    }

    @Inject(method = "func_146237_a", at = @At("RETURN"))
    private void cointcore$trimTags(IChatComponent component, int id, int time, boolean refresh, CallbackInfo ci) {
        if (!refresh) cointcore$prune();
    }

    @Unique
    private void cointcore$prune() {
        if (cointcore$channels.size() <= coint.client.chat.ChatCompatibility.historyLimit() + 100) return;
        Set<IChatComponent> live = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ChatLine line : chatLines) live.add(line.func_151461_a());
        cointcore$channels.keySet()
            .retainAll(live);
    }

    @Override
    public ChatChannel cointcore$channel(IChatComponent component) {
        return cointcore$channels.get(component);
    }

    @Inject(method = "clearChatMessages", at = @At("RETURN"))
    private void cointcore$clearTags(CallbackInfo ci) {
        cointcore$channels.clear();
    }
}
