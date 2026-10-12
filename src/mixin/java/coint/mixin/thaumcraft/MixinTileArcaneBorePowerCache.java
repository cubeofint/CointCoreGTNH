package coint.mixin.thaumcraft;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import thaumcraft.common.tiles.TileArcaneBore;

/**
 * {@code gettingPower()} polls the redstone power of the bore and its base several times every tick. Reuse the
 * answer for {@link #CACHE_TICKS} ticks; the bore reacts to a redstone change within half a second.
 */
@Mixin(value = TileArcaneBore.class, remap = false)
public abstract class MixinTileArcaneBorePowerCache {

    @Unique
    private static final int CACHE_TICKS = 10;

    @Unique
    private long cointcore$powerCheckedAt = Long.MIN_VALUE;

    @Unique
    private boolean cointcore$powered;

    @WrapMethod(method = "gettingPower", require = 0)
    private boolean cointcore$cachedGettingPower(Operation<Boolean> original) {
        TileEntity self = (TileEntity) (Object) this;
        if (self.getWorldObj() == null || self.getWorldObj().isRemote) {
            return original.call();
        }
        long now = self.getWorldObj()
            .getTotalWorldTime();
        if (now - cointcore$powerCheckedAt >= CACHE_TICKS || now < cointcore$powerCheckedAt) {
            cointcore$powered = original.call();
            cointcore$powerCheckedAt = now;
        }
        return cointcore$powered;
    }
}
