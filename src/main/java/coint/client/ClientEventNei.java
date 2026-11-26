package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.client.event.GuiOpenEvent;

import codechicken.nei.recipe.GuiRecipe;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public final class ClientEventNei {

    // Main-thread state tied to the connection, including through a respawn or dimension change.
    private static NetworkManager hiddenConnection;

    public static boolean isHidden() {
        if (hiddenConnection == null || !hiddenConnection.isChannelOpen()) return false;
        NetHandlerPlayClient handler = Minecraft.getMinecraft()
            .getNetHandler();
        return handler != null && handler.getNetworkManager() == hiddenConnection;
    }

    public static void receive(NetworkManager connection, boolean hidden) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (!hidden) {
                if (hiddenConnection == connection) hiddenConnection = null;
                return;
            }
            if (!connection.isChannelOpen()) return;
            hiddenConnection = connection;
            if (isHidden() && mc.currentScreen instanceof GuiRecipe<?>recipe) {
                GuiScreen previous = recipe.getFirstScreen();
                mc.displayGuiScreen(previous instanceof GuiRecipe<?> ? null : previous);
            }
        });
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (event.gui instanceof GuiRecipe<?> && isHidden()) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        receive(event.manager, false);
    }
}
