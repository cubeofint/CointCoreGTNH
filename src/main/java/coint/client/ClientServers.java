package coint.client;

import net.minecraft.client.Minecraft;

import coint.cluster.PacketServers;
import coint.network.WorldTravelNetwork;

/** Opens the servers screen and feeds it the list the server sends. */
public final class ClientServers {

    private ClientServers() {}

    /** Shows the screen at once (empty until the list comes) and asks for the list. */
    public static void open() {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiServers());
        ask();
    }

    static void ask() {
        WorldTravelNetwork.CHANNEL.sendToServer(new PacketServers.Request());
    }

    public static void receive(PacketServers m) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (mc.currentScreen instanceof GuiServers gui) {
                gui.update(m);
            } else if (m.open && mc.thePlayer != null) {
                GuiServers gui = new GuiServers();
                gui.update(m);
                mc.displayGuiScreen(gui);
            }
        });
    }
}
