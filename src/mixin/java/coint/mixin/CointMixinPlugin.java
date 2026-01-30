package coint.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import cpw.mods.fml.common.Loader;

/**
 * Mixin plugin for CointCore.
 * Allows conditional mixin loading based on which mods are present.
 */
public class CointMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        // Called when the mixin config is loaded
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Check if the mixin should be applied based on mod presence

        // Example: Only apply GregTech mixins if GT is loaded
        if (mixinClassName.contains(".gregtech.")) {
            return isModLoaded("gregtech");
        }

        // Example: Only apply BetterQuesting mixins if BQ is loaded
        if (mixinClassName.contains(".betterquesting.")) {
            return isModLoaded("betterquesting");
        }

        // Example: Only apply AE2 mixins if AE2 is loaded
        if (mixinClassName.contains(".appliedenergistics2.")) {
            return isModLoaded("appliedenergistics2");
        }

        // Apply all other mixins
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // Called to allow the plugin to add/remove targets
    }

    @Override
    public List<String> getMixins() {
        // Return additional mixins to load dynamically
        List<String> mixins = new ArrayList<>();

        // Add BetterQuesting mixins if BQ is loaded
        if (isModLoaded("betterquesting")) {
            mixins.add("betterquesting.MixinPartyInstance");
        }

        return mixins;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Called before a mixin is applied
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Called after a mixin is applied
    }

    /**
     * Check if a mod is loaded
     */
    private boolean isModLoaded(String modId) {
        return Loader.isModLoaded(modId);
    }
}
