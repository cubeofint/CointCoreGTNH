package coint.mixin.gregtech;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import coint.performance.gregtech.PowerNodeRouteCache.NodeCacheAccess;
import gregtech.api.graphs.Node;

@Mixin(value = Node.class, remap = false)
public abstract class MixinNodePowerRoutingCache implements NodeCacheAccess {

    @Shadow
    public Node[] mNeighbourNodes;

    @Shadow
    public int mCreationTime;

    @Shadow
    public int mNodeValue;

    @Unique
    private int cointcore$cacheCreationTime = Integer.MIN_VALUE;
    @Unique
    private byte cointcore$parentSide = Byte.MIN_VALUE;
    @Unique
    private int cointcore$target0 = Integer.MIN_VALUE;
    @Unique
    private int cointcore$target1 = Integer.MIN_VALUE;
    @Unique
    private int cointcore$target2 = Integer.MIN_VALUE;
    @Unique
    private int cointcore$target3 = Integer.MIN_VALUE;
    @Unique
    private byte cointcore$side0 = Byte.MIN_VALUE;
    @Unique
    private byte cointcore$side1 = Byte.MIN_VALUE;
    @Unique
    private byte cointcore$side2 = Byte.MIN_VALUE;
    @Unique
    private byte cointcore$side3 = Byte.MIN_VALUE;

    @Override
    public int cointcore$getParentSide() {
        cointcore$ensureCacheVersion();
        int side = cointcore$parentSide;
        if (side >= 0) {
            Node next = mNeighbourNodes[side];
            if (next != null && next.mNodeValue < mNodeValue) return side;
            cointcore$parentSide = Byte.MIN_VALUE;
        }

        for (int i = 0; i < 6; i++) {
            Node next = mNeighbourNodes[i];
            if (next != null && next.mNodeValue < mNodeValue) {
                cointcore$parentSide = (byte) i;
                return i;
            }
        }
        return -1;
    }

    @Override
    public int cointcore$getHigherSide(int targetValue) {
        cointcore$ensureCacheVersion();
        int slot = targetValue & 3;
        int cachedTarget = cointcore$getTarget(slot);
        if (cachedTarget == targetValue) {
            int side = cointcore$getSide(slot);
            if (side >= 0) {
                Node next = mNeighbourNodes[side];
                if (next != null && ((next.mNodeValue > mNodeValue && next.mNodeValue < targetValue)
                    || next.mNodeValue == targetValue)) {
                    return side;
                }
            }
        }

        for (int side = 5; side >= 0; side--) {
            Node next = mNeighbourNodes[side];
            if (next == null) continue;
            if ((next.mNodeValue > mNodeValue && next.mNodeValue < targetValue) || next.mNodeValue == targetValue) {
                cointcore$setEntry(slot, targetValue, side);
                return side;
            }
        }
        return -1;
    }

    @Unique
    private void cointcore$ensureCacheVersion() {
        if (cointcore$cacheCreationTime == mCreationTime) return;
        cointcore$cacheCreationTime = mCreationTime;
        cointcore$parentSide = Byte.MIN_VALUE;
        cointcore$target0 = Integer.MIN_VALUE;
        cointcore$target1 = Integer.MIN_VALUE;
        cointcore$target2 = Integer.MIN_VALUE;
        cointcore$target3 = Integer.MIN_VALUE;
        cointcore$side0 = Byte.MIN_VALUE;
        cointcore$side1 = Byte.MIN_VALUE;
        cointcore$side2 = Byte.MIN_VALUE;
        cointcore$side3 = Byte.MIN_VALUE;
    }

    @Unique
    private int cointcore$getTarget(int slot) {
        switch (slot) {
            case 0:
                return cointcore$target0;
            case 1:
                return cointcore$target1;
            case 2:
                return cointcore$target2;
            default:
                return cointcore$target3;
        }
    }

    @Unique
    private int cointcore$getSide(int slot) {
        switch (slot) {
            case 0:
                return cointcore$side0;
            case 1:
                return cointcore$side1;
            case 2:
                return cointcore$side2;
            default:
                return cointcore$side3;
        }
    }

    @Unique
    private void cointcore$setEntry(int slot, int targetValue, int side) {
        switch (slot) {
            case 0:
                cointcore$target0 = targetValue;
                cointcore$side0 = (byte) side;
                break;
            case 1:
                cointcore$target1 = targetValue;
                cointcore$side1 = (byte) side;
                break;
            case 2:
                cointcore$target2 = targetValue;
                cointcore$side2 = (byte) side;
                break;
            default:
                cointcore$target3 = targetValue;
                cointcore$side3 = (byte) side;
                break;
        }
    }
}
