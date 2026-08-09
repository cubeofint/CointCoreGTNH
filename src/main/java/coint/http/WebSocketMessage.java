package coint.http;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraft.command.ICommandSender;
import net.minecraft.network.rcon.RConThreadMain;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import coint.CointConfig;
import coint.CointCore;
import coint.util.ChatUtil;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

public class WebSocketMessage {

    static Gson gson = new Gson();

    public enum Action {
        @SerializedName("info")
        Info,

        @SerializedName("chat")
        Chat,

        @SerializedName("player.logged")
        PlayerLogged
    }

    public String origin;
    public Action action;
    public JsonElement payload;

    public WebSocketMessage(Action action, JsonElement payload) {
        this.origin = CointConfig.api.serverTag;
        this.action = action;
        this.payload = payload;
    }

    public static class Player {

        String name;
        String nameFormatted;
        String uuid;
        String team;

        public Player(ForgePlayer f) {
            this.name = f.getName();
            this.nameFormatted = ChatUtil.getRankFormattedName(f.getPlayer());
            this.uuid = f.getId()
                .toString();
            this.team = f.hasTeam() ? f.team.getTitle()
                .getUnformattedText() : "no team";
        }
    }

    public static class InfoMessage {

        List<Player> players;
        int slots;
        double mspt;
        double tps;
        int rconPort;

        public static WebSocketMessage create(MinecraftServer server) {
            var players = Universe.get()
                .getOnlinePlayers()
                .stream()
                .map(Player::new)
                .collect(Collectors.toList());

            var ticks = server.tickTimeArray;
            var mspt = ((double) Arrays.stream(ticks)
                .sum() / ticks.length) * 1.0E-6D;
            var tps = Math.min(1000.0 / mspt, 20);

            var msg = new InfoMessage();
            msg.players = players;
            msg.slots = server.getMaxPlayers();
            msg.mspt = mspt;
            msg.tps = tps;
            msg.rconPort = getRconPort(server);

            return new WebSocketMessage(Action.Info, gson.toJsonTree(msg));
        }

        private static int getRconPort(MinecraftServer server) {
            if (!(server instanceof DedicatedServer dedicated)) {
                CointCore.LOG.warn("[rconPort] server is not a DedicatedServer ({}), reporting 0", server.getClass());
                return 0;
            }
            try {
                Field rconField = findField(DedicatedServer.class, "theRConThreadMain", "field_71339_n");
                rconField.setAccessible(true);
                var rcon = (RConThreadMain) rconField.get(dedicated);
                if (rcon == null) {
                    CointCore.LOG.warn(
                        "[rconPort] theRConThreadMain is null (enable-rcon=false in server.properties?), reporting 0");
                    return 0;
                }

                Field portField = findField(RConThreadMain.class, "rconPort", "field_72647_g");
                portField.setAccessible(true);
                int port = portField.getInt(rcon);
                CointCore.LOG.debug("[rconPort] resolved rcon port: {}", port);
                return port;
            } catch (ReflectiveOperationException e) {
                CointCore.LOG.error("[rconPort] reflection failed, reporting 0", e);
                return 0;
            }
        }

        private static Field findField(Class<?> clazz, String mcpName, String srgName) throws NoSuchFieldException {
            try {
                return clazz.getDeclaredField(mcpName);
            } catch (NoSuchFieldException e) {
                return clazz.getDeclaredField(srgName);
            }
        }
    }

    public static class ChatMessage {

        String sender;
        String senderFormatted;
        String text;

        public static WebSocketMessage create(ICommandSender sender, String formattedName, String text) {
            var msg = new ChatMessage();
            msg.sender = sender.getCommandSenderName();
            msg.senderFormatted = formattedName;
            msg.text = text;

            return new WebSocketMessage(Action.Chat, gson.toJsonTree(msg));
        }
    }

    @SuppressWarnings("unused")
    public static class PlayerLoggedMessage {

        String name;
        String uuid;
        String team;
        boolean in;
        long timestamp;

        public static WebSocketMessage create(ICommandSender sender, boolean in) {
            var player = Universe.get()
                .getPlayer(sender);
            var msg = new PlayerLoggedMessage();
            msg.name = player.getName();
            msg.uuid = player.getId()
                .toString();
            msg.team = player.hasTeam() ? player.team.getTitle()
                .getUnformattedText() : "no team";
            msg.in = in;
            msg.timestamp = System.currentTimeMillis();

            return new WebSocketMessage(Action.PlayerLogged, gson.toJsonTree(msg));
        }
    }
}
