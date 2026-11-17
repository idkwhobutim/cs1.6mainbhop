package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.csmove.CsMoveClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CS movement reads Shift (duck) and Ctrl (walk) itself, so the same keys must not also sprint or sneak: the input that
 * vanilla and the server see keeps WASD and jump and drops sneak and sprint.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void cshudcraft$noSneakSprint(CallbackInfo ci) {
        if (!CsMoveClient.driving()) return;
        GameOptions settings = MinecraftClient.getInstance().options;
        this.playerInput = new PlayerInput(settings.forwardKey.isPressed(), settings.backKey.isPressed(),
                settings.leftKey.isPressed(), settings.rightKey.isPressed(), settings.jumpKey.isPressed(), false, false);
    }
}
