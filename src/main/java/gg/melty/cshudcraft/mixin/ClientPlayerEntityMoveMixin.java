package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.csmove.CsMoveClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No sprint in CS movement (Ctrl is walk): neither the key nor the double-tap on W starts it, so there is no sprint FOV either. */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMoveMixin {
    @Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true, require = 0)
    private void cshudcraft$noSprint(CallbackInfoReturnable<Boolean> cir) {
        if (CsMoveClient.driving()) cir.setReturnValue(false);
    }
}
