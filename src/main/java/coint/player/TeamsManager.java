package coint.player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;

import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

@SuppressWarnings("unused")
public class TeamsManager extends WorldSavedData {

    private static final String DATA_NAME = "COINT_Teams";

    public static final String NBT_PDS = "pds";
    private static final String NBT_PDIM_REWARD_PLAYERS = "pdimRewardPlayers";
    private static final String NBT_PDIM_REWARD_TEAMS = "pdimRewardTeams";
    private static final String NBT_PDIM_REWARD_QUESTS = "pdimRewardQuests";
    private static final String NBT_RETIRED_PDIM_IDS = "retiredPDimIds";

    public HashMap<Short, Integer> pdBinds = new HashMap<>();
    private final Set<UUID> pdimRewardConsumedPlayers = new HashSet<>();
    private final Set<String> pdimRewardClaimedTeams = new HashSet<>();
    private final Set<UUID> pdimRewardQuestIds = new HashSet<>();
    private final Set<Integer> retiredPDimIds = new HashSet<>();

    public static TeamsManager get() {
        WorldServer overworld = MinecraftServer.getServer()
            .worldServerForDimension(0);
        TeamsManager instance = (TeamsManager) overworld.loadItemData(TeamsManager.class, DATA_NAME);

        if (instance == null) {
            instance = new TeamsManager(DATA_NAME);
            overworld.setItemData(DATA_NAME, instance);
        }
        return instance;
    }

    public TeamsManager(String name) {
        super(name);
    }

    public boolean hasDimBinding(EntityPlayer player) {
        var p = Universe.get()
            .getPlayer(player);
        if (!p.hasTeam() || p.isOP()) return false;

        return pdBinds.containsKey(p.team.getUID());
    }

    public int getDim(EntityPlayer player) {
        var p = Universe.get()
            .getPlayer(player);
        if (!p.hasTeam()) return 0;

        return getDim(p.team);
    }

    public int getDim(ForgeTeam team) {
        if (team == null) return 0;
        return pdBinds.getOrDefault(team.getUID(), 0);
    }

    public Short getTeamUidForDim(int dimId) {
        for (Map.Entry<Short, Integer> entry : pdBinds.entrySet()) {
            if (entry.getValue() == dimId) return entry.getKey();
        }
        return null;
    }

    public boolean bindDimIfFree(ForgeTeam team, int dimId) {
        if (team == null || dimId <= 0) return false;
        int current = getDim(team);
        if (current == dimId) {
            markPDimRewardTeamClaimed(team);
            return true;
        }
        if (current != 0 || getTeamUidForDim(dimId) != null) return false;
        pdBinds.put(team.getUID(), dimId);
        markPDimRewardTeamClaimed(team);
        markDirty();
        return true;
    }

    public void bindDim(ForgeTeam team, int dimId) {
        if (team == null) return;
        pdBinds.put(team.getUID(), dimId);
        markPDimRewardTeamClaimed(team);
        markDirty();
    }

    public void bindDim(EntityPlayer player, int dimId) {
        var p = Universe.get()
            .getPlayer(player);
        if (p.hasTeam()) bindDim(p.team, dimId);
    }

    public boolean removeDimBind(ForgeTeam team) {
        if (team == null) return false;
        if (pdBinds.remove(team.getUID()) != null) {
            markDirty();
            return true;
        }
        return false;
    }

    public void removeDimBind(EntityPlayer player) {
        var p = Universe.get()
            .getPlayer(player);
        if (p.hasTeam()) removeDimBind(p.team);
    }

    public boolean isPDimRewardConsumed(UUID playerId) {
        return playerId != null && pdimRewardConsumedPlayers.contains(playerId);
    }

    public boolean markPDimRewardConsumed(UUID playerId) {
        if (playerId == null || !pdimRewardConsumedPlayers.add(playerId)) return false;
        markDirty();
        return true;
    }

    public boolean isPDimRewardTeamClaimed(ForgeTeam team) {
        return team != null && (getDim(team) != 0 || pdimRewardClaimedTeams.contains(team.getId()));
    }

    public boolean hasPDimRewardConsumedMember(ForgeTeam team) {
        if (team == null || !team.isValid()) return false;
        for (ForgePlayer member : team.getMembers()) {
            if (pdimRewardConsumedPlayers.contains(member.getId())) return true;
        }
        return false;
    }

    public boolean isPDimRewardTeamBlocked(ForgeTeam team) {
        return isPDimRewardTeamClaimed(team) || hasPDimRewardConsumedMember(team);
    }

    public boolean markPDimRewardTeamClaimed(ForgeTeam team) {
        if (team == null || !team.isValid()) return false;
        boolean changed = pdimRewardClaimedTeams.add(team.getId());
        for (ForgePlayer member : team.getMembers()) {
            changed |= pdimRewardConsumedPlayers.add(member.getId());
        }
        if (changed) markDirty();
        return changed;
    }

    public boolean registerPDimRewardQuest(UUID questId) {
        if (questId == null || !pdimRewardQuestIds.add(questId)) return false;
        markDirty();
        return true;
    }

    public Set<UUID> getPDimRewardQuestIds() {
        return new HashSet<>(pdimRewardQuestIds);
    }

    public boolean isRetiredPDim(int dimId) {
        return retiredPDimIds.contains(dimId);
    }

    public boolean resetPDimState(ForgeTeam team, int retiredDimId) {
        if (team == null || !team.isValid()) return false;

        boolean changed = pdBinds.remove(team.getUID()) != null;
        changed |= pdimRewardClaimedTeams.remove(team.getId());

        for (ForgePlayer member : team.getMembers()) {
            changed |= pdimRewardConsumedPlayers.remove(member.getId());
        }

        if (retiredDimId > 0) {
            changed |= retiredPDimIds.add(retiredDimId);
        }

        if (changed) markDirty();
        return changed;
    }

    public boolean resetPDimPlayerState(UUID playerId) {
        if (playerId == null) return false;
        boolean changed = pdimRewardConsumedPlayers.remove(playerId);
        if (changed) markDirty();
        return changed;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        pdBinds.clear();
        pdimRewardConsumedPlayers.clear();
        pdimRewardClaimedTeams.clear();
        pdimRewardQuestIds.clear();
        retiredPDimIds.clear();

        NBTTagCompound list = nbt.getCompoundTag(NBT_PDS);
        for (String key : list.func_150296_c()) {
            pdBinds.put(Short.parseShort(key), list.getInteger(key));
        }

        readUuidSet(nbt.getCompoundTag(NBT_PDIM_REWARD_PLAYERS), pdimRewardConsumedPlayers);
        readStringSet(nbt.getCompoundTag(NBT_PDIM_REWARD_TEAMS), pdimRewardClaimedTeams);
        readUuidSet(nbt.getCompoundTag(NBT_PDIM_REWARD_QUESTS), pdimRewardQuestIds);
        readIntSet(nbt.getCompoundTag(NBT_RETIRED_PDIM_IDS), retiredPDimIds);
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound list = new NBTTagCompound();
        for (Map.Entry<Short, Integer> entry : pdBinds.entrySet()) {
            list.setInteger(
                entry.getKey()
                    .toString(),
                entry.getValue());
        }
        nbt.setTag(NBT_PDS, list);
        nbt.setTag(NBT_PDIM_REWARD_PLAYERS, writeUuidSet(pdimRewardConsumedPlayers));
        nbt.setTag(NBT_PDIM_REWARD_TEAMS, writeStringSet(pdimRewardClaimedTeams));
        nbt.setTag(NBT_PDIM_REWARD_QUESTS, writeUuidSet(pdimRewardQuestIds));
        nbt.setTag(NBT_RETIRED_PDIM_IDS, writeIntSet(retiredPDimIds));
    }

    private static void readUuidSet(NBTTagCompound nbt, Set<UUID> target) {
        for (String key : nbt.func_150296_c()) {
            try {
                target.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private static void readStringSet(NBTTagCompound nbt, Set<String> target) {
        target.addAll(nbt.func_150296_c());
    }

    private static void readIntSet(NBTTagCompound nbt, Set<Integer> target) {
        for (String key : nbt.func_150296_c()) {
            try {
                target.add(Integer.parseInt(key));
            } catch (NumberFormatException ignored) {}
        }
    }

    private static NBTTagCompound writeUuidSet(Set<UUID> values) {
        NBTTagCompound nbt = new NBTTagCompound();
        for (UUID value : values) {
            nbt.setBoolean(value.toString(), true);
        }
        return nbt;
    }

    private static NBTTagCompound writeStringSet(Set<String> values) {
        NBTTagCompound nbt = new NBTTagCompound();
        for (String value : values) {
            nbt.setBoolean(value, true);
        }
        return nbt;
    }

    private static NBTTagCompound writeIntSet(Set<Integer> values) {
        NBTTagCompound nbt = new NBTTagCompound();
        for (Integer value : values) {
            nbt.setBoolean(value.toString(), true);
        }
        return nbt;
    }
}
