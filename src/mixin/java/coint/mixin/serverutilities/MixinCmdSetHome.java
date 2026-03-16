package coint.mixin.serverutilities;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.integration.serverutilities.CointRankConfigs;
import serverutils.ServerUtilities;
import serverutils.ServerUtilitiesPermissions;
import serverutils.command.tp.CmdSetHome;
import serverutils.data.ServerUtilitiesPlayerData;
import serverutils.lib.command.CommandUtils;
import serverutils.lib.config.ConfigInt;
import serverutils.lib.config.ConfigValue;
import serverutils.lib.config.RankConfigAPI;
import serverutils.lib.math.BlockDimPos;

/**
 * Adds {@code cointcore.bonus_homes} on top of the base {@code serverutilities.homes.max}
 * limit enforced by {@link CmdSetHome#processCommand}.
 *
 * <p>
 * Redirects the {@code RankConfigAPI.get(player, HOMES_MAX)} call and returns
 * {@code base + bonus} as a {@link ConfigInt}. This approach is independent of
 * local-variable debug symbols in the target JAR.
 */
@Mixin(value = CmdSetHome.class, remap = false)
public class MixinCmdSetHome {

    /**
     * @author EternalQ
     * @reason Integrating bonus homes
     */
    @Overwrite(remap = false)
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player;
        if (sender instanceof EntityPlayerMP) {
            player = (EntityPlayerMP) sender;
        } else {
            throw new PlayerNotFoundException("You must specify which player you wish to perform this action on.", new Object[0]);
        }
        ServerUtilitiesPlayerData data = ServerUtilitiesPlayerData.get(CommandUtils.getForgePlayer(player));

        if (args.length == 0) {
            args = new String[]{"home"};
        }

        args[0] = args[0].toLowerCase();

        int maxHomes = RankConfigAPI.get(player, ServerUtilitiesPermissions.HOMES_MAX).getInt() + RankConfigAPI.get(player, CointRankConfigs.BONUS_HOMES).getInt();

        if (maxHomes <= 0 || data.homes.size() >= maxHomes) {
            if (maxHomes == 0 || data.homes.get(args[0]) == null) {
                throw ServerUtilities.error(sender, "serverutilities.lang.homes.limit");
            }
        }

        data.homes.set(args[0], new BlockDimPos(sender));
        sender.addChatMessage(ServerUtilities.lang(sender, "serverutilities.lang.homes.set", args[0]));
        data.player.markDirty();
    }
}
