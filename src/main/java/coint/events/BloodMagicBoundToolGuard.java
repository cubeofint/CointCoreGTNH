package coint.events;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.util.ClaimGuardNotifier;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.data.ClaimedChunks;

/**
 * Blocks BloodMagic bound-tool AoE activation in foreign ServerUtilities claims.
 *
 * <p>
 * This guard does not rely on mixin target timing and runs on server-side
 * interact events before item right-click logic executes.
 */
@EventBusSubscriber
public class BloodMagicBoundToolGuard {

    private static final Set<String> BOUND_TOOL_CLASSES = new HashSet<>(
        Arrays.asList(
            "WayofTime.alchemicalWizardry.common.items.BoundPickaxe",
            "WayofTime.alchemicalWizardry.common.items.BoundShovel",
            "WayofTime.alchemicalWizardry.common.items.BoundAxe"));

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerInteract(PlayerInteractEvent event) {
        if (event == null || event.entityPlayer == null) {
            return;
        }

        EntityPlayer player = event.entityPlayer;
        World world = player.worldObj;
        if (world == null || world.isRemote || !ClaimedChunks.isActive()) {
            return;
        }

        ItemStack held = player.getHeldItem();
        if (held == null || held.getItem() == null) {
            return;
        }

        String itemClassName = held.getItem()
            .getClass()
            .getName();
        if (!BOUND_TOOL_CLASSES.contains(itemClassName)) {
            return;
        }

        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_AIR
            && event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        MovingObjectPosition hit = cointcore$rayTrace(player, 5.0D);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }

        if (cointcore$hitsForeignClaim(player, world, itemClassName, hit.blockX, hit.blockY, hit.blockZ)) {
            event.setCanceled(true);
            event.useItem = cpw.mods.fml.common.eventhandler.Event.Result.DENY;
            event.useBlock = cpw.mods.fml.common.eventhandler.Event.Result.DENY;
            ClaimGuardNotifier.notifyDenied(player);
        }
    }

    private static boolean cointcore$hitsForeignClaim(EntityPlayer player, World world, String toolClassName,
        int centerX, int centerY, int centerZ) {
        int minX = centerX - 5;
        int maxX = centerX + 5;
        int minZ = centerZ - 5;
        int maxZ = centerZ + 5;
        int minY = toolClassName.endsWith("BoundPickaxe") ? centerY - 5 : centerY;
        int maxY = toolClassName.endsWith("BoundPickaxe") ? centerY + 5 : centerY + 10;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlock(x, y, z);
                    if (block == null || block.isAir(world, x, y, z)) {
                        continue;
                    }
                    if (block.getBlockHardness(world, x, y, z) == -1.0F) {
                        continue;
                    }
                    if (cointcore$isClaimDenied(player, x, y, z)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean cointcore$isClaimDenied(EntityPlayer player, int x, int y, int z) {
        EntityPlayer safePlayer = Objects.requireNonNull(player, "player");
        for (int side = 0; side < 6; side++) {
            if (ClaimedChunks.blockBlockEditing(safePlayer, x, y, z, side)) {
                return true;
            }
        }
        return false;
    }

    private static MovingObjectPosition cointcore$rayTrace(EntityPlayer player, double distance) {
        float yaw = player.rotationYaw;
        float pitch = player.rotationPitch;
        float yawRad = (float) Math.toRadians(-yaw) - (float) Math.PI;
        float pitchRad = (float) Math.toRadians(-pitch);

        double cosPitch = MathHelper.cos(pitchRad);
        double sinPitch = MathHelper.sin(pitchRad);
        double cosYaw = MathHelper.cos(yawRad);
        double sinYaw = MathHelper.sin(yawRad);

        Vec3 start = Vec3.createVectorHelper(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 look = Vec3.createVectorHelper(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
        Vec3 end = start.addVector(look.xCoord * distance, look.yCoord * distance, look.zCoord * distance);
        return player.worldObj.rayTraceBlocks(start, end);
    }
}
