package gg.melty.cshudcraft.csmove;

import gg.melty.cshudcraft.CsNet;
import gg.melty.cshudcraft.CsState;
import gg.melty.cshudcraft.Sheets;
import gg.melty.cshudcraft.pmove.CsPlayerMove;
import gg.melty.cshudcraft.pmove.MovementConfig;
import gg.melty.cshudcraft.pmove.MovementSheet;
import gg.melty.cshudcraft.pmove.UserCmd;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Shared (client and server) side of the CS movement port: the one {@link CsPlayerMove} instance, who is exempt,
 * the CS weapon speed, status effects folded into a {@link UserCmd}, and the hull the game should use for a player.
 * Everything numeric comes from sheets/movement.json via {@link MovementSheet}.
 */
public final class CsMove {
    private CsMove() {}

    private static CsPlayerMove core;

    public static MovementSheet sheet() { return MovementSheet.get(); }

    public static MovementConfig cfg() { return sheet().config; }

    public static synchronized CsPlayerMove core() {
        if (core == null) core = new CsPlayerMove(cfg());
        return core;
    }

    /** sheets/movement.json cs_movement: the master switch (0 = every player is fully vanilla). */
    public static boolean master() { return sheet().masterOn(); }

    /**
     * Situations that stay entirely with Minecraft: riding, gliding, creative / spectator flight, sleeping, dead.
     * (Elytra gliding starts from vanilla's own jump-in-air rule, so it is covered here too.)
     */
    public static boolean exempt(PlayerEntity p) {
        return p.hasVehicle() || p.isGliding() || p.isSpectator() || p.isSleeping() || !p.isAlive()
                || p.getAbilities().flying;
    }

    /** CS move speed (u/s) of what the player holds: the weapon's speed (scoped speed while zoomed), grenades and C4 at equipment_speed. */
    public static double weaponSpeed(CsState st, boolean scoped) {
        if (st == null) return cfg().toUnits(cfg().maxSpeed);
        if (st.slot == CsState.SLOT_GRENADE || st.slot == CsState.SLOT_BOMB) return cfg().toUnits(cfg().value("equipment_speed"));
        Sheets.Weapon w = st.current();
        if (w == null) return cfg().toUnits(cfg().value("equipment_speed"));
        if (scoped) {
            Double s = sheet().scopedSpeed.get(w.id());
            if (s != null) return s;
        }
        return w.speed();
    }

    /** Builds the input for one tick. Both sides call this with the same inputs, so Speed / Slowness etc. match. */
    public static UserCmd makeCmd(PlayerEntity p, CsState st, int buttons, float forward, float side,
                                  float yawPrev, float yawCur, float pitchPrev, float pitchCur) {
        MovementConfig c = cfg();
        double speed = weaponSpeed(st, (buttons & Buttons.SCOPED) != 0);
        StatusEffectInstance fast = p.getStatusEffect(StatusEffects.SPEED);
        StatusEffectInstance slow = p.getStatusEffect(StatusEffects.SLOWNESS);
        double mul = 1;
        if (fast != null) mul += c.value("effect_speed_per_level") * (fast.getAmplifier() + 1);
        if (slow != null) mul -= c.value("effect_slow_per_level") * (slow.getAmplifier() + 1);
        speed *= Math.max(0, mul);
        double jump = 1;
        StatusEffectInstance boost = p.getStatusEffect(StatusEffects.JUMP_BOOST);
        if (boost != null) {
            double base = c.value("vanilla_jump_velocity");
            jump = (base + c.value("jump_boost_per_level") * (boost.getAmplifier() + 1)) / base;
        }
        double gravity = p.hasStatusEffect(StatusEffects.SLOW_FALLING) ? c.value("slow_fall_gravity_mul") : 1;
        StatusEffectInstance lev = p.getStatusEffect(StatusEffects.LEVITATION);
        double levitate = lev == null ? 0 : c.value("levitation_speed") * (lev.getAmplifier() + 1);
        return new UserCmd(buttons & Buttons.PMOVE_MASK, forward, side, yawPrev, yawCur, pitchPrev, pitchCur,
                (float) speed, (float) jump, (float) gravity, (float) levitate);
    }

    /** Button bits on the wire: the core's JUMP / DUCK / WALK, plus two that only the game side uses. */
    public static final class Buttons {
        private Buttons() {}
        public static final int SCOPED = 8;
        /** Client is leaving CS movement (toggle off, riding, ...): the server hands the player back to vanilla. */
        public static final int LEAVE = 16;
        public static final int PMOVE_MASK = UserCmd.JUMP | UserCmd.DUCK | UserCmd.WALK;
    }

    // ------------------------------------------------------------------ hull

    /** Set by the client for the local player while it drives them; null otherwise. The server keeps its own per-player controllers. */
    private static volatile MoveCtl localCtl;
    private static volatile Entity localEntity;

    static void setLocal(Entity e, MoveCtl ctl) {
        localEntity = e;
        localCtl = ctl;
    }

    /** The controller currently driving this player on this side (the local client's, or the server's), or null for vanilla. */
    public static MoveCtl ctlOf(Entity e) {
        if (e == localEntity) return localCtl;
        return CsMoveServer.ctlOf(e);
    }

    /** What the player's hull should be while CS movement drives them: null = leave vanilla's. */
    public static EntityDimensions hull(Entity e, EntityDimensions vanilla) {
        if (!(e instanceof PlayerEntity p)) return null;
        EntityPose pose = p.getPose();
        if (pose != EntityPose.STANDING && pose != EntityPose.CROUCHING) return null;
        double height, eye;
        MoveCtl ctl = ctlOf(e);
        MovementConfig c = cfg();
        if (ctl != null) {
            height = c.hullHeight(ctl.state.ducking);
            eye = c.eyeHeight(ctl.state);
        } else {
            int mode = p.getAttachedOrElse(CsNet.MOVE, 0); // remote players: 0 vanilla, 1 standing, 2 ducked (server writes it)
            if (mode == 0) return null;
            height = c.hullHeight(mode == 2);
            eye = mode == 2 ? c.eyeDuck : c.eyeStand;
        }
        float scale = p.getScale(); // the scale attribute still applies
        return EntityDimensions.changing((float) c.hullWidth * scale, (float) height * scale).withEyeHeight((float) eye * scale);
    }

    /** True if CS movement currently owns this player's movement on this side. */
    public static boolean drives(Entity e) { return ctlOf(e) != null; }

    /** Set by the client: runs one tick of CS movement for the local player (true = handled). Kept as a hook so common mixins never touch client classes. */
    static volatile java.util.function.Predicate<Entity> clientTravel;

    /** LivingEntity.travel for a player: CS movement replaces it (client: predicts and sends; server: never runs vanilla travel for a driven player). */
    public static boolean cancelTravel(Entity e) {
        if (!(e instanceof PlayerEntity)) return false;
        if (e.getEntityWorld().isClient()) {
            java.util.function.Predicate<Entity> h = clientTravel;
            return h != null && h.test(e);
        }
        return CsMoveServer.ctlOf(e) != null;
    }

    /** Vanilla jump, swim-up and sink-down impulses are not applied to a driven player (pmove owns the vertical speed). */
    public static boolean cancelImpulse(Entity e) {
        return e instanceof PlayerEntity && ctlOf(e) != null;
    }
}
