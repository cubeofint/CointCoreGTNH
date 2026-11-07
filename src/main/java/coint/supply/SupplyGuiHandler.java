package coint.supply;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.IGuiHandler;

/** Opens the window of a remote supply buffer. */
public class SupplyGuiHandler implements IGuiHandler {

    public static final int BUFFER = 1;
    /** The remote ME window; the view comes from {@link coint.cluster.RemoteMe#opening()}. */
    public static final int REMOTE_ME = 2;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == REMOTE_ME) {
            coint.cluster.RemoteMe.View view = coint.cluster.RemoteMe.opening();
            return view == null ? null : new coint.cluster.ContainerRemoteMe(player.inventory, view);
        }
        if (id == BUFFER && world.getTileEntity(x, y, z) instanceof TileSupplyBuffer tile) {
            return new ContainerSupplyBuffer(player.inventory, tile);
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == REMOTE_ME) {
            return new coint.client.GuiRemoteMe(player.inventory);
        }
        if (id == BUFFER && world.getTileEntity(x, y, z) instanceof TileSupplyBuffer tile) {
            return new GuiSupplyBuffer(player.inventory, tile);
        }
        return null;
    }
}
