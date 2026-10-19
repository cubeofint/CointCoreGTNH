package coint.supply;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

/** The cross-server supply buffer block; all it does is in {@link TileSupplyBuffer}. */
public class BlockSupplyBuffer extends BlockContainer {

    public BlockSupplyBuffer() {
        super(Material.iron);
        setHardness(3.5F);
        setResistance(60F);
        setStepSound(soundTypeMetal);
        setBlockName("cointcore.supply_buffer");
        setBlockTextureName("cointcore:supply_buffer");
        setCreativeTab(CreativeTabs.tabMisc);
        setHarvestLevel("pickaxe", 1);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileSupplyBuffer();
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        if (!world.isRemote && placer instanceof EntityPlayer player
            && world.getTileEntity(x, y, z) instanceof TileSupplyBuffer tile) {
            tile.setOwner(player);
        }
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        if (!(world.getTileEntity(x, y, z) instanceof TileSupplyBuffer tile)) {
            return false;
        }
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemSupplyLinkCard) {
            ItemSupplyLinkCard.use(held, tile, player);
            return true;
        }
        if (tile.getRole() == TileSupplyBuffer.Role.REMOTE && !player.isSneaking()) {
            if (!tile.canEdit(player)) {
                player.addChatMessage(new ChatComponentText("§cЭто не ваш буфер."));
                return true;
            }
            player.openGui(coint.CointCore.instance, SupplyGuiHandler.BUFFER, world, x, y, z);
            return true;
        }
        for (String line : tile.status()) {
            player.addChatMessage(new ChatComponentText(line));
        }
        return true;
    }

    /** Not while stocks are held or things are on their way: they would have to be dropped, any amount, or lost. */
    @Override
    public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z, boolean willHarvest) {
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof TileSupplyBuffer tile && !tile.canBreak()) {
            player.addChatMessage(
                new ChatComponentText(
                    "§cВ буфере есть запас или операции в пути. Уберите фильтры (запас вернётся в главную ME) "
                        + "и дождитесь, пока «в пути» станет 0."));
            world.markBlockForUpdate(x, y, z);
            return false;
        }
        return super.removedByPlayer(world, player, x, y, z, willHarvest);
    }

    @Override
    public float getExplosionResistance(net.minecraft.entity.Entity entity, World world, int x, int y, int z,
        double explosionX, double explosionY, double explosionZ) {
        // Explosions would break it with its stocks in it.
        return world.getTileEntity(x, y, z) instanceof TileSupplyBuffer tile && !tile.canBreak() ? 6000000F
            : super.getExplosionResistance(entity, world, x, y, z, explosionX, explosionY, explosionZ);
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof TileSupplyBuffer tile) {
            tile.dropContents();
        }
        super.breakBlock(world, x, y, z, block, meta);
    }
}
