package io.github.pulverizer.movecraft.mapUpdater.update;

import io.github.pulverizer.movecraft.Movecraft;
import org.spongepowered.api.entity.explosive.Explosive;
import org.spongepowered.api.entity.explosive.PrimedTNT;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;
import org.spongepowered.api.world.explosion.Explosion;

import java.util.Objects;

public class ExplosionUpdateCommand extends UpdateCommand {
    private final Location<World> explosionLocation;
    private final float explosionStrength;

    public ExplosionUpdateCommand(Location<World> explosionLocation, float explosionStrength) throws IllegalArgumentException {
        if(explosionStrength < 0){
            throw new IllegalArgumentException("Explosion strength cannot be negative");
        }
        this.explosionLocation = explosionLocation;
        this.explosionStrength = explosionStrength;
    }

    public Location<World> getLocation() {
        return explosionLocation;
    }

    public float getStrength() {
        return explosionStrength;
    }

    @Override
    public void doUpdate() {
        //if (explosionStrength > 0) { // don't bother with tiny explosions
        //Location loc = new Location(explosionLocation.getWorld(), explosionLocation.getX() + 0.5, explosionLocation.getY() + 0.5, explosionLocation.getZ());
        this.createExplosion(explosionLocation.add(.5,.5,.5), explosionStrength);
        //}

    }

    private void createExplosion(Location<World> loc, float explosionPower) {

        Explosion.builder()
                .location(loc)
                .shouldBreakBlocks(true)
                .shouldDamageEntities(true)
                .shouldPlaySmoke(true)
                .radius(explosionPower)
                .canCauseFire(false)
                .build();
    }

    @Override
    public int hashCode() {
        return Objects.hash(explosionLocation, explosionStrength);
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof ExplosionUpdateCommand)){
            return false;
        }
        ExplosionUpdateCommand other = (ExplosionUpdateCommand) obj;
        return this.explosionLocation.equals(other.explosionLocation) &&
                this.explosionStrength == other.explosionStrength;
    }
}