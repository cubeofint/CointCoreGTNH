package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraftforge.common.DimensionManager;

public class CommandRemover extends CommandBase {

    private static int lastDim = -2;

    @Override
    public String getCommandName() {
        return "remover";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/remover (dim <ID>) | (dim-undo)";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        switch (args[0]) {
            case "dim": {
                try {
                    int dimId = Integer.parseInt(args[1]);
                    if (dimId < 180) throw new WrongUsageException("ID измерения должно быть числом (>=180)");

                    DimensionManager.unloadWorld(dimId);
                    DimensionManager.unregisterDimension(dimId);
                    lastDim = dimId;
                } catch (NumberFormatException e) {
                    throw new WrongUsageException("ID измерения должно быть числом (>=180)");
                }
                return;
            }
            case "dim-undo": {
                DimensionManager.registerDimension(lastDim, lastDim);
                return;
            }
            default:
                throw new WrongUsageException(getCommandUsage(sender));
        }
    }
}
