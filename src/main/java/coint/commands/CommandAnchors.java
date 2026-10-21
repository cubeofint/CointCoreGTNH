package coint.commands;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.mojang.authlib.GameProfile;

import coint.worldtravel.WorldTravelManager;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * {@code /anchors} lists the Railcraft anchors in loaded chunks of every loaded world: all working world, personal
 * and admin anchors (they keep their own chunk loaded) and the passive ones that happen to be loaded. Each line
 * teleports there when clicked ({@code /anchors tp <dim> <x> <y> <z>}).
 */
public class CommandAnchors extends CommandBase {

    private static final String PERM = "cointcore.command.anchors";
    private static final String ANCHOR_PACKAGE = "mods.railcraft.common.blocks.machine.alpha.TileAnchor";

    public CommandAnchors() {
        PermissionAPI.registerNode(PERM, DefaultPermissionLevel.OP, "List Railcraft anchors in loaded chunks");
    }

    @Override
    public String getCommandName() {
        return "anchors";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/anchors  |  /anchors tp <dim> <x> <y> <z>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERM);
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 5 && "tp".equalsIgnoreCase(args[0])) {
            teleport(sender, args);
            return;
        }
        if (args.length != 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        List<TileEntity> anchors = new ArrayList<>();
        for (WorldServer world : DimensionManager.getWorlds()) {
            for (Object o : world.loadedTileEntityList) {
                TileEntity tile = (TileEntity) o;
                if (!tile.isInvalid() && tile.getClass()
                    .getName()
                    .startsWith(ANCHOR_PACKAGE)) {
                    anchors.add(tile);
                }
            }
        }
        if (anchors.isEmpty()) {
            sender.addChatMessage(new ChatComponentText("§aЯкорей Railcraft в загруженных чанках нет."));
            return;
        }
        sender.addChatMessage(
            new ChatComponentText(
                "§6Якоря Railcraft в загруженных чанках: §f" + anchors.size() + " §7(клик — телепорт)"));
        for (TileEntity tile : anchors) {
            int dim = tile.getWorldObj().provider.dimensionId;
            String kind = tile.getClass()
                .getSimpleName()
                .substring("TileAnchor".length());
            String command = "/anchors tp " + dim + " " + tile.xCoord + " " + tile.yCoord + " " + tile.zCoord;
            ChatComponentText line = new ChatComponentText(
                " §e" + kind
                    + " §7dim §f"
                    + dim
                    + " §7"
                    + tile.xCoord
                    + " "
                    + tile.yCoord
                    + " "
                    + tile.zCoord
                    + " §7владелец §f"
                    + owner(tile));
            line.setChatStyle(
                new ChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
            sender.addChatMessage(line);
        }
    }

    private static void teleport(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP player)) {
            sender.addChatMessage(new ChatComponentText("Только для игроков."));
            return;
        }
        int dim = parseInt(sender, args[1]);
        int x = parseInt(sender, args[2]);
        int y = parseInt(sender, args[3]);
        int z = parseInt(sender, args[4]);
        WorldServer world = DimensionManager.getWorld(dim);
        if (world == null) {
            ChatComponentText msg = new ChatComponentText("Измерение " + dim + " не загружено.");
            msg.getChatStyle()
                .setColor(EnumChatFormatting.RED);
            sender.addChatMessage(msg);
            return;
        }
        // On top of the anchor, not inside it.
        WorldTravelManager.teleportTo(player, world, x + 0.5D, y + 1.0D, z + 0.5D);
    }

    /** The owner Railcraft keeps on the tile, or "?" when there is none. */
    private static String owner(TileEntity tile) {
        try {
            Method getOwner = tile.getClass()
                .getMethod("getOwner");
            Object owner = getOwner.invoke(tile);
            if (owner instanceof GameProfile profile && profile.getName() != null) {
                return profile.getName();
            }
        } catch (ReflectiveOperationException ignored) {
            // Not a Railcraft tile after all: no owner to show.
        }
        return "?";
    }
}
