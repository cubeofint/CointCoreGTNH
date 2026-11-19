package coint.commands.privilege;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.cluster.ClusterHomes;
import coint.migration.MigrationGuard;
import coint.player.TeamsManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.data.ClaimedChunks;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;
import serverutils.lib.math.ChunkDimPos;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/** Bounded, server-authorized building edits. No WorldEdit bypass permissions are used. */
@EventBusSubscriber
public final class CommandDset extends CommandBase {

    private static final String PERMISSION = "cointcore.command.dset";
    private static final Map<UUID, Selection> selections = new HashMap<>();
    private static final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private static final Map<UUID, Undo> undo = new LinkedHashMap<>();
    private static final List<String> VANILLA = Arrays.asList(
        "stone",
        "cobblestone",
        "planks",
        "glass",
        "stained_glass",
        "wool",
        "brick_block",
        "stonebrick",
        "sandstone",
        "quartz_block",
        "hardened_clay",
        "stained_hardened_clay",
        "stone_slab",
        "double_stone_slab",
        "wooden_slab",
        "double_wooden_slab",
        "stone_stairs",
        "brick_stairs",
        "stone_brick_stairs",
        "sandstone_stairs",
        "quartz_stairs",
        "oak_stairs",
        "spruce_stairs",
        "birch_stairs",
        "jungle_stairs",
        "acacia_stairs",
        "dark_oak_stairs");

    public CommandDset() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "Building blocks in your own base: //dset");
    }

    public static void reset() {
        jobs.clear();
        undo.clear();
        selections.clear();
    }

    @Override
    public String getCommandName() {
        return "/dset";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("dset");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "//dset <mod:block[:meta]> | //dset pos1 | pos2 | undo | cancel";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender instanceof EntityPlayerMP && PermissionAPI.hasPermission((EntityPlayerMP) sender, PERMISSION);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender)) throw new CommandException("commands.generic.permission");
        EntityPlayerMP player = (EntityPlayerMP) sender;
        UUID id = player.getUniqueID();
        if (args.length != 1) throw new WrongUsageException(getCommandUsage(sender));
        if (args[0].equals("cancel")) {
            Job job = jobs.remove(id);
            if (job != null) finish(job, "Отменено");
            return;
        }
        if (jobs.containsKey(id))
            throw new CommandException("Дождитесь завершения //dset или используйте //dset cancel");
        if (args[0].equals("pos1") || args[0].equals("pos2")) {
            Selection s = selections.computeIfAbsent(id, key -> new Selection());
            int[] point = { (int) Math.floor(player.posX), (int) Math.floor(player.posY),
                (int) Math.floor(player.posZ) };
            if (s.dim != player.dimension) {
                s.a = null;
                s.b = null;
            }
            s.dim = player.dimension;
            if (args[0].equals("pos1")) s.a = point;
            else s.b = point;
            say(player, "Точка " + args[0] + ": " + Arrays.toString(point));
            return;
        }
        if (jobs.size() >= 8) throw new CommandException("Слишком много активных строительных операций");
        if (args[0].equals("undo")) {
            Undo previous = undo.get(id);
            if (previous == null || previous.changes.isEmpty()) throw new CommandException("Нечего отменять");
            if (previous.dimension != player.dimension)
                throw new CommandException("Вернитесь в измерение последнего изменения");
            undo.remove(id);
            Job reverse = new Job(player, player.worldObj, Blocks.air, 0, null);
            for (Change c : previous.changes)
                reverse.pending.add(new Change(c.x, c.y, c.z, c.after, c.afterMeta, c.before, c.beforeMeta));
            reverse.applying = true;
            jobs.put(id, reverse);
            say(player, "Проверяю и отменяю последнее изменение");
            return;
        }
        String[] parts = args[0].split(":", -1);
        if (parts.length < 2 || parts.length > 3) throw new WrongUsageException(getCommandUsage(sender));
        Block target = Block.getBlockFromName(parts[0] + ":" + parts[1]);
        int metadata = parts.length == 3 ? parseIntBounded(sender, parts[2], 0, 15) : 0;
        if (target == null || !building(target, metadata))
            throw new CommandException("Этот блок не разрешён для //dset");
        Selection s = selections.get(id);
        if (s == null || s.a == null || s.b == null || s.dim != player.dimension) s = worldEdit(player);
        if (s == null) throw new CommandException("Выделите кубоид WorldEdit или задайте //dset pos1 и pos2");
        int[] bounds = new int[6];
        long volume = 1;
        for (int axis = 0; axis < 3; axis++) {
            bounds[axis] = Math.min(s.a[axis], s.b[axis]);
            bounds[axis + 3] = Math.max(s.a[axis], s.b[axis]);
            long length = (long) bounds[axis + 3] - bounds[axis] + 1;
            if (length > CointConfig.building.maxBlocks) throw new CommandException("Слишком большое выделение");
            volume *= length;
        }
        if (bounds[1] < 0 || bounds[4] >= player.worldObj.getActualHeight() || volume > CointConfig.building.maxBlocks)
            throw new CommandException("Выделение превышает высоту мира или лимит " + CointConfig.building.maxBlocks);
        Job job = new Job(player, player.worldObj, target, metadata, bounds);
        jobs.put(id, job);
        say(player, "Проверяю " + volume + " блоков. Изменение начнётся после проверки всего выделения.");
    }

    static boolean building(Block block, int meta) {
        if (block == null || block.hasTileEntity(meta)
            || block instanceof BlockLiquid
            || block.getMaterial()
                .isLiquid())
            return false;
        if (block == Blocks.air) return true;
        net.minecraft.item.Item item = net.minecraft.item.Item.getItemFromBlock(block);
        if (item == null) return false;
        for (int ore : net.minecraftforge.oredict.OreDictionary
            .getOreIDs(new net.minecraft.item.ItemStack(item, 1, meta))) {
            String oreName = net.minecraftforge.oredict.OreDictionary.getOreName(ore);
            if (oreName.startsWith("ore")) return false;
            // Chisel also decorates resource storage blocks; those can be converted back to ingots/gems.
            if (oreName.startsWith("block") && !oreName.matches(
                "block(?:Glass|Wool|Concrete|Stone|Marble|Limestone|Basalt|Granite|Wood|Clay|Brick|Sand|Gravel|Snow|Ice).*"))
                return false;
        }
        String name = String.valueOf(Block.blockRegistry.getNameForObject(block))
            .toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:") && VANILLA.contains(name.substring(10))) return true;
        // Use the owning namespace, not a loose substring that could admit machines.
        if (name.startsWith("chisel:")) return true;
        if (name.equals("gregtech:gt.blockconcretes")) return true;
        if (name.matches("(?:chisel|gregtech|dreamcraft|etfuturum):(?:block_?)?concrete(?:_[a-z]+)?")) return true;
        return Arrays.asList(CointConfig.building.extraBlocks)
            .contains(name);
    }

    private static boolean owns(EntityPlayerMP player, World world, int x, int z) {
        if (player.worldObj != world || ClusterHomes.isGuest(player)
            || !PermissionAPI.hasPermission(player, PERMISSION)
            || !world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4)
            || MigrationGuard.block(world, x, z)) return false;
        ForgePlayer fp = Universe.get()
            .getPlayer(player);
        if (fp == null || !fp.hasTeam()) return false;
        ForgeTeam claimed = ClaimedChunks.instance.getChunkTeam(new ChunkDimPos(x, 0, z, world.provider.dimensionId));
        if (claimed != null && claimed.isValid()) return claimed.getUID() == fp.team.getUID();
        int personal = TeamsManager.get()
            .getDim(fp.team);
        return personal > 0 && personal == world.provider.dimensionId;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int budget = Math.max(1, Math.min(4096, CointConfig.building.blocksPerTick));
        Iterator<Job> iterator = jobs.values()
            .iterator();
        while (iterator.hasNext() && budget > 0) {
            Job job = iterator.next();
            try {
                while (budget-- > 0 && step(job)) {}
                if (job.done) {
                    iterator.remove();
                    finish(job, "Готово");
                }
            } catch (RuntimeException e) {
                iterator.remove();
                coint.CointCore.LOG.warn("[Dset] Stopped for {}", job.player.getCommandSenderName(), e);
                finish(job, "Остановлено: " + e.getMessage());
            }
        }
    }

    private static boolean step(Job job) {
        EntityPlayerMP p = job.player;
        if (p.playerNetServerHandler == null || p.playerNetServerHandler.netManager == null
            || !p.playerNetServerHandler.netManager.isChannelOpen()) throw new CommandException("Игрок вышел");
        if (!job.applying) {
            int x = job.x, y = job.y, z = job.z;
            if (!owns(p, job.world, x, z))
                throw new CommandException("Нет доступа или чанк не загружен: " + x + " " + y + " " + z);
            Block old = job.world.getBlock(x, y, z);
            int meta = job.world.getBlockMetadata(x, y, z);
            if (job.world.getTileEntity(x, y, z) != null || !building(old, meta))
                throw new CommandException("В выделении есть защищённый блок: " + x + " " + y + " " + z);
            if (old != job.target || meta != job.meta)
                job.pending.add(new Change(x, y, z, old, meta, job.target, job.meta));
            if (++job.x > job.bounds[3]) {
                job.x = job.bounds[0];
                if (++job.z > job.bounds[5]) {
                    job.z = job.bounds[2];
                    job.y++;
                }
            }
            if (job.y > job.bounds[4]) job.applying = true;
        } else {
            if (job.cursor == job.pending.size()) {
                job.done = true;
                return false;
            }
            Change c = job.pending.get(job.cursor++);
            if (!owns(p, job.world, c.x, c.z) || job.world.getBlock(c.x, c.y, c.z) != c.before
                || job.world.getBlockMetadata(c.x, c.y, c.z) != c.beforeMeta
                || job.world.getTileEntity(c.x, c.y, c.z) != null
                || !building(c.before, c.beforeMeta)
                || !building(c.after, c.afterMeta))
                throw new CommandException("Мир или права изменились; оставшиеся блоки не затронуты");
            boolean previousSkipping = coint.blocklog.BlockLog.skipping;
            coint.blocklog.BlockLog.skipping = true;
            try {
                if (c.before != Blocks.air && MinecraftForge.EVENT_BUS
                    .post(new BlockEvent.BreakEvent(c.x, c.y, c.z, job.world, c.before, c.beforeMeta, p)))
                    throw new CommandException("Защита запретила изменение блока");
                BlockSnapshot snapshot = BlockSnapshot.getBlockSnapshot(job.world, c.x, c.y, c.z);
                boolean committed = false;
                try {
                    if (!job.world.setBlock(c.x, c.y, c.z, c.after, c.afterMeta, 3))
                        throw new CommandException("Не удалось изменить блок");
                    if (c.after != Blocks.air
                        && MinecraftForge.EVENT_BUS.post(new BlockEvent.PlaceEvent(snapshot, Blocks.air, p)))
                        throw new CommandException("Защита запретила установку блока");
                    committed = true;
                } finally {
                    // A protection plugin may throw as well as cancel: retain no untracked replacement.
                    if (!committed) snapshot.restore(true, false);
                }
                job.changes.add(c);
            } finally {
                coint.blocklog.BlockLog.skipping = previousSkipping;
            }
            coint.blocklog.BlockLog
                .buildingChanged(p, job.world, c.x, c.y, c.z, c.before, c.beforeMeta, c.after, c.afterMeta);
        }
        return true;
    }

    private static void finish(Job job, String status) {
        if (!job.changes.isEmpty()) {
            undo.put(job.player.getUniqueID(), new Undo(job.world.provider.dimensionId, new ArrayList<>(job.changes)));
            int retained = 0;
            for (Undo entry : undo.values()) retained += entry.changes.size();
            while (undo.size() > 16 || retained > 262144) {
                retained -= undo.remove(
                    undo.keySet()
                        .iterator()
                        .next()).changes.size();
            }
        }
        say(job.player, status + ". Изменено: " + job.changes.size() + ". Отмена: //dset undo");
    }

    private static void say(EntityPlayerMP player, String text) {
        player.addChatMessage(new ChatComponentText(text));
    }

    private static Selection worldEdit(EntityPlayerMP player) {
        try {
            Class<?> type = Class.forName("com.sk89q.worldedit.forge.ForgeWorldEdit");
            Object we = type.getField("inst")
                .get(null);
            Object session = type.getMethod("getSession", EntityPlayerMP.class)
                .invoke(we, player);
            Object world = type.getMethod("getWorld", World.class)
                .invoke(we, player.worldObj);
            Method get = session.getClass()
                .getMethod("getSelection", Class.forName("com.sk89q.worldedit.world.World"));
            Object region = get.invoke(session, world);
            if (!Class.forName("com.sk89q.worldedit.regions.CuboidRegion")
                .isInstance(region)) return null;
            Selection s = new Selection();
            s.dim = player.dimension;
            s.a = point(
                region.getClass()
                    .getMethod("getMinimumPoint")
                    .invoke(region));
            s.b = point(
                region.getClass()
                    .getMethod("getMaximumPoint")
                    .invoke(region));
            return s;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static int[] point(Object vector) throws ReflectiveOperationException {
        return new int[] { (Integer) vector.getClass()
            .getMethod("getBlockX")
            .invoke(vector),
            (Integer) vector.getClass()
                .getMethod("getBlockY")
                .invoke(vector),
            (Integer) vector.getClass()
                .getMethod("getBlockZ")
                .invoke(vector) };
    }

    private static final class Selection {

        int dim = Integer.MIN_VALUE;
        int[] a, b;
    }

    private static final class Change {

        final int x, y, z, beforeMeta, afterMeta;
        final Block before, after;

        Change(int x, int y, int z, Block before, int beforeMeta, Block after, int afterMeta) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.before = before;
            this.beforeMeta = beforeMeta;
            this.after = after;
            this.afterMeta = afterMeta;
        }
    }

    /** No player/world references: undo must not keep unloaded personal worlds alive. */
    private static final class Undo {

        final int dimension;
        final List<Change> changes;

        Undo(int dimension, List<Change> changes) {
            this.dimension = dimension;
            this.changes = changes;
        }
    }

    private static final class Job {

        final EntityPlayerMP player;
        final World world;
        final Block target;
        final int meta;
        final int[] bounds;
        final List<Change> pending = new ArrayList<>(), changes = new ArrayList<>();
        int x, y, z, cursor;
        boolean applying, done;

        Job(EntityPlayerMP p, World w, Block b, int meta, int[] bounds) {
            player = p;
            world = w;
            target = b;
            this.meta = meta;
            this.bounds = bounds;
            if (bounds != null) {
                x = bounds[0];
                y = bounds[1];
                z = bounds[2];
            }
        }
    }
}
