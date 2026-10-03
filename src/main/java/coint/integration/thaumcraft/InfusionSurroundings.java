package coint.integration.thaumcraft;

import java.util.ArrayList;
import java.util.HashSet;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import coint.world.LoadedWorld;
import thaumcraft.api.crafting.IInfusionStabiliser;
import thaumcraft.common.tiles.TilePedestal;

/**
 * Same pedestal and stabiliser sets as {@code TileInfusionMatrix.getSurroundings}, without calling
 * {@code World.getTileEntity} on every cell. That call loads chunks and hashes the tile map once per block.
 */
public final class InfusionSurroundings {

    private static final int HORIZONTAL_RADIUS = 12;
    private static final int PEDESTAL_RADIUS = 8;
    private static final int Y_BELOW = 10;
    private static final int Y_ABOVE = 5;

    private InfusionSurroundings() {}

    public static final class Scan {

        public final ArrayList<ChunkCoordinates> pedestals = new ArrayList<>();
        public final int symmetry;

        private Scan(int symmetry) {
            this.symmetry = symmetry;
        }
    }

    public static Scan scan(World world, int originX, int originY, int originZ) {
        ChunkCoordinates[] columnPedestal = new ChunkCoordinates[17 * 17];
        int[] columnDistance = new int[17 * 17];
        LoadedWorld.forEachTile(
            world,
            originX - PEDESTAL_RADIUS,
            originY - Y_BELOW,
            originZ - PEDESTAL_RADIUS,
            originX + PEDESTAL_RADIUS,
            originY - 1,
            originZ + PEDESTAL_RADIUS,
            tile -> considerPedestal(originX, originY, originZ, tile, columnPedestal, columnDistance));

        ArrayList<ChunkCoordinates> pedestals = new ArrayList<>();
        HashSet<Long> pedestalCells = new HashSet<>();
        for (int dx = -PEDESTAL_RADIUS; dx <= PEDESTAL_RADIUS; dx++) {
            for (int dz = -PEDESTAL_RADIUS; dz <= PEDESTAL_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ChunkCoordinates pedestal = columnPedestal[(dx + PEDESTAL_RADIUS) * 17 + (dz + PEDESTAL_RADIUS)];
                if (pedestal == null) {
                    continue;
                }
                pedestals.add(pedestal);
                pedestalCells.add(pack(pedestal.posX, pedestal.posY, pedestal.posZ));
            }
        }

        ArrayList<ChunkCoordinates> stabilizers = new ArrayList<>();
        collectStabilizers(world, originX, originY, originZ, pedestalCells, stabilizers);
        int symmetry = symmetry(world, originX, originZ, pedestals, stabilizers);
        Scan scan = new Scan(symmetry);
        scan.pedestals.addAll(pedestals);
        return scan;
    }

    private static void considerPedestal(int originX, int originY, int originZ, TileEntity tile,
        ChunkCoordinates[] columnPedestal, int[] columnDistance) {
        if (!(tile instanceof TilePedestal)) {
            return;
        }
        int dx = tile.xCoord - originX;
        int dz = tile.zCoord - originZ;
        int below = originY - tile.yCoord;
        if (dx == 0 && dz == 0) {
            return;
        }
        if (Math.abs(dx) > PEDESTAL_RADIUS || Math.abs(dz) > PEDESTAL_RADIUS || below <= 0 || below > Y_BELOW) {
            return;
        }
        int index = (dx + PEDESTAL_RADIUS) * 17 + (dz + PEDESTAL_RADIUS);
        if (columnPedestal[index] != null && below >= columnDistance[index]) {
            return;
        }
        columnPedestal[index] = new ChunkCoordinates(tile.xCoord, tile.yCoord, tile.zCoord);
        columnDistance[index] = below;
    }

    private static void collectStabilizers(World world, int originX, int originY, int originZ,
        HashSet<Long> pedestalCells, ArrayList<ChunkCoordinates> stabilizers) {
        IChunkProvider provider = world.getChunkProvider();
        if (provider == null) {
            return;
        }
        int minX = originX - HORIZONTAL_RADIUS;
        int maxX = originX + HORIZONTAL_RADIUS;
        int minZ = originZ - HORIZONTAL_RADIUS;
        int maxZ = originZ + HORIZONTAL_RADIUS;
        int minY = Math.max(0, originY - Y_BELOW);
        int maxY = Math.min(255, originY + Y_ABOVE);
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!provider.chunkExists(chunkX, chunkZ)) {
                    continue;
                }
                Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
                int baseX = chunkX << 4;
                int baseZ = chunkZ << 4;
                int xStart = Math.max(minX, baseX);
                int xEnd = Math.min(maxX, baseX + 15);
                int zStart = Math.max(minZ, baseZ);
                int zEnd = Math.min(maxZ, baseZ + 15);
                for (int x = xStart; x <= xEnd; x++) {
                    int dx = x - originX;
                    for (int z = zStart; z <= zEnd; z++) {
                        int dz = z - originZ;
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        for (int y = minY; y <= maxY; y++) {
                            if (pedestalCells.contains(pack(x, y, z))) {
                                continue;
                            }
                            Block block = chunk.getBlock(x & 15, y, z & 15);
                            if (isStabilizer(block, world, x, y, z)) {
                                stabilizers.add(new ChunkCoordinates(x, y, z));
                            }
                        }
                    }
                }
            }
        }
    }

    private static int symmetry(World world, int originX, int originZ, ArrayList<ChunkCoordinates> pedestals,
        ArrayList<ChunkCoordinates> stabilizers) {
        int symmetry = 0;
        for (ChunkCoordinates pedestal : pedestals) {
            boolean hasItem = false;
            int mirrorX = originX + (originX - pedestal.posX);
            int mirrorZ = originZ + (originZ - pedestal.posZ);
            TileEntity tile = LoadedWorld.getTile(world, pedestal.posX, pedestal.posY, pedestal.posZ);
            if (tile instanceof TilePedestal) {
                symmetry += 2;
                if (((IInventory) tile).getStackInSlot(0) != null) {
                    symmetry++;
                    hasItem = true;
                }
            }
            TileEntity mirror = LoadedWorld.getTile(world, mirrorX, pedestal.posY, mirrorZ);
            if (mirror instanceof TilePedestal) {
                symmetry -= 2;
                if (hasItem && ((IInventory) mirror).getStackInSlot(0) != null) {
                    symmetry--;
                }
            }
        }

        float stabilizerSymmetry = 0.0F;
        for (ChunkCoordinates stabilizer : stabilizers) {
            int mirrorX = originX + (originX - stabilizer.posX);
            int mirrorZ = originZ + (originZ - stabilizer.posZ);
            Block block = LoadedWorld.getBlock(world, stabilizer.posX, stabilizer.posY, stabilizer.posZ);
            if (isStabilizer(block, world, stabilizer.posX, stabilizer.posY, stabilizer.posZ)) {
                stabilizerSymmetry += 0.1F;
            }
            Block mirrored = LoadedWorld.getBlock(world, mirrorX, stabilizer.posY, mirrorZ);
            // Vanilla passes the original coordinates into the mirrored block's stabiliser check.
            if (isStabilizer(mirrored, world, stabilizer.posX, stabilizer.posY, stabilizer.posZ)) {
                stabilizerSymmetry -= 0.2F;
            }
        }
        return (int) (symmetry + stabilizerSymmetry);
    }

    private static boolean isStabilizer(Block block, World world, int x, int y, int z) {
        if (block == null || block == Blocks.air) {
            return false;
        }
        if (block == Blocks.skull) {
            return true;
        }
        return block instanceof IInfusionStabiliser stabilizer && stabilizer.canStabaliseInfusion(world, x, y, z);
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }
}
