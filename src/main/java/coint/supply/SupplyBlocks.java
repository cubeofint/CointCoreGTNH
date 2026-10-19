package coint.supply;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

import cpw.mods.fml.common.registry.GameRegistry;

/** The blocks and items of the cross-server supply buffer. */
public final class SupplyBlocks {

    public static Block buffer;
    public static Item linkCard;

    private SupplyBlocks() {}

    public static void register() {
        buffer = new BlockSupplyBuffer();
        GameRegistry.registerBlock(buffer, "supply_buffer");
        GameRegistry.registerTileEntity(TileSupplyBuffer.class, "cointcore:supply_buffer");
        linkCard = new ItemSupplyLinkCard();
        GameRegistry.registerItem(linkCard, "supply_link_card");
    }
}
