package gg.melty.cshudcraft.pmove;

import java.util.Arrays;

/** Everything pmove remembers about a player between steps. Positions in blocks, velocities in blocks per second. */
public final class PlayerMoveState {
    public double x, y, z, vx, vy, vz;
    public boolean onGround, ducking, onLadder, inLava, jumpArmed;
    /** 0..1: how far the view has dropped toward the ducked eye height (the hull flips separately). */
    public double duckProgress;
    public double duckTimer, waterJumpTime, ladderLockout, jumpSlow, surfaceFriction = 1;
    public int waterLevel, prevButtons;
    public double yaw, pitch;
    /** Set for the step in which the player touched ground after being airborne; the game side reads and clears it. */
    public boolean landed;
    /** Downward speed (blocks/s, positive) at the last landing, and at the last floor hit inside the current step. */
    public double landSpeed, impactVy;
    /** Corner points of the path walked since the game side last cleared it: x, y, z triplets. */
    public double[] path = new double[96];
    public int pathLen;

    public void addPoint() {
        if (pathLen + 3 > path.length) path = Arrays.copyOf(path, path.length * 2);
        path[pathLen++] = x;
        path[pathLen++] = y;
        path[pathLen++] = z;
    }

    public void clearPath() { pathLen = 0; }


    /** Number of values in pack(). */
    public static final int PACK = 22;

    /** Every simulated field as doubles, in a fixed order: what a server correction carries. */
    public double[] pack() {
        return new double[] {x, y, z, vx, vy, vz, onGround ? 1 : 0, ducking ? 1 : 0, onLadder ? 1 : 0, inLava ? 1 : 0,
                duckProgress, duckTimer, waterJumpTime, ladderLockout, jumpSlow, surfaceFriction, waterLevel, prevButtons,
                landSpeed, yaw, pitch, landed ? 1 : 0};
    }

    public void unpack(double[] a) {
        x = a[0]; y = a[1]; z = a[2]; vx = a[3]; vy = a[4]; vz = a[5];
        onGround = a[6] != 0; ducking = a[7] != 0; onLadder = a[8] != 0; inLava = a[9] != 0;
        duckProgress = a[10]; duckTimer = a[11]; waterJumpTime = a[12]; ladderLockout = a[13]; jumpSlow = a[14];
        surfaceFriction = a[15]; waterLevel = (int) a[16]; prevButtons = (int) a[17];
        landSpeed = a[18]; yaw = a[19]; pitch = a[20]; landed = a[21] != 0;
        jumpArmed = false;
        impactVy = 0;
        clearPath();
    }

    public double speed2D() { return Math.sqrt(vx * vx + vz * vz); }

    public void copyFrom(PlayerMoveState o) {
        x = o.x; y = o.y; z = o.z; vx = o.vx; vy = o.vy; vz = o.vz;
        onGround = o.onGround; ducking = o.ducking; onLadder = o.onLadder; inLava = o.inLava; jumpArmed = o.jumpArmed;
        duckProgress = o.duckProgress; duckTimer = o.duckTimer; waterJumpTime = o.waterJumpTime;
        ladderLockout = o.ladderLockout; jumpSlow = o.jumpSlow; surfaceFriction = o.surfaceFriction;
        waterLevel = o.waterLevel; prevButtons = o.prevButtons; yaw = o.yaw; pitch = o.pitch;
        landed = o.landed; landSpeed = o.landSpeed; impactVy = o.impactVy;
    }

    /** Exact equality of every simulated field (path excluded): the determinism check. */
    public boolean sameAs(PlayerMoveState o) {
        return same(x, o.x) && same(y, o.y) && same(z, o.z) && same(vx, o.vx) && same(vy, o.vy) && same(vz, o.vz)
                && onGround == o.onGround && ducking == o.ducking && onLadder == o.onLadder && inLava == o.inLava
                && same(duckProgress, o.duckProgress) && same(duckTimer, o.duckTimer)
                && same(waterJumpTime, o.waterJumpTime) && same(ladderLockout, o.ladderLockout)
                && same(jumpSlow, o.jumpSlow) && waterLevel == o.waterLevel && prevButtons == o.prevButtons
                && same(landSpeed, o.landSpeed) && same(surfaceFriction, o.surfaceFriction);
    }

    private static boolean same(double a, double b) { return Double.compare(a, b) == 0; }
}
