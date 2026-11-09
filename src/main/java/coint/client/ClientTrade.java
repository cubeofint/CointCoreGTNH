package coint.client;

import net.minecraft.client.Minecraft;

import coint.trade.PacketTradeState;

/** The trade this client is in, as the server last told it; opens, updates and closes the trade window. */
public final class ClientTrade {

    private ClientTrade() {}

    public static void receive(PacketTradeState m) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> apply(mc, m));
    }

    private static void apply(Minecraft mc, PacketTradeState m) {
        if (!m.open) {
            if (mc.currentScreen instanceof GuiTrade gui) {
                gui.closedByServer = true;
                mc.displayGuiScreen(null);
            }
            return;
        }
        if (mc.currentScreen instanceof GuiTrade gui) {
            gui.update(m);
        } else {
            mc.displayGuiScreen(new GuiTrade(m));
        }
    }
}
