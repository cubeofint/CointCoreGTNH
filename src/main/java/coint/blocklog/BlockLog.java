package coint.blocklog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryLargeChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.GameData;
import cpw.mods.fml.relauncher.ReflectionHelper;

/**
 * Notes who broke and placed blocks, what explosions destroyed, and what players took from and put into containers.
 * Only players are logged, not machines acting for them (fake players): miners and planes would bury everything else.
 *
 * <p>
 * Server thread only; the records go to {@link BlockLogStore}.
 */
@EventBusSubscriber
public final class BlockLog {

    /** More blocks than this from one explosion are not logged one by one. */
    private static final int EXPLOSION_LIMIT = 4096;

    /** The container a player has open, as it was when he opened it. */
    private static final Map<UUID, Session> sessions = new HashMap<>();

    private BlockLog() {}

    public static void start() {
        if (CointConfig.blockLog.enabled) {
            BlockLogStore.start();
        }
    }

    public static void stop() {
        sessions.clear();
        BlockLogStore.stop();
    }

    static boolean active() {
        return BlockLogStore.isRunning();
    }

    static String node() {
        return CointConfig.cluster.nodeId;
    }

    // ---------------------------------------------------------------- blocks

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!active() || event.world.isRemote || !isRealPlayer(event.getPlayer())) {
            return;
        }
        ItemKey what = describe(event.world, event.x, event.y, event.z, event.block, event.blockMetadata);
        add(
            event.world,
            event.x,
            event.y,
            event.z,
            BlockLogEntry.BREAK,
            event.getPlayer()
                .getCommandSenderName(),
            what,
            1);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.PlaceEvent event) {
        if (!active() || event.world.isRemote || !isRealPlayer(event.player)) {
            return;
        }
        ItemKey what;
        ItemStack hand = event.itemInHand;
        if (hand != null && hand.getItem() != null) {
            what = new ItemKey(itemName(hand.getItem()), hand.getItemDamage());
        } else {
            what = describe(event.world, event.x, event.y, event.z, event.block, event.blockMetadata);
        }
        add(event.world, event.x, event.y, event.z, BlockLogEntry.PLACE, event.player.getCommandSenderName(), what, 1);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!active() || event.world.isRemote) {
            return;
        }
        List<ChunkPosition> blocks = event.getAffectedBlocks();
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        String actor = explosionActor(event);
        int logged = 0;
        for (ChunkPosition pos : blocks) {
            Block block = event.world.getBlock(pos.chunkPosX, pos.chunkPosY, pos.chunkPosZ);
            if (block.isAir(event.world, pos.chunkPosX, pos.chunkPosY, pos.chunkPosZ)) {
                continue;
            }
            int meta = event.world.getBlockMetadata(pos.chunkPosX, pos.chunkPosY, pos.chunkPosZ);
            add(
                event.world,
                pos.chunkPosX,
                pos.chunkPosY,
                pos.chunkPosZ,
                BlockLogEntry.EXPLODE,
                actor,
                describe(event.world, pos.chunkPosX, pos.chunkPosY, pos.chunkPosZ, block, meta),
                1);
            if (++logged >= EXPLOSION_LIMIT) {
                break;
            }
        }
    }

    private static String explosionActor(ExplosionEvent.Detonate event) {
        EntityLivingBase by = event.explosion.getExplosivePlacedBy();
        if (by instanceof EntityPlayer player && !(player instanceof FakePlayer)) {
            return player.getCommandSenderName();
        }
        Entity exploder = event.explosion.exploder;
        String kind = exploder == null ? null : EntityList.getEntityString(exploder);
        return "#" + (kind == null ? "explosion" : kind);
    }

    private static boolean isRealPlayer(EntityPlayer player) {
        return player instanceof EntityPlayerMP && !(player instanceof FakePlayer);
    }

    private static void add(World world, int x, int y, int z, int action, String actor, ItemKey what, int amount) {
        BlockLogStore.add(
            new BlockLogEntry(
                node(),
                world.provider.dimensionId,
                x,
                y,
                z,
                action,
                actor,
                what.name,
                what.meta,
                amount));
    }

    /** The item a block is picked as (a GregTech machine as that machine), else the block itself. */
    private static ItemKey describe(World world, int x, int y, int z, Block block, int meta) {
        try {
            MovingObjectPosition target = new MovingObjectPosition(x, y, z, 1, Vec3.createVectorHelper(x, y, z));
            ItemStack picked = block.getPickBlock(target, world, x, y, z);
            if (picked != null && picked.getItem() != null) {
                return new ItemKey(itemName(picked.getItem()), picked.getItemDamage());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Some blocks pick only on the client: the block itself is noted.
        }
        Item item = Item.getItemFromBlock(block);
        if (item != null) {
            int damage;
            try {
                damage = block.damageDropped(meta);
            } catch (RuntimeException e) {
                damage = meta;
            }
            return new ItemKey(itemName(item), damage);
        }
        String name = GameData.getBlockRegistry()
            .getNameForObject(block);
        return new ItemKey("b:" + (name == null ? "?" : name), meta);
    }

    private static String itemName(Item item) {
        String name = GameData.getItemRegistry()
            .getNameForObject(item);
        return name == null ? "?" : name;
    }

    // ---------------------------------------------------------------- containers

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof EntityPlayerMP player)
            || player instanceof FakePlayer) {
            return;
        }
        Session session = sessions.get(player.getUniqueID());
        Container open = player.openContainer;
        boolean watching = active() && CointConfig.blockLog.containers;
        if (session != null && (session.container != open || !watching)) {
            sessions.remove(player.getUniqueID());
            session.close(player);
            session = null;
        }
        if (session == null && watching && open != null && open != player.inventoryContainer) {
            sessions.put(player.getUniqueID(), Session.open(player, open));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Session session = sessions.remove(event.player.getUniqueID());
        if (session != null && event.player instanceof EntityPlayerMP player) {
            session.close(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            BlockLogStore.runResults();
        }
    }

    /** Where the items of a slot are kept, when that is a block. */
    private static TileEntity tileOf(IInventory inventory) {
        if (inventory instanceof TileEntity tile) {
            return tile;
        }
        if (inventory instanceof InventoryLargeChest chest) {
            try {
                Object upper = ReflectionHelper
                    .getPrivateValue(InventoryLargeChest.class, chest, "upperChest", "field_70477_b");
                if (upper instanceof TileEntity tile) {
                    return tile;
                }
            } catch (RuntimeException ignored) {
                // Not a chest of blocks.
            }
        }
        return null;
    }

    private static final class Session {

        final Container container;
        /** Null: not a container of a block, nothing is noted. */
        final TileEntity tile;
        final Map<ItemKey, Long> inside, carried;

        private Session(Container container, TileEntity tile, Map<ItemKey, Long> inside, Map<ItemKey, Long> carried) {
            this.container = container;
            this.tile = tile;
            this.inside = inside;
            this.carried = carried;
        }

        static Session open(EntityPlayerMP player, Container container) {
            TileEntity tile = null;
            for (Object o : container.inventorySlots) {
                Slot slot = (Slot) o;
                if (slot.inventory != player.inventory) {
                    tile = tileOf(slot.inventory);
                    if (tile != null) {
                        break;
                    }
                }
            }
            if (tile == null) {
                return new Session(container, null, null, null);
            }
            return new Session(container, tile, countInside(player, container), countCarried(player));
        }

        void close(EntityPlayerMP player) {
            if (tile == null || !active()) {
                return;
            }
            Map<ItemKey, Long> insideNow = countInside(player, container);
            Map<ItemKey, Long> carriedNow = countCarried(player);
            Map<ItemKey, Long> changed = new HashMap<>();
            delta(changed, inside, insideNow);
            for (Map.Entry<ItemKey, Long> e : changed.entrySet()) {
                ItemKey key = e.getKey();
                long inDelta = e.getValue();
                long carriedDelta = carriedNow.getOrDefault(key, 0L) - carried.getOrDefault(key, 0L);
                // Only what went between the player and the container: a machine filling its output is not his.
                long moved = inDelta > 0 ? Math.min(inDelta, -carriedDelta) : Math.min(-inDelta, carriedDelta);
                if (moved <= 0) {
                    continue;
                }
                add(
                    tile.getWorldObj(),
                    tile.xCoord,
                    tile.yCoord,
                    tile.zCoord,
                    inDelta > 0 ? BlockLogEntry.PUT : BlockLogEntry.TAKE,
                    player.getCommandSenderName(),
                    key,
                    (int) Math.min(Integer.MAX_VALUE, moved));
            }
        }

        private static void delta(Map<ItemKey, Long> out, Map<ItemKey, Long> before, Map<ItemKey, Long> after) {
            for (Map.Entry<ItemKey, Long> e : after.entrySet()) {
                long d = e.getValue() - before.getOrDefault(e.getKey(), 0L);
                if (d != 0) {
                    out.put(e.getKey(), d);
                }
            }
            for (Map.Entry<ItemKey, Long> e : before.entrySet()) {
                if (!after.containsKey(e.getKey())) {
                    out.put(e.getKey(), -e.getValue());
                }
            }
        }

        private static Map<ItemKey, Long> countInside(EntityPlayerMP player, Container container) {
            Map<ItemKey, Long> counts = new HashMap<>();
            for (Object o : container.inventorySlots) {
                Slot slot = (Slot) o;
                if (slot.inventory == player.inventory) {
                    continue;
                }
                ItemStack stack;
                try {
                    stack = slot.getStack();
                } catch (RuntimeException e) {
                    continue;
                }
                count(counts, stack);
            }
            return counts;
        }

        private static Map<ItemKey, Long> countCarried(EntityPlayerMP player) {
            Map<ItemKey, Long> counts = new HashMap<>();
            for (ItemStack stack : player.inventory.mainInventory) {
                count(counts, stack);
            }
            for (ItemStack stack : player.inventory.armorInventory) {
                count(counts, stack);
            }
            count(counts, player.inventory.getItemStack());
            return counts;
        }

        private static void count(Map<ItemKey, Long> counts, ItemStack stack) {
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
                return;
            }
            ItemKey key = new ItemKey(itemName(stack.getItem()), stack.getItemDamage(), stack.getTagCompound());
            counts.merge(key, (long) stack.stackSize, Long::sum);
        }
    }

    /** An item kind: name, damage and, for counting, its NBT. */
    static final class ItemKey {

        final String name;
        final int meta;
        final NBTTagCompound tag;

        ItemKey(String name, int meta) {
            this(name, meta, null);
        }

        ItemKey(String name, int meta, NBTTagCompound tag) {
            this.name = name;
            this.meta = meta;
            this.tag = tag;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ItemKey k && k.meta == meta && k.name.equals(name) && Objects.equals(k.tag, tag);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + meta;
        }
    }
}
