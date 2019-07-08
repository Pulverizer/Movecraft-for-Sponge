package io.github.pulverizer.movecraft.async.rotation;

import com.flowpowered.math.vector.Vector3d;
import com.flowpowered.math.vector.Vector3i;
import io.github.pulverizer.movecraft.CraftState;
import io.github.pulverizer.movecraft.Movecraft;
import io.github.pulverizer.movecraft.MovecraftLocation;
import io.github.pulverizer.movecraft.Rotation;
import io.github.pulverizer.movecraft.craft.Craft;
import io.github.pulverizer.movecraft.events.CraftRotateEvent;
import io.github.pulverizer.movecraft.utils.*;
import io.github.pulverizer.movecraft.utils.HashHitBox;
import io.github.pulverizer.movecraft.async.AsyncTask;
import io.github.pulverizer.movecraft.config.Settings;
import io.github.pulverizer.movecraft.craft.CraftManager;
import io.github.pulverizer.movecraft.mapUpdater.update.CraftRotateCommand;
import io.github.pulverizer.movecraft.mapUpdater.update.EntityUpdateCommand;
import io.github.pulverizer.movecraft.mapUpdater.update.UpdateCommand;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.block.tileentity.carrier.Furnace;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.item.ItemTypes;
import org.spongepowered.api.item.inventory.Inventory;
import org.spongepowered.api.item.inventory.query.QueryOperationTypes;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.util.AABB;
import org.spongepowered.api.util.Direction;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class RotationTask extends AsyncTask {
    private final MovecraftLocation originPoint;
    private final Rotation rotation;
    private final World w;
    private final boolean isSubCraft;
    private boolean failed = false;
    private String failMessage;
    private Set<UpdateCommand> updates = new HashSet<>();
    private boolean taskFinished = false;

    private final HashHitBox oldHitBox;
    private final HashHitBox newHitBox;

    public RotationTask(Craft c, Vector3i originPoint, Rotation rotation, World w, boolean isSubCraft) {
        super(c);
        this.originPoint = new MovecraftLocation(originPoint.getX(), originPoint.getY(), originPoint.getZ());
        this.rotation = rotation;
        this.w = w;
        this.isSubCraft = isSubCraft;
        this.newHitBox = new HashHitBox();
        this.oldHitBox = new HashHitBox(c.getHitBox());
    }

    @Override
    protected void execute() {

        if(oldHitBox.isEmpty())
            return;

        if (getCraft().getState() == CraftState.DISABLED) {
            failed = true;
            failMessage = "Craft is disabled!";
        }

        // check for fuel, burn some from a furnace if needed. Blocks of coal are supported, in addition to coal and charcoal
        double fuelBurnRate = getCraft().getType().getFuelBurnRate();
        if (fuelBurnRate != 0.0 && getCraft().getState() != CraftState.SINKING) {

            boolean fuelBurned = getCraft().burnFuel(fuelBurnRate);

            if (!fuelBurned) {
                failed = true;
                failMessage = "Translation Failed - Craft out of fuel";
            }
        }

        // if a subcraft, find the parent craft. If not a subcraft, it is it's own parent
        Set<Craft> craftsInWorld = CraftManager.getInstance().getCraftsInWorld(getCraft().getWorld());
        Craft parentCraft = getCraft();
        for (Craft craft : craftsInWorld) {
            if ( craft != getCraft() && craft.getHitBox().intersects(oldHitBox)) {
                parentCraft = craft;
                break;
            }
        }

        for(MovecraftLocation originalLocation : oldHitBox){
            MovecraftLocation newLocation = MathUtils.rotateVec(rotation,originalLocation.subtract(originPoint)).add(originPoint);
            newHitBox.add(newLocation);

            BlockType oldMaterial = originalLocation.toSponge(w).getBlockType();
            //prevent chests collision
            if ((oldMaterial.equals(BlockTypes.CHEST) || oldMaterial.equals(BlockTypes.TRAPPED_CHEST)) &&
                    !checkChests(oldMaterial, newLocation)) {
                failed = true;
                failMessage = String.format("Rotation Failed- Craft is obstructed" + " @ %d,%d,%d", newLocation.getX(), newLocation.getY(), newLocation.getZ());
                break;
            }


            BlockType newMaterial = newLocation.toSponge(w).getBlockType();
            if ((newMaterial == BlockTypes.AIR) || (newMaterial == BlockTypes.PISTON_EXTENSION) || craft.getType().getPassthroughBlocks().contains(newMaterial)) {
                continue;
            }

            if (!oldHitBox.contains(newLocation)) {
                failed = true;
                failMessage = String.format("Rotation Failed - Craft is obstructed" + " @ %d,%d,%d", newLocation.getX(), newLocation.getY(), newLocation.getZ());
                break;
            }
        }
        if (failed) {
            if (this.isSubCraft && parentCraft != getCraft()) {
                parentCraft.setProcessing(false);
            }
            return;
        }
        //call event
        CraftRotateEvent event = new CraftRotateEvent(craft, oldHitBox, newHitBox);
        Sponge.getEventManager().post(event);
        if(event.isCancelled()){
            failed = true;
            failMessage = event.getFailMessage();
            return;
        }


        updates.add(new CraftRotateCommand(getCraft(),originPoint, rotation));
        //translate entities in the craft
        final Vector3d tOP = originPoint.toVector3i().toDouble().add(0.5, 0, 0.5);

        //prevents torpedo and rocket passengers
        if (craft.getType().getMoveEntities() && craft.getState() != CraftState.SINKING) {

            if (Settings.Debug)
                Movecraft.getInstance().getLogger().info("Craft moves Entities.");

            Task.builder()
                    .execute(() -> {
                        for (Entity entity : craft.getWorld().getIntersectingEntities(new AABB(oldHitBox.getMinX() -0.5, oldHitBox.getMinY() -0.5, oldHitBox.getMinZ() -0.5, oldHitBox.getMaxX() +0.5, oldHitBox.getMaxY() +0.5, oldHitBox.getMaxZ() +0.5))) {

                            if (entity.getType() == EntityTypes.PLAYER || entity.getType() == EntityTypes.PRIMED_TNT || !craft.getType().getOnlyMovePlayers()) {
                                if (Settings.Debug) {
                                    Movecraft.getInstance().getLogger().info(originPoint + ", " + tOP);
                                    Movecraft.getInstance().getLogger().info("Registering Entity of type " + entity.getType().getName() + " for movement.");
                                }

                                Location<World> adjustedPLoc = entity.getLocation().sub(tOP);

                                if (Settings.Debug)
                                    Movecraft.getInstance().getLogger().info(adjustedPLoc.getPosition().toString());

                                double[] rotatedCoords = MathUtils.rotateVecNoRound(rotation, adjustedPLoc.getX(), adjustedPLoc.getZ());
                                float newYaw = rotation == Rotation.CLOCKWISE ? 90F : -90F;
                                EntityUpdateCommand eUp = new EntityUpdateCommand(entity, new Vector3d(rotatedCoords[0] + tOP.getX(), entity.getLocation().getY(), rotatedCoords[1] + tOP.getZ()), newYaw);
                                updates.add(eUp);
                            }
                        }

                        if (Settings.Debug)
                            Movecraft.getInstance().getLogger().info("Submitting Entity Movements.");

                        setTaskFinished();
                    })
                    .submit(Movecraft.getInstance());

            while (!taskFinished) {
                if (Settings.Debug)
                    Movecraft.getInstance().getLogger().info("Still Processing Entities!");
            }

            if (taskFinished && Settings.Debug)
                Movecraft.getInstance().getLogger().info("Processed Entities.");
        }

        if (getCraft().getState() == CraftState.CRUISING) {
            if (rotation == Rotation.ANTICLOCKWISE) {

                switch (getCraft().getCruiseDirection()) {
                    case NORTH:
                        getCraft().setCruiseDirection(Direction.WEST);
                        break;

                    case SOUTH:
                        getCraft().setCruiseDirection(Direction.EAST);
                        break;

                    case EAST:
                        getCraft().setCruiseDirection(Direction.NORTH);
                        break;

                    case WEST:
                        getCraft().setCruiseDirection(Direction.SOUTH);
                        break;
                }
            } else if (rotation == Rotation.CLOCKWISE) {

                switch (getCraft().getCruiseDirection()) {
                    case NORTH:
                        getCraft().setCruiseDirection(Direction.EAST);
                        break;

                    case SOUTH:
                        getCraft().setCruiseDirection(Direction.WEST);
                        break;

                    case EAST:
                        getCraft().setCruiseDirection(Direction.SOUTH);
                        break;

                    case WEST:
                        getCraft().setCruiseDirection(Direction.NORTH);
                        break;
                }
            }
        }

        // if you rotated a subcraft, update the parent with the new blocks
        if (this.isSubCraft) {
            // also find the furthest extent from center and notify the player of the new direction
            int farthestX = 0;
            int farthestZ = 0;
            for (MovecraftLocation loc : newHitBox) {
                if (Math.abs(loc.getX() - originPoint.getX()) > Math.abs(farthestX))
                    farthestX = loc.getX() - originPoint.getX();
                if (Math.abs(loc.getZ() - originPoint.getZ()) > Math.abs(farthestZ))
                    farthestZ = loc.getZ() - originPoint.getZ();
            }

            Player pilot = Sponge.getServer().getPlayer(getCraft().getPilot()).orElse(null);

            if (pilot != null) {
                if (Math.abs(farthestX) > Math.abs(farthestZ)) {
                    if (farthestX > 0) {
                        pilot.sendMessage(Text.of("The farthest extent now faces East"));
                    } else {
                        pilot.sendMessage(Text.of("The farthest extent now faces West"));
                    }
                } else {
                    if (farthestZ > 0) {
                        pilot.sendMessage(Text.of("The farthest extent now faces South"));
                    } else {
                        pilot.sendMessage(Text.of("The farthest extent now faces North"));
                    }
                }
            }

            craftsInWorld = CraftManager.getInstance().getCraftsInWorld(getCraft().getWorld());
            for (Craft craft : craftsInWorld) {
                if (newHitBox.intersects(craft.getHitBox()) && craft != getCraft()) {
                    //newHitBox.addAll(CollectionUtils.filter(craft.getHitBox(),newHitBox));
                    //craft.setHitBox(newHitBox);
                    craft.getHitBox().removeAll(oldHitBox);
                    craft.getHitBox().addAll(newHitBox);
                    break;
                }
            }
        }

    }

    public void setTaskFinished(){
        taskFinished = true;
    }

    private static HitBox rotateHitBox(HitBox hitBox, MovecraftLocation originPoint, Rotation rotation){
        MutableHitBox output = new HashHitBox();
        for(MovecraftLocation location : hitBox){
            output.add(MathUtils.rotateVec(rotation,originPoint.subtract(originPoint)).add(originPoint));
        }
        return output;
    }
    public MovecraftLocation getOriginPoint() {
        return originPoint;
    }

    public boolean isFailed() {
        return failed;
    }

    public String getFailMessage() {
        return failMessage;
    }

    public Set<UpdateCommand> getUpdates() {
        return updates;
    }

    public Rotation getRotation() {
        return rotation;
    }

    public boolean getIsSubCraft() {
        return isSubCraft;
    }

    private boolean checkChests(BlockType mBlock, MovecraftLocation newLoc) {
        BlockType testMaterial;
        MovecraftLocation aroundNewLoc;

        aroundNewLoc = newLoc.translate(1, 0, 0);
        testMaterial = craft.getWorld().getBlockType(aroundNewLoc.getX(), aroundNewLoc.getY(), aroundNewLoc.getZ());
        if (testMaterial.equals(mBlock)) {
            if (!oldHitBox.contains(aroundNewLoc)) {
                return false;
            }
        }

        aroundNewLoc = newLoc.translate(-1, 0, 0);
        testMaterial = craft.getWorld().getBlockType(aroundNewLoc.getX(), aroundNewLoc.getY(), aroundNewLoc.getZ());
        if (testMaterial.equals(mBlock)) {
            if (!oldHitBox.contains(aroundNewLoc)) {
                return false;
            }
        }

        aroundNewLoc = newLoc.translate(0, 0, 1);
        testMaterial = craft.getWorld().getBlockType(aroundNewLoc.getX(), aroundNewLoc.getY(), aroundNewLoc.getZ());
        if (testMaterial.equals(mBlock)) {
            if (!oldHitBox.contains(aroundNewLoc)) {
                return false;
            }
        }

        aroundNewLoc = newLoc.translate(0, 0, -1);
        testMaterial = craft.getWorld().getBlockType(aroundNewLoc.getX(), aroundNewLoc.getY(), aroundNewLoc.getZ());
        return !testMaterial.equals(mBlock) || oldHitBox.contains(aroundNewLoc);
    }

    public HashHitBox getNewHitBox() {
        return newHitBox;
    }
}