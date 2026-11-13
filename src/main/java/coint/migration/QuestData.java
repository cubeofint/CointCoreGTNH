package coint.migration;

import java.io.File;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.JsonObject;

import betterquesting.api.questing.party.IParty;
import betterquesting.api.utils.JsonHelper;
import betterquesting.api.utils.NBTConverter;
import betterquesting.api2.storage.DBEntry;
import betterquesting.handlers.SaveLoadHandler;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.party.PartyManager;

/** BetterQuesting progress and parties, loaded into the running server. */
final class QuestData {

    private QuestData() {}

    static NBTTagCompound partyOf(UUID player) {
        DBEntry<IParty> entry = PartyManager.INSTANCE.getParty(player);
        return entry == null ? null
            : entry.getValue()
                .writeToNBT(new NBTTagCompound());
    }

    /** Adds a player's progress file (QuestProgress/&lt;uuid&gt;.json) to what this server knows. */
    static void mergeProgress(File json, UUID player) {
        JsonObject root = JsonHelper.ReadFromFile(json);
        NBTTagCompound tag = NBTConverter.JSONtoNBT_Object(root, new NBTTagCompound(), true);
        NBTTagList progress = tag.getTagList("questProgress", 10);
        QuestDatabase.INSTANCE.readProgressFromNBT(progress, true);
        SaveLoadHandler.INSTANCE.addDirtyPlayers(player);
    }

    /** Puts the party as it was: its members leave whatever party they are in here first. */
    static void restoreParty(NBTTagCompound tag) {
        IParty party = new betterquesting.questing.party.PartyInstance();
        party.readFromNBT(tag);
        for (UUID member : party.getMembers()) {
            DBEntry<IParty> old = PartyManager.INSTANCE.getParty(member);
            if (old != null) {
                old.getValue()
                    .kickUser(member);
                if (old.getValue()
                    .getMembers()
                    .isEmpty()) {
                    PartyManager.INSTANCE.removeID(old.getID());
                }
            }
        }
        PartyManager.INSTANCE.add(PartyManager.INSTANCE.nextID(), party);
        SaveLoadHandler.INSTANCE.markDirty();
    }

    static void joinParty(UUID player, UUID owner) {
        DBEntry<IParty> destination = PartyManager.INSTANCE.getParty(owner);
        if (destination == null) return;
        DBEntry<IParty> old = PartyManager.INSTANCE.getParty(player);
        if (old != null && java.util.Objects.equals(old.getID(), destination.getID())) return;
        if (old != null) {
            old.getValue()
                .kickUser(player);
            if (old.getValue()
                .getMembers()
                .isEmpty()) PartyManager.INSTANCE.removeID(old.getID());
        }
        destination.getValue()
            .setStatus(player, betterquesting.api.enums.EnumPartyStatus.MEMBER);
        SaveLoadHandler.INSTANCE.addDirtyPlayers(player);
        SaveLoadHandler.INSTANCE.markDirty();
    }

    static void leaveParty(UUID player) {
        DBEntry<IParty> party = PartyManager.INSTANCE.getParty(player);
        if (party == null) return;
        party.getValue()
            .kickUser(player);
        if (party.getValue()
            .getMembers()
            .isEmpty()) PartyManager.INSTANCE.removeID(party.getID());
        SaveLoadHandler.INSTANCE.markDirty();
    }
}
