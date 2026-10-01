package coint.worldtravel;

import net.minecraft.entity.Entity;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;

final class FixedTeleporter extends Teleporter {

    private final WorldTravelDestination destination;

    FixedTeleporter(WorldServer world, WorldTravelDestination destination) {
        super(world);
        this.destination = destination;
    }

    @Override
    public void placeInPortal(Entity entity, double x, double y, double z, float yaw) {
        entity.setLocationAndAngles(destination.x, destination.y, destination.z, destination.yaw, destination.pitch);
        entity.motionX = 0.0D;
        entity.motionY = 0.0D;
        entity.motionZ = 0.0D;
    }
}
