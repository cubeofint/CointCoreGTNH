package coint.mixin.appliedenergistics2;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.config.FuzzyMode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;
import appeng.parts.automation.PartBaseImportBus;
import coint.CointCore;

/** The bus's while loop must consume its remaining budget or stop; successful work keeps its full speed. */
@Mixin(value = PartBaseImportBus.class, remap = false)
public abstract class MixinImportBusProgressGuard {

    @Shadow
    protected int itemToSend;

    @Unique
    private long cointcore$nextNoProgressWarning;

    @WrapOperation(
        method = "doBusWork",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/parts/automation/PartBaseImportBus;importStuff(Ljava/lang/Object;Lappeng/api/storage/data/IAEStack;Lappeng/api/storage/IMEMonitor;Lappeng/api/networking/energy/IEnergySource;Lappeng/api/config/FuzzyMode;)Z"),
        require = 2)
    private boolean cointcore$stopWithoutProgress(PartBaseImportBus<?> bus, Object target, IAEStack<?> stack,
        IMEMonitor<?> inventory, IEnergySource energy, FuzzyMode fuzzy, Operation<Boolean> original) {
        int before = itemToSend;
        boolean stop = original.call(bus, target, stack, inventory, energy, fuzzy);
        if (stop || before <= 0 || itemToSend < before) return stop;
        long now = System.nanoTime();
        if (now - cointcore$nextNoProgressWarning >= 0) {
            cointcore$nextNoProgressWarning = now + 60_000_000_000L;
            TileEntity tile = bus.getHost() == null ? null
                : bus.getHost()
                    .getTile();
            CointCore.LOG.warn(
                "[AE2 Import] Stopped a no-progress import loop: dimension={}, x={}, y={}, z={}, remaining={} -> {}",
                tile == null || tile.getWorldObj() == null ? "unknown" : tile.getWorldObj().provider.dimensionId,
                tile == null ? "?" : tile.xCoord,
                tile == null ? "?" : tile.yCoord,
                tile == null ? "?" : tile.zCoord,
                before,
                itemToSend);
        }
        return true;
    }
}
