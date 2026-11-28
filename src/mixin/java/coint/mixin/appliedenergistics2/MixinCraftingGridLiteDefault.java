package coint.mixin.appliedenergistics2;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants.NBT;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridStorage;
import appeng.me.cache.CraftingGridCache;
import appeng.util.Platform;
import coint.integration.appliedenergistics2.LiteCraftingMode;

@Mixin(value = CraftingGridCache.class, remap = false)
public abstract class MixinCraftingGridLiteDefault {

    @Unique
    private static final String cointcore$LITE_CHOICE = "CointCoreLiteCraftingChoice";

    @Shadow
    protected boolean liteCraftingDefault;

    @Unique
    private boolean cointcore$endgame;

    @Unique
    private Boolean cointcore$userChoice;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cointcore$setEndgameDefault(IGrid grid, CallbackInfo ci) {
        cointcore$endgame = Platform.isServer() && LiteCraftingMode.isEndgame();
        if (cointcore$endgame) liteCraftingDefault = true;
    }

    @Inject(method = "onJoin", at = @At("RETURN"))
    private void cointcore$loadChoice(IGridStorage storage, CallbackInfo ci) {
        if (!cointcore$endgame || storage == null) return;
        NBTTagCompound data = storage.dataObject();
        // AE2 also stored false for untouched networks. Only a recorded selection overrides the new default.
        if (cointcore$userChoice == null && data.hasKey(cointcore$LITE_CHOICE, NBT.TAG_BYTE)) {
            cointcore$userChoice = data.getBoolean(cointcore$LITE_CHOICE);
        }
        liteCraftingDefault = cointcore$userChoice == null || cointcore$userChoice;
    }

    @Inject(method = "setLiteCraftingDefault", at = @At("HEAD"))
    private void cointcore$recordChoice(boolean enabled, CallbackInfo ci) {
        if (cointcore$endgame) cointcore$userChoice = enabled;
    }

    @Inject(method = "populateGridStorage", at = @At("TAIL"))
    private void cointcore$saveChoice(IGridStorage storage, CallbackInfo ci) {
        if (cointcore$endgame && cointcore$userChoice != null) {
            storage.dataObject()
                .setBoolean(cointcore$LITE_CHOICE, cointcore$userChoice);
        }
    }
}
