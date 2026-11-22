package coint.teamhistory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.events.team.ForgeTeamDataEvent;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.NBTDataStorage;

/** Stable identity and SQL outbox travel inside the existing SU team export. Server thread only. */
@EventBusSubscriber
public final class TeamHistoryData implements NBTDataStorage.Data {

    private static final String ID = "cointcore_history";
    private String history = "";
    private long since;
    private final Set<String> seen = new HashSet<>();
    private final Map<String, HistoryEvent> pending = new LinkedHashMap<>();

    @SubscribeEvent
    public static void register(ForgeTeamDataEvent event) {
        event.register(new TeamHistoryData());
    }

    @Override
    public String getId() {
        return ID;
    }

    public static TeamHistoryData of(ForgeTeam team) {
        return team.getData()
            .get(ID);
    }

    public String existingId() {
        return history;
    }

    public String ensureId(ForgeTeam team) {
        if (history.isEmpty()) {
            history = UUID.randomUUID()
                .toString();
            since = System.currentTimeMillis();
            team.markDirty();
        }
        return history;
    }

    public long since() {
        return since;
    }

    /** All members completing the same epoch produce one event for the team. */
    public boolean record(ForgeTeam team, String key, String kind, String label, String actor, String node,
        String target, long time) {
        ensureId(team);
        if (!seen.add(key)) return false;
        HistoryEvent event = HistoryEvent.create(history, key, kind, label, actor, node, target, time);
        pending.put(event.uuid, event);
        team.markDirty();
        return true;
    }

    public List<HistoryEvent> pending() {
        return new ArrayList<>(pending.values());
    }

    public void acknowledged(ForgeTeam team, String capturedHistory, List<HistoryEvent> captured) {
        // A migration/import can replace the data while a database callback is still queued.
        if (!history.equals(capturedHistory)) return;
        boolean changed = false;
        for (HistoryEvent event : captured) changed |= pending.remove(event.uuid) != null;
        if (changed) team.markDirty();
    }

    @Override
    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        if (history.isEmpty()) return tag;
        tag.setString("id", history);
        tag.setLong("since", since);
        NBTTagList keys = new NBTTagList();
        for (String key : seen) keys.appendTag(new NBTTagString(key));
        tag.setTag("seen", keys);
        NBTTagList outbox = new NBTTagList();
        for (HistoryEvent event : pending.values()) outbox.appendTag(event.save());
        tag.setTag("pending", outbox);
        return tag;
    }

    @Override
    public void deserializeNBT(NBTTagCompound tag) {
        history = "";
        since = 0;
        seen.clear();
        pending.clear();
        if (!tag.hasKey("id", 8)) return;
        try {
            history = UUID.fromString(tag.getString("id"))
                .toString();
            since = tag.getLong("since");
            NBTTagList keys = tag.getTagList("seen", 8);
            for (int i = 0; i < keys.tagCount(); i++) seen.add(keys.getStringTagAt(i));
            NBTTagList outbox = tag.getTagList("pending", 10);
            for (int i = 0; i < outbox.tagCount(); i++) {
                HistoryEvent event = HistoryEvent.load(outbox.getCompoundTagAt(i));
                pending.put(event.uuid, event);
            }
        } catch (IllegalArgumentException e) {
            CointCore.LOG.warn("[TeamHistory] Invalid team history data", e);
        }
    }
}
