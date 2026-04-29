package coint.events;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Сохраняет инвентарь, опыт, слоты GalacticraftCore и надетый рюкзак (AdventureBackpack2)
 * при смерти игрока, если у него есть пермишен {@link #PERMISSION}.
 *
 * <p>
 * Galacticraft и AdventureBackpack2 — опциональные зависимости; их API вызывается через
 * рефлексию, поэтому отсутствие любого из модов не вызывает ошибок.
 *
 * <p>
 * Порядок событий при смерти:
 * <ol>
 * <li>{@link LivingDeathEvent} (HIGHEST) — сохраняем всё состояние игрока</li>
 * <li>{@code inventory.dropAllItems()} — vanilla очищает слоты</li>
 * <li>{@link PlayerDropsEvent} (HIGHEST) — отменяем дроп</li>
 * <li>{@link PlayerEvent.Clone} (wasDeath=true) — восстанавливаем всё состояние</li>
 * </ol>
 */
@EventBusSubscriber
public class KeepInventoryHandler {

    public static final String PERMISSION = "cointcore.keepinventory";

    // --- Ключи IExtendedEntityProperties ---
    private static final String GC_PROPS_KEY = "GCPlayerStats";
    private static final String ABP_PROPS_KEY = "abp.property";

    // --- Названия поля/методов GalacticraftCore (reflection) ---
    private static final String GC_FIELD_EXT_INV = "extendedInventory";
    private static final String GC_METHOD_WRITE_NBT = "writeToNBT";
    private static final String GC_METHOD_READ_NBT = "readFromNBT";

    // --- Названия методов AdventureBackpack2 (reflection) ---
    private static final String ABP_METHOD_GET_WEARABLE = "getWearable";
    private static final String ABP_METHOD_SET_WEARABLE = "setWearable";

    // -------------------------------------------------------------------------

    /** Всё, что нужно восстановить после respawn. */
    private static final class SavedData {

        final NBTTagList vanillaInv;
        /** Содержимое GC extendedInventory (10 слотов). Null, если GC не загружен. */
        final NBTTagList gcInv;
        /** Надетый рюкзак из AdventureBackpack2. Null, если мод не загружен или рюкзак не надет. */
        final NBTTagCompound backpack;
        final int xpLevel;
        final float xpProgress;
        final int xpTotal;

        SavedData(NBTTagList vanillaInv, NBTTagList gcInv, NBTTagCompound backpack, int xpLevel, float xpProgress,
            int xpTotal) {
            this.vanillaInv = vanillaInv;
            this.gcInv = gcInv;
            this.backpack = backpack;
            this.xpLevel = xpLevel;
            this.xpProgress = xpProgress;
            this.xpTotal = xpTotal;
        }
    }

    private static final Map<UUID, SavedData> saved = new ConcurrentHashMap<>();

    // =========================================================================
    // Шаг 1 — сохранение
    // =========================================================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.entity instanceof EntityPlayer player)) return;
        if (player.worldObj.isRemote) return;
        if (!PermissionAPI.hasPermission(player, PERMISSION)) return;

        // Vanilla inventory (hotbar + main + armor)
        NBTTagList vanillaInv = new NBTTagList();
        player.inventory.writeToNBT(vanillaInv);

        // Опыт — сохраняем, затем обнуляем на умирающей сущности.
        // Без обнуления vanilla-код смерти считает experienceLevel и спавнит сферы опыта
        // на месте смерти (независимо от отмены PlayerDropsEvent).
        int xpLevel = player.experienceLevel;
        float xpProgress = player.experience;
        int xpTotal = player.experienceTotal;
        player.experienceLevel = 0;
        player.experience = 0;
        player.experienceTotal = 0;

        // GalacticraftCore — extendedInventory (маска, шланг, баллоны, термобронь…)
        NBTTagList gcInv = saveGcInventory(player);

        // AdventureBackpack2 — надетый рюкзак:
        // сохраняем И сразу зануляем wearable, чтобы ABP's playerDies (NORMAL priority)
        // увидел isWearingWearable=false и не разместил/не дропнул рюкзак в мире.
        NBTTagCompound backpack = saveAndClearBackpack(player);

        saved.put(player.getUniqueID(), new SavedData(vanillaInv, gcInv, backpack, xpLevel, xpProgress, xpTotal));
    }

    // =========================================================================
    // Шаг 2 — отмена дропа
    // =========================================================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDrop(PlayerDropsEvent event) {
        if (saved.containsKey(event.entityPlayer.getUniqueID())) {
            event.setCanceled(true);
        }
    }

    // =========================================================================
    // Шаг 3 — восстановление
    // =========================================================================

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.wasDeath) return;

        UUID uuid = event.original.getUniqueID();
        SavedData data = saved.remove(uuid);
        if (data == null) return;

        EntityPlayer newPlayer = event.entityPlayer;

        // Vanilla inventory
        newPlayer.inventory.readFromNBT(data.vanillaInv);

        // Опыт
        newPlayer.experienceLevel = data.xpLevel;
        newPlayer.experience = data.xpProgress;
        newPlayer.experienceTotal = data.xpTotal;

        // GalacticraftCore — восстанавливаем extendedInventory
        if (data.gcInv != null) {
            restoreGcInventory(newPlayer, data.gcInv);
        }

        // AdventureBackpack2 — восстанавливаем надетый рюкзак
        if (data.backpack != null) {
            restoreBackpack(newPlayer, data.backpack);
        }
    }

    // =========================================================================
    // Helpers — GalacticraftCore (reflection, опциональная зависимость)
    // =========================================================================

    /**
     * Сохраняет {@code GCPlayerStats.extendedInventory} через рефлексию.
     *
     * @return сериализованный инвентарь или {@code null}, если GC не загружен
     */
    private static NBTTagList saveGcInventory(EntityPlayer player) {
        Object gcStats = player.getExtendedProperties(GC_PROPS_KEY);
        if (gcStats == null) return null;
        try {
            Field field = gcStats.getClass()
                .getField(GC_FIELD_EXT_INV);
            Object extInv = field.get(gcStats);
            Method write = extInv.getClass()
                .getMethod(GC_METHOD_WRITE_NBT, NBTTagList.class);
            return (NBTTagList) write.invoke(extInv, new NBTTagList());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Восстанавливает {@code GCPlayerStats.extendedInventory} через рефлексию.
     */
    private static void restoreGcInventory(EntityPlayer player, NBTTagList tag) {
        Object gcStats = player.getExtendedProperties(GC_PROPS_KEY);
        if (gcStats == null) return;
        try {
            Field field = gcStats.getClass()
                .getField(GC_FIELD_EXT_INV);
            Object extInv = field.get(gcStats);
            Method read = extInv.getClass()
                .getMethod(GC_METHOD_READ_NBT, NBTTagList.class);
            read.invoke(extInv, tag);
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Helpers — AdventureBackpack2 (reflection, опциональная зависимость)
    // =========================================================================

    /**
     * Сохраняет надетый рюкзак из {@code BackpackProperty} через рефлексию и сразу
     * устанавливает {@code wearable = null} на старой сущности игрока.
     *
     * <p>
     * Обнуление необходимо потому, что ABP's {@code playerDies} обработчик срабатывает
     * с приоритетом NORMAL (после нашего HIGHEST) и проверяет
     * {@code Wearing.isWearingWearable(player)}. Если wearable не null — ABP размещает
     * рюкзак как блок в мире или дропает его через {@code dropPlayerItemWithRandomChoice},
     * минуя {@link PlayerDropsEvent}. Это вызвало бы дюпликацию при нашем восстановлении.
     *
     * @return NBT стека рюкзака или {@code null}
     */
    private static NBTTagCompound saveAndClearBackpack(EntityPlayer player) {
        Object abp = player.getExtendedProperties(ABP_PROPS_KEY);
        if (abp == null) return null;
        try {
            Method get = abp.getClass()
                .getMethod(ABP_METHOD_GET_WEARABLE);
            ItemStack stack = (ItemStack) get.invoke(abp);
            if (stack == null) return null;
            NBTTagCompound nbt = stack.writeToNBT(new NBTTagCompound());
            // Зануляем wearable — ABP's playerDies увидит isWearingWearable=false
            Method set = abp.getClass()
                .getMethod(ABP_METHOD_SET_WEARABLE, ItemStack.class);
            set.invoke(abp, (Object) null);
            return nbt;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Восстанавливает надетый рюкзак в {@code BackpackProperty} через рефлексию.
     * Синхронизация с клиентами произойдёт автоматически через ABP {@code playerRespawn}.
     */
    private static void restoreBackpack(EntityPlayer player, NBTTagCompound tag) {
        Object abp = player.getExtendedProperties(ABP_PROPS_KEY);
        if (abp == null) return;
        try {
            ItemStack stack = ItemStack.loadItemStackFromNBT(tag);
            if (stack == null) return;
            Method set = abp.getClass()
                .getMethod(ABP_METHOD_SET_WEARABLE, ItemStack.class);
            set.invoke(abp, stack);
        } catch (Exception ignored) {}
    }
}
