package coint.player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import net.minecraft.command.CommandException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import betterquesting.api.enums.EnumPartyStatus;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.party.IParty;
import betterquesting.api2.storage.DBEntry;
import betterquesting.handlers.SaveLoadHandler;
import betterquesting.network.handlers.NetQuestSync;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.party.PartyManager;
import coint.commands.temprank.TempRankManager;
import coint.integration.personalspace.PersonalSpaceBinding;
import coint.worldtravel.WorldTravelManager;
import me.eigenraven.personalspace.CommonProxy;
import me.eigenraven.personalspace.PersonalSpaceMod;
import me.eigenraven.personalspace.block.PortalTileEntity;
import me.eigenraven.personalspace.net.Packets;
import me.eigenraven.personalspace.world.DimensionConfig;
import serverutils.data.ClaimedChunk;
import serverutils.data.ClaimedChunks;
import serverutils.data.ServerUtilitiesPlayerData;
import serverutils.lib.EnumTeamStatus;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;
import serverutils.lib.math.BlockDimPos;
import serverutils.lib.math.ChunkDimPos;
import serverutils.ranks.PlayerRank;
import serverutils.ranks.Ranks;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.research.ResearchCategories;
import thaumcraft.api.research.ResearchCategoryList;
import thaumcraft.api.research.ResearchItem;
import thaumcraft.common.Thaumcraft;
import thaumcraft.common.lib.network.PacketHandler;
import thaumcraft.common.lib.network.playerdata.PacketSyncAspects;
import thaumcraft.common.lib.network.playerdata.PacketSyncResearch;
import thaumcraft.common.lib.network.playerdata.PacketSyncWarp;
import thaumcraft.common.lib.research.PlayerKnowledge;
import thaumcraft.common.lib.research.ResearchManager;

public final class PlayerResetManager {

    private PlayerResetManager() {}

    public static boolean requiresLeadershipTransfer(ForgePlayer target) {
        ForgeTeam suTeam = getServerUtilitiesTeam(target);
        if (suTeam != null && suTeam.getMembers()
            .size() > 1 && suTeam.isOwner(target)) {
            return true;
        }

        DBEntry<IParty> partyEntry = PartyManager.INSTANCE.getParty(target.getId());
        if (partyEntry == null) {
            return false;
        }

        IParty party = partyEntry.getValue();
        return party.getMembers()
            .size() > 1 && party.getStatus(target.getId()) == EnumPartyStatus.OWNER;
    }

    public static List<String> getLeadershipCandidates(ForgePlayer target) {
        ForgeTeam suTeam = getServerUtilitiesTeam(target);
        DBEntry<IParty> partyEntry = PartyManager.INSTANCE.getParty(target.getId());

        boolean suNeedsTransfer = suTeam != null && suTeam.getMembers()
            .size() > 1 && suTeam.isOwner(target);
        boolean bqNeedsTransfer = partyEntry != null && partyEntry.getValue()
            .getMembers()
            .size() > 1
            && partyEntry.getValue()
                .getStatus(target.getId()) == EnumPartyStatus.OWNER;

        if (!suNeedsTransfer && !bqNeedsTransfer) {
            return new ArrayList<>();
        }

        Set<UUID> candidates = null;

        if (suNeedsTransfer) {
            candidates = new LinkedHashSet<>();
            for (ForgePlayer member : suTeam.getMembers()) {
                if (!member.getId()
                    .equals(target.getId())) {
                    candidates.add(member.getId());
                }
            }
        }

        if (bqNeedsTransfer) {
            Set<UUID> bqCandidates = new LinkedHashSet<>();
            for (UUID memberId : partyEntry.getValue()
                .getMembers()) {
                if (!memberId.equals(target.getId())) {
                    bqCandidates.add(memberId);
                }
            }
            if (candidates == null) {
                candidates = bqCandidates;
            } else {
                candidates.retainAll(bqCandidates);
            }
        }

        List<String> result = new ArrayList<>();
        if (candidates == null) {
            return result;
        }

        for (UUID id : candidates) {
            ForgePlayer player = Universe.get()
                .getPlayer(id);
            result.add(player != null ? player.getName() : id.toString());
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public static TeamResetResult detachTeamsForFullReset(ForgePlayer target, ForgePlayer successor)
        throws CommandException {
        ForgeTeam suTeam = getServerUtilitiesTeam(target);
        DBEntry<IParty> partyEntry = PartyManager.INSTANCE.getParty(target.getId());

        int suMemberCount = suTeam != null ? suTeam.getMembers()
            .size() : 0;
        int bqMemberCount = partyEntry != null ? partyEntry.getValue()
            .getMembers()
            .size() : 0;

        boolean suNeedsTransfer = suTeam != null && suMemberCount > 1 && suTeam.isOwner(target);
        boolean bqNeedsTransfer = partyEntry != null && bqMemberCount > 1
            && partyEntry.getValue()
                .getStatus(target.getId()) == EnumPartyStatus.OWNER;

        if (suNeedsTransfer || bqNeedsTransfer) {
            validateSuccessor(target, successor, suTeam, partyEntry, suNeedsTransfer, bqNeedsTransfer);
        }

        TeamResetResult result = new TeamResetResult();

        if (suTeam != null && suMemberCount <= 1) {
            int dimId = resetPersonalDimension(target);
            if (dimId > 0) {
                result.deletedPersonalDimension = dimId;
            }
        }

        if (partyEntry != null) {
            IParty party = partyEntry.getValue();

            if (bqNeedsTransfer) {
                party.setStatus(successor.getId(), EnumPartyStatus.OWNER);
            }

            party.kickUser(target.getId());

            if (bqMemberCount <= 1) {
                PartyManager.INSTANCE.removeID(partyEntry.getID());
                result.betterQuestingPartyDeleted = true;
            } else {
                result.betterQuestingPartyRemoved = true;
            }

            SaveLoadHandler.INSTANCE.markDirty();
        }

        if (suTeam != null) {
            if (suMemberCount <= 1) {
                if (suTeam.isValid() && suTeam.isMember(target) && !suTeam.removeMember(target)) {
                    throw new CommandException(
                        "Не удалось удалить одиночную ServerUtilities-команду игрока " + target.getName() + ".");
                }
                result.serverUtilitiesTeamDeleted = true;
            } else {
                if (TeamsManager.get()
                    .getDim(suTeam) > 0) {
                    result.keptSharedPersonalDimension = true;
                }

                TeamsManager.get()
                    .resetPDimPlayerState(target.getId());

                if (suNeedsTransfer && !suTeam.setStatus(successor, EnumTeamStatus.OWNER)) {
                    throw new CommandException(
                        "Не удалось передать лидерство ServerUtilities игроку " + successor.getName() + ".");
                }

                if (!suTeam.removeMember(target)) {
                    throw new CommandException(
                        "Не удалось удалить игрока " + target.getName() + " из ServerUtilities-команды.");
                }
                result.serverUtilitiesTeamRemoved = true;
            }
        } else {
            TeamsManager.get()
                .resetPDimPlayerState(target.getId());
        }

        if (suNeedsTransfer || bqNeedsTransfer) {
            result.leadershipTransferredTo = successor.getName();
        }

        return result;
    }

    private static ForgeTeam getServerUtilitiesTeam(ForgePlayer target) {
        if (target == null || !target.hasTeam() || target.team == null || !target.team.isValid()) {
            return null;
        }
        return target.team;
    }

    private static void validateSuccessor(ForgePlayer target, ForgePlayer successor, ForgeTeam suTeam,
        DBEntry<IParty> partyEntry, boolean suNeedsTransfer, boolean bqNeedsTransfer) throws CommandException {
        if (successor == null) {
            throw new CommandException("Нужно указать нового лидера команды.");
        }
        if (successor.getId()
            .equals(target.getId())) {
            throw new CommandException("Нельзя передать лидерство самому сбрасываемому игроку.");
        }
        if (suNeedsTransfer && !suTeam.isMember(successor)) {
            throw new CommandException(
                "Игрок " + successor.getName() + " не состоит в ServerUtilities-команде " + target.getName() + ".");
        }
        if (bqNeedsTransfer && !partyEntry.getValue()
            .getMembers()
            .contains(successor.getId())) {
            throw new CommandException(
                "Игрок " + successor.getName() + " не состоит в BetterQuesting-команде " + target.getName() + ".");
        }
    }

    public static final class TeamResetResult {

        public int deletedPersonalDimension;
        public boolean keptSharedPersonalDimension;
        public boolean serverUtilitiesTeamRemoved;
        public boolean serverUtilitiesTeamDeleted;
        public boolean betterQuestingPartyRemoved;
        public boolean betterQuestingPartyDeleted;
        public String leadershipTransferredTo;
    }

    public static void resetQuests(ForgePlayer target) {
        for (IQuest quest : QuestDatabase.INSTANCE.values()) {
            quest.resetUser(target.getId(), true);
        }
        SaveLoadHandler.INSTANCE.addDirtyPlayers(target.getId());
        SaveLoadHandler.INSTANCE.markDirty();

        EntityPlayerMP player = target.getNullablePlayer();
        if (player != null) {
            NetQuestSync.sendSync(player, null, false, true, true);
        }
    }

    public static void resetThaum(ForgePlayer target) throws CommandException {
        String playerName = target.getName();
        PlayerKnowledge knowledge = Thaumcraft.proxy.getPlayerKnowledge();

        knowledge.wipePlayerKnowledge(playerName);
        knowledge.setWarpCounter(playerName, 0);
        knowledge.setWarpPerm(playerName, 0);
        knowledge.setWarpTemp(playerName, 0);
        knowledge.setWarpSticky(playerName, 0);
        knowledge.researchCompleted.put(playerName, new ArrayList<>());
        knowledge.aspectsDiscovered.put(playerName, new AspectList());
        knowledge.objectsScanned.put(playerName, new ArrayList<>());
        knowledge.entitiesScanned.put(playerName, new ArrayList<>());
        knowledge.phenomenaScanned.put(playerName, new ArrayList<>());

        EntityPlayerMP online = target.getNullablePlayer();
        WorldServer overworld = MinecraftServer.getServer()
            .worldServerForDimension(0);

        for (Aspect aspect : Aspect.aspects.values()) {
            if (aspect.getComponents() == null) {
                short amount = (short) (15 + (online != null ? online.worldObj.rand : overworld.rand).nextInt(5));
                ResearchManager.completeAspectUnsaved(playerName, aspect, amount);
            }
        }

        for (ResearchCategoryList category : ResearchCategories.researchCategories.values()) {
            for (ResearchItem research : category.research.values()) {
                if (research.isAutoUnlock()) {
                    ResearchManager.completeResearchUnsaved(playerName, research.key);
                }
            }
        }

        File playerDataDirectory = getPlayerDataDirectory();
        File primary = new File(playerDataDirectory, playerName + ".thaum");
        File backup = new File(playerDataDirectory, playerName + ".thaumback");
        File uuidPrimary = new File(playerDataDirectory, target.getId() + ".thaum");
        File uuidBackup = new File(playerDataDirectory, target.getId() + ".thaumback");
        File uuidOldBackup = new File(playerDataDirectory, target.getId() + ".thaumbak");

        deleteFileIfExists(primary);
        deleteFileIfExists(backup);
        deleteFileIfExists(uuidPrimary);
        deleteFileIfExists(uuidBackup);
        deleteFileIfExists(uuidOldBackup);

        if (online != null) {
            if (Thaumcraft.instance != null && Thaumcraft.instance.runicEventHandler != null) {
                Thaumcraft.instance.runicEventHandler.runicCharge.remove(online.getEntityId());
                Thaumcraft.instance.runicEventHandler.isDirty = true;
            }

            if (!ResearchManager.savePlayerData(online, primary, backup)) {
                throw new CommandException("Не удалось сохранить сброшенные данные Thaumcraft для " + playerName + ".");
            }

            PacketHandler.INSTANCE.sendTo(new PacketSyncResearch(online), online);
            PacketHandler.INSTANCE.sendTo(new PacketSyncAspects(online), online);
            PacketHandler.INSTANCE.sendTo(new PacketSyncWarp(online, (byte) 0), online);
            PacketHandler.INSTANCE.sendTo(new PacketSyncWarp(online, (byte) 1), online);
            PacketHandler.INSTANCE.sendTo(new PacketSyncWarp(online, (byte) 2), online);
        }
    }

    public static boolean resetPlayerData(ForgePlayer target) throws CommandException {
        target.deserializeNBT(new NBTTagCompound());
        target.clearCache();
        target.markDirty();

        Ranks ranks = Ranks.INSTANCE;
        if (ranks != null) {
            PlayerRank playerRank = ranks.playerRanks.get(target.getId());
            if (playerRank != null && playerRank.remove()) {
                ranks.save();
                ranks.clearCache();
            }
        }

        TempRankManager.get()
            .clearPlayer(target.getId());

        EntityPlayerMP online = target.getNullablePlayer();
        if (online == null) {
            try {
                finalizePlayerDataReset(target.getId(), target.getName());
            } catch (IOException e) {
                throw new CommandException(
                    "Не удалось удалить playerdata/stats игрока " + target.getName() + ": " + e.getMessage());
            }
            return false;
        }

        PlayerResetEvents.INSTANCE.queue(target.getId(), target.getName());
        online.playerNetServerHandler.kickPlayerFromServer("Ваш прогресс сброшен. Перезайдите на сервер.");
        return true;
    }

    static int finalizePlayerDataReset(UUID playerId, String playerName) throws IOException {
        File saveRoot = DimensionManager.getCurrentSaveRootDirectory();
        if (saveRoot == null) {
            throw new IOException("Не удалось определить папку мира сервера");
        }

        int deleted = 0;
        File playerData = new File(saveRoot, "playerdata");
        File stats = new File(saveRoot, "stats");

        deleted += deleteResetFile(new File(playerData, playerId + ".dat"));
        deleted += deleteResetFile(new File(playerData, playerId + ".dat_old"));
        deleted += deleteResetFile(new File(playerData, playerName + ".dat"));
        deleted += deleteResetFile(new File(playerData, playerName + ".dat_old"));
        deleted += deleteResetFile(new File(stats, playerId + ".json"));
        deleted += deleteResetFile(new File(stats, playerName + ".json"));

        ForgePlayer forgePlayer = Universe.get()
            .getPlayer(playerId);
        if (forgePlayer != null) {
            forgePlayer.clearCache();
        }

        return deleted;
    }

    public static int resetPersonalDimension(ForgePlayer target) throws CommandException {
        if (!target.hasTeam()) {
            return -1;
        }

        ForgeTeam team = target.team;
        TeamsManager manager = TeamsManager.get();
        int dimId = resolvePersonalDimension(target, team, manager);

        if (dimId <= 0) {
            manager.resetPDimState(team, 0);
            return 0;
        }

        File saveRoot = DimensionManager.getCurrentSaveRootDirectory();
        if (saveRoot == null) {
            throw new CommandException("Не удалось определить папку мира сервера.");
        }

        DimensionConfig config = DimensionConfig.getForDimension(dimId, false);
        if (config == null && DimensionManager.isDimensionRegistered(dimId)) {
            throw new CommandException(
                "DIM" + dimId + " зарегистрирован, но не принадлежит PersonalSpace. Удаление остановлено.");
        }

        File dimensionDirectory = resolveDimensionDirectory(saveRoot, dimId, config);

        evacuatePlayers(dimId);
        disableTeamPortals(team, dimId);
        removeTeamHomes(team, dimId);
        removeTeamClaims(team, dimId);
        unloadDimension(dimId);
        RegionFileCache.clearRegionFileReferences();
        deleteDimensionDirectory(saveRoot, dimensionDirectory);
        unregisterPersonalDimension(dimId);
        manager.resetPDimState(team, dimId);
        syncPersonalSpaceWorldList();

        return dimId;
    }

    private static int resolvePersonalDimension(ForgePlayer target, ForgeTeam team, TeamsManager manager)
        throws CommandException {
        int dimId = manager.getDim(team);
        if (dimId > 0) {
            return dimId;
        }

        EntityPlayerMP online = target.getNullablePlayer();
        if (online != null && PersonalSpaceBinding.isPersonalDimension(online.dimension)) {
            if (manager.bindDimIfFree(team, online.dimension)) {
                return online.dimension;
            }
        }

        Set<Integer> found = PersonalSpaceBinding.findHistoricalPersonalDimensions(team);
        if (found.size() > 1) {
            throw new CommandException(
                "У команды игрока " + target.getName()
                    + " найдено несколько PersonalSpace: "
                    + found
                    + ". Сначала используй /pdim bind "
                    + target.getName()
                    + " <dim>.");
        }

        if (found.size() == 1) {
            int detected = found.iterator()
                .next();
            if (!manager.bindDimIfFree(team, detected)) {
                throw new CommandException("Не удалось привязать найденную PersonalSpace DIM" + detected + ".");
            }
            return detected;
        }

        return 0;
    }

    private static void evacuatePlayers(int dimId) {
        MinecraftServer server = MinecraftServer.getServer();
        List<EntityPlayerMP> players = new ArrayList<>();

        for (Object value : server.getConfigurationManager().playerEntityList) {
            if (value instanceof EntityPlayerMP player && player.dimension == dimId) {
                players.add(player);
            }
        }

        for (EntityPlayerMP player : players) {
            WorldTravelManager.returnToDimensionSpawn(player, 0);
        }
    }

    private static void disableTeamPortals(ForgeTeam team, int dimId) {
        WorldServer overworld = MinecraftServer.getServer()
            .worldServerForDimension(0);
        if (overworld == null) {
            return;
        }

        for (Object value : new ArrayList<>(overworld.loadedTileEntityList)) {
            if (value instanceof PortalTileEntity portal) {
                resetPortalTarget(portal, dimId);
            }
        }

        if (ClaimedChunks.instance == null) {
            return;
        }

        for (ClaimedChunk claimed : ClaimedChunks.instance.getTeamChunks(team, OptionalInt.of(0), true)) {
            ChunkDimPos pos = claimed.getPos();
            boolean wasLoaded = overworld.theChunkProviderServer.chunkExists(pos.posX, pos.posZ);
            Chunk chunk = overworld.theChunkProviderServer.loadChunk(pos.posX, pos.posZ);

            try {
                for (Object value : chunk.chunkTileEntityMap.values()) {
                    if (value instanceof PortalTileEntity portal) {
                        resetPortalTarget(portal, dimId);
                    }
                }
            } finally {
                if (!wasLoaded) {
                    overworld.theChunkProviderServer.unloadChunksIfNotNearSpawn(pos.posX, pos.posZ);
                }
            }
        }
    }

    private static void resetPortalTarget(PortalTileEntity portal, int dimId) {
        if (portal.targetDimId != dimId) {
            return;
        }
        portal.active = false;
        portal.targetDimId = 0;
        portal.markDirty();
    }

    private static void removeTeamHomes(ForgeTeam team, int dimId) {
        for (ForgePlayer member : team.getMembers()) {
            ServerUtilitiesPlayerData data = ServerUtilitiesPlayerData.get(member);
            if (data == null || data.homes == null) {
                continue;
            }

            boolean changed = false;
            List<String> homes = new ArrayList<>(data.homes.list());
            for (String home : homes) {
                BlockDimPos pos = data.homes.get(home);
                if (pos != null && pos.dim == dimId) {
                    data.homes.set(home, null);
                    changed = true;
                }
            }

            if (changed) {
                member.markDirty();
            }
        }
    }

    private static void removeTeamClaims(ForgeTeam team, int dimId) {
        if (ClaimedChunks.instance == null) {
            return;
        }
        ClaimedChunks.instance.processQueue();
        ClaimedChunks.instance.unclaimAllChunks(null, team, OptionalInt.of(dimId));
        ClaimedChunks.instance.processQueue();
    }

    private static void unloadDimension(int dimId) throws CommandException {
        WorldServer world = DimensionManager.getWorld(dimId);
        if (world == null) {
            return;
        }

        try {
            world.saveAllChunks(true, null);
        } catch (MinecraftException e) {
            throw new CommandException("Не удалось сохранить PersonalSpace DIM" + dimId + " перед удалением.");
        }

        MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(world));
        world.flush();
        DimensionManager.setWorld(dimId, null);
    }

    private static void unregisterPersonalDimension(int dimId) {
        synchronized (CommonProxy.getDimensionConfigObjects(false)) {
            CommonProxy.getDimensionConfigObjects(false)
                .remove(dimId);
        }

        if (DimensionManager.isDimensionRegistered(dimId)) {
            DimensionManager.unregisterDimension(dimId);
            DimensionManager.unregisterProviderType(dimId);
        }
    }

    private static File resolveDimensionDirectory(File saveRoot, int dimId, DimensionConfig config) {
        if (config != null) {
            return new File(saveRoot, config.getSaveDir(dimId));
        }

        File direct = new File(saveRoot, "PERSONAL_DIM_" + dimId);
        if (hasMatchingMetadata(direct, dimId)) {
            return direct;
        }

        File[] files = saveRoot.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && hasMatchingMetadata(file, dimId)) {
                    return file;
                }
            }
        }

        return null;
    }

    private static boolean hasMatchingMetadata(File directory, int dimId) {
        File metadata = new File(directory, PersonalSpaceMod.DIM_METADATA_FILE);
        if (!metadata.isFile()) {
            return false;
        }

        try {
            DimensionConfig probe = new DimensionConfig();
            return probe.syncWithFile(metadata, false, 0) == dimId;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void deleteDimensionDirectory(File saveRoot, File dimensionDirectory) throws CommandException {
        if (dimensionDirectory == null || !dimensionDirectory.exists()) {
            return;
        }

        try {
            File root = saveRoot.getCanonicalFile();
            File target = dimensionDirectory.getCanonicalFile();
            String rootPath = root.getPath() + File.separator;
            if (target.equals(root) || !target.getPath()
                .startsWith(rootPath)) {
                throw new CommandException("Небезопасный путь PersonalSpace: " + target.getPath());
            }
            deleteRecursively(target);
        } catch (IOException e) {
            throw new CommandException("Не удалось удалить папку PersonalSpace: " + dimensionDirectory.getPath());
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        if (!file.delete()) {
            throw new IOException("Could not delete " + file.getPath());
        }
    }

    private static void syncPersonalSpaceWorldList() {
        try {
            Object packet = Packets.class.getMethod("sendWorldList")
                .invoke(Packets.INSTANCE);
            if (packet != null) {
                packet.getClass()
                    .getMethod("sendToClients")
                    .invoke(packet);
            }
        } catch (Throwable ignored) {}
    }

    private static File getPlayerDataDirectory() throws CommandException {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        if (root == null) {
            throw new CommandException("Не удалось определить папку playerdata для Thaumcraft.");
        }
        return new File(root, "playerdata");
    }

    private static int deleteResetFile(File file) throws IOException {
        if (!file.exists()) {
            return 0;
        }
        if (!file.delete()) {
            throw new IOException("Не удалось удалить " + file.getPath());
        }
        return 1;
    }

    private static void deleteFileIfExists(File file) throws CommandException {
        if (file.exists() && !file.delete()) {
            throw new CommandException("Не удалось удалить файл Thaumcraft: " + file.getPath());
        }
    }
}
