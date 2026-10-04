package coint.performance;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

public final class ChunkLoadGuard {

    private ChunkLoadGuard() {}

    public static boolean isBlockChunkLoaded(World world, int blockX, int blockZ) {
        if (world == null || world.isRemote) {
            return true;
        }
        IChunkProvider provider = world.getChunkProvider();
        return provider != null && provider.chunkExists(blockX >> 4, blockZ >> 4);
    }

    public static Block getLoadedBlockOrAir(World world, int x, int y, int z) {
        return isBlockChunkLoaded(world, x, z) ? world.getBlock(x, y, z) : Blocks.air;
    }

    public static TileEntity getLoadedTileEntity(World world, int x, int y, int z) {
        return isBlockChunkLoaded(world, x, z) ? world.getTileEntity(x, y, z) : null;
    }
}
