package coint.mixin.appliedenergistics2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.parts.automation.PartBaseExportBus;
import appeng.parts.automation.PartExportBus;
import appeng.util.prioitylist.OreFilteredList;

/**
 * An ore-filtered export bus walks the whole network item list and runs the filter on every entry each operation.
 * Remember which item types matched and only re-walk the list every {@link #REFRESH_TICKS} ticks or when the filter
 * changes; between refreshes only the remembered types are exported. A new matching type is picked up on the next
 * refresh.
 */
@Mixin(value = PartExportBus.class, remap = false)
public abstract class MixinPartExportBusOreCache extends PartBaseExportBus<IAEItemStack> {

    @Unique
    private static final int REFRESH_TICKS = 100;

    @Unique
    private List<IAEItemStack> cointcore$oreCandidates;
    @Unique
    private String cointcore$oreCandidatesFilter;
    @Unique
    private int cointcore$oreCandidatesExpire;

    private MixinPartExportBusOreCache() {
        super((ItemStack) null);
    }

    @Inject(method = "doOreDict", at = @At("HEAD"), cancellable = true)
    private void cointcore$cachedOreDict(IEnergyGrid energy, IMEMonitor<IAEItemStack> gridInv, CallbackInfo ci) {
        if (oreFilterString.isEmpty() || gridInv == null) {
            return;
        }
        if (filterPredicate == null) {
            filterPredicate = OreFilteredList.makeFilter(oreFilterString);
        }
        Predicate<IAEItemStack> predicate = filterPredicate;
        if (predicate == null) {
            return;
        }
        ci.cancel();

        int now = MinecraftServer.getServer()
            .getTickCounter();
        if (cointcore$oreCandidates == null || !oreFilterString.equals(cointcore$oreCandidatesFilter)
            || now - cointcore$oreCandidatesExpire >= 0) {
            List<IAEItemStack> matched = new ArrayList<>();
            for (IAEItemStack stack : gridInv.getStorageList()) {
                if (stack != null && predicate.test(stack)) {
                    matched.add(stack.copy());
                }
            }
            cointcore$oreCandidates = matched;
            cointcore$oreCandidatesFilter = oreFilterString;
            cointcore$oreCandidatesExpire = now + REFRESH_TICKS;
        }

        IItemList<IAEItemStack> available = gridInv.getStorageList();
        for (IAEItemStack stack : cointcore$oreCandidates) {
            IAEItemStack current = available.findPrecise(stack);
            if (current == null || current.getStackSize() <= 0) {
                continue; // no longer stored; skip the target simulation
            }
            this.pushItemIntoTarget(energy, gridInv, current);
            if (this.itemToSend <= 0) {
                break;
            }
        }
    }
}
