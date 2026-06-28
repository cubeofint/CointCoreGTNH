package coint.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.CointConfig;
import coint.CointCore;
import serverutils.ServerUtilitiesPermissions;
import serverutils.lib.config.ConfigEnum;
import serverutils.lib.config.RankConfigAPI;
import serverutils.ranks.Ranks;

public class PlayerUtil {

    public static ChatComponentText getChatMessage(String senderFormatted, String text, boolean isGlobal) {
        return getChatMessage(senderFormatted, text, isGlobal, "");
    }

    public static ChatComponentText getChatMessage(String senderFormatted, String text, boolean isGlobal,
        String origin) {
        String formatted = String
            .format(isGlobal ? CointConfig.chat.globalFormat : CointConfig.chat.localFormat, senderFormatted, text);

        LocalTime now = LocalTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        String time = now.format(formatter);

        formatted = EnumChatFormatting.GRAY + "[" + time + " " + origin + "]" + EnumChatFormatting.RESET + formatted;

        return new ChatComponentText(formatted);
    }

    public static String getRankFormattedName(EntityPlayerMP player) {
        String plainName = player.getGameProfile()
            .getName();

        try {
            if (Ranks.INSTANCE == null) {
                return plainName;
            }

            String format = Ranks.INSTANCE.getPlayerRank(player.getGameProfile())
                .getPermission(ServerUtilitiesPermissions.CHAT_NAME_FORMAT);
            if (format.isEmpty()) {
                return plainName;
            }

            format = format.replace("{name}", plainName)
                .replace("<", "")
                .replace(">", "")
                .replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1")
                .replaceAll(":\\s*$", "")
                .trim();

            return format;

        } catch (Exception e) {
            CointCore.LOG.warn("Failed to get rank format for {}:\n{}", plainName, e.getMessage());
            return plainName;
        }
    }

    public static String getTextColorCode(EntityPlayerMP player) {
        if (Ranks.INSTANCE == null) {
            return "";
        }

        try {
            EnumChatFormatting color = (EnumChatFormatting) ((ConfigEnum<?>) RankConfigAPI
                .get(player, ServerUtilitiesPermissions.CHAT_TEXT_COLOR)).getValue();
            if (color == EnumChatFormatting.WHITE) {
                return "";
            }

            return color.toString();
        } catch (Exception e) {
            CointCore.LOG.warn(
                "Failed to get text color for {}:\n{}",
                player.getGameProfile()
                    .getName(),
                e.getMessage());
            return "";
        }
    }
}
