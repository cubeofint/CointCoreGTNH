package coint;

import java.net.URI;
import java.net.URISyntaxException;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

/**
 * Configuration handler for CointCore.
 */
@Config(modid = CointCore.MOD_ID, category = "")
public class CointConfig {

    public static void load() throws ConfigException {
        ConfigurationManager.registerConfig(CointConfig.class);
    }

    public static void reload() {
        ConfigurationManager.reloadConfig(CointConfig.class, "reload");
    }

    public static final General general = new General();
    public static final Epochs epochs = new Epochs();
    public static final Api api = new Api();
    public static final MobLimiter limiter = new MobLimiter();
    public static final Chat chat = new Chat();

    public static class General {

        @Config.Comment("Enable cleanup")
        @Config.DefaultBoolean(true)
        public boolean cleanupEnabled;
    }

    public static class Epochs {

        @Config.Comment("Enable epoch synchronization module")
        @Config.DefaultBoolean(true)
        public boolean enabled;

        @Config.Comment("Automatically sync rank when a quest is completed")
        @Config.DefaultBoolean(true)
        public boolean syncOnQuestComplete;

        @Config.Comment("Sync ranks to all party members when a quest is completed")
        @Config.DefaultBoolean(true)
        public boolean partySync;

        @Config.Comment("Sync ranks to new players when they join a party")
        @Config.DefaultBoolean(true)
        public boolean syncNewPartyMembers;
    }

    @Config.Comment("only for local web api")
    public static class Api {

        @Config.Comment("[WIP] Enable sending player epoch updates")
        @Config.DefaultBoolean(false)
        public boolean notifyEnabled;

        @Config.Comment("Enable player to server binding")
        @Config.DefaultBoolean(false)
        public boolean bindingEnabled;

        @Config.Comment("API host")
        @Config.DefaultString("localhost:5665")
        public String host;

        @Config.Comment("Server Tag in chat")
        @Config.DefaultString("S")
        @Config.Reloadable("reload")
        public String serverTag;

        public URI getChatWs() throws URISyntaxException {
            return new URI("ws://" + host + "/gtnh-chat");
        }

        public URI buildUri(String ep) throws URISyntaxException {
            return new URI("http://" + host + "/gtnh" + ep);
        }
    }

    public static class MobLimiter {

        @Config.Comment("Enable mob limiter")
        @Config.DefaultBoolean(true)
        @Config.Reloadable("reload")
        public boolean enabled;

        @Config.Comment("General chunk mobs cup")
        @Config.DefaultInt(20)
        @Config.RangeInt(min = 0, max = 50)
        @Config.Reloadable("reload")
        public int chunkCup;

        @Config.Comment("Passive mobs cup")
        @Config.DefaultInt(20)
        @Config.RangeInt(min = 0, max = 50)
        @Config.Reloadable("reload")
        public int passiveCup;

        @Config.Comment("Hostile mobs cup")
        @Config.DefaultInt(20)
        @Config.RangeInt(min = 0, max = 50)
        @Config.Reloadable("reload")
        public int hostileCup;

    }

    // TODO: move to client mod
    public static class Chat {

        @Config.Comment("Enable chat splitting")
        @Config.DefaultBoolean(true)
        public boolean splitEnabled;

        @Config.Comment("Radius of local chat")
        @Config.DefaultInt(300)
        @Config.RangeInt(min = 50, max = 10000)
        public int radius;

        @Config.Comment("Prefix for global chat")
        @Config.DefaultString("!")
        public String prefix;

        @Config.Comment("Formatting of local chat")
        @Config.DefaultString("§7[L] %s§r§7: §f%s")
        public String localFormat;

        @Config.Comment("Formatting of global chat")
        @Config.DefaultString("§a[G] %s§r§7: §f%s")
        public String globalFormat;

        @Config.Comment("Enable login message. Works only if NewHorizonsCoreMod login msg disabled.")
        @Config.DefaultBoolean(true)
        public boolean loginMsgEnabled;

        @Config.Comment("Login message lines")
        @Config.DefaultStringList({ "&6&m————————————————————————————————————————————",
            "&fWelcome to our server, %player%!", "&7Configure these lines in cointcore.cfg -> [login_message]" })
        public String[] loginMessageLines;
    }
}
