package coint.mixin.serverutilities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "serverutils.lib.data.Universe", remap = false)
public abstract class MixinUniverseDottedPlayerName {

    @Redirect(
        method = "load",
        at = @At(value = "INVOKE", target = "Ljava/lang/String;lastIndexOf(I)I", ordinal = 0),
        require = 1)
    private int cointcore$allowDottedPlayerDataName(String name, int character) {
        return name.indexOf(character);
    }
}
