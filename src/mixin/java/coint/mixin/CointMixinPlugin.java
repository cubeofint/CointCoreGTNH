package coint.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin plugin for CointCore.
 * Allows conditional mixin loading based on which mods are present.
 *
 * <p>
 * NOTE: {@code shouldApplyMixin} is invoked during the Mixin PREPARE phase — well before
 * FML has finished discovering mods. At that point {@code Loader.instance().namedMods} is
 * still {@code null}, so {@code Loader.isModLoaded()} throws a NullPointerException.
 * Instead we check whether the mixin's <em>target class</em> is actually present on the
 * classpath: if the target cannot be loaded the mixin must not be applied anyway.
 */
public class CointMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // For every optional-mod mixin package, guard by checking whether the
        // target class (= a class that only exists when the mod is installed)
        // is present on the classpath.

        if (mixinClassName.contains(".gregtech.")) {
            return isClassAvailable(targetClassName);
        }
        if (mixinClassName.contains(".betterquesting.")) {
            return isClassAvailable(targetClassName);
        }
        if (mixinClassName.contains(".galacticraft.")) {
            return isClassAvailable(targetClassName);
        }
        if (mixinClassName.contains(".serverutilities.")) {
            return isClassAvailable(targetClassName);
        }
        if (mixinClassName.contains(".appliedenergistics2.")) {
            return isClassAvailable(targetClassName);
        }
        if (mixinClassName.contains(".thaumcraft.")) {
            return isClassAvailable(targetClassName);
        }
        if (mixinClassName.contains(".dreamcraft.")) {
            return isClassAvailable(targetClassName);
        }
        if (mixinClassName.contains(".mattermanipulator.")) {
            return isClassAvailable(targetClassName);
        }
        if (mixinClassName.contains(".forestry.")) {
            return isClassAvailable(targetClassName);
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        // All mixins are declared in mixins.cointcore.json; nothing to add dynamically.
        return new ArrayList<>();
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    /**
     * Returns {@code true} if {@code className} is present on the classpath.
     *
     * <p>
     * Uses {@link ClassLoader#getResource} instead of {@link Class#forName} so that
     * the class is <em>never actually loaded</em>. Loading a class during the Mixin
     * PREPARE phase triggers other transformers (e.g. CoFH AT), which re-enter the
     * Mixin transformer and cause a {@code ReEntrantTransformerError} that breaks
     * unrelated early mixins (e.g. ArchaicFix's {@code MixinEntity}).
     */
    private boolean isClassAvailable(String className) {
        // Convert binary class name to resource path: dots → slashes, keep '$' as-is.
        String resourcePath = className.replace('.', '/') + ".class";
        return getClass().getClassLoader()
            .getResource(resourcePath) != null;
    }
}
