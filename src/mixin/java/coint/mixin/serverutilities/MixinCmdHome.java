package coint.mixin.serverutilities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.integration.serverutilities.CointRankConfigs;
import serverutils.ServerUtilitiesPermissions;
import serverutils.command.tp.CmdHome;
import serverutils.lib.config.ConfigInt;
import serverutils.lib.config.ConfigValue;
import serverutils.lib.data.ForgePlayer;

/**
 * Fixes the {@code /home list} display so it shows {@code size / (base + bonus)}
 * rather than just the base {@code serverutilities.homes.max} value.
 *
 * <p>
 * Redirects the target player's rank lookup, so viewing another player's homes
 * also shows that player's bonus rather than the sender's.
 */
@Mixin(value = CmdHome.class, remap = false)
public class MixinCmdHome {

    @Redirect(
        method = "processCommand",
        remap = true,
        at = @At(
            value = "INVOKE",
            target = "Lserverutils/lib/data/ForgePlayer;getRankConfig(Ljava/lang/String;)Lserverutils/lib/config/ConfigValue;",
            remap = false))
    private ConfigValue cointcore$withBonusHomesDisplay(ForgePlayer player, String node) {
        ConfigValue value = player.getRankConfig(node);
        if (ServerUtilitiesPermissions.HOMES_MAX.equals(node)) {
            return new ConfigInt(
                value.getInt() + player.getRankConfig(CointRankConfigs.BONUS_HOMES)
                    .getInt());
        }
        return value;
    }
}
