package coint.integration.thaumcraft;

import java.util.ArrayList;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import coint.world.LoadedWorld;
import thaumcraft.api.WorldCoordinates;
import thaumcraft.api.aspects.IAspectSource;
import thaumcraft.common.tiles.TileMirrorEssentia;

/**
 * Replacement for {@code EssentiaHandler.getSources}. The original walks every block in the radius and calls
 * {@code getTileEntity}, which loads chunks. Jars and mirrors are tile entities, so the loaded chunk maps are enough.
 */
public final class EssentiaSourceSearch {

    private EssentiaSourceSearch() {}

    public static ArrayList<WorldCoordinates> find(World world, WorldCoordinates origin, ForgeDirection direction,
        int range) {
        ArrayList<WorldCoordinates> found = new ArrayList<>();
        if (world == null || origin == null || direction == null || range < 1) {
            return found;
        }
        Bounds bounds = Bounds.of(origin, direction, range);
        TileEntity requester = LoadedWorld.getTile(world, origin.x, origin.y, origin.z);
        int dimension = world.provider.dimensionId;
        LoadedWorld
            .forEachTile(world, bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, tile -> {
                if (!(tile instanceof IAspectSource)
                    || tile.xCoord == origin.x && tile.yCoord == origin.y && tile.zCoord == origin.z) {
                    return;
                }
                if (!acceptSource(requester, tile)) {
                    return;
                }
                found.add(new WorldCoordinates(tile.xCoord, tile.yCoord, tile.zCoord, dimension));
            });
        return found;
    }

    private static boolean acceptSource(TileEntity requester, TileEntity source) {
        if (!(requester instanceof TileMirrorEssentia) || !(source instanceof TileMirrorEssentia mirror)) {
            return true;
        }
        return requester.xCoord != mirror.linkX || requester.yCoord != mirror.linkY
            || requester.zCoord != mirror.linkZ
            || requester.getWorldObj().provider.dimensionId != mirror.linkDim;
    }

    private static final class Bounds {

        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;

        private Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        private static Bounds of(WorldCoordinates origin, ForgeDirection direction, int range) {
            int start = 0;
            ForgeDirection axis = direction;
            if (axis == ForgeDirection.UNKNOWN) {
                start = -range;
                axis = ForgeDirection.UP;
            }
            if (axis.offsetY != 0) {
                int yA = origin.y + start * axis.offsetY;
                int yB = origin.y + (range - 1) * axis.offsetY;
                return new Bounds(
                    origin.x - range,
                    origin.x + range,
                    Math.min(yA, yB),
                    Math.max(yA, yB),
                    origin.z - range,
                    origin.z + range);
            }
            if (axis.offsetX == 0) {
                int zA = origin.z + start * axis.offsetZ;
                int zB = origin.z + (range - 1) * axis.offsetZ;
                return new Bounds(
                    origin.x - range,
                    origin.x + range,
                    origin.y - range,
                    origin.y + range,
                    Math.min(zA, zB),
                    Math.max(zA, zB));
            }
            int xA = origin.x + start * axis.offsetX;
            int xB = origin.x + (range - 1) * axis.offsetX;
            return new Bounds(
                Math.min(xA, xB),
                Math.max(xA, xB),
                origin.y - range,
                origin.y + range,
                origin.z - range,
                origin.z + range);
        }
    }
}
