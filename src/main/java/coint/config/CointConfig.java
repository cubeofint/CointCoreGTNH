package coint.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import coint.CointCore;

/**
 * Configuration handler for CointCore.
 */
public class CointConfig {

    // Categories
    public static final String CATEGORY_GENERAL = "general";
    public static final String CATEGORY_EPOCHSYNC = "epochsync";
    public static final String CATEGORY_API = "api";
    public static final String CATEGORY_DEBUG = "debug";
    public static final String CATEGORY_TASKS = "tasks";
    public static final String CATEGORY_CHAT = "chat";
    public static final String CATEGORY_LIMITER = "limiter";
    public static final String CATEGORY_LOGIN_MESSAGE = "login_message";

    private static Configuration config;

    // General settings
    public static String greeting = "Hello from CointCore!";

    // EpochSync settings
    public static boolean epochSyncEnabled = true;
    public static boolean autoSyncOnQuestComplete = true;
    public static boolean partySyncEnabled = true;
    public static boolean syncNewPartyMembers = true;
    public static boolean autoParseRewardCommands = true;

    // API settings
    public static boolean notifyEnabled = false;
    public static String apiUrl = "";
    public static int apiTimeout = 10000;

    // Debug settings
    public static boolean debugMode = false;
    public static boolean verboseLogging = false;

    // Cleanup task settings
    public static boolean cleanupEnabled = true;

    // Mob limiter
    public static boolean limiterEnabled = true;
    public static int limiterChunkCup = 20;
    public static int limiterPassiveCup = 20;
    public static int limiterHostileCup = 20;

    // Chat split settings
    public static boolean chatSplitEnabled = true;
    public static double localChatRadius = 100.0;
    public static String globalChatPrefix = "!";
    public static boolean sameDimensionOnly = true;
    public static String localChatFormat = "§7[Лок] %s§r§7: §f%s";
    public static String globalChatFormat = "§a[Глоб] %s§r§7: §f%s";

    // Login message override settings (NewHorizonsCoreMod LoginHandler)
    public static boolean loginMessageOverrideEnabled = false;
    public static String[] loginMessageLines = new String[] {
        "&6&m-----------------------------------------------------", "&fWelcome to our server, %player%!",
        "&7Configure these lines in cointcore.cfg -> [login_message]" };

    /**
     * Initialize and load configuration
     */
    public static void init(File configFile) {
        if (config == null) {
            config = new Configuration(configFile);
            loadConfig();
        }
    }

    /**
     * Load configuration values
     */
    public static void loadConfig() {
        try {
            config.load();

            // General
            config.addCustomCategoryComment(CATEGORY_GENERAL, "General CointCore settings");
            greeting = config.getString("greeting", CATEGORY_GENERAL, greeting, "Greeting message shown on startup");

            // EpochSync
            config.addCustomCategoryComment(CATEGORY_EPOCHSYNC, "Epoch synchronization settings");
            epochSyncEnabled = config
                .getBoolean("enabled", CATEGORY_EPOCHSYNC, epochSyncEnabled, "Enable epoch synchronization module");
            autoSyncOnQuestComplete = config.getBoolean(
                "autoSyncOnQuestComplete",
                CATEGORY_EPOCHSYNC,
                autoSyncOnQuestComplete,
                "Automatically sync rank when a quest is completed");
            partySyncEnabled = config.getBoolean(
                "partySyncEnabled",
                CATEGORY_EPOCHSYNC,
                partySyncEnabled,
                "Sync ranks to all party members when a quest is completed");
            syncNewPartyMembers = config.getBoolean(
                "syncNewPartyMembers",
                CATEGORY_EPOCHSYNC,
                syncNewPartyMembers,
                "Sync ranks to new players when they join a party");
            autoParseRewardCommands = config.getBoolean(
                "autoParseRewardCommands",
                CATEGORY_EPOCHSYNC,
                autoParseRewardCommands,
                "Automatically parse /ranks add commands from quest rewards (no manual quest ID mapping needed)");

            // API
            config.addCustomCategoryComment(CATEGORY_API, "External API settings");
            notifyEnabled = config.getBoolean("enable", CATEGORY_API, false, "Enable api notify");
            apiUrl = config.getString(
                "url",
                CATEGORY_API,
                apiUrl,
                "Base URL for external API (leave empty to use API_URL env variable)");
            apiTimeout = config
                .getInt("timeout", CATEGORY_API, apiTimeout, 1000, 60000, "API request timeout in milliseconds");

            // Debug
            config.addCustomCategoryComment(CATEGORY_DEBUG, "Debug settings");
            debugMode = config.getBoolean("debugMode", CATEGORY_DEBUG, debugMode, "Enable debug mode");
            verboseLogging = config
                .getBoolean("verboseLogging", CATEGORY_DEBUG, verboseLogging, "Enable verbose logging");

            // Tasks
            config.addCustomCategoryComment(CATEGORY_TASKS, "tasks settings");
            cleanupEnabled = config.getBoolean(
                "cleanupEnabled",
                CATEGORY_TASKS,
                cleanupEnabled,
                "Enable cleanup task (server utilities config)");

            // Tasks
            config.addCustomCategoryComment(CATEGORY_LIMITER, "limiter settings");
            limiterEnabled = config
                .getBoolean("limiterEnabled", CATEGORY_LIMITER, limiterEnabled, "Enable Mob Limiter");
            limiterChunkCup = config
                .getInt("limiterChunkCup", CATEGORY_LIMITER, limiterChunkCup, 0, 50, "All mobs chunk limit");
            limiterHostileCup = config
                .getInt("limiterHostileCup", CATEGORY_LIMITER, limiterHostileCup, 0, 50, "Hostile mob limit");
            limiterPassiveCup = config
                .getInt("limiterPassiveCup", CATEGORY_LIMITER, limiterPassiveCup, 0, 50, "Passive mob limit");

            // Chat split
            config.addCustomCategoryComment(CATEGORY_CHAT, "Local/global chat split settings");
            chatSplitEnabled = config.getBoolean(
                "enabled",
                CATEGORY_CHAT,
                chatSplitEnabled,
                "Enable local/global chat split. Local chat is distance-limited; prefix a message with '!' to send globally.");
            localChatRadius = config.getFloat(
                "localRadius",
                CATEGORY_CHAT,
                (float) localChatRadius,
                1f,
                10000f,
                "Radius (in blocks) within which local chat messages are visible");
            globalChatPrefix = config.getString(
                "globalPrefix",
                CATEGORY_CHAT,
                globalChatPrefix,
                "Prefix character that switches a message to global chat (e.g. '!')");
            sameDimensionOnly = config.getBoolean(
                "sameDimensionOnly",
                CATEGORY_CHAT,
                sameDimensionOnly,
                "If true, local chat is only visible to players in the same dimension");
            localChatFormat = config.getString(
                "localFormat",
                CATEGORY_CHAT,
                localChatFormat,
                "Format string for local chat. %s placeholders: 1=player name, 2=message");
            globalChatFormat = config.getString(
                "globalFormat",
                CATEGORY_CHAT,
                globalChatFormat,
                "Format string for global chat. %s placeholders: 1=player name, 2=message");

            // Login message override
            config.addCustomCategoryComment(
                CATEGORY_LOGIN_MESSAGE,
                "Overrides NewHorizonsCoreMod login welcome text. Open to LAN warning is kept original.");
            loginMessageOverrideEnabled = config.getBoolean(
                "enabled",
                CATEGORY_LOGIN_MESSAGE,
                loginMessageOverrideEnabled,
                "Enable replacing NHCore login welcome lines with values from this category");
            loginMessageLines = config.getStringList(
                "lines",
                CATEGORY_LOGIN_MESSAGE,
                loginMessageLines,
                "Custom welcome lines sent on PlayerLoggedInEvent. Supports placeholders: %player% and %mod_version%. Supports '&' color codes. http/https links are auto-detected and sent as clickable links.");

        } catch (Exception e) {
            CointCore.LOG.error("Error loading config: {}", e.getMessage());
        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
    }

    /**
     * Get the effective API URL (from config or environment variable)
     */
    public static String getEffectiveApiUrl() {
        if (apiUrl != null && !apiUrl.isEmpty()) {
            return apiUrl;
        }
        return System.getenv("API_URL");
    }

    /**
     * Get the configuration instance
     */
    public static Configuration getConfig() {
        return config;
    }
}
