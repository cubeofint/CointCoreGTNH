package coint.mixin.serverutilities;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.integration.serverutilities.CointRankConfigs;
import serverutils.ServerUtilitiesPermissions;
import serverutils.command.tp.CmdSetHome;
import serverutils.lib.config.ConfigInt;
import serverutils.lib.config.ConfigValue;
import serverutils.lib.config.RankConfigAPI;

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
     * Intercepts the {@code RankConfigAPI.get(player, node)} call used to read
     * {@code maxHomes} and adds the player's {@code cointcore.bonus_homes} rank
     * config value on top.
     */
    @Redirect(
        method = "processCommand(Lnet/minecraft/command/ICommandSender;[Ljava/lang/String;)V",
        at = @At(
            value = "INVOKE",
            target = "Lserverutils/lib/config/RankConfigAPI;get(Lnet/minecraft/entity/player/EntityPlayerMP;Ljava/lang/String;)Lserverutils/lib/config/ConfigValue;"))
    @SuppressWarnings("unused")
    private ConfigValue cointcore$getMaxHomesWithBonus(EntityPlayerMP player, String node) {
        ConfigValue base = RankConfigAPI.get(player, node);
        if (ServerUtilitiesPermissions.HOMES_MAX.equals(node)) {
            int bonus = RankConfigAPI.get(player, CointRankConfigs.BONUS_HOMES)
                .getInt();
            if (bonus > 0) {
                return new ConfigInt(base.getInt() + bonus);
            }
        }
        return base;
    }
}
