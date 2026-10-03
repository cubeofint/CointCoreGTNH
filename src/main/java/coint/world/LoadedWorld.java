package coint.world;

import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

/**
 * Chunk and tile lookups that never load or generate a chunk.
 * {@code World.getTileEntity} and {@code World.getBlock} both load missing chunks, which is the expensive part of a
 * wide altar or essentia scan inside a GregTech base.
 */
public final class LoadedWorld {

    private LoadedWorld() {}

    public static boolean chunkLoaded(World world, int blockX, int blockZ) {
        if (world == null) {
            return false;
        }
        IChunkProvider provider = world.getChunkProvider();
        return provider != null && provider.chunkExists(blockX >> 4, blockZ >> 4);
    }

    public static Block getBlock(World world, int x, int y, int z) {
        if (world == null || y < 0 || y > 255 || !chunkLoaded(world, x, z)) {
            return null;
        }
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        return chunk.getBlock(x & 15, y, z & 15);
    }

    public static TileEntity getTile(World world, int x, int y, int z) {
        if (world == null || y < 0 || y > 255 || !chunkLoaded(world, x, z)) {
            return null;
        }
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        return findTile(chunk, x, y, z);
    }

    public static void forEachTile(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
        Consumer<TileEntity> consumer) {
        if (world == null || consumer == null) {
            return;
        }
        int y0 = Math.max(0, minY);
        int y1 = Math.min(255, maxY);
        if (y0 > y1 || minX > maxX || minZ > maxZ) {
            return;
        }
        IChunkProvider provider = world.getChunkProvider();
        if (provider == null) {
            return;
        }
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!provider.chunkExists(chunkX, chunkZ)) {
                    continue;
                }
                Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
                visitTiles(chunk, minX, y0, minZ, maxX, y1, maxZ, consumer);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static TileEntity findTile(Chunk chunk, int x, int y, int z) {
        Map tiles = chunk.chunkTileEntityMap;
        if (tiles == null || tiles.isEmpty()) {
            return null;
        }
        Object direct = tiles.get(new ChunkPosition(x & 15, y, z & 15));
        if (direct instanceof TileEntity tile && tile.xCoord == x
            && tile.yCoord == y
            && tile.zCoord == z
            && !tile.isInvalid()) {
            return tile;
        }
        for (Object value : tiles.values()) {
            if (!(value instanceof TileEntity tile)) {
                continue;
            }
            if (!tile.isInvalid() && tile.xCoord == x && tile.yCoord == y && tile.zCoord == z) {
                return tile;
            }
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    private static void visitTiles(Chunk chunk, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
        Consumer<TileEntity> consumer) {
        Map tiles = chunk.chunkTileEntityMap;
        if (tiles == null || tiles.isEmpty()) {
            return;
        }
        Object[] values = tiles.values()
            .toArray();
        for (Object value : values) {
            if (!(value instanceof TileEntity tile) || tile.isInvalid()) {
                continue;
            }
            int x = tile.xCoord;
            int y = tile.yCoord;
            int z = tile.zCoord;
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
                continue;
            }
            consumer.accept(tile);
        }
    }
}
