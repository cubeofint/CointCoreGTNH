package coint.cluster;

import java.io.File;
import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import coint.player.PlayerLookup;
import coint.util.TimeUtil;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

/**
 * /seen &lt;nick&gt;: where a player is now, or where and how long ago he was last seen, on any server of the network.
 * Without the network: when he was last here.
 */
public class CommandSeen extends CommandBase {

    @Override
    public String getCommandName() {
        return "seen";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/seen <ник>";
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        return getListOfStringsMatchingLastWord(
            args,
            Universe.get()
                .getPlayers()
                .stream()
                .map(ForgePlayer::getName)
                .toArray(String[]::new));
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        String name = args[0];
        EntityPlayerMP here = MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152612_a(name);
        if (here != null) {
            sender.addChatMessage(
                new ChatComponentText("§b" + here.getCommandSenderName() + "§a сейчас в сети на этом сервере."));
            return;
        }
        String remote = ClusterService.remotePlayerName(name);
        if (remote != null) {
            sender.addChatMessage(
                new ChatComponentText(
                    "§b" + remote + "§a сейчас в сети на сервере " + ClusterService.remotePlayerServer(remote) + "."));
            return;
        }
        boolean asked = ClusterService.query(db -> db.seen(name), row -> {
            if (row == null) {
                local(sender, name);
                return;
            }
            sender.addChatMessage(
                new ChatComponentText(
                    "§b" + row.name
                        + "§7 был в сети "
                        + TimeUtil.formatDuration(Math.max(0, row.secondsAgo) * 1000L)
                        + " назад на сервере "
                        + row.nodeName
                        + "."));
        }, error -> local(sender, name));
        if (!asked) {
            local(sender, name);
        }
    }

    /** When he was last on this server: his player file is written when he leaves. */
    private static void local(ICommandSender sender, String name) {
        ForgePlayer player = PlayerLookup.exact(name);
        if (player == null) {
            sender.addChatMessage(new ChatComponentText("§cИгрок " + name + " ни разу не заходил."));
            return;
        }
        File dir = MinecraftServer.getServer()
            .worldServerForDimension(0)
            .getSaveHandler()
            .getWorldDirectory();
        File dat = new File(new File(dir, "playerdata"), player.getId() + ".dat");
        if (!dat.isFile()) {
            sender.addChatMessage(new ChatComponentText("§7Нет данных о том, когда " + player.getName() + " заходил."));
            return;
        }
        long ago = Math.max(0, System.currentTimeMillis() - dat.lastModified());
        sender.addChatMessage(
            new ChatComponentText(
                "§b" + player.getName() + "§7 был в сети " + TimeUtil.formatDuration(ago) + " назад на этом сервере."));
    }
}
