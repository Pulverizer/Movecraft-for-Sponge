package io.github.pulverizer.movecraft.sign;

import io.github.pulverizer.movecraft.craft.Craft;
import io.github.pulverizer.movecraft.utils.MathUtils;
import io.github.pulverizer.movecraft.craft.CraftManager;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.block.tileentity.Sign;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.block.InteractBlockEvent;
import org.spongepowered.api.event.filter.cause.Root;
import org.spongepowered.api.event.filter.type.Include;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.world.World;

public class AntiAircraftDirectorSign {
    private static final String HEADER = "AA Director";

    @Listener
    @Include({InteractBlockEvent.Primary.class, InteractBlockEvent.Secondary.MainHand.class})
    public final void onSignClick(InteractBlockEvent event, @Root Player player) {

        BlockSnapshot block = event.getTargetBlock();
        if (block.getState().getType() != BlockTypes.STANDING_SIGN && block.getState().getType() != BlockTypes.WALL_SIGN) {
            return;
        }

        if (!block.getLocation().isPresent() || !block.getLocation().get().getTileEntity().isPresent())
            return;

        Sign sign = (Sign) block.getLocation().get().getTileEntity().get();
        if (!sign.lines().get(0).toPlain().equalsIgnoreCase(HEADER)) {
            return;
        }

        event.setCancelled(true);

        Craft craft = CraftManager.getInstance().getCraftByPlayer(player.getUniqueId());

        if (craft == null) {
            player.sendMessage(Text.of("You are not part of the crew aboard this craft!"));
            return;
        }

        if (!craft.getType().allowAADirectorSign()) {
            player.sendMessage(Text.of("ERROR: AA Director Signs not allowed on this craft!"));
            return;
        }
        if(event instanceof InteractBlockEvent.Primary && player.getUniqueId() == craft.getAADirector()){
            craft.setAADirector(null);
            player.sendMessage(Text.of("You are no longer directing the AA of this craft."));
            return;
        }

        craft.setAADirector(player.getUniqueId());
        player.sendMessage(Text.of("You are now directing the AA of this craft."));

        if (craft.getCannonDirector() == player.getUniqueId())
            craft.setCannonDirector(null);

        if (craft.getPilot() == player.getUniqueId())
            craft.setPilot(null);
    }
}