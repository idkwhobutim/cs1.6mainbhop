package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.csmove.CsMoveServer;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's "moved too quickly / moved wrongly" validation and its position update are skipped for players whose
 * position comes from CS movement (the server simulates them itself from their input); only the view angles are taken.
 * Everybody else goes through vanilla unchanged.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMoveMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true, require = 0)
    private void cshudcraft$move(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        ServerPlayerEntity p = this.player;
        if (p == null || !CsMoveServer.controls(p)) return;
        // first call arrives on the network thread: let vanilla's own forceMainThread re-queue it and handle it on the next pass
        if (!p.getEntityWorld().getServer().isOnThread()) return;
        if (packet.changesLook()) {
            p.setYaw(packet.getYaw(p.getYaw()));
            p.setPitch(packet.getPitch(p.getPitch()));
        }
        ci.cancel();
    }
}
