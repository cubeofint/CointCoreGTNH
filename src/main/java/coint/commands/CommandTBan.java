package coint.commands;

import java.util.Arrays;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.google.common.base.Joiner;

import coint.commands.tban.PlayerTBanData;
import coint.commands.tban.TBan;
import coint.commands.tban.TBanStorage;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;
import serverutils.ranks.Ranks;

public class CommandTBan extends CommandBase {

    public CommandTBan() {
        PermissionAPI.registerNode("cointcore.command.tban", DefaultPermissionLevel.OP, "CointCore tban permission");
    }

    @Override
    public String getCommandName() {
        return "tban";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return Ranks.INSTANCE.getPermission(player.getGameProfile(), "cointcore.command.tban", false)
                .getBoolean();
        }
        // Console and other non-player senders always have access
        return true;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/tban <player> <time> 'reason'";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String playerName = args[0].toLowerCase();

        ForgePlayer player = Universe.get()
            .getPlayer(playerName);
        if (player == null) {
            throw new PlayerNotFoundException();
        }

        long durationMs = parseTimeToMs(args[1]);
        String reason = Joiner.on(" ")
            .join(Arrays.copyOfRange(args, 2, args.length));
        reason = reason.replaceAll("^['\"]|['\"]$", "");

        tban(sender, player, durationMs, reason);
    }

    private void tban(ICommandSender sender, ForgePlayer player, long durationMs, String reason) {
        TBan tban = new TBan(sender, reason, durationMs);

        // Always persist the ban so it survives for offline players and relogs
        TBanStorage.store(
            player.getProfile()
                .getId(),
            tban);

        if (player.isOnline()) {
            EntityPlayer entityPlayer = player.getPlayer();
            PlayerTBanData tbanData = PlayerTBanData.get(entityPlayer);
            if (tbanData != null) {
                tbanData.set(tban);
            }

            if (entityPlayer instanceof EntityPlayerMP playerMP) {
                String banMessage = EnumChatFormatting.RED + "Вы забанены на "
                    + formatDuration(durationMs)
                    + EnumChatFormatting.RED
                    + " по причине: "
                    + EnumChatFormatting.YELLOW
                    + reason;
                playerMP.playerNetServerHandler.kickPlayerFromServer(banMessage);
            }
        }

        MinecraftServer server = MinecraftServer.getServer();
        ChatComponentText message = new ChatComponentText(
            EnumChatFormatting.GOLD + sender.getCommandSenderName()
                + EnumChatFormatting.RESET
                + " забанил "
                + EnumChatFormatting.GOLD
                + player.getName()
                + EnumChatFormatting.RESET
                + " на "
                + formatDuration(durationMs)
                + EnumChatFormatting.RESET
                + ": "
                + EnumChatFormatting.YELLOW
                + reason);

        @SuppressWarnings("unchecked")
        java.util.List<EntityPlayer> players = (java.util.List<EntityPlayer>) (java.util.List<?>) server
            .getConfigurationManager().playerEntityList;
        for (EntityPlayer p : players) {
            p.addChatMessage(message);
        }
    }

    private long parseTimeToMs(String time) {
        String str = time.toLowerCase();

        if (str.endsWith("s")) {
            return Long.parseLong(str.substring(0, str.length() - 1)) * 1000;
        } else if (str.endsWith("m")) {
            return Long.parseLong(str.substring(0, str.length() - 1)) * 60 * 1000;
        } else if (str.endsWith("h")) {
            return Long.parseLong(str.substring(0, str.length() - 1)) * 60 * 60 * 1000;
        } else if (str.endsWith("d")) {
            return Long.parseLong(str.substring(0, str.length() - 1)) * 24 * 60 * 60 * 1000;
        }

        throw new WrongUsageException("Неверный формат времени. Используйте: 10s, 5m, 2h, 1d");
    }

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return days + "д " + (hours % 24) + "ч";
        } else if (hours > 0) {
            return hours + "ч " + (minutes % 60) + "м";
        } else if (minutes > 0) {
            return minutes + "м " + (seconds % 60) + "с";
        } else {
            return seconds + "с";
        }
    }
}
