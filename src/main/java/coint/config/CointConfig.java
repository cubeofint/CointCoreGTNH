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
    public static String apiUrl = "";
    public static int apiTimeout = 10000;

    // Debug settings
    public static boolean debugMode = false;
    public static boolean verboseLogging = false;

    // Cleanup task settings
    public static boolean cleanupEnabled = true;
    public static int droppedItemTTL = 180;

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
            droppedItemTTL = config.getInt(
                "droppedItemTTL",
                CATEGORY_TASKS,
                droppedItemTTL,
                0,
                600,
                "Time that dropped item will not be cleaned");

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
     * Check if API is configured
     */
    public static boolean isApiConfigured() {
        String url = getEffectiveApiUrl();
        return url != null && !url.isEmpty();
    }

    /**
     * Get the configuration instance
     */
    public static Configuration getConfig() {
        return config;
    }
}
