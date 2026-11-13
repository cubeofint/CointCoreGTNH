package coint.mixin.minecraft;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import coint.migration.MigrationGuard;

@Mixin(WorldServer.class)
public abstract class MixinWorldServerMigrationFreeze {

    @WrapOperation(
        method = { "func_147456_g()V", "tickUpdates(Z)Z",
            "scheduleBlockUpdateWithPriority(IIILnet/minecraft/block/Block;II)V" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/block/Block;updateTick(Lnet/minecraft/world/World;IIILjava/util/Random;)V"))
    private void cointcore$freezeTick(Block block, World world, int x, int y, int z, Random random,
        Operation<Void> original) {
        if (!MigrationGuard.block(world, x, z)) original.call(block, world, x, y, z, random);
    }
}
