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
    public static final Discord discord = new Discord();
    public static final Cleaner cleaner = new Cleaner();
    public static final ArcaneBore arcaneBore = new ArcaneBore();
    public static final Infusion infusion = new Infusion();
    public static final Conduits conduits = new Conduits();
    public static final Crops crops = new Crops();
    public static final Restart restart = new Restart();

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

        @Config.Comment("Enable iTNT|nuke explosion")
        @Config.DefaultBoolean(false)
        @Config.Reloadable(RELOAD)
        public boolean ic2ExplosionEnabled;

        @Config.Comment("Unload empty non-critical dimensions (no players, no forced chunks) after server start")
        @Config.DefaultBoolean(true)
        public boolean unloadEmptyDimensions;

        @Config.Comment("Prevent IC2 water kinetic generators from synchronously loading chunks while checking rotor space")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean preventIc2WaterKineticChunkLoading;

        @Config.Comment("Prevent Magic Bees apiaries from synchronously loading chunks while searching for aura providers")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean preventMagicApiaryChunkLoading;

        @Config.Comment("Number of player slots reserved for ranks with cointcore.reserved_slot permission")
        @Config.DefaultInt(5)
        @Config.RangeInt(min = 0, max = 1000)
        @Config.Reloadable(RELOAD)
        public int reservedSlots;

    }

    public static class ArcaneBore {

        @Config.Comment("Enable idle sleep optimization for Thaumcraft Arcane Bores")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean enabled;

        @Config.Comment("Seconds without a dig target before a powered Arcane Bore enters sleep mode")
        @Config.DefaultInt(300)
        @Config.RangeInt(min = 1, max = 86400)
        @Config.Reloadable(RELOAD)
        public int idleTimeoutSeconds;

        @Config.Comment("How often a sleeping Arcane Bore runs one normal server tick to look for work")
        @Config.DefaultInt(20)
        @Config.RangeInt(min = 1, max = 1200)
        @Config.Reloadable(RELOAD)
        public int sleepCheckIntervalTicks;

        @Config.Comment("Prevent Arcane Bore scans from synchronously loading unloaded chunks")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean preventChunkLoading;
    }

    public static class Infusion {

        @Config.Comment("Replace the infusion altar block scan with a loaded-tile search, and skip repeat scans during an active craft")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean fastSurroundings;

        @Config.Comment("Minimum ticks between infusion surroundings scans while crafting. Starting a craft always scans immediately")
        @Config.DefaultInt(80)
        @Config.RangeInt(min = 1, max = 1200)
        @Config.Reloadable(RELOAD)
        public int surroundingsIntervalTicks;

        @Config.Comment("Find essentia sources from tile entities in already loaded chunks. Jars in unloaded chunks are ignored instead of loading those chunks")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean fastEssentiaSearch;
    }

    public static class Conduits {

        @Config.Comment("Coalesce EnderIO redstone conduit network rebuilds. The first change still rebuilds immediately; later changes within the interval share one rebuild. Interval 1 disables coalescing")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean redstoneRebuildThrottle;

        @Config.Comment("Minimum ticks between redstone conduit network rebuilds after a neighbor block change")
        @Config.DefaultInt(5)
        @Config.RangeInt(min = 1, max = 200)
        @Config.Reloadable(RELOAD)
        public int redstoneRebuildIntervalTicks;
    }

    public static class Crops {

        @Config.Comment("Spread CropsNH growth ticks by crop position so a whole field does not grow on the same server tick")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean spreadGrowthTicks;
    }

    public static class Restart {

        @Config.Comment("Enable safe automatic restarts through CointCore")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean autoEnabled;

        @Config.Comment("Automatic restart times in server local time. Use HH:mm; midnight is 00:00")
        @Config.DefaultStringList({ "00:00", "06:00", "12:00", "18:00" })
        @Config.Reloadable(RELOAD)
        public String[] autoTimes;

        @Config.Comment("Show time remaining until the next restart in the ServerUtilities TAB footer")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean showInTab;
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

        @Config.Comment("Radius (in blocks) around the spawn point to count mobs in")
        @Config.DefaultInt(8)
        @Config.RangeInt(min = 8, max = 128)
        @Config.Reloadable(RELOAD)
        public int radius;

        @Config.Comment("General mobs cup within double the radius")
        @Config.DefaultInt(10)
        @Config.RangeInt(min = 0, max = 100)
        @Config.Reloadable(RELOAD)
        public int totalCup;

        @Config.Comment("Passive mobs cup within the radius")
        @Config.DefaultInt(3)
        @Config.RangeInt(min = 0, max = 50)
        @Config.Reloadable(RELOAD)
        public int passiveCup;

        @Config.Comment("Hostile mobs cup within the radius")
        @Config.DefaultInt(3)
        @Config.RangeInt(min = 0, max = 50)
        @Config.Reloadable(RELOAD)
        public int hostileCup;

        @Config.Comment("Run natural mob spawn scans once every N world ticks. 1 = vanilla")
        @Config.DefaultInt(2)
        @Config.RangeInt(min = 1, max = 20)
        @Config.Reloadable(RELOAD)
        public int spawnCheckInterval;

        @Config.Comment("Natural mob spawn cap as a percentage of vanilla")
        @Config.DefaultInt(75)
        @Config.RangeInt(min = 0, max = 100)
        @Config.Reloadable(RELOAD)
        public int naturalSpawnCapPercent;

        @Config.Comment("Throttle full hostile mob AI ticks when no player is nearby")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean aiThrottleEnabled;

        @Config.Comment("Hostile mobs within this distance of a player keep full-speed AI")
        @Config.DefaultInt(64)
        @Config.RangeInt(min = 16, max = 256)
        @Config.Reloadable(RELOAD)
        public int aiFullSpeedDistance;

        @Config.Comment("Run full AI once every N ticks for hostile mobs farther than aiFullSpeedDistance. 1 = vanilla")
        @Config.DefaultInt(2)
        @Config.RangeInt(min = 1, max = 20)
        @Config.Reloadable(RELOAD)
        public int aiThrottleInterval;

        @Config.Comment("Skip Forge LivingUpdateEvent on throttled far-entity ticks")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean livingUpdateThrottleEnabled;

        @Config.Comment("Prevent vanilla chest adjacency checks from synchronously loading neighboring chunks")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean preventChestChunkLoading;

        @Config.Comment("Make the vanilla per-dimension natural spawn cap static, ignoring player spread")
        @Config.DefaultBoolean(false)
        @Config.Reloadable(RELOAD)
        public boolean staticVanillaCap;

    }

    public static class Discord {

        @Config.Comment("Enable Discord chat bridge")
        @Config.DefaultBoolean(false)
        @Config.Reloadable(RELOAD)
        public boolean enabled;

        @Config.Comment("Discord bot token. Do not share it")
        @Config.DefaultString("")
        @Config.Reloadable(RELOAD)
        public String botToken;

        @Config.Comment("Main Discord chat channel ID for LOCAL/GLOBAL and Discord -> Minecraft GLOBAL")
        @Config.DefaultString("")
        @Config.Reloadable(RELOAD)
        public String channelId;

        @Config.Comment("Legacy main Discord chat channel ID fallback")
        @Config.DefaultString("")
        @Config.Reloadable(RELOAD)
        public String globalChannelId;

        @Config.Comment("Discord channel ID for private-message log")
        @Config.DefaultString("")
        @Config.Reloadable(RELOAD)
        public String logChannelId;

        @Config.Comment("Send global Minecraft chat to the main Discord channel")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean sendGlobalChat;

        @Config.Comment("Send local Minecraft chat to the main Discord channel")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean sendLocalChat;

        @Config.Comment("Send private Minecraft messages to the private-message Discord channel")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean sendPrivateChat;

        @Config.Comment("Discord -> Minecraft poll interval in seconds")
        @Config.DefaultInt(2)
        @Config.RangeInt(min = 1, max = 30)
        @Config.Reloadable(RELOAD)
        public int pollIntervalSeconds;
    }

    // TODO: move to client mod
    public static class Chat {

        @Config.Comment("Enable chat splitting")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public boolean splitEnabled;

        @Config.Comment("Radius of local chat")
        @Config.DefaultInt(300)
        @Config.RangeInt(min = 50, max = 10000)
        @Config.Reloadable(RELOAD)
        public int radius;

        @Config.Comment("Prefix for global chat")
        @Config.DefaultString("!")
        @Config.Reloadable(RELOAD)
        public String prefix;

        @Config.Comment("Formatting of chat message. Required params: {name}, {msg}. Optional: {time}, {origin}, {origin_sep}|{sep_origin} (with separator)")
        @Config.DefaultString("§7[{time}][{origin}]§r {name}: {msg}")
        @Config.Reloadable(RELOAD)
        public String msgFormat;

        @Config.Comment("Enable login message. Works only if NewHorizonsCoreMod login msg disabled.")
        @Config.DefaultBoolean(true)
        public boolean loginMsgEnabled;

        @Config.Comment("Login message lines")
        @Config.DefaultStringList({ "&6&m————————————————————————————————————————————",
            "&fWelcome to our server, %player%!", "&7Configure these lines in cointcore.cfg -> [login_message]" })
        public String[] loginMessageLines;
    }
}
