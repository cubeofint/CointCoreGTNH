package coint.teamhistory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

/** An observed milestone, never a date inferred from the player's current rank. */
public final class HistoryEvent {

    public String uuid = "", kind = "", label = "", actor = "", node = "", target = "";
    public long row, time;

    public static HistoryEvent create(String history, String key, String kind, String label, String actor, String node,
        String target, long time) {
        HistoryEvent event = new HistoryEvent();
        event.uuid = UUID.nameUUIDFromBytes((history + ":" + key).getBytes(StandardCharsets.UTF_8))
            .toString();
        event.kind = kind;
        event.label = plain(label, 128);
        event.actor = plain(actor, 64);
        event.node = plain(node, 64);
        event.target = plain(target, 64);
        event.time = time;
        return event;
    }

    public NBTTagCompound save() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("uuid", uuid);
        tag.setString("kind", kind);
        tag.setString("label", label);
        tag.setString("actor", actor);
        tag.setString("node", node);
        tag.setString("target", target);
        tag.setLong("time", time);
        return tag;
    }

    public static HistoryEvent load(NBTTagCompound tag) {
        HistoryEvent event = new HistoryEvent();
        event.uuid = UUID.fromString(tag.getString("uuid"))
            .toString();
        event.kind = tag.getString("kind");
        if (!event.kind.equals("epoch") && !event.kind.equals("move"))
            throw new IllegalArgumentException("History kind");
        event.label = plain(tag.getString("label"), 128);
        event.actor = plain(tag.getString("actor"), 64);
        event.node = plain(tag.getString("node"), 64);
        event.target = plain(tag.getString("target"), 64);
        event.time = tag.getLong("time");
        if (event.time <= 0) throw new IllegalArgumentException("History date");
        return event;
    }

    public static String plain(String value, int limit) {
        if (value == null) return "";
        String clean = value.replaceAll("§.", "")
            .replaceAll("[\\p{Cntrl}]", " ")
            .trim();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
}
