package coint.commands;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import coint.commands.warn.PlayerWarnsData;
import coint.commands.warn.Warn;
import coint.integration.serverutilities.SURanksManager;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;
import serverutils.ranks.Ranks;

public class CommandWarn extends CommandBase {

    public CommandWarn() {
        PermissionAPI.registerNode("cointcore.command.warn", DefaultPermissionLevel.OP, "CointCore warn permission");
    }

    @Override
    public String getCommandName() {
        return "warn";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return Ranks.INSTANCE.getPermission(player.getGameProfile(), "cointcore.command.warn", false)
                .getBoolean();
        }
        return false;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/warn add <player> 'reason' | /warn get <player>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String sub = args[0].toLowerCase();
        String playerName = args[1].toLowerCase();

        ForgePlayer player = SURanksManager.getInstance()
            .getUniverse()
            .getPlayer(playerName);
        if (player == null) {
            throw new PlayerNotFoundException();
        }

        switch (sub) {
            case "add": {
                if (args.length < 3) {
                    throw new WrongUsageException(getCommandUsage(sender));
                }
                String reason = args[2];
                Warn warn = new Warn(sender, reason);

                if (player.isOnline()) {
                    PlayerWarnsData warnsData = PlayerWarnsData.get(player.getPlayer());
                    warnsData.add(warn);
                } else {
                    PlayerWarnsData.addOffline(player.getId(), warn);
                }
            }
            case "get": {
                List<Warn> warns;

                if (player.isOnline()) {
                    PlayerWarnsData warnsData = PlayerWarnsData.get(player.getPlayer());
                    warns = warnsData.get();
                } else {
                    warns = PlayerWarnsData.getOffline(player.getId());
                }

                sender.addChatMessage(new ChatComponentText(playerName + ": " + warns.size() + " warns"));

                for (Warn warn : warns) {
                    Instant when = Instant.parse(warn.timestamp);
                    ZonedDateTime zdt = when.atZone(ZoneId.of("Europe/Moscow"));
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                    sender.addChatMessage(
                        new ChatComponentText(warn.warner + ":" + zdt.format(formatter) + " - " + warn.reason));
                }
            }
            default:
                throw new WrongUsageException(getCommandUsage(sender));
        }
    }
}
