package coint.module.epochsync;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import betterquesting.api.utils.UuidConverter;

/**
 * Registry for epoch definitions and quest-to-epoch mappings.
 */
public final class EpochRegistry {

    // Epoch rank constants
    public static final String RANK_START = "bravebro";
    public static final String RANK_STONE = "stone";
    public static final String RANK_STEAM = "steam";
    public static final String RANK_LV = "lv";
    public static final String RANK_MV = "mv";
    public static final String RANK_HV = "hv";
    public static final String RANK_EV = "ev";
    public static final String RANK_IV = "iv";
    public static final String RANK_LUV = "luv";
    public static final String RANK_ZPM = "zpm";
    public static final String RANK_UV = "uv";
    public static final String RANK_UHV = "uhv";
    public static final String RANK_UEV = "uev";
    public static final String RANK_UIV = "uiv";
    public static final String RANK_UMV = "umv";
    public static final String RANK_UXV = "uxv";
    public static final String RANK_STARGATEOWNER = "stargateowner";

    // Ordered list of epochs (from lowest to highest)
    private static final List<String> EPOCH_ORDER = Collections.unmodifiableList(
        Arrays.asList(
            RANK_START,
            RANK_STONE,
            RANK_STEAM,
            RANK_LV,
            RANK_MV,
            RANK_HV,
            RANK_EV,
            RANK_IV,
            RANK_LUV,
            RANK_ZPM,
            RANK_UV,
            RANK_UHV,
            RANK_UEV,
            RANK_UIV,
            RANK_UMV,
            RANK_UXV,
            RANK_STARGATEOWNER));

    // All epoch ranks as set for fast lookup
    private static final Set<String> EPOCH_RANKS = Collections.unmodifiableSet(new HashSet<>(EPOCH_ORDER));

    // Epoch priority map (higher = better)
    private static final Map<String, Integer> EPOCH_PRIORITY = new HashMap<>();

    static {
        // Build priority map from order list
        for (int i = 0; i < EPOCH_ORDER.size(); i++) {
            EPOCH_PRIORITY.put(EPOCH_ORDER.get(i), i);
        }
    }

    // Quest UUID to epoch mapping (explicit overrides)
    private static final Map<UUID, String> QUEST_EPOCH_MAP = new HashMap<>();

    static {
        // Initialize quest-to-epoch mappings
        // These are optional explicit mappings; if not present, system will parse from reward commands
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAAAA=="), RANK_START);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAADw=="), RANK_STONE);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAALA=="), RANK_STEAM);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAAWA=="), RANK_LV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAEfA=="), RANK_MV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAAoA=="), RANK_HV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAAsA=="), RANK_EV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAA1Q=="), RANK_IV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAF1Q=="), RANK_LUV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAKMg=="), RANK_ZPM);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAKNQ=="), RANK_UV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAKOA=="), RANK_UHV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("k9PA3buhTvK1EJpPfA8ZAg=="), RANK_UEV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("u-1Y-eQ-TLyw-WGi5EEnVA=="), RANK_UIV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("jlXvqqgIQ_Crha2tW0gEQg=="), RANK_UMV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("7wI7VuLKRe6lqAJpuYKKJg=="), RANK_UXV);
        QUEST_EPOCH_MAP.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAKJg=="), RANK_STARGATEOWNER);
    }

    private EpochRegistry() {
        // Utility class
    }

    /**
     * Check if a rank is an epoch rank
     */
    public static boolean isEpoch(String rank) {
        return rank != null && EPOCH_RANKS.contains(rank.toLowerCase());
    }

    /**
     * Get all epoch ranks
     */
    public static Set<String> getAllEpochs() {
        return EPOCH_RANKS;
    }

    /**
     * Get epoch for a quest UUID (explicit mapping only)
     *
     * @return The epoch name, or null if not an explicitly mapped epoch quest
     */
    public static String getEpochForQuest(UUID questId) {
        return QUEST_EPOCH_MAP.get(questId);
    }

    /**
     * Check if a quest has an explicit epoch mapping
     */
    public static boolean hasExplicitMapping(UUID questId) {
        return QUEST_EPOCH_MAP.containsKey(questId);
    }

    /**
     * Check if a quest is an epoch quest (explicit mapping)
     */
    public static boolean isEpochQuest(UUID questId) {
        return QUEST_EPOCH_MAP.containsKey(questId);
    }

    /**
     * Get all quest-to-epoch mappings
     */
    public static Map<UUID, String> getQuestEpochMap() {
        return Collections.unmodifiableMap(QUEST_EPOCH_MAP);
    }

    /**
     * Get the priority of an epoch (higher = more advanced)
     *
     * @param epoch The epoch name
     * @return The priority (0-based index in progression), or -1 if not a valid epoch
     */
    public static int getEpochPriority(String epoch) {
        if (epoch == null) {
            return -1;
        }
        Integer priority = EPOCH_PRIORITY.get(epoch.toLowerCase());
        return priority != null ? priority : -1;
    }

    /**
     * Compare two epochs.
     *
     * @return positive if epoch1 > epoch2, negative if epoch1 < epoch2, 0 if equal
     */
    public static int compareEpochs(String epoch1, String epoch2) {
        return getEpochPriority(epoch1) - getEpochPriority(epoch2);
    }

    /**
     * Check if epoch1 is higher (more advanced) than epoch2.
     */
    public static boolean isHigherEpoch(String epoch1, String epoch2) {
        return compareEpochs(epoch1, epoch2) > 0;
    }

    /**
     * Get the ordered list of all epochs (from lowest to highest)
     */
    public static List<String> getEpochOrder() {
        return EPOCH_ORDER;
    }

    /**
     * Get the next epoch after the given one.
     *
     * @return The next epoch, or null if already at max or invalid epoch
     */
    public static String getNextEpoch(String currentEpoch) {
        int priority = getEpochPriority(currentEpoch);
        if (priority < 0 || priority >= EPOCH_ORDER.size() - 1) {
            return null;
        }
        return EPOCH_ORDER.get(priority + 1);
    }

    /**
     * Get the previous epoch before the given one.
     *
     * @return The previous epoch, or null if already at min or invalid epoch
     */
    public static String getPreviousEpoch(String currentEpoch) {
        int priority = getEpochPriority(currentEpoch);
        if (priority <= 0) {
            return null;
        }
        return EPOCH_ORDER.get(priority - 1);
    }
}
