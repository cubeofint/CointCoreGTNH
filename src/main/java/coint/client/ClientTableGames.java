package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

import coint.cards.PacketTableState;

/** All GUI changes are scheduled on the Minecraft client thread. */
public final class ClientTableGames {

    private ClientTableGames() {}

    public static void receive(PacketTableState message) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (!message.error.isEmpty() && mc.thePlayer != null)
                mc.thePlayer.addChatMessage(new ChatComponentTranslation(message.error));
            if (message.closed) {
                if (mc.currentScreen instanceof GuiTableGames) mc.displayGuiScreen(null);
            } else if (message.open) mc.displayGuiScreen(new GuiTableGames(message));
            else if (mc.currentScreen instanceof GuiTableGames gui) gui.update(message);
        });
    }
}
