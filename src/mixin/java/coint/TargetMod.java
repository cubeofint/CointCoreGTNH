package coint;

import com.gtnewhorizon.gtnhmixins.builders.ITargetMod;
import com.gtnewhorizon.gtnhmixins.builders.TargetModBuilder;

public enum TargetMod implements ITargetMod {

    BACKPACK("Backpack"),
    BETTERQUESTING("betterquesting"),
    BLOODMAGIC("AWWayofTime"),
    FORESTRY("Forestry"),
    GALACTICRAFT("GalacticraftCore"),
    MATTERMANIPULATOR("matter-manipulator"),
    SERVERUTILITIES("serverutilities"),
    THAUMCRAFT("Thaumcraft");

    private final TargetModBuilder builder;

    TargetMod(String modId) {
        this.builder = new TargetModBuilder().setModId(modId);
    }

    @Override
    public TargetModBuilder getBuilder() {
        return builder;
    }
}
