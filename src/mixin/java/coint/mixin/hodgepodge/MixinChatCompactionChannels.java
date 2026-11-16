package coint.mixin.hodgepodge;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.client.chat.ChatHistoryTags;
import coint.client.chat.ClientChatChannels;

@Pseudo
@Mixin(targets = "com.mitchej123.hodgepodge.client.chat.ChatHandler", remap = false)
public class MixinChatCompactionChannels {

    @Inject(method = "tryCompactMessage", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cointcore$keepChannelsSeparate(IChatComponent component, List<ChatLine> history,
        CallbackInfoReturnable<Boolean> cir) {
        if (!ClientChatChannels.active() || history.isEmpty()) return;
        var channel = ClientChatChannels.pending();
        var tags = (ChatHistoryTags) Minecraft.getMinecraft().ingameGUI.getChatGUI();
        if (!ClientChatChannels.view()
            .shows(channel) || channel
                != tags.cointcore$channel(
                    history.get(0)
                        .func_151461_a()))
            cir.setReturnValue(false);
    }
}
