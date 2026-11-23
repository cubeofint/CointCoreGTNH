package coint.mixin.minecraft;

import java.net.SocketAddress;

import net.minecraft.server.management.BanList;
import net.minecraft.server.management.IPBanEntry;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.server.management.UserListBans;
import net.minecraft.server.management.UserListBansEntry;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.authlib.GameProfile;

import coint.CointConfig;
import coint.integration.serverutilities.CointSUPermissions;
import coint.util.TimeUtil;
import serverutils.lib.util.permission.PermissionAPI;

@Mixin(targets = "net.minecraft.server.management.ServerConfigurationManager", remap = true)
public class MixinServerConfigurationManager {

    @Shadow
    UserListBans bannedPlayers;

    @Shadow
    BanList bannedIPs;

    @Shadow
    int maxPlayers;

    // Keep the server log, but do not broadcast routine connection announcements to players.
    @Inject(
        method = "sendChatMsgImpl",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/management/ServerConfigurationManager;sendPacketToAllPlayers(Lnet/minecraft/network/Packet;)V"),
        cancellable = true,
        require = 1)
    private void cointcore$hideConnectionAnnouncements(IChatComponent component, boolean isChat, CallbackInfo ci) {
        if (component instanceof ChatComponentTranslation translation) {
            String key = translation.getKey();
            if ("multiplayer.player.joined".equals(key) || "multiplayer.player.joined.renamed".equals(key)
                || "multiplayer.player.left".equals(key)) {
                ci.cancel();
            }
        }
    }

    // Run after loading the saved position, before vanilla tries to hotload that world.
    @Inject(
        method = "initializeConnectionToPlayer(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/entity/player/EntityPlayerMP;Lnet/minecraft/network/NetHandlerPlayServer;)V",
        remap = false,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/management/ServerConfigurationManager;readPlayerDataFromFile(Lnet/minecraft/entity/player/EntityPlayerMP;)Lnet/minecraft/nbt/NBTTagCompound;",
            shift = At.Shift.AFTER,
            remap = true),
        require = 1)
    private void cointcore$spawnAfterPersonalWorldRetirement(net.minecraft.network.NetworkManager network,
        net.minecraft.entity.player.EntityPlayerMP player, net.minecraft.network.NetHandlerPlayServer handler,
        CallbackInfo ci) {
        int previous = player.dimension;
        if (previous == 0 || net.minecraftforge.common.DimensionManager.isDimensionRegistered(previous)
            || !coint.player.TeamsManager.get()
                .isRetiredPDim(previous))
            return;
        net.minecraft.world.WorldServer world = net.minecraftforge.common.DimensionManager.getWorld(0);
        if (world == null) return;
        net.minecraft.util.ChunkCoordinates spawn = world.getSpawnPoint();
        player.dimension = 0;
        player.setPositionAndRotation(spawn.posX + 0.5D, spawn.posY, spawn.posZ + 0.5D, 0.0F, 0.0F);
        player.motionX = player.motionY = player.motionZ = 0.0D;
        player.fallDistance = 0.0F;
        coint.CointCore.LOG.info(
            "[Migration] {} returned to spawn on login: personal dimension {} retired",
            player.getCommandSenderName(),
            previous);
    }

    @Inject(
        method = "transferPlayerToDimension(Lnet/minecraft/entity/player/EntityPlayerMP;ILnet/minecraft/world/Teleporter;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false)
    private void cointcore$blockMovingDimension(net.minecraft.entity.player.EntityPlayerMP player, int dimension,
        net.minecraft.world.Teleporter teleporter, CallbackInfo ci) {
        if (coint.migration.MigrationGuard.dimension(dimension)) {
            player.addChatMessage(
                new net.minecraft.util.ChatComponentText("§eИзмерение заблокировано переносом команды."));
            ci.cancel();
        }
    }

    @Inject(
        method = "transferPlayerToDimension(Lnet/minecraft/entity/player/EntityPlayerMP;I)V",
        at = @At("HEAD"),
        cancellable = true)
    private void cointcore$blockMovingDimensionEarly(net.minecraft.entity.player.EntityPlayerMP player, int dimension,
        CallbackInfo ci) {
        if (coint.migration.MigrationGuard.dimension(dimension)) {
            player.addChatMessage(
                new net.minecraft.util.ChatComponentText("§eИзмерение заблокировано переносом команды."));
            ci.cancel();
        }
    }

    /**
     * @author EternalQ
     * @reason Control player connection and reserved slots
     */
    @Overwrite(aliases = { "func_148542_a" })
    public String allowUserToConnect(SocketAddress address, GameProfile profile) {
        String migration = coint.migration.MigrationGuard.loginReason(profile.getId());
        if (migration != null) return migration;
        var mgr = (ServerConfigurationManager) (Object) this;
        String s;

        // Read through the list: that drops a ban that has run out, and then there is none.
        UserListBansEntry userlistbansentry = (UserListBansEntry) this.bannedPlayers.func_152683_b(profile);
        if (userlistbansentry != null) {
            s = "Вы забанены на сервере!\nПричина: " + userlistbansentry.getBanReason();

            if (userlistbansentry.getBanEndDate() != null) {
                s = s + "\nБан закончится " + TimeUtil.formatBanExpiry(userlistbansentry.getBanEndDate());
            }

            return s;
        } else if (!mgr.func_152607_e(profile)) {
            return "Вас нет в белом списке сервера!";
        }

        // Checking raw membership before lookup also matches expired IP bans; lookup removes those first.
        IPBanEntry ipbanentry = this.bannedIPs.func_152709_b(address);
        if (ipbanentry != null) {
            s = "Ваш IP-адрес забанен на сервере!\nПричина: " + ipbanentry.getBanReason();

            if (ipbanentry.getBanEndDate() != null) {
                s = s + "\nБан закончится " + TimeUtil.formatBanExpiry(ipbanentry.getBanEndDate());
            }

            return s;
        }

        int online = mgr.getCurrentPlayerCount();
        int reserved = Math.max(0, CointConfig.general.reservedSlots);
        boolean hasReservedSlot = PermissionAPI.hasPermission(profile, CointSUPermissions.RESERVED_SLOT, null);

        if (coint.cluster.ClusterEntryQueue.hasRoom(profile, online, this.maxPlayers, reserved, hasReservedSlot)) {
            return null;
        }

        if (!hasReservedSlot) {
            return reserved > 0 ? "На сервере остались только резервные слоты!" : "Сервер переполнен!";
        }

        return "Все доступные места заняты или зарезервированы. Войдите через хаб и выберите сервер для очереди.";
    }
}
