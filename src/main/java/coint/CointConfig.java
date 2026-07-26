package coint;

import java.net.URI;
import java.net.URISyntaxException;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

/**
 * Configuration handler for CointCore.
 */
@Config(modid = CointCore.MOD_ID, category = "", configSubDirectory = "../cointcore/")
public class CointConfig {

    public static final String RELOAD = "reload";

    public static void load() throws ConfigException {
        ConfigurationManager.registerConfig(CointConfig.class);
    }

    public static void reload() {
        ConfigurationManager.reloadConfig(CointConfig.class, RELOAD);
    }

    public static final General general = new General();
    public static final Epochs epochs = new Epochs();
    public static final Api api = new Api();
    public static final MobLimiter limiter = new MobLimiter();
    public static final Chat chat = new Chat();
    public static final Cleaner cleaner = new Cleaner();

    public static class General {

        @Config.Comment("Is GTNH version 2.9+")
        @Config.DefaultBoolean(false)
        public boolean isNew;

        @Config.Comment("Enable entity cleanup")
        @Config.DefaultBoolean(true)
        public boolean cleanupEnabled;

        @Config.Comment("Enable pdim 'out of world' death saver")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean pdimSaverEnabled;
    }

    public static class Cleaner {

        @Config.Comment("Enable personal dimensions cleaner")
        @Config.DefaultBoolean(true)
        public boolean enabled;

        @Config.Comment("Enable personal dimensions freeze")
        @Config.DefaultBoolean(true)
        public boolean freezeEnabled;

        @Config.Comment("Enable personal dimensions delete")
        @Config.DefaultBoolean(false)
        public boolean deleteEnabled;

        @Config.Comment("[W.I.P] Remove player files with their dimension")
        @Config.DefaultBoolean(false)
        public boolean removePlayers;

        @Config.Comment("Number of days before dimension freeze due to team inactivity")
        @Config.DefaultInt(30)
        public int daysToFreeze;

        @Config.Comment("Number of days before dimension delete due to team inactivity")
        @Config.DefaultInt(180)
        public int daysToDelete;
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

        @Config.Comment("Enable websocket")
        @Config.DefaultBoolean(false)
        public boolean wsEnabled;

        @Config.Comment("API host")
        @Config.DefaultString("localhost:5665")
        public String host;

        @Config.Comment("Server Tag in chat")
        @Config.DefaultString("S")
        @Config.Reloadable(RELOAD)
        public String serverTag;

        public URI getChatWs() throws URISyntaxException {
            return new URI("ws://" + host + "/ws/gtnh");
        }

        public URI buildUri(String ep) throws URISyntaxException {
            return new URI("http://" + host + "/gtnh" + ep);
        }
    }

    public static class MobLimiter {

        @Config.Comment("Enable mob limiter")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean enabled;

        @Config.Comment("General chunk mobs cup")
        @Config.DefaultInt(20)
        @Config.RangeInt(min = 0, max = 50)
        @Config.Reloadable(RELOAD)
        public int chunkCup;

        @Config.Comment("Passive mobs cup")
        @Config.DefaultInt(20)
        @Config.RangeInt(min = 0, max = 50)
        @Config.Reloadable(RELOAD)
        public int passiveCup;

        @Config.Comment("Hostile mobs cup")
        @Config.DefaultInt(20)
        @Config.RangeInt(min = 0, max = 50)
        @Config.Reloadable(RELOAD)
        public int hostileCup;

    }

    public static class Chat {

        @Config.Comment("Enable login message. Works only if NewHorizonsCoreMod login msg disabled.")
        @Config.DefaultBoolean(true)
        public boolean loginMsgEnabled;

        @Config.Comment("Login message lines")
        @Config.DefaultStringList({ "&6&m————————————————————————————————————————————",
            "&fWelcome to our server, %player%!", "&7Configure these lines in cointcore.cfg -> [login_message]" })
        public String[] loginMessageLines;
    }
}
