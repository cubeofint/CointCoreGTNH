package coint.mixin.appliedenergistics2;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import appeng.api.config.FuzzyMode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.parts.automation.PartBaseImportBus;
import appeng.parts.automation.PartImportBus;
import appeng.util.InventoryAdaptor;
import appeng.util.Platform;
import appeng.util.inv.AdaptorIInventory;

/** Keep the bus's energy-limited budget, without simulating the same network once per 64 items. */
@Mixin(value = PartImportBus.class, remap = false)
public abstract class MixinPartImportBusBulkTransfer extends PartBaseImportBus<IAEItemStack> {

    @Unique
    private IInventory cointcore$bulkSource;
    @Unique
    private List<ItemStack> cointcore$oversizedSourceTypes;

    private MixinPartImportBusBulkTransfer() {
        super(null);
    }

    @WrapMethod(
        method = "importStuff(Ljava/lang/Object;Lappeng/api/storage/data/IAEItemStack;Lappeng/api/storage/IMEMonitor;Lappeng/api/networking/energy/IEnergySource;Lappeng/api/config/FuzzyMode;)Z")
    private boolean cointcore$scopeBulkTransfer(Object target, IAEItemStack filter, IMEMonitor<IAEItemStack> inventory,
        IEnergySource energy, FuzzyMode fuzzy, Operation<Boolean> original) {
        IInventory previousSource = cointcore$bulkSource;
        List<ItemStack> previousTypes = cointcore$oversizedSourceTypes;
        cointcore$bulkSource = null;
        cointcore$oversizedSourceTypes = null;
        try {
            return original.call(target, filter, inventory, energy, fuzzy);
        } finally {
            cointcore$bulkSource = previousSource;
            cointcore$oversizedSourceTypes = previousTypes;
        }
    }

    @ModifyConstant(method = "calculateMaximumAmountToImport", constant = @Constant(intValue = 64), require = 1)
    private int cointcore$useTransferBudget(int original, InventoryAdaptor adaptor, IAEItemStack filter,
        IMEMonitor<IAEItemStack> inventory, FuzzyMode fuzzy) {
        // Custom adaptors may impose their own stack-sized contract; leave them unchanged.
        if (adaptor.getClass() != AdaptorIInventory.class || itemToSend <= original) return original;
        cointcore$bulkSource = ((AccessorAdaptorIInventory) adaptor).cointcore$getInventory();
        for (int slot = 0; slot < cointcore$bulkSource.getSizeInventory(); slot++) {
            ItemStack stack = cointcore$bulkSource.getStackInSlot(slot);
            if (stack != null && !cointcore$bulkSource.isItemValidForSlot(slot, stack)) {
                // An extract-only source cannot accept a refused bulk remainder back. Retain its
                // original stack-sized transfers instead of increasing the amount at risk.
                cointcore$bulkSource = null;
                cointcore$oversizedSourceTypes = null;
                return original;
            }
            if (stack != null && stack.stackSize > stack.getMaxStackSize()) {
                if (cointcore$oversizedSourceTypes == null) cointcore$oversizedSourceTypes = new ArrayList<>();
                ItemStack type = stack.copy();
                type.stackSize = 1;
                cointcore$oversizedSourceTypes.add(type);
            }
        }
        // doBusWork has already limited itemToSend by the installed upgrades and available energy.
        // The original simulation still limits this amount by source stock and destination space.
        return itemToSend;
    }

    @WrapOperation(
        method = "importStuff(Ljava/lang/Object;Lappeng/api/storage/data/IAEItemStack;Lappeng/api/storage/IMEMonitor;Lappeng/api/networking/energy/IEnergySource;Lappeng/api/config/FuzzyMode;)Z",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(DD)D"),
        require = 1)
    private double cointcore$preserveExtractedItems(double removed, double powered, Operation<Double> original) {
        // poweredInsert checks energy again and returns every uninserted item. Truncating an already
        // extracted stack here would delete its unpaid portion if power changed during extraction.
        return cointcore$bulkSource == null ? original.call(removed, powered) : removed;
    }

    @WrapOperation(
        method = "importStuff(Ljava/lang/Object;Lappeng/api/storage/data/IAEItemStack;Lappeng/api/storage/IMEMonitor;Lappeng/api/networking/energy/IEnergySource;Lappeng/api/config/FuzzyMode;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/util/InventoryAdaptor;addItems(Lnet/minecraft/item/ItemStack;)Lnet/minecraft/item/ItemStack;"),
        require = 1)
    private ItemStack cointcore$returnOversizedRemainder(InventoryAdaptor adaptor, ItemStack returned,
        Operation<ItemStack> original) {
        ItemStack remaining = original.call(adaptor, returned);
        if (remaining == null || cointcore$bulkSource == null || cointcore$oversizedSourceTypes == null)
            return remaining;
        for (ItemStack type : cointcore$oversizedSourceTypes) {
            if (Platform.isSameItemPrecise(type, remaining)) {
                // This item already occupied an oversized source stack before extraction. Restore it
                // up to the inventory's own limit, keeping its slot validation and dirty notifications.
                return new AdaptorIInventory(cointcore$bulkSource, Integer.MAX_VALUE).addItems(remaining);
            }
        }
        return remaining;
    }
}
