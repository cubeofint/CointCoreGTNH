package coint.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for parsing reward commands.
 */
public final class CommandParser {

    private static final Logger LOG = LogManager.getLogger(CommandParser.class);

    // Pattern for: /ranks add @p <rank> or /ranks add VAR_NAME <rank> or /ranks add <player> <rank>
    private static final Pattern RANKS_ADD_PATTERN = Pattern
        .compile("/ranks\\s+add\\s+(?:@p|VAR_NAME|VAR_UUID|\\S+)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    // Pattern for extracting rank from any /ranks command variation
    private static final Pattern RANKS_GENERAL_PATTERN = Pattern
        .compile("/ranks?\\s+(?:add|set|assign)\\s+\\S+\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    private CommandParser() {
        // Utility class
    }

    /**
     * Parse rank name from a /ranks add command.
     *
     * @param command The command string (e.g., "/ranks add @p stone")
     * @return The rank name, or null if not a valid ranks command
     */
    @Nullable
    public static String parseRankFromCommand(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }

        // Try primary pattern first
        Matcher matcher = RANKS_ADD_PATTERN.matcher(command);
        if (matcher.find()) {
            String rank = matcher.group(1);
            LOG.debug("Parsed rank '{}' from command: {}", rank, command);
            return rank;
        }

        // Try general pattern as fallback
        matcher = RANKS_GENERAL_PATTERN.matcher(command);
        if (matcher.find()) {
            String rank = matcher.group(1);
            LOG.debug("Parsed rank '{}' from command (general pattern): {}", rank, command);
            return rank;
        }

        LOG.debug("Could not parse rank from command: {}", command);
        return null;
    }

    /**
     * Check if a command is a ranks assignment command.
     *
     * @param command The command string
     * @return true if this is a /ranks add command
     */
    public static boolean isRanksAddCommand(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        return RANKS_ADD_PATTERN.matcher(command)
            .find()
            || RANKS_GENERAL_PATTERN.matcher(command)
                .find();
    }

    /**
     * Replace player placeholder in command with actual player name.
     *
     * @param command    The command template
     * @param playerName The player name to substitute
     * @return The command with placeholder replaced
     */
    public static String substitutePlayer(String command, String playerName) {
        return command.replaceAll("@p", playerName)
            .replaceAll("VAR_NAME", playerName);
    }

    /**
     * Replace player placeholder in command with actual UUID.
     *
     * @param command    The command template
     * @param playerUUID The player UUID to substitute
     * @return The command with placeholder replaced
     */
    public static String substituteUUID(String command, String playerUUID) {
        return command.replaceAll("VAR_UUID", playerUUID);
    }
}
