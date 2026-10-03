package coint.mixin.serverutilities;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;
import serverutils.ServerUtilitiesConfig;

@Mixin(value = ServerUtilitiesConfig.WorldConfig.class, remap = false)
public abstract class MixinWorldConfigDisabledRightClick {

    @Shadow
    public String[] disabled_right_click_items;

    @Inject(method = "isItemRightClickDisabled", at = @At("HEAD"), cancellable = true)
    private void cointcore$guardNullAndModWildcard(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack == null || stack.getItem() == null) {
            cir.setReturnValue(false);
            return;
        }

        UniqueIdentifier id = GameRegistry.findUniqueIdentifierFor(stack.getItem());
        if (id == null || id.modId == null || disabled_right_click_items == null) return;

        for (String raw : disabled_right_click_items) {
            if (raw == null) continue;

            String entry = raw.trim();
            if (entry.isEmpty()) continue;

            String[] parts = entry.split("@", 2);
            String key = parts[0].trim();
            int colon = key.indexOf(':');
            if (colon <= 0 || colon == key.length() - 1) continue;
            if (!"*".equals(
                key.substring(colon + 1)
                    .trim()))
                continue;
            if (!id.modId.equalsIgnoreCase(
                key.substring(0, colon)
                    .trim()))
                continue;

            if (parts.length == 1 || parts[1].trim()
                .startsWith("*")) {
                cir.setReturnValue(true);
                return;
            }

            try {
                if (stack.getItemDamage() == Integer.parseInt(parts[1].trim())) {
                    cir.setReturnValue(true);
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }
    }
}
