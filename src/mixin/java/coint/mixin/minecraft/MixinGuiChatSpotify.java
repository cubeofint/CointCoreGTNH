package coint.mixin.minecraft;

import net.minecraft.client.gui.GuiChat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.client.spotify.ClientSpotify;

@Mixin(GuiChat.class)
public class MixinGuiChatSpotify {

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void cointcore$spotifyDraw(int x, int y, float partialTick, CallbackInfo ci) {
        ClientSpotify.drawChat((GuiChat) (Object) this, x, y);
    }

    @Inject(method = "handleMouseInput", at = @At("HEAD"), cancellable = true)
    private void cointcore$spotifyMouse(CallbackInfo ci) {
        if (ClientSpotify.chatMouse((GuiChat) (Object) this)) ci.cancel();
    }

    @Inject(method = "onGuiClosed", at = @At("HEAD"))
    private void cointcore$spotifyClose(CallbackInfo ci) {
        ClientSpotify.chatClosed();
    }
}
