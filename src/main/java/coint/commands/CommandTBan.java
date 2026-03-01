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
import coint.util.TimeUtil;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

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
            return PermissionAPI.hasPermission(player, "cointcore.command.tban");
        }
        return true; // console/RCON
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/tban <player> <time> 'reason' | /tban <player> remove";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String playerName = args[0].toLowerCase();

        ForgePlayer player = Universe.get()
            .getPlayer(playerName);
        if (player == null) {
            throw new PlayerNotFoundException();
        }

        if (args[1].equals("remove")) {
            PlayerTBanData.setOffline(player.getId(), null);
            return;
        }

        long durationMs = parseTimeToMs(args[1]);
        String reason = Joiner.on(" ")
            .join(Arrays.copyOfRange(args, 2, args.length));
        reason = reason.replaceAll("^['\"]|['\"]$", "");

        tban(sender, player, durationMs, reason);
    }

    private void tban(ICommandSender sender, ForgePlayer player, long durationMs, String reason) {
        TBan tban = new TBan(sender, reason, durationMs);

        if (player.isOnline()) {
            EntityPlayerMP entityPlayer = player.getPlayer();
            PlayerTBanData tbanData = PlayerTBanData.get(entityPlayer);
            if (tbanData != null) {
                tbanData.set(tban);
            }

            entityPlayer.playerNetServerHandler.kickPlayerFromServer(tban.getBanMessage());
        } else {
            PlayerTBanData.setOffline(player.getId(), tban);
        }

        ChatComponentText message = new ChatComponentText(
            EnumChatFormatting.GOLD + sender.getCommandSenderName()
                + EnumChatFormatting.RESET
                + " забанил "
                + EnumChatFormatting.GOLD
                + player.getName()
                + EnumChatFormatting.RESET
                + " на "
                + TimeUtil.formatDuration(durationMs)
                + EnumChatFormatting.RESET
                + ": "
                + EnumChatFormatting.YELLOW
                + reason);

        MinecraftServer.getServer()
            .addChatMessage(message);
    }

    private long parseTimeToMs(String time) {
        String str = time.toLowerCase();
        if (str.equals("perm")) {
            return -1;
        }

        long l = Long.parseLong(str.substring(0, str.length() - 1));
        if (str.endsWith("s")) {
            return l * 1000;
        } else if (str.endsWith("m")) {
            return l * 60 * 1000;
        } else if (str.endsWith("h")) {
            return l * 60 * 60 * 1000;
        } else if (str.endsWith("d")) {
            return l * 24 * 60 * 60 * 1000;
        }

        throw new WrongUsageException("Неверный формат времени. Используйте: 10s, 5m, 2h, 1d, perm");
    }
}
