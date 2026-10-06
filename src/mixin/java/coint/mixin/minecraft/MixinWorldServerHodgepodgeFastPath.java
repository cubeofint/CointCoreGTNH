package coint.mixin.minecraft;

import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = WorldServer.class, priority = 100)
public abstract class MixinWorldServerHodgepodgeFastPath {
}
