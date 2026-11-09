package coint.migration;

import WayofTime.alchemicalWizardry.api.soulNetwork.SoulNetworkHandler;

/** A player's Blood Magic soul network (by player name, as Blood Magic keys it). Only touched with the mod loaded. */
final class BloodMagicData {

    private BloodMagicData() {}

    static int essence(String owner) {
        return SoulNetworkHandler.getCurrentEssence(owner);
    }

    static int orb(String owner) {
        return SoulNetworkHandler.getCurrentMaxOrb(owner);
    }

    /** Sets the network to what it was on the old server: the essence as it was, the orb tier at least as high. */
    static void restore(String owner, int essence, int orb) {
        SoulNetworkHandler.setMaxOrbToMax(owner, orb);
        SoulNetworkHandler.setCurrentEssence(owner, essence);
    }
}
