package coint.mixin.minecraft;

import net.minecraft.client.gui.GuiScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.client.GuiLiteCraftingConfirm;

@Mixin(GuiScreen.class)
public abstract class MixinGuiScreenLiteCraftingConfirmation {

    @Inject(method = "handleInput", at = @At("HEAD"), cancellable = true)
    private void cointcore$handleLiteCraftingConfirmation(CallbackInfo ci) {
        if (GuiLiteCraftingConfirm.handleInputFor((GuiScreen) (Object) this)) ci.cancel();
    }
}
