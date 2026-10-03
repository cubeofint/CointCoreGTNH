package coint;

import com.gtnewhorizon.gtnhmixins.builders.ITargetMod;
import com.gtnewhorizon.gtnhmixins.builders.TargetModBuilder;

public enum TargetMod implements ITargetMod {

    APPLIEDENERGISTICS2("appliedenergistics2"),
    BACKPACK("Backpack"),
    BETTERQUESTING("betterquesting"),
    BLOODMAGIC("AWWayofTime"),
    CROPSNH("cropsnh"),
    ENDERIO("EnderIO"),
    FORESTRY("Forestry"),
    GALACTICRAFT("GalacticraftCore"),
    MATTERMANIPULATOR("matter-manipulator"),
    PERSONALSPACE("personalspace"),
    SERVERUTILITIES("serverutilities"),
    THAUMCRAFT("Thaumcraft"),
    THAUMICEXPLORATION("ThaumicExploration");

    private final TargetModBuilder builder;

    TargetMod(String modId) {
        this.builder = new TargetModBuilder().setModId(modId);
    }

    @Override
    public TargetModBuilder getBuilder() {
        return builder;
    }
}
