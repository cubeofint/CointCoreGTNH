package coint.mixin.minecraft;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraftforge.common.chunkio.QueuedChunk", remap = false)
public interface AccessorQueuedMigrationChunk {

    @Accessor("world")
    World cointcore$world();

    @Accessor("x")
    int cointcore$x();

    @Accessor("z")
    int cointcore$z();
}
