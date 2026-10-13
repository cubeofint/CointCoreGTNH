package coint.integration.personalspace;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import betterquesting.api.api.QuestingAPI;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.rewards.IReward;
import betterquesting.api.utils.BigItemStack;
import betterquesting.api2.storage.DBEntry;
import bq_standard.rewards.RewardItem;
import coint.CointCore;
import coint.network.WorldTravelNetwork;
import coint.player.TeamsManager;
import cpw.mods.fml.common.registry.GameRegistry;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

public final class PersonalSpaceTeamReward {

    private static final String MOD_ID = "personalspace";
    private static final String PORTAL_ID = "personalPortal";

    private static volatile Item portalItem;
    private static volatile boolean databaseResolved;
    private static Object questDatabase;
    private static Method getQuestMethod;

    private PersonalSpaceTeamReward() {}

    public static boolean isPortalReward(RewardItem reward) {
        if (reward == null) return false;
        Item portal = getPortalItem();
        if (portal == null) return false;
        for (BigItemStack bigStack : reward.items) {
            if (bigStack == null) continue;
            for (ItemStack stack : bigStack.getCombinedStacks()) {
                if (stack != null && stack.getItem() == portal) return true;
            }
        }
        return false;
    }

    public static boolean canClaim(EntityPlayer player, Map.Entry<UUID, IQuest> questEntry) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) return true;
        if (questEntry != null) {
            TeamsManager.get()
                .registerPDimRewardQuest(questEntry.getKey());
        }
        return !isBlocked(player);
    }

    /**
     * Team reward, like FTB Quests: one portal per ServerUtilities team, claimed by whichever member gets there first.
     * Players without a team claim for themselves (BetterQuesting's own claimed state stops a second claim).
     */
    public static boolean isBlocked(EntityPlayer player) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) return false;
        ForgePlayer forgePlayer = Universe.get()
            .getPlayer(player);
        if (forgePlayer == null) return true;
        ForgeTeam team = forgePlayer.hasTeam() ? forgePlayer.team : null;
        return team != null && TeamsManager.get()
            .isPDimRewardTeamClaimed(team);
    }

    public static void syncClientState(EntityPlayerMP player) {
        if (player == null) return;
        WorldTravelNetwork.syncPDimRewardState(player, isBlocked(player));
    }

    public static boolean beforeClaim(EntityPlayer player, Map.Entry<UUID, IQuest> questEntry) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) return true;
        if (questEntry == null || questEntry.getValue() == null) return false;

        UUID questId = questEntry.getKey();
        IQuest quest = questEntry.getValue();
        TeamsManager manager = TeamsManager.get();
        manager.registerPDimRewardQuest(questId);

        ForgePlayer forgePlayer = Universe.get()
            .getPlayer(player);
        if (forgePlayer == null) return false;
        ForgeTeam team = forgePlayer.hasTeam() ? forgePlayer.team : null;
        if (team == null) {
            return true; // solo: BetterQuesting marks the quest claimed for this player
        }

        if (manager.isPDimRewardTeamClaimed(team)) {
            // Someone in the team already took the portal: close the quest for this member without an item
            markClaimedIfComplete(forgePlayer.getId(), questId, quest);
            if (player instanceof EntityPlayerMP) syncClientState((EntityPlayerMP) player);
            return false;
        }

        manager.markPDimRewardTeamClaimed(team);
        syncCompletedMembers(team, questId, quest);
        syncTeamClientState(team);
        return true;
    }

    public static void onQuestCompleted(UUID playerId, UUID questId) {
        if (playerId == null || questId == null) return;
        IQuest quest = getQuest(questId);
        if (quest == null || !isPortalRewardQuest(quest)) return;

        TeamsManager manager = TeamsManager.get();
        manager.registerPDimRewardQuest(questId);
        ForgePlayer forgePlayer = Universe.get()
            .getPlayer(playerId);
        if (forgePlayer == null) return;

        ForgeTeam team = forgePlayer.hasTeam() ? forgePlayer.team : null;
        if (team != null && manager.isPDimRewardTeamClaimed(team)) {
            markClaimedIfComplete(forgePlayer.getId(), questId, quest);
            EntityPlayerMP online = QuestingAPI.getPlayer(forgePlayer.getId());
            if (online != null) syncClientState(online);
        }
    }

    public static void onServerStarted() {
        TeamsManager manager = TeamsManager.get();
        for (UUID questId : manager.getPDimRewardQuestIds()) {
            IQuest quest = getQuest(questId);
            if (quest == null || !isPortalRewardQuest(quest)) continue;
            for (ForgePlayer player : Universe.get()
                .getPlayers()) {
                ForgeTeam team = player.hasTeam() ? player.team : null;
                if (team != null && manager.isPDimRewardTeamClaimed(team)) {
                    markClaimedIfComplete(player.getId(), questId, quest);
                }
            }
        }
    }

    /** A player joining a team that already took its portal cannot claim another one. */
    public static void onServerUtilitiesTeamJoined(ForgePlayer player) {
        if (player == null || !player.hasTeam()) return;
        TeamsManager manager = TeamsManager.get();
        if (!manager.isPDimRewardTeamClaimed(player.team)) return;
        for (UUID questId : manager.getPDimRewardQuestIds()) {
            IQuest quest = getQuest(questId);
            if (quest != null && isPortalRewardQuest(quest)) {
                markClaimedIfComplete(player.getId(), questId, quest);
            }
        }
        EntityPlayerMP online = QuestingAPI.getPlayer(player.getId());
        if (online != null) syncClientState(online);
    }

    /**
     * Admin fix for a team blocked by mistake: frees the team's reward and reopens the claim for this player if the
     * quest was closed without the portal. Returns a short description of what changed.
     */
    public static String resetReward(ForgePlayer player) {
        if (player == null) return "игрок не найден";
        TeamsManager manager = TeamsManager.get();
        StringBuilder result = new StringBuilder();
        if (player.hasTeam() && manager.unmarkPDimRewardTeamClaimed(player.team)) {
            result.append("награда команды ")
                .append(player.team.getId())
                .append(" снова доступна");
        }
        int reopened = 0;
        for (UUID questId : manager.getPDimRewardQuestIds()) {
            IQuest quest = getQuest(questId);
            if (quest == null || !isPortalRewardQuest(quest) || !quest.hasClaimed(player.getId())) continue;
            NBTTagCompound info = quest.getCompletionInfo(player.getId());
            if (info != null) {
                info.setBoolean("claimed", false);
                markQuestDirty(player.getId(), questId);
                reopened++;
            }
        }
        if (reopened > 0) {
            if (result.length() > 0) result.append(", ");
            result.append("квест можно забрать заново");
        }
        EntityPlayerMP online = QuestingAPI.getPlayer(player.getId());
        if (online != null) syncClientState(online);
        return result.length() == 0 ? "менять нечего: награда не заблокирована" : result.toString();
    }

    private static void syncTeamClientState(ForgeTeam team) {
        if (team == null || !team.isValid()) return;
        for (ForgePlayer member : team.getMembers()) {
            EntityPlayerMP online = QuestingAPI.getPlayer(member.getId());
            if (online != null) {
                syncClientState(online);
            }
        }
    }

    private static void syncCompletedMembers(ForgeTeam team, UUID questId, IQuest quest) {
        if (team == null || quest == null) return;
        for (ForgePlayer member : team.getMembers()) {
            markClaimedIfComplete(member.getId(), questId, quest);
        }
    }

    private static void markClaimedIfComplete(UUID playerId, UUID questId, IQuest quest) {
        if (playerId == null || quest == null || !quest.isComplete(playerId) || quest.hasClaimed(playerId)) return;
        quest.setClaimed(playerId, System.currentTimeMillis());
        markQuestDirty(playerId, questId);
    }

    private static boolean isPortalRewardQuest(IQuest quest) {
        try {
            for (DBEntry<IReward> entry : quest.getRewards()
                .getEntries()) {
                IReward reward = entry.getValue();
                if (reward instanceof RewardItem && isPortalReward((RewardItem) reward)) return true;
            }
        } catch (Throwable t) {
            CointCore.LOG.error("[PDimTeamReward] Failed to inspect quest rewards", t);
        }
        return false;
    }

    private static Item getPortalItem() {
        Item cached = portalItem;
        if (cached != null) return cached;
        cached = GameRegistry.findItem(MOD_ID, PORTAL_ID);
        portalItem = cached;
        return cached;
    }

    private static IQuest getQuest(UUID questId) {
        try {
            resolveQuestDatabase();
            if (questDatabase == null || getQuestMethod == null) return null;
            Object quest = getQuestMethod.invoke(questDatabase, questId);
            return quest instanceof IQuest ? (IQuest) quest : null;
        } catch (Throwable t) {
            CointCore.LOG.error("[PDimTeamReward] Failed to resolve quest {}", questId, t);
            return null;
        }
    }

    private static synchronized void resolveQuestDatabase() throws Exception {
        if (databaseResolved) return;
        Class<?> databaseClass = Class.forName("betterquesting.questing.QuestDatabase");
        Field instance = databaseClass.getField("INSTANCE");
        questDatabase = instance.get(null);
        getQuestMethod = findLookupMethod(databaseClass, "get");
        if (getQuestMethod == null) getQuestMethod = findLookupMethod(databaseClass, "getValue");
        if (getQuestMethod == null) getQuestMethod = findLookupMethod(databaseClass, "getQuest");
        if (getQuestMethod == null) {
            throw new NoSuchMethodException("QuestDatabase UUID lookup method not found");
        }
        databaseResolved = true;
    }

    private static Method findLookupMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (!method.getName()
                .equals(name) || method.getParameterTypes().length != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter.isAssignableFrom(UUID.class) || UUID.class.isAssignableFrom(parameter)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static void markQuestDirty(UUID playerId, UUID questId) {
        try {
            EntityPlayerMP player = QuestingAPI.getPlayer(playerId);
            if (player == null) return;
            Class<?> cacheClass = Class.forName("betterquesting.api2.cache.QuestCache");
            Field locationField = cacheClass.getField("LOC_QUEST_CACHE");
            Object location = locationField.get(null);
            Object cache = player.getExtendedProperties(String.valueOf(location));
            if (cache == null) return;
            Method markDirty = cacheClass.getMethod("markQuestDirty", UUID.class);
            markDirty.invoke(cache, questId);
        } catch (Throwable t) {
            CointCore.LOG.debug("[PDimTeamReward] Failed to mark quest cache dirty for {}", playerId, t);
        }
    }
}
