package coint.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import coint.integration.personalspace.PersonalSpaceBinding;
import coint.player.TeamsManager;
import me.eigenraven.personalspace.world.DimensionConfig;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandPDim extends CommandBase {

    private static final String PERMISSION = "cointcore.command.pdim";

    public CommandPDim() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "Lookup and manage PersonalSpace bindings");
    }

    @Override
    public String getCommandName() {
        return "pdim";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/pdim <player> | /pdim bind <player> <dim> | /pdim unbind <player>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 1) {
            lookup(sender, args[0]);
            return;
        }
        if (args.length == 3 && "bind".equalsIgnoreCase(args[0])) {
            bind(sender, args[1], args[2]);
            return;
        }
        if (args.length == 2 && "unbind".equalsIgnoreCase(args[0])) {
            unbind(sender, args[1]);
            return;
        }
        throw new WrongUsageException(getCommandUsage(sender));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            values.add("bind");
            values.add("unbind");
            for (ForgePlayer player : Universe.get()
                .getPlayers()) {
                values.add(player.getName());
            }
            return getListOfStringsMatchingLastWord(args, values.toArray(new String[0]));
        }
        if (args.length == 2 && ("bind".equalsIgnoreCase(args[0]) || "unbind".equalsIgnoreCase(args[0]))) {
            List<String> values = new ArrayList<>();
            for (ForgePlayer player : Universe.get()
                .getPlayers()) {
                values.add(player.getName());
            }
            return getListOfStringsMatchingLastWord(args, values.toArray(new String[0]));
        }
        return super.addTabCompletionOptions(sender, args);
    }

    private void lookup(ICommandSender sender, String playerName) throws CommandException {
        ForgePlayer player = requireForgePlayer(playerName);
        ForgeTeam team = requireTeam(player);
        TeamsManager manager = TeamsManager.get();
        int dimId = manager.getDim(team);

        if (dimId == 0 && player.isOnline()) {
            EntityPlayerMP online = player.getPlayer();
            if (PersonalSpaceBinding.isPersonalDimension(online.dimension)) {
                manager.bindDimIfFree(team, online.dimension);
                dimId = manager.getDim(team);
            }
        }

        if (dimId == 0) {
            Set<Integer> found = PersonalSpaceBinding.findHistoricalPersonalDimensions(team);
            if (found.size() == 1) {
                int detected = found.iterator()
                    .next();
                if (manager.bindDimIfFree(team, detected)) {
                    dimId = detected;
                }
            } else if (found.size() > 1) {
                throw new CommandException(
                    "У команды игрока " + player.getName()
                        + " найдено несколько PersonalSpace: "
                        + found
                        + ". Привяжи нужную: /pdim bind "
                        + player.getName()
                        + " <dim>");
            }
        }

        if (dimId == 0) {
            throw new CommandException(
                "Для команды игрока " + player.getName()
                    + " привязка не найдена. Не найдено старых home/клеймов/онлайн-игроков в PersonalSpace");
        }

        DimensionConfig config = DimensionConfig.getForDimension(dimId, false);
        String saveDir = config == null ? "PERSONAL_DIM_" + dimId : config.getSaveDir(dimId);
        sender.addChatMessage(
            new ChatComponentText("§a" + player.getName() + " §7-> §f" + saveDir + " §7(dim §f" + dimId + "§7)"));
    }

    private void bind(ICommandSender sender, String playerName, String dimText) throws CommandException {
        ForgePlayer player = requireForgePlayer(playerName);
        ForgeTeam team = requireTeam(player);
        int dimId;
        try {
            dimId = Integer.parseInt(dimText);
        } catch (NumberFormatException e) {
            throw new CommandException("Некорректный DIM: " + dimText);
        }
        if (!PersonalSpaceBinding.isPersonalDimension(dimId)) {
            throw new CommandException("DIM " + dimId + " не является PersonalSpace");
        }

        TeamsManager manager = TeamsManager.get();
        Short owner = manager.getTeamUidForDim(dimId);
        if (owner != null && owner.shortValue() != team.getUID()) {
            throw new CommandException("DIM " + dimId + " уже привязан к другой команде (UID " + owner + ")");
        }
        manager.bindDim(team, dimId);
        DimensionConfig config = DimensionConfig.getForDimension(dimId, false);
        sender.addChatMessage(
            new ChatComponentText(
                "§aПривязано: §f" + player
                    .getName() + " §7-> §f" + config.getSaveDir(dimId) + " §7(dim §f" + dimId + "§7)"));
    }

    private void unbind(ICommandSender sender, String playerName) throws CommandException {
        ForgePlayer player = requireForgePlayer(playerName);
        ForgeTeam team = requireTeam(player);
        if (!TeamsManager.get()
            .removeDimBind(team)) {
            throw new CommandException("Для команды игрока " + player.getName() + " сохранённой привязки нет");
        }
        sender.addChatMessage(
            new ChatComponentText("§aПривязка персоналки команды игрока §f" + player.getName() + " §aудалена"));
    }

    private ForgePlayer requireForgePlayer(String name) throws CommandException {
        ForgePlayer player = Universe.get()
            .getPlayer(name);
        if (player == null) {
            throw new CommandException("Игрок не найден: " + name);
        }
        return player;
    }

    private ForgeTeam requireTeam(ForgePlayer player) throws CommandException {
        if (!player.hasTeam()) {
            throw new CommandException("Игрок " + player.getName() + " не состоит в команде ServerUtilities");
        }
        return player.team;
    }
}
