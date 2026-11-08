package coint.cards;

import net.minecraft.block.Block;

import cpw.mods.fml.common.registry.GameRegistry;

/** The card table block. It has no recipe: tables are handed out by admins. */
public final class CardBlocks {

    public static Block table;

    private CardBlocks() {}

    /** In preInit. */
    public static void register() {
        table = new BlockCardTable();
        GameRegistry.registerBlock(table, "card_table");
        GameRegistry.registerTileEntity(TileCardTable.class, "cointcore_card_table");
    }
}
