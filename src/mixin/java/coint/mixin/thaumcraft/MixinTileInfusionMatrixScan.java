package coint.mixin.thaumcraft;

import java.util.ArrayList;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChunkCoordinates;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.CointConfig;
import coint.CointCore;
import coint.integration.thaumcraft.InfusionSurroundings;
import thaumcraft.common.tiles.TileInfusionMatrix;

@Mixin(value = TileInfusionMatrix.class, remap = false)
public abstract class MixinTileInfusionMatrixScan {

    @Shadow(remap = false)
    private ArrayList<ChunkCoordinates> pedestals;

    @Shadow(remap = false)
    public int symmetry;

    @Unique
    private long cointcore$lastScanTick = Long.MIN_VALUE;

    @Unique
    private boolean cointcore$forceScan;

    @Unique
    private boolean cointcore$scanFailed;

    @Inject(method = "craftingStart", at = @At("HEAD"), remap = false)
    private void cointcore$scanWhenCraftStarts(EntityPlayer player, CallbackInfo ci) {
        this.cointcore$forceScan = true;
    }

    @Inject(method = "getSurroundings", at = @At("HEAD"), cancellable = true, remap = false)
    private void cointcore$replaceSurroundingsScan(CallbackInfo ci) {
        TileInfusionMatrix matrix = (TileInfusionMatrix) (Object) this;
        if (matrix.getWorldObj() == null || matrix.getWorldObj().isRemote || !CointConfig.infusion.fastSurroundings) {
            return;
        }

        long now = matrix.getWorldObj()
            .getTotalWorldTime();
        int interval = Math.max(1, CointConfig.infusion.surroundingsIntervalTicks);
        boolean due = this.cointcore$forceScan || this.cointcore$lastScanTick == Long.MIN_VALUE
            || now - this.cointcore$lastScanTick >= interval;
        this.cointcore$forceScan = false;
        if (!due) {
            ci.cancel();
            return;
        }

        ci.cancel();
        this.cointcore$lastScanTick = now;
        try {
            InfusionSurroundings.Scan scan = InfusionSurroundings
                .scan(matrix.getWorldObj(), matrix.xCoord, matrix.yCoord, matrix.zCoord);
            this.pedestals.clear();
            this.pedestals.addAll(scan.pedestals);
            this.symmetry = scan.symmetry;
        } catch (Exception ex) {
            if (!this.cointcore$scanFailed) {
                this.cointcore$scanFailed = true;
                CointCore.LOG.warn(
                    "[CointCore] Infusion surroundings scan failed at {}, {}, {}",
                    matrix.xCoord,
                    matrix.yCoord,
                    matrix.zCoord,
                    ex);
            }
        }
    }
}
