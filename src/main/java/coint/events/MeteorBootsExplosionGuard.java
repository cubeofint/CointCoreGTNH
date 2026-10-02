package coint.events;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.event.world.ExplosionEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;

@EventBusSubscriber
public class MeteorBootsExplosionGuard {

    private static final Map<EntityItem, Long> protectedItems = new WeakHashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (event.world == null) return;

        EntityPlayer player = getMeteorPlayer(
            event.world.playerEntities,
            event.explosion.exploder,
            event.explosion.explosionX,
            event.explosion.explosionY,
            event.explosion.explosionZ);
        if (player == null) return;

        long protectedUntil = event.world.getTotalWorldTime() + 4L;
        double radius = 8.0D;
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
            event.explosion.explosionX - radius,
            event.explosion.explosionY - radius,
            event.explosion.explosionZ - radius,
            event.explosion.explosionX + radius,
            event.explosion.explosionY + radius,
            event.explosion.explosionZ + radius);

        List<?> items = event.world.getEntitiesWithinAABB(EntityItem.class, box);
        for (Object entry : items) {
            if (!(entry instanceof EntityItem)) continue;
            EntityItem item = (EntityItem) entry;
            protectedItems.put(item, protectedUntil);
            restoreItem(item);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.world == null) return;

        EntityPlayer player = getMeteorPlayer(
            event.world.playerEntities,
            event.explosion.exploder,
            event.explosion.explosionX,
            event.explosion.explosionY,
            event.explosion.explosionZ);
        if (player == null) return;

        long protectedUntil = event.world.getTotalWorldTime() + 4L;
        Iterator<Entity> iterator = event.getAffectedEntities()
            .iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            if (entity instanceof EntityItem) {
                EntityItem item = (EntityItem) entity;
                protectedItems.put(item, protectedUntil);
                restoreItem(item);
                iterator.remove();
            }
        }
    }

    public static boolean shouldSuppressItemDamage(EntityItem item) {
        if (item == null || item.worldObj == null) return false;

        Long protectedUntil = protectedItems.get(item);
        if (protectedUntil == null) return false;

        if (item.worldObj.getTotalWorldTime() <= protectedUntil.longValue()) {
            return true;
        }

        protectedItems.remove(item);
        return false;
    }

    public static void restoreProtectedItem(EntityItem item) {
        if (shouldSuppressItemDamage(item)) restoreItem(item);
    }

    public static void restoreItem(EntityItem item) {
        if (item == null || item.getEntityItem() == null) return;

        NBTTagCompound tag = new NBTTagCompound();
        item.writeEntityToNBT(tag);
        tag.setShort("Health", (short) 5);
        item.readEntityFromNBT(tag);
        item.isDead = false;
    }

    private static EntityPlayer getMeteorPlayer(List<?> players, Entity exploder, double x, double y, double z) {
        if (exploder instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) exploder;
            if (hasMeteorBoots(player)) return player;
        }

        for (Object entry : players) {
            if (!(entry instanceof EntityPlayer)) continue;
            EntityPlayer player = (EntityPlayer) entry;
            if (!hasMeteorBoots(player)) continue;
            if (player.getDistanceSq(x, y, z) <= 64.0D) return player;
        }

        return null;
    }

    public static boolean hasMeteorBoots(EntityPlayer player) {
        return isMeteorBoots(player.inventory.armorItemInSlot(0));
    }

    private static boolean isMeteorBoots(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;

        Item item = stack.getItem();
        UniqueIdentifier id = GameRegistry.findUniqueIdentifierFor(item);
        if (id != null) {
            String modId = id.modId == null ? "" : id.modId.toLowerCase(Locale.ROOT);
            String name = id.name == null ? "" : id.name.toLowerCase(Locale.ROOT);
            if ("thaumicexploration".equals(modId) && name.contains("meteor")) return true;
            if ("thaumicboots".equals(modId) && name.contains("meteor")) return true;
        }

        Class<?> itemClass = item.getClass();
        if (implementsInterface(itemClass, "thaumicboots.api.IMeteor")) return true;

        String className = itemClass.getName()
            .toLowerCase(Locale.ROOT);
        if (className.contains("meteor")
            && (className.contains("thaumicexploration") || className.startsWith("thaumicboots."))) {
            return true;
        }

        String unlocalizedName = item.getUnlocalizedName(stack);
        return unlocalizedName != null && unlocalizedName.toLowerCase(Locale.ROOT)
            .contains("meteor") && (className.contains("thaumicexploration") || className.startsWith("thaumicboots."));
    }

    private static boolean implementsInterface(Class<?> type, String interfaceName) {
        Class<?> current = type;
        while (current != null) {
            for (Class<?> iface : current.getInterfaces()) {
                if (interfaceName.equals(iface.getName()) || implementsInterface(iface, interfaceName)) return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }
}
