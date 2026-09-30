package coint.commands;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import coint.CointCore;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.modularui.IControllerWithOptionalFeatures;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandBatchModeAll extends CommandBase {

    private static final String PERM_BATCH_MODE_ALL = "cointcore.command.batchmodeall";

    public CommandBatchModeAll() {
        PermissionAPI.registerNode(
            PERM_BATCH_MODE_ALL,
            DefaultPermissionLevel.OP,
            "CointCore enable Batch Mode on loaded GT multiblocks");
    }

    @Override
    public String getCommandName() {
        return "batchmodeall";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/batchmodeall";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERM_BATCH_MODE_ALL);
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        int worlds = 0;
        int gregTechTiles = 0;
        int supported = 0;
        int enabled = 0;
        int alreadyEnabled = 0;
        int errors = 0;

        for (WorldServer world : DimensionManager.getWorlds()) {
            if (world == null) continue;
            worlds++;

            List<TileEntity> tiles = new ArrayList<>(world.loadedTileEntityList);
            for (TileEntity tile : tiles) {
                if (!(tile instanceof IGregTechTileEntity gtTile)) continue;

                gregTechTiles++;

                try {
                    IMetaTileEntity meta = gtTile.getMetaTileEntity();
                    if (!(meta instanceof IControllerWithOptionalFeatures controller)) continue;
                    if (!controller.supportsBatchMode()) continue;
                    supported++;

                    if (controller.isBatchModeEnabled()) {
                        alreadyEnabled++;
                        continue;
                    }

                    controller.setBatchMode(true);
                    tile.markDirty();
                    world.markBlockForUpdate(tile.xCoord, tile.yCoord, tile.zCoord);
                    enabled++;
                } catch (RuntimeException | LinkageError e) {
                    errors++;
                    CointCore.LOG.warn(
                        "Failed to enable Batch Mode at dim {} {},{},{} ({})",
                        world.provider.dimensionId,
                        tile.xCoord,
                        tile.yCoord,
                        tile.zCoord,
                        tile.getClass()
                            .getName(),
                        e);
                }
            }
        }

        send(
            sender,
            EnumChatFormatting.GREEN,
            "Batch Mode: включено " + enabled
                + ", уже включено "
                + alreadyEnabled
                + ", поддерживают "
                + supported
                + ".");
        send(
            sender,
            EnumChatFormatting.GRAY,
            "Проверено GT tile entities: " + gregTechTiles + " в " + worlds + " загруженных мирах.");

        if (errors > 0) {
            send(sender, EnumChatFormatting.YELLOW, "Ошибок: " + errors + ". Подробности в server log.");
        }

        send(
            sender,
            EnumChatFormatting.YELLOW,
            "Команда обрабатывает только машины в загруженных чанках; выгруженные персоналки не затрагиваются.");
    }

    private static void send(ICommandSender sender, EnumChatFormatting color, String text) {
        ChatComponentText message = new ChatComponentText(text);
        message.getChatStyle()
            .setColor(color);
        sender.addChatMessage(message);
    }
}
