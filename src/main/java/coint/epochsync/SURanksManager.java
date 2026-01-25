package coint.epochsync;

import java.util.Map;

import serverutils.ranks.Rank;
import serverutils.ranks.Ranks;

public class SURanksManager {

    Ranks ranksInst = Ranks.INSTANCE;

    public Map<String, Rank> getRanks() {
        // Map<String, Rank> ranks = ranksInst.ranks;
        return ranksInst.ranks;
    }

    public void save() {

    }
}
