package gg.melty.cshudcraft.mixin;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The key a binding is currently set to, so CS movement can read the physical key instead of vanilla's toggled sneak / sprint state. */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("boundKey")
    InputUtil.Key cshudcraft$getBoundKey();
}
