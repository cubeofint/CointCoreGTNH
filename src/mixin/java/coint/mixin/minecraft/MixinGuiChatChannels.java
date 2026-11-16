package coint.mixin.minecraft;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.chat.ChatChannel;
import coint.client.chat.ClientChatChannels;

@Mixin(GuiChat.class)
public class MixinGuiChatChannels {

    @Shadow
    protected GuiTextField inputField;

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void cointcore$tabs(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ClientChatChannels.render((GuiChat) (Object) this);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cointcore$click(int x, int y, int button, CallbackInfo ci) {
        if (ClientChatChannels.click((GuiChat) (Object) this, x, y, button)) {
            cointcore$inputLimit();
            ci.cancel();
        }
    }

    @Inject(method = "initGui", at = @At("RETURN"))
    private void cointcore$init(CallbackInfo ci) {
        cointcore$inputLimit();
    }

    @org.spongepowered.asm.mixin.Unique
    private void cointcore$inputLimit() {
        // Reserve space for !/$ while retaining the pack's longer-message tweak.
        ChatChannel.View view = ClientChatChannels.view();
        int limit = coint.client.chat.ChatCompatibility.inputLimit();
        inputField.setMaxStringLength(
            ClientChatChannels.active() && (view == ChatChannel.View.GLOBAL || view == ChatChannel.View.TRADE)
                ? limit - 1
                : limit);
    }

    @Inject(method = "func_146403_a", at = @At("HEAD"), cancellable = true)
    private void cointcore$privateHint(String text, CallbackInfo ci) {
        if (ClientChatChannels.blockPrivate(text)) {
            ClientChatChannels.privateHint();
            ci.cancel();
        }
    }

    @ModifyVariable(method = "func_146403_a", at = @At("HEAD"), argsOnly = true)
    private String cointcore$sendChannel(String text) {
        return ClientChatChannels.route(text);
    }
}
