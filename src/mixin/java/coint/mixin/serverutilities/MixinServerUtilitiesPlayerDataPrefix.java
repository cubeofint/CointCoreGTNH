package coint.mixin.serverutilities;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.prefix.PrefixService;
import serverutils.ServerUtilitiesPermissions;
import serverutils.data.ServerUtilitiesPlayerData;
import serverutils.lib.config.ConfigString;
import serverutils.lib.config.ConfigValue;
import serverutils.lib.data.ForgePlayer;

/** The name ServerUtilities builds for the tab list wears the prefix the player picked and his name color. */
@Mixin(value = ServerUtilitiesPlayerData.class, remap = false)
public abstract class MixinServerUtilitiesPlayerDataPrefix {

    @Redirect(
        method = "getNameForChat",
        at = @At(
            value = "INVOKE",
            target = "Lserverutils/lib/data/ForgePlayer;getRankConfig(Ljava/lang/String;)Lserverutils/lib/config/ConfigValue;"))
    private ConfigValue cointcore$pickedFormat(ForgePlayer player, String node) {
        ConfigValue value = player.getRankConfig(node);
        if (!ServerUtilitiesPermissions.CHAT_NAME_FORMAT.equals(node)) {
            return value;
        }
        EntityPlayerMP online = player.getNullablePlayer();
        if (online == null) {
            return value;
        }
        String format = PrefixService.format(online, value.getString());
        return format.equals(value.getString()) ? value : new ConfigString(format);
    }
}
