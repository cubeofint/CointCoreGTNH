package coint.trade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import coint.cluster.ClusterService;

/** /trade &lt;nick&gt;, /trade accept|deny [nick], /trade cancel. */
public class CommandTrade extends CommandBase {

    @Override
    public String getCommandName() {
        return "trade";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender instanceof EntityPlayerMP;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/trade <ник> | /trade accept [ник] | /trade deny [ник] | /trade cancel";
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        String[] online = MinecraftServer.getServer()
            .getAllUsernames();
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            Collections.addAll(options, "accept", "deny", "cancel");
            Collections.addAll(options, online);
            return getListOfStringsMatchingLastWord(args, options.toArray(new String[0]));
        }
        if (args.length == 2 && ("accept".equalsIgnoreCase(args[0]) || "deny".equalsIgnoreCase(args[0]))) {
            return getListOfStringsMatchingLastWord(args, online);
        }
        return Collections.emptyList();
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP player) || args.length < 1 || args.length > 2) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        String first = args[0];
        String name = args.length > 1 ? args[1] : null;
        switch (first.toLowerCase(java.util.Locale.ROOT)) {
            case "accept" -> TradeManager.accept(player, name);
            case "deny" -> TradeManager.deny(player, name);
            case "cancel" -> TradeManager.cancel(player);
            default -> {
                if (args.length != 1) {
                    throw new WrongUsageException(getCommandUsage(sender));
                }
                EntityPlayerMP target = MinecraftServer.getServer()
                    .getConfigurationManager()
                    .func_152612_a(first);
                if (target != null) {
                    TradeManager.request(player, target);
                    return;
                }
                String remote = ClusterService.remotePlayerName(first);
                if (remote != null) {
                    player.addChatMessage(
                        TradeManager.text(
                            "§b" + remote
                                + "§c сейчас на сервере "
                                + ClusterService.remotePlayerServer(remote)
                                + ". Обмен возможен только на одном сервере."));
                } else {
                    player.addChatMessage(TradeManager.text("§cИгрок §b" + first + "§c не в сети."));
                }
            }
        }
    }
}
