package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.CsNet;
import gg.melty.cshudcraft.csmove.CsMove;
import gg.melty.cshudcraft.csmove.MoveCtl;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A ducking CS player shows (and is) crouched for everyone: pose CROUCHING with the duck hull from sheets/movement.json. */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMoveMixin {
    @Inject(method = "updatePose", at = @At("TAIL"), require = 0)
    private void cshudcraft$pose(CallbackInfo ci) {
        PlayerEntity p = (PlayerEntity) (Object) this;
        MoveCtl ctl = CsMove.ctlOf(p);
        int mode = ctl != null ? (ctl.state.ducking ? 2 : 1) : p.getAttachedOrElse(CsNet.MOVE, 0);
        if (mode == 0) return;
        EntityPose now = p.getPose();
        if (now != EntityPose.STANDING && now != EntityPose.CROUCHING) return;
        p.setPose(mode == 2 ? EntityPose.CROUCHING : EntityPose.STANDING);
    }

    @Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true, require = 0)
    private void cshudcraft$hull(EntityPose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        EntityDimensions d = CsMove.hull((Entity) (Object) this, cir.getReturnValue());
        if (d != null) cir.setReturnValue(d);
    }
}
