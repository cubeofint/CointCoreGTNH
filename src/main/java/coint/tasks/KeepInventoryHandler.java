package coint.tasks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Сохраняет весь инвентарь игрока при смерти, если у него есть пермишен {@link #PERMISSION}.
 *
 * <p>
 * Порядок событий при смерти:
 * <ol>
 * <li>{@link LivingDeathEvent} — инвентарь ещё не очищен → сохраняем в NBT</li>
 * <li>{@code inventory.dropAllItems()} — слоты обнуляются, дроп создаётся</li>
 * <li>{@link PlayerDropsEvent} — отменяем дроп, чтобы предметы не упали на землю</li>
 * <li>{@link PlayerEvent.Clone} (wasDeath=true) — восстанавливаем инвентарь новому игроку</li>
 * </ol>
 */
public class KeepInventoryHandler {

    public static final String PERMISSION = "cointcore.keepinventory";

    /** UUID → сериализованный инвентарь игрока, ожидающий восстановления после respawn. */
    private final Map<UUID, NBTTagList> savedInventories = new ConcurrentHashMap<>();

    /**
     * Шаг 1: сохраняем инвентарь до того, как vanilla вызовет {@code dropAllItems()}.
     * Событие не отменяем — игрок должен умереть как обычно.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.entity instanceof EntityPlayer player)) return;
        if (player.worldObj.isRemote) return;
        if (!PermissionAPI.hasPermission(player, PERMISSION)) return;

        NBTTagList tag = new NBTTagList();
        player.inventory.writeToNBT(tag);
        savedInventories.put(player.getUniqueID(), tag);
    }

    /**
     * Шаг 2: отменяем физический дроп предметов на землю.
     * {@link PlayerDropsEvent} — это точный event для дропа инвентаря игрока при смерти.
     * Используем HIGHEST, чтобы перехватить до {@link DropHandler}, который навешивает теги.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerDrop(PlayerDropsEvent event) {
        if (savedInventories.containsKey(event.entityPlayer.getUniqueID())) {
            event.setCanceled(true);
        }
    }

    /**
     * Шаг 3: восстанавливаем сохранённый инвентарь на нового (respawn) игрока.
     * Запись удаляется из карты — утечки памяти нет.
     */
    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.wasDeath) return;

        UUID uuid = event.original.getUniqueID();
        NBTTagList saved = savedInventories.remove(uuid);
        if (saved == null) return;

        event.entityPlayer.inventory.readFromNBT(saved);
    }
}
