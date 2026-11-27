package coint.chunkbonus;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.data.ClaimedChunk;
import serverutils.events.chunks.ChunkModifiedEvent;
import serverutils.events.team.ForgeTeamDataEvent;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.NBTDataStorage;
import serverutils.lib.math.ChunkDimPos;

/** Oldest to newest. Stored in the SU team data that is saved and exported with the team. */
@EventBusSubscriber
public final class ChunkLoadOrder implements NBTDataStorage.Data {

    private static final String ID = "cointcore_chunk_load_order";
    private final Set<ChunkDimPos> order = new LinkedHashSet<>();

    @SubscribeEvent
    public static void register(ForgeTeamDataEvent event) {
        event.register(new ChunkLoadOrder());
    }

    private static ChunkLoadOrder of(ForgeTeam team) {
        return team.getData()
            .get(ID);
    }

    // SU posts this for an explicit load, and restores saved flags and inactivity pauses directly.
    @SubscribeEvent
    public static void onLoad(ChunkModifiedEvent.Loaded event) {
        ClaimedChunk chunk = event.getChunk();
        ForgeTeam team = chunk.getTeam();
        Set<ChunkDimPos> order = of(team).order;
        ChunkDimPos pos = chunk.getPos();
        order.remove(pos);
        order.add(new ChunkDimPos(pos.posX, pos.posZ, pos.dim));
        team.markDirty();
    }

    @SubscribeEvent
    public static void onUnload(ChunkModifiedEvent.Unloaded event) {
        forget(event.getChunk());
    }

    @SubscribeEvent
    public static void onUnclaim(ChunkModifiedEvent.Unclaimed event) {
        forget(event.getChunk());
    }

    static void forget(ClaimedChunk chunk) {
        ForgeTeam team = chunk.getTeam();
        if (of(team).order.remove(chunk.getPos())) team.markDirty();
    }

    static void newestFirst(ForgeTeam team, List<ClaimedChunk> chunks) {
        Map<ChunkDimPos, Integer> positions = new HashMap<>();
        for (ChunkDimPos pos : of(team).order) positions.put(pos, positions.size() + 1);

        // Claims from before this feature have no known order. Use coordinates to break those ties.
        chunks.sort(
            Comparator.<ClaimedChunk>comparingInt(chunk -> positions.getOrDefault(chunk.getPos(), 0))
                .reversed()
                .thenComparingInt(chunk -> chunk.getPos().dim)
                .thenComparingInt(chunk -> chunk.getPos().posX)
                .thenComparingInt(chunk -> chunk.getPos().posZ));
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        if (order.isEmpty()) return tag;
        int[] chunks = new int[order.size() * 3];
        int i = 0;
        for (ChunkDimPos pos : order) {
            chunks[i++] = pos.dim;
            chunks[i++] = pos.posX;
            chunks[i++] = pos.posZ;
        }
        tag.setIntArray("chunks", chunks);
        return tag;
    }

    @Override
    public void deserializeNBT(NBTTagCompound tag) {
        order.clear();
        int[] chunks = tag.getIntArray("chunks");
        for (int i = 0; i + 2 < chunks.length; i += 3) {
            order.add(new ChunkDimPos(chunks[i + 1], chunks[i + 2], chunks[i]));
        }
    }
}
