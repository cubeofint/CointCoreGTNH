package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;

import coint.CointCore;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Leaves the server and joins another one of the network, as the server asked, without going through the menu. */
@SideOnly(Side.CLIENT)
public final class ClientTransfer {

    private ClientTransfer() {}

    /** Called from the network thread: the move happens on the client thread. */
    public static void schedule(String name, String address) {
        if (address == null || address.trim()
            .isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> transfer(mc, name, address.trim()));
    }

    private static void transfer(Minecraft mc, String name, String address) {
        CointCore.LOG.info("[Cluster] Moving to server {} ({})", name, address);
        WorldClient world = mc.theWorld;
        if (world != null) {
            world.sendQuittingDisconnectingPacket();
            mc.loadWorld(null);
        }
        ServerData server = new ServerData(name == null || name.isEmpty() ? address : name, address);
        mc.displayGuiScreen(new GuiConnecting(new GuiMainMenu(), mc, server));
    }
}
