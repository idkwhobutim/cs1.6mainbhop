package gg.melty.cshudcraft.pmove;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * sheets/movement.json as typed numbers. Distances are blocks, speeds blocks per second, accelerations blocks per
 * second squared, times seconds: the sheet loader has already applied unit_scale to every u, u/s and u/s^2 value.
 * There are no defaults here: a missing key is an error, the sheet is the only source of numbers.
 */
public final class MovementConfig {
    private final Map<String, Double> v;

    public final double unitScale, dt;
    public final int substeps;
    public final double gravity, friction, stopSpeed, edgeFriction, edgeLook, edgeDrop, accelerate, airAccelerate, airWishCap;
    public final double maxVelocity, maxSpeed, cmdSpeed, stepSize, jumpHeight, jumpVelocity, bhopCapMul;
    public final boolean bhopCap, autohop;
    public final double groundLostVy, groundCheck, contactEps, duckSpeedMul, walkSpeedMul, duckTime;
    public final double hullWidth, halfWidth, standHeight, duckHeight, tuck, eyeStand, eyeDuck;
    public final double ladderSpeed, ladderJumpSpeed, ladderRegrab;
    public final double waterAccel, waterFriction, waterSpeedMul, waterSink, waterUp;
    public final double waterJumpReach, waterJumpLift, waterJumpForward, waterJumpUp, waterJumpTime, lavaScale;
    public final double slipBase, slipIce, iceFriction, minSurfaceFriction;
    public final double jumpSlowdownSec, jumpSlowdownRatio, fallSafe, fallFatal, equipmentSpeed;
    public final double correctionTolerance;
    public final int maxCmdsPerTick, cmdQueueLimit;

    private MovementConfig(Map<String, Double> values) {
        this.v = values;
        unitScale = req("unit_scale");
        dt = req("substep_ms") / 1000.0;
        substeps = (int) Math.round(req("substeps_per_tick"));
        gravity = req("gravity");
        friction = req("friction");
        stopSpeed = req("stopspeed");
        edgeFriction = req("edgefriction");
        edgeLook = req("edge_look");
        edgeDrop = req("edge_drop");
        accelerate = req("accelerate");
        airAccelerate = req("airaccelerate");
        airWishCap = req("air_wishspeed_cap");
        maxVelocity = req("maxvelocity");
        maxSpeed = req("maxspeed");
        cmdSpeed = req("cmd_speed");
        stepSize = req("stepsize");
        jumpHeight = req("jump_height");
        jumpVelocity = Math.sqrt(2 * gravity * jumpHeight);
        bhopCap = req("bhop_cap") != 0;
        bhopCapMul = req("bhop_cap_mul");
        autohop = req("autohop") != 0;
        groundLostVy = req("ground_lost_vz");
        groundCheck = req("ground_check");
        contactEps = req("contact_epsilon");
        duckSpeedMul = req("duck_speed_mul");
        walkSpeedMul = req("walk_speed_mul");
        duckTime = req("duck_time");
        hullWidth = req("hull_width");
        halfWidth = hullWidth / 2;
        standHeight = req("stand_height");
        duckHeight = req("duck_height");
        tuck = (standHeight - duckHeight) / 2;
        eyeStand = req("eye_stand");
        eyeDuck = req("eye_duck");
        ladderSpeed = req("ladder_speed");
        ladderJumpSpeed = req("ladder_jump_speed");
        ladderRegrab = req("ladder_regrab_delay");
        waterAccel = req("water_accel");
        waterFriction = req("water_friction");
        waterSpeedMul = req("water_speed_mul");
        waterSink = req("water_sink");
        waterUp = req("water_up_speed");
        waterJumpReach = req("waterjump_reach");
        waterJumpLift = req("waterjump_lift");
        waterJumpForward = req("waterjump_forward");
        waterJumpUp = req("waterjump_up");
        waterJumpTime = req("waterjump_time");
        lavaScale = req("lava_speed_scale");
        slipBase = req("slip_base");
        slipIce = req("slip_ice");
        iceFriction = req("surface_friction_ice");
        minSurfaceFriction = req("surface_friction_min");
        jumpSlowdownSec = req("jump_slowdown_ms") / 1000.0;
        jumpSlowdownRatio = req("jump_slowdown_ratio");
        fallSafe = req("fall_safe");
        fallFatal = req("fall_fatal");
        equipmentSpeed = req("equipment_speed");
        correctionTolerance = req("correction_tolerance");
        maxCmdsPerTick = (int) Math.round(req("max_cmds_per_tick"));
        cmdQueueLimit = (int) Math.round(req("cmd_queue_limit"));
    }

    /** values are already in blocks / seconds (see the class comment). */
    public static MovementConfig from(Map<String, Double> values) { return new MovementConfig(new LinkedHashMap<>(values)); }

    /** Same config with one value replaced (already scaled); for tests and tuning. */
    public MovementConfig with(String key, double value) {
        Map<String, Double> m = new LinkedHashMap<>(v);
        if (!m.containsKey(key)) throw new IllegalArgumentException("no such key " + key);
        m.put(key, value);
        return new MovementConfig(m);
    }

    public double value(String key) { return req(key); }

    private double req(String key) {
        Double d = v.get(key);
        if (d == null) throw new IllegalStateException("movement.json has no " + key);
        return d;
    }

    /** CS speed in u/s -> blocks per second. */
    public double fromUnits(double units) { return units * unitScale; }

    /** blocks per second -> CS u/s. */
    public double toUnits(double blocks) { return blocks / unitScale; }

    /** Eye height for the current duck progress (smoothstep between standing and ducked). */
    public double eyeHeight(PlayerMoveState s) {
        double t = s.duckProgress, k = t * t * (3 - 2 * t);
        return eyeStand + (eyeDuck - eyeStand) * k;
    }

    public double hullHeight(boolean ducking) { return ducking ? duckHeight : standHeight; }

    /** CS fall damage as a 0..1 fraction of a lethal fall, from the speed in u/s. */
    public double csFallFraction(double fallSpeedUnits) {
        double f = (fallSpeedUnits - fallSafe / unitScale) / ((fallFatal - fallSafe) / unitScale);
        return Math.max(0, Math.min(1, f));
    }
}
