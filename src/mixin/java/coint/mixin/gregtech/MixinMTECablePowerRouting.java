package coint.mixin.gregtech;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.performance.gregtech.PowerNodeRouteCache;
import gregtech.api.graphs.Node;
import gregtech.api.graphs.NodeList;
import gregtech.api.graphs.PowerNodes;
import gregtech.api.metatileentity.implementations.MTECable;

@Mixin(value = MTECable.class, remap = false)
public abstract class MixinMTECablePowerRouting {

    @Redirect(
        method = "transferElectricity(Lnet/minecraftforge/common/util/ForgeDirection;JJLjava/util/HashSet;)J",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/graphs/PowerNodes;powerNode(Lgregtech/api/graphs/Node;Lgregtech/api/graphs/Node;Lgregtech/api/graphs/NodeList;JJ)J"),
        require = 0,
        remap = false)
    private long cointcore$cachedPowerRouting(Node current, Node previous, NodeList consumers, long voltage,
        long maxAmps) {
        try {
            AccessorNodeList accessor = (AccessorNodeList) (Object) consumers;
            return PowerNodeRouteCache.powerNode(
                current,
                previous,
                consumers,
                accessor.cointcore$getNodes(),
                accessor.cointcore$getCounter(),
                voltage,
                maxAmps);
        } catch (RuntimeException | LinkageError ignored) {
            return PowerNodes.powerNode(current, previous, consumers, voltage, maxAmps);
        }
    }
}
