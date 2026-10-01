package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import coint.network.PacketOpenWorlds;
import coint.network.WorldTravelNetwork;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class ClientWorldTravelEvents {

    private static final KeyBinding OPEN_WORLDS = new KeyBinding("Открыть меню миров", Keyboard.KEY_M, "CointCoreGTNH");

    public ClientWorldTravelEvents() {
        ClientRegistry.registerKeyBinding(OPEN_WORLDS);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }

        if (mc.currentScreen == null && OPEN_WORLDS.isPressed()) {
            WorldTravelNetwork.requestOpen();
        }

        PacketOpenWorlds packet = ClientWorldTravelState.take();
        if (packet != null) {
            mc.displayGuiScreen(new GuiWorldTravelScreen(packet));
        }
    }
}
