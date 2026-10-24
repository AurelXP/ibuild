package com.ibuild;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class BuilderStickItem extends Item {
    public BuilderStickItem(Properties properties) {
        super(properties);
    }

    /** Clic droit sur un bloc : place le build selectionne. Sneak + clic : build suivant. */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (!(level instanceof ServerLevel sl) || !(ctx.getPlayer() instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        if (sp.isShiftKeyDown()) {
            Builds.cycle(sp);
        } else {
            BlockPos base = ctx.getClickedPos().relative(ctx.getClickedFace());
            Builds.place(sl, sp, base);
        }
        return InteractionResult.SUCCESS;
    }

    /** Clic droit dans le vide : affiche le build selectionne. Sneak + clic : build suivant. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel && player instanceof ServerPlayer sp) {
            if (sp.isShiftKeyDown()) Builds.cycle(sp);
            else Builds.showSelected(sp);
        }
        return InteractionResult.SUCCESS;
    }
}
