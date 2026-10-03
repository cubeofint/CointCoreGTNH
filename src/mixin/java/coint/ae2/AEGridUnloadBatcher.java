package coint.ae2;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.world.World;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.me.GridNode;

public final class AEGridUnloadBatcher {

    private static final Set<GridNode> PENDING = Collections.newSetFromMap(new IdentityHashMap<GridNode, Boolean>());
    private static int unloadDepth;

    private AEGridUnloadBatcher() {}

    public static void begin(World world) {
        if (world != null && !world.isRemote) {
            unloadDepth++;
        }
    }

    public static void end(World world) {
        if (world != null && !world.isRemote && unloadDepth > 0) {
            unloadDepth--;
        }
    }

    public static boolean isBatching() {
        return unloadDepth > 0;
    }

    public static void queue(GridNode node) {
        if (node != null) {
            PENDING.add(node);
        }
    }

    public static void flush() {
        unloadDepth = 0;
        if (PENDING.isEmpty()) {
            return;
        }

        Set<GridNode> pending = Collections.newSetFromMap(new IdentityHashMap<GridNode, Boolean>());
        pending.addAll(PENDING);
        PENDING.clear();

        Set<IGrid> originalGrids = Collections.newSetFromMap(new IdentityHashMap<IGrid, Boolean>());
        for (GridNode node : pending) {
            IGrid grid = node.getGrid();
            if (grid != null) {
                originalGrids.add(grid);
            }
        }

        Set<GridNode> liveNodes = Collections.newSetFromMap(new IdentityHashMap<GridNode, Boolean>());
        for (IGrid grid : originalGrids) {
            for (IGridNode node : grid.getNodes()) {
                if (node instanceof GridNode) {
                    liveNodes.add((GridNode) node);
                }
            }
        }

        Set<GridNode> visited = Collections.newSetFromMap(new IdentityHashMap<GridNode, Boolean>());
        Deque<GridNode> open = new ArrayDeque<GridNode>();

        for (GridNode root : pending) {
            if (!liveNodes.contains(root) || visited.contains(root)) {
                continue;
            }

            open.add(root);
            visited.add(root);

            while (!open.isEmpty()) {
                GridNode current = open.removeFirst();
                for (IGridConnection connection : current.getConnections()) {
                    IGridNode other = connection.getOtherSide(current);
                    if (other instanceof GridNode) {
                        GridNode otherNode = (GridNode) other;
                        if (liveNodes.contains(otherNode) && visited.add(otherNode)) {
                            open.addLast(otherNode);
                        }
                    }
                }
            }

            ((GridNodeAccess) (Object) root).cointcore$validateGrid();
        }

        Set<IGrid> finalGrids = Collections.newSetFromMap(new IdentityHashMap<IGrid, Boolean>());
        for (GridNode node : liveNodes) {
            IGrid grid = node.getGrid();
            if (grid != null) {
                finalGrids.add(grid);
            }
        }

        for (IGrid grid : finalGrids) {
            IPathingGrid pathing = grid.getCache(IPathingGrid.class);
            if (pathing != null) {
                pathing.repath();
            }
        }
    }
}
