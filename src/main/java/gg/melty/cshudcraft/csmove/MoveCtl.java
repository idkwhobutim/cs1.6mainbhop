package gg.melty.cshudcraft.csmove;

import gg.melty.cshudcraft.pmove.MovementConfig;
import gg.melty.cshudcraft.pmove.PlayerMoveState;
import gg.melty.cshudcraft.pmove.UserCmd;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Drives one player with the pmove core: owns the {@link PlayerMoveState}, runs a tick of input over vanilla's block
 * collision, then "commits" the result to the entity by walking the core's path with vanilla {@code Entity.move} calls
 * (so collision events, step sounds, slime bounce, fall distance and every other vanilla side effect keep working) and
 * writes the entity's velocity. Used on the client (prediction) and on the server (authority) with the same code.
 */
public final class MoveCtl {
    private static final double TPS = 20.0;

    public final PlayerMoveState state = new PlayerMoveState();
    public final McMoveWorld world = new McMoveWorld();
    private boolean started;
    /** Velocity (blocks / tick) and position this controller last wrote to the entity: what "somebody else changed it" is measured against. */
    private double wvx, wvy, wvz, wx, wy, wz;
    private double lastHull = -1, lastEye = -1;
    /** The vanilla tick's fall-damage handling is replaced by the CS rule (set per tick by the caller). */
    public boolean csFall;
    /** Results of the last commit, for the server's fall damage and the debug overlay. */
    public boolean landedThisTick;
    public double landSpeedBps;

    /** Puts the state where the entity is (first use, after a teleport, or when vanilla takes over and gives back). */
    public void adopt(PlayerEntity p) {
        Vec3d pos = p.getEntityPos(), v = p.getVelocity();
        state.x = pos.x;
        state.y = pos.y;
        state.z = pos.z;
        state.vx = v.x * TPS;
        state.vy = v.y * TPS;
        state.vz = v.z * TPS;
        state.onGround = p.isOnGround();
        state.yaw = p.getYaw();
        state.pitch = p.getPitch();
        state.clearPath();
        remember(v);
        started = true;
    }

    private void remember(Vec3d v) {
        wvx = v.x; wvy = v.y; wvz = v.z;
        wx = state.x; wy = state.y; wz = state.z;
    }

    public boolean started() { return started; }

    /** True if the entity was moved by something other than this controller since the last commit (teleport, portal, command). */
    public boolean teleported(PlayerEntity p) {
        if (!started) return false;
        Vec3d pos = p.getEntityPos();
        double e = CsMove.cfg().value("external_teleport_eps");
        return pos.squaredDistanceTo(wx, wy, wz) > e * e;
    }

    /**
     * Velocity the game added since the last commit (knockback, explosion, a push), in blocks / s, or null. The server adds
     * it to the pmove velocity; it is the same value vanilla would have produced, because knockback is "set velocity",
     * and (new - what we wrote) is exactly what it changed.
     */
    public double[] externalImpulse(PlayerEntity p) {
        if (!started) return null;
        Vec3d v = p.getVelocity();
        double dx = (v.x - wvx) * TPS, dy = (v.y - wvy) * TPS, dz = (v.z - wvz) * TPS;
        double written = Math.sqrt(wvx * wvx + wvy * wvy + wvz * wvz) * TPS;
        double e = CsMove.cfg().value("external_impulse_eps") + CsMove.cfg().value("external_impulse_rel") * written;
        if (dx * dx + dy * dy + dz * dz <= e * e) return null;
        return new double[] {dx, dy, dz};
    }

    /** Runs one tick of input and applies it to the entity. */
    public void tick(PlayerEntity p, UserCmd cmd) {
        MovementConfig c = CsMove.cfg();
        double speed = Math.sqrt(state.vx * state.vx + state.vy * state.vy + state.vz * state.vz);
        world.gather(p.getEntityWorld(), p, state.x, state.y, state.z, speed);
        double sx = state.x, sy = state.y, sz = state.z;
        state.clearPath();
        CsMove.core().tick(state, cmd, world);
        commit(p, c, sx, sy, sz);
    }

    /** Replay of an already-sent input on the client after a correction: the core only, nothing is applied to the entity. */
    public void simulate(PlayerEntity p, UserCmd cmd) {
        double speed = Math.sqrt(state.vx * state.vx + state.vy * state.vy + state.vz * state.vz);
        world.gather(p.getEntityWorld(), p, state.x, state.y, state.z, speed);
        state.clearPath();
        CsMove.core().tick(state, cmd, world);
        state.clearPath();
    }

    private void commit(PlayerEntity p, MovementConfig c, double sx, double sy, double sz) {
        double[] path = state.path;
        int n = state.pathLen;
        // the hull first: the path was walked with the hull the core ended in (duck tuck, ceiling gaps), so vanilla must use it too
        refreshHull(p, c);
        Vec3d start = p.getEntityPos();
        if (start.squaredDistanceTo(sx, sy, sz) > 0) p.setPosition(sx, sy, sz); // a correction may have moved the state
        EntityAccess.noMovementMultiplier(p);
        boolean lands = state.landed && state.landSpeed > 0;
        double px = sx, py = sy, pz = sz;
        for (int i = 0; i + 2 < n; i += 3) {
            Vec3d d = new Vec3d(path[i] - px, path[i + 1] - py, path[i + 2] - pz);
            px = path[i];
            py = path[i + 1];
            pz = path[i + 2];
            if (d.lengthSquared() == 0) continue;
            if (csFall) p.fallDistance = 0;
            EntityAccess.noMovementMultiplier(p);
            p.move(MovementType.SELF, d);
        }
        // vanilla won where the game blocked the way (boats, shulkers: things the core does not collide with)
        double tol = c.value("commit_tolerance");
        Vec3d at = p.getEntityPos();
        if (at.squaredDistanceTo(state.x, state.y, state.z) > tol * tol) {
            state.x = at.x;
            state.y = at.y;
            state.z = at.z;
        }
        if (state.onGround) {
            // a nudge down so vanilla sees the floor: onGround, fall()/onLandedUpon (slime, hay, bed) and step sounds
            double probe = c.value("commit_probe");
            p.setVelocity(state.vx / TPS, lands ? -state.landSpeed / TPS : 0, state.vz / TPS);
            if (csFall) p.fallDistance = 0;
            EntityAccess.noMovementMultiplier(p);
            p.move(MovementType.SELF, new Vec3d(0, -probe, 0));
            Vec3d v = p.getVelocity();
            if (lands && v.y > 0) { // a bouncy block flipped the fall: let pmove carry on upward
                state.vy = v.y * TPS;
                state.onGround = false;
                lands = false; // no fall damage on a bounce
            }
            p.setPosition(state.x, state.y, state.z);
        }
        p.setOnGround(state.onGround);
        p.setVelocity(state.vx / TPS, state.vy / TPS, state.vz / TPS);
        if (csFall) p.fallDistance = 0;
        landedThisTick = lands;
        landSpeedBps = lands ? state.landSpeed : 0;
        state.clearPath();
        remember(p.getVelocity());
    }

    /** Re-reads the hull when the ducked flag or the eye height moved. */
    public void refreshHull(PlayerEntity p, MovementConfig c) {
        double h = c.hullHeight(state.ducking), eye = c.eyeHeight(state);
        if (h != lastHull || Math.abs(eye - lastEye) > 1e-3) {
            lastHull = h;
            lastEye = eye;
            p.calculateDimensions();
        }
    }

    /** Back to vanilla: the hull returns to Minecraft's. */
    public void release(PlayerEntity p) {
        lastHull = -1;
        lastEye = -1;
        started = false;
        p.calculateDimensions(); // the caller has already stopped driving this player, so the hull is vanilla's again
    }
}
