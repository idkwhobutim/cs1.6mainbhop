package gg.melty.cshudcraft.pmove;

/**
 * One client tick of input: the same record drives the client's prediction and the server's simulation.
 * forward/side are -1..1 (side positive = right). View angles are Minecraft's (yaw 0 = south, pitch positive = down);
 * the core interpolates them from prev to cur across the tick's substeps. maxSpeed is the weapon's CS speed in u/s with
 * status effects folded in; jumpMul, gravityMul and levitate carry the Jump Boost, Slow Falling and Levitation effects.
 */
public final class UserCmd {
    public static final int JUMP = 1, DUCK = 2, WALK = 4;
    /** Player option "autobhop": holding jump re-jumps the moment the player lands (bits 8 and 16 are the game side's, see CsMove.Buttons). */
    public static final int AUTOBHOP = 32;

    public final int buttons;
    public final float forward, side;
    public final float yawPrev, yawCur, pitchPrev, pitchCur;
    public final float maxSpeed, jumpMul, gravityMul, levitate;

    public UserCmd(int buttons, float forward, float side, float yawPrev, float yawCur, float pitchPrev, float pitchCur,
                   float maxSpeed, float jumpMul, float gravityMul, float levitate) {
        this.buttons = buttons;
        this.forward = clampAxis(forward);
        this.side = clampAxis(side);
        this.yawPrev = yawPrev;
        this.yawCur = yawCur;
        this.pitchPrev = pitchPrev;
        this.pitchCur = pitchCur;
        this.maxSpeed = maxSpeed;
        this.jumpMul = jumpMul;
        this.gravityMul = gravityMul;
        this.levitate = levitate;
    }

    private static float clampAxis(float v) { return Float.isNaN(v) ? 0 : Math.max(-1f, Math.min(1f, v)); }

    public static double wrapDegrees(double a) {
        a = a % 360.0;
        if (a >= 180.0) a -= 360.0;
        if (a < -180.0) a += 360.0;
        return a;
    }

    /** View yaw (degrees) at the end of substep sub of subs. */
    public double yawAt(int sub, int subs) { return yawPrev + wrapDegrees((double) yawCur - yawPrev) * (sub + 1) / subs; }

    public double pitchAt(int sub, int subs) { return pitchPrev + ((double) pitchCur - pitchPrev) * (sub + 1) / subs; }
}
