package coint.mixin.serverutilities;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.integration.serverutilities.CointRankConfigs;
import serverutils.ServerUtilitiesPermissions;
import serverutils.command.tp.CmdHome;
import serverutils.lib.config.ConfigInt;
import serverutils.lib.config.ConfigValue;
import serverutils.lib.config.RankConfigAPI;
import serverutils.lib.data.ForgePlayer;

/**
 * Fixes the {@code /home list} display so it shows {@code size / (base + bonus)}
 * rather than just the base {@code serverutilities.homes.max} value.
 *
 * <p>
 * Redirects the call to {@link ForgePlayer#getRankConfig(String)} inside
 * {@code CmdHome.processCommand} and, when the node is {@code HOMES_MAX},
 * adds the player's {@code cointcore.bonus_homes} value before returning.
 */
@Mixin(value = CmdHome.class, remap = false)
public class MixinCmdHome {

    @Redirect(
        method = "processCommand(Lnet/minecraft/command/ICommandSender;[Ljava/lang/String;)V",
        at = @At(
            value = "INVOKE",
            target = "Lserverutils/lib/data/ForgePlayer;getRankConfig(Ljava/lang/String;)Lserverutils/lib/config/ConfigValue;"))
    @SuppressWarnings("unused")
    private ConfigValue cointcore$withBonusHomesDisplay(ForgePlayer p, String node) {
        ConfigValue base = RankConfigAPI.get(MinecraftServer.getServer(), p.getProfile(), node);
        if (ServerUtilitiesPermissions.HOMES_MAX.equals(node)) {
            int bonus = RankConfigAPI.get(MinecraftServer.getServer(), p.getProfile(), CointRankConfigs.BONUS_HOMES)
                .getInt();
            if (bonus > 0) {
                return new ConfigInt(base.getInt() + bonus);
            }
        }
        return base;
    }
}
