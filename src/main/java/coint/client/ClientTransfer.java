package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;

import coint.CointCore;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Leaves the server and joins another one of the network, as the server asked, without going through the menu.
 *
 * <p>
 * The new server is joined the way the server list joins it, through {@link FMLClientHandler#connectToServer}: that
 * arms the latch FML's network thread waits on until the connection has switched to play. A bare
 * {@code GuiConnecting} leaves the latch of the last connection open, so the new
 * server's channel registration can be handled before the switch ({@code NetHandlerLoginClient} cast to
 * {@code NetHandlerPlayClient}), and the login fails or hangs until it times out. The old connection is let close
 * first, as when the player leaves through the menu.
 */
@SideOnly(Side.CLIENT)
public final class ClientTransfer {

    /** How long after leaving the old server the new one is joined. */
    private static final long SETTLE_MS = 500;
    /** Longest wait for the old connection to close before joining anyway. */
    private static final long CLOSE_WAIT_MS = 10_000;

    private static ServerData target;
    private static NetworkManager leaving;
    private static long connectAt;
    private static long closeWaitUntil;

    private ClientTransfer() {}

    /** Called from the network thread: the move happens on the client thread. */
    public static void schedule(String name, String address) {
        if (address == null || address.trim()
            .isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> leave(mc, name, address.trim()));
    }

    private static void leave(Minecraft mc, String name, String address) {
        CointCore.LOG.info("[Cluster] Moving to server {} ({})", name, address);
        String shown = name == null || name.isEmpty() ? address : name;
        NetHandlerPlayClient handler = mc.getNetHandler();
        leaving = handler == null ? null : handler.getNetworkManager();
        WorldClient world = mc.theWorld;
        if (world != null) {
            world.sendQuittingDisconnectingPacket();
            mc.loadWorld(null);
        }
        // The countdown of the server left behind is over for this player.
        ClientRestartState.update(false, 0, "");
        long now = System.currentTimeMillis();
        target = new ServerData(shown, address);
        connectAt = now + SETTLE_MS;
        closeWaitUntil = now + CLOSE_WAIT_MS;
        mc.displayGuiScreen(new GuiMoving(shown));
    }

    /** Joins the new server once the old connection is gone and the client has settled. */
    public static final class Ticker {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || target == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (leaving != null && leaving.isChannelOpen() && now < closeWaitUntil) {
                return;
            }
            if (now < connectAt) {
                return;
            }
            ServerData server = target;
            target = null;
            leaving = null;
            FMLClientHandler.instance()
                .connectToServer(new GuiMainMenu(), server);
        }
    }

    /** Shown between leaving one server and joining the next; cancel goes back to the menu. */
    private static final class GuiMoving extends GuiScreen {

        private final String name;

        GuiMoving(String name) {
            this.name = name;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void initGui() {
            buttonList.clear();
            buttonList.add(new GuiButton(0, width / 2 - 100, height / 2 + 30, "Отмена"));
        }

        @Override
        protected void actionPerformed(GuiButton button) {
            if (button.id == 0) {
                target = null;
                leaving = null;
                mc.displayGuiScreen(new GuiMainMenu());
            }
        }

        @Override
        protected void keyTyped(char typed, int key) {
            // Escape would leave an empty screen behind: only the button cancels.
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawDefaultBackground();
            drawCenteredString(
                fontRendererObj,
                "Переход на сервер " + name + "...",
                width / 2,
                height / 2 - 20,
                0xFFFFFF);
            super.drawScreen(mouseX, mouseY, partialTicks);
        }
    }
}
