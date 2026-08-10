package coint.mixin.minecraft;

import java.net.SocketAddress;
import java.text.SimpleDateFormat;
import java.util.stream.Collectors;

import net.minecraft.server.management.BanList;
import net.minecraft.server.management.IPBanEntry;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.server.management.UserListBans;
import net.minecraft.server.management.UserListBansEntry;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import com.mojang.authlib.GameProfile;

import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.PermissionAPI;

@Mixin(targets = "net.minecraft.server.management.ServerConfigurationManager", remap = true)
public class MixinServerConfigurationManager {

    @Shadow
    UserListBans bannedPlayers;

    @Shadow
    BanList bannedIPs;

    @Shadow
    int maxPlayers;

    /**
     * @author EternalQ
     * @reason Control teammate connection
     */
    @Overwrite(aliases = { "func_148542_a" })
    public String allowUserToConnect(SocketAddress address, GameProfile profile) {
        var mgr = (ServerConfigurationManager) (Object) this;
        var dateFormat = new SimpleDateFormat("yyyy-MM-dd \'в\' HH:mm:ss МСК");
        String s;

        if (this.bannedPlayers.func_152702_a(profile)) {
            UserListBansEntry userlistbansentry = (UserListBansEntry) this.bannedPlayers.func_152683_b(profile);
            s = "Вы забанены на сервере!\nПричина: " + userlistbansentry.getBanReason();

            if (userlistbansentry.getBanEndDate() != null) {
                s = s + "\nБан закончится " + dateFormat.format(userlistbansentry.getBanEndDate());
            }

            return s;
        } else if (!mgr.func_152607_e(profile)) {
            return "Вас нет в белом списке сервера!";
        } else if (this.bannedIPs.func_152708_a(address)) {
            IPBanEntry ipbanentry = this.bannedIPs.func_152709_b(address);
            s = "Ваш IP-адрес забанен на сервере!\nПричина: " + ipbanentry.getBanReason();

            if (ipbanentry.getBanEndDate() != null) {
                s = s + "\nБан закончится " + dateFormat.format(ipbanentry.getBanEndDate());
            }

            return s;
        } else if (PermissionAPI.hasPermission(profile, "cointcore.kit.uranium", null)) {
            return null;
        } else {
            return getCurrentPlayerCount() >= this.maxPlayers ? "Сервер переполнен!" : null;
        }
    }

    /**
     * @author EternalQ
     * @reason show online teams
     */
    @Overwrite(aliases = { "func_72394_k" })
    public int getCurrentPlayerCount() {
        return Universe.get()
            .getOnlinePlayers()
            .stream()
            .map(player -> player.team)
            .collect(Collectors.toSet())
            .size();
    }
}
