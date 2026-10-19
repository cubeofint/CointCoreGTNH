package coint.supply;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.IGuiHandler;

/** Opens the window of a remote supply buffer. */
public class SupplyGuiHandler implements IGuiHandler {

    public static final int BUFFER = 1;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == BUFFER && world.getTileEntity(x, y, z) instanceof TileSupplyBuffer tile) {
            return new ContainerSupplyBuffer(player.inventory, tile);
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == BUFFER && world.getTileEntity(x, y, z) instanceof TileSupplyBuffer tile) {
            return new GuiSupplyBuffer(player.inventory, tile);
        }
        return null;
    }
}
