package coint.commands;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.google.common.base.Joiner;

import coint.commands.warn.PlayerWarnsData;
import coint.commands.warn.Warn;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
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
        return "/warn add <player> 'reason' | /warn get <player> | /warn clear <player>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String sub = args[0].toLowerCase();
        String playerName = args[1].toLowerCase();

        ForgePlayer player = Universe.get()
            .getPlayer(playerName);
        if (player == null) {
            throw new PlayerNotFoundException();
        }

        switch (sub) {
            case "add": {
                if (args.length < 3) {
                    throw new WrongUsageException(getCommandUsage(sender));
                }

                String reason = Joiner.on(" ")
                    .join(Arrays.copyOfRange(args, 2, args.length));
                reason = reason.replaceAll("^['\"]|['\"]$", "");
                Warn warn = new Warn(sender, reason);

                if (player.isOnline()) {
                    EntityPlayer entityPlayer = player.getPlayer();
                    PlayerWarnsData warnsData = PlayerWarnsData.get(entityPlayer);
                    warnsData.add(warn);

                    entityPlayer.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.RED + "Вам выдано предупреждение от "
                                + EnumChatFormatting.GOLD
                                + sender.getCommandSenderName()
                                + EnumChatFormatting.RED
                                + " по причине: "
                                + EnumChatFormatting.YELLOW
                                + reason));
                } else {
                    PlayerWarnsData.addOffline(player.getId(), warn);
                }

                // Broadcast to all players
                MinecraftServer server = MinecraftServer.getServer();
                ChatComponentText message = new ChatComponentText(
                    EnumChatFormatting.GOLD + sender.getCommandSenderName()
                        + EnumChatFormatting.RESET
                        + " выдал варн "
                        + EnumChatFormatting.GOLD
                        + playerName
                        + EnumChatFormatting.RESET
                        + " по причине: "
                        + EnumChatFormatting.YELLOW
                        + reason);

                @SuppressWarnings("unchecked")
                java.util.List<EntityPlayer> players = (java.util.List<EntityPlayer>) (java.util.List<?>) server
                    .getConfigurationManager().playerEntityList;
                for (EntityPlayer p : players) {
                    p.addChatMessage(message);
                }

                sender.addChatMessage(new ChatComponentText("Add warn for " + playerName));
                break;
            }
            case "get": {
                List<Warn> warns;

                if (player.isOnline()) {
                    PlayerWarnsData warnsData = PlayerWarnsData.get(player.getPlayer());
                    warns = warnsData.get();
                } else {
                    warns = PlayerWarnsData.getOffline(player.getId());
                }

                if (warns == null) {
                    warns = new java.util.ArrayList<>();
                }

                sender.addChatMessage(new ChatComponentText(playerName + ": " + warns.size() + " warn(s)"));

                int i = 1;
                for (Warn warn : warns) {
                    Instant when = Instant.parse(warn.timestamp);
                    ZonedDateTime zdt = when.atZone(ZoneId.of("Europe/Moscow"));
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                    sender.addChatMessage(
                        new ChatComponentText(
                            i + ". "
                                + warn.warner
                                + EnumChatFormatting.GREEN
                                + ":"
                                + EnumChatFormatting.RESET
                                + zdt.format(formatter)
                                + " - "
                                + EnumChatFormatting.YELLOW
                                + warn.reason));
                    i++;
                }
                break;
            }
            case "remove": {
                if (args.length < 3) {
                    throw new WrongUsageException(getCommandUsage(sender));
                }
                int i = parseInt(sender, args[2]) - 1;

                if (player.isOnline()) {
                    PlayerWarnsData warnsData = PlayerWarnsData.get(player.getPlayer());
                    warnsData.remove(i);
                } else {
                    PlayerWarnsData.removeOffline(player.getId(), i);
                }

                sender.addChatMessage(new ChatComponentText("Варн №" + (i + 1) + " снят с игрока " + playerName));
                break;
            }
            case "clear": {
                if (player.isOnline()) {
                    PlayerWarnsData.get(player.getPlayer())
                        .clear();
                } else {
                    PlayerWarnsData.clearOffline(player.getId());
                }

                sender.addChatMessage(new ChatComponentText(playerName + "'s warns cleared"));
                break;
            }
            default: {
                throw new WrongUsageException(getCommandUsage(sender));
            }
        }
    }
}
