package coint.cluster;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.lib.math.TeleporterDimPos;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Where a player can do nothing anyway, he should come to no harm: on the hub everyone, and guests on the other
 * servers, fly, take no damage, see in the dark, never go hungry, and mobs leave them alone
 * ({@link CointConfig.Cluster#guestBuffs}). Gone again once he is no longer a guest, even after a relog.
 *
 * <p>
 * The hub also holds its players: they come in on the world spawn, are put back on it when they stray past
 * {@link CointConfig.Cluster#hubRadius} or into another dimension, and no hostile mobs spawn there.
 */
@EventBusSubscriber
public final class ClusterComfort {

    public static final String HUB_BYPASS = "cointcore.cluster.hub_bypass";
    /** In the player's own data: the abilities he has are ours, to be taken back. */
    private static final String MARK = "cointcoreComfort";
    private static final int NIGHT_VISION_TICKS = 400;

    /** Logged in on the hub: to the spawn on the next pass. */
    private static final Set<UUID> toSpawn = new HashSet<>();
    private static int tick;

    private ClusterComfort() {}

    static void registerPermissions() {
        PermissionAPI.registerNode(
            HUB_BYPASS,
            DefaultPermissionLevel.OP,
            "Not held near the spawn on the hub, and may use world travel there");
    }

    private static boolean onHub() {
        return ClusterService.isRunning() && ClusterHub.isHub();
    }

    /** On the hub and held there: no world travel, kept near the spawn. */
    public static boolean hubHeld(EntityPlayerMP player) {
        return onHub() && !PermissionAPI.hasPermission(player, HUB_BYPASS);
    }

    private static boolean comforted(EntityPlayer player) {
        return CointConfig.cluster.guestBuffs && !player.capabilities.isCreativeMode
            && (onHub() || ClusterHomes.isGuest(player));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++tick % 20 != 0) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        for (Object o : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player.isDead) {
                continue;
            }
            if (comforted(player)) {
                comfort(player);
            } else if (player.getEntityData()
                .getBoolean(MARK)) {
                    uncomfort(player);
                }
            if (hubHeld(player)) {
                hold(player);
            }
        }
    }

    private static void comfort(EntityPlayerMP player) {
        PlayerCapabilities abilities = player.capabilities;
        if (!abilities.allowFlying || !abilities.disableDamage) {
            abilities.allowFlying = true;
            abilities.disableDamage = true;
            player.sendPlayerAbilities();
        }
        PotionEffect sight = player.getActivePotionEffect(Potion.nightVision);
        if (sight == null || sight.getDuration() < 220) {
            player.addPotionEffect(new PotionEffect(Potion.nightVision.id, NIGHT_VISION_TICKS, 0, true));
        }
        if (player.getFoodStats()
            .needFood()) {
            player.getFoodStats()
                .addStats(20, 20F);
        }
        player.extinguish();
        player.getEntityData()
            .setBoolean(MARK, true);
    }

    private static void uncomfort(EntityPlayerMP player) {
        NBTTagCompound data = player.getEntityData();
        data.removeTag(MARK);
        if (!player.capabilities.isCreativeMode) {
            player.capabilities.allowFlying = false;
            player.capabilities.isFlying = false;
            player.capabilities.disableDamage = false;
            player.sendPlayerAbilities();
        }
        PotionEffect sight = player.getActivePotionEffect(Potion.nightVision);
        // Only ours: night vision from a potion or /nv lasts longer.
        if (sight != null && sight.getDuration() <= NIGHT_VISION_TICKS) {
            player.removePotionEffect(Potion.nightVision.id);
        }
    }

    private static void hold(EntityPlayerMP player) {
        boolean arrived = toSpawn.remove(player.getUniqueID());
        int radius = CointConfig.cluster.hubRadius;
        WorldServer world = MinecraftServer.getServer()
            .worldServerForDimension(0);
        ChunkCoordinates spawn = world.getSpawnPoint();
        double dx = player.posX - (spawn.posX + 0.5), dz = player.posZ - (spawn.posZ + 0.5);
        boolean strayed = player.dimension != 0 || player.posY < 0
            || radius > 0 && dx * dx + dz * dz > (double) radius * radius;
        if (!arrived && !strayed) {
            return;
        }
        int y = Math.max(spawn.posY, world.getTopSolidOrLiquidBlock(spawn.posX, spawn.posZ));
        player.fallDistance = 0;
        TeleporterDimPos.of(spawn.posX + 0.5, y, spawn.posZ + 0.5, 0)
            .teleport(player);
        if (strayed && !arrived) {
            player.addChatMessage(
                new ChatComponentText("§eДальше хаба не уйти. §7Другие серверы: клавиша M или /server"));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player && hubHeld(player)) {
            toSpawn.add(player.getUniqueID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        toSpawn.remove(event.player.getUniqueID());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttacked(LivingAttackEvent event) {
        if (event.entityLiving instanceof EntityPlayerMP player && comforted(player)) {
            event.setCanceled(true);
        }
    }

    /** Mobs leave them alone: whatever picks one as a target drops it at once. */
    @SubscribeEvent
    public static void onTarget(LivingSetAttackTargetEvent event) {
        if (!(event.target instanceof EntityPlayerMP player) || !comforted(player)) {
            return;
        }
        if (event.entityLiving instanceof EntityLiving living && living.getAttackTarget() == player) {
            living.setAttackTarget(null);
        }
        if (event.entityLiving instanceof EntityCreature creature && creature.getEntityToAttack() == player) {
            creature.setTarget(null);
        }
    }

    /** No hostile mobs on the hub. */
    @SubscribeEvent
    public static void onCheckSpawn(LivingSpawnEvent.CheckSpawn event) {
        if (event.entityLiving instanceof IMob && onHub()) {
            event.setResult(Event.Result.DENY);
        }
    }

    static void reset() {
        toSpawn.clear();
    }
}
