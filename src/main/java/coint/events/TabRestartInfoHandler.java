package coint.events;

import java.nio.charset.StandardCharsets;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonObject;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.restart.RestartManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.netty.buffer.Unpooled;
import serverutils.ServerUtilitiesConfig;

@EventBusSubscriber
public final class TabRestartInfoHandler {

    private static final String CHANNEL = "SU|TabHF";
    private static int ticks;

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            send(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !CointConfig.restart.showInTab) {
            return;
        }
        if (++ticks < 20) {
            return;
        }
        ticks = 0;

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return;
        }

        for (Object object : server.getConfigurationManager().playerEntityList) {
            if (object instanceof EntityPlayerMP player) {
                send(player);
            }
        }
    }

    private static void send(EntityPlayerMP player) {
        if (!CointConfig.restart.showInTab || player == null || player.playerNetServerHandler == null) {
            return;
        }

        String restart = RestartManager.INSTANCE.getTabStatus();
        String footer = ServerUtilitiesConfig.tab.footerText;
        if (restart != null && !restart.isEmpty()) {
            footer = footer == null || footer.isEmpty() ? restart : footer + "\n\n" + restart;
        }

        JsonObject data = new JsonObject();
        data.addProperty(
            "header",
            ServerUtilitiesConfig.tab.headerText == null ? "" : ServerUtilitiesConfig.tab.headerText);
        data.addProperty("footer", footer == null ? "" : footer);

        byte[] bytes = data.toString()
            .getBytes(StandardCharsets.UTF_8);
        PacketBuffer buffer = new PacketBuffer(Unpooled.copiedBuffer(bytes));
        player.playerNetServerHandler.sendPacket(new S3FPacketCustomPayload(CHANNEL, buffer));
    }
}
