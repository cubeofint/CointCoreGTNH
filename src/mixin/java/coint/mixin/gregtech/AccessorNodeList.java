package coint.mixin.gregtech;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import gregtech.api.graphs.Node;
import gregtech.api.graphs.NodeList;

@Mixin(value = NodeList.class, remap = false)
public interface AccessorNodeList {

    @Accessor("mNodes")
    Node[] cointcore$getNodes();

    @Accessor("mCounter")
    int cointcore$getCounter();
}
