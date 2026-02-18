package coint.module.epochsync;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.SerializedName;

public class EpochEntry {

    @SerializedName("quest_id")
    public String questId;

    public List<String> commands;

    @SerializedName("epoch_up_message")
    public String epochUpMessage;

    @SerializedName("rank_name")
    public String rankName;

    public int priority;

    public int chunks;

    @SerializedName("forced_chunks")
    public int forcedChunks;

    public int homes;

    public EpochEntry() {
        this.commands = new ArrayList<>();
    }

    public EpochEntry(String questId, List<String> commands, String rankName, int priority, int chunks,
        int forcedChunks, int homes) {
        this.questId = questId;
        this.commands = commands;
        this.rankName = rankName;
        this.priority = priority;
        this.chunks = chunks;
        this.forcedChunks = forcedChunks;
        this.homes = homes;
    }

    public int getPriority() {
        return priority;
    }
}
