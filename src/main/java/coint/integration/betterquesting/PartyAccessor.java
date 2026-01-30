package coint.integration.betterquesting;

import java.util.UUID;
import java.util.function.BiConsumer;

import betterquesting.api.enums.EnumPartyStatus;
import betterquesting.api.questing.party.IParty;

/**
 * Accessor for party-related mixin callbacks.
 * This class serves as a bridge between the mixin and the main mod code.
 */
public final class PartyAccessor {

    private static BiConsumer<UUID, IParty> onPlayerJoinPartyCallback;

    private PartyAccessor() {
        // Utility class
    }

    /**
     * Set the callback to be invoked when a player joins a party.
     *
     * @param callback The callback function (UUID playerId, IParty party)
     */
    public static void setOnPlayerJoinPartyCallback(BiConsumer<UUID, IParty> callback) {
        onPlayerJoinPartyCallback = callback;
    }

    /**
     * Called from the mixin when a player's party status changes.
     *
     * @param playerId The player's UUID
     * @param party    The party
     * @param status   The new status
     */
    public static void onPlayerStatusChange(UUID playerId, IParty party, EnumPartyStatus status) {
        // Only trigger callback for new members (not for removals or status changes)
        if (status != null && onPlayerJoinPartyCallback != null) {
            // Check if this is a new member by seeing if the status is one of the member statuses
            if (status == EnumPartyStatus.MEMBER || status == EnumPartyStatus.ADMIN
                || status == EnumPartyStatus.OWNER) {
                onPlayerJoinPartyCallback.accept(playerId, party);
            }
        }
    }
}
