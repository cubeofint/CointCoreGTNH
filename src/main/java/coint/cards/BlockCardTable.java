package coint.cards;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/** A table to play cards at: using it takes a seat and opens the game window. */
public class BlockCardTable extends Block {

    public BlockCardTable() {
        super(Material.wood);
        setHardness(2F);
        setResistance(5F);
        setStepSound(soundTypeWood);
        setBlockName("cointcore.card_table");
        setBlockTextureName("cointcore:card_table");
        setCreativeTab(CreativeTabs.tabDecorations);
        setHarvestLevel("axe", 0);
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (!world.isRemote && player instanceof EntityPlayerMP mp) {
            CardTables.use(mp, x, y, z);
        }
        return true;
    }

    @Override
    public boolean hasTileEntity(int meta) {
        return true;
    }

    @Override
    public net.minecraft.tileentity.TileEntity createTileEntity(World world, int meta) {
        return new TileCardTable();
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        if (!world.isRemote) {
            CardTables.removed(world.provider.dimensionId, x, y, z);
        }
        super.breakBlock(world, x, y, z, block, meta);
    }
}
