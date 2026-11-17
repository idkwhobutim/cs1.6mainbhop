package gg.melty.cshudcraft.csmove;

import gg.melty.cshudcraft.CsNet;
import gg.melty.cshudcraft.CsState;
import gg.melty.cshudcraft.pmove.MovementConfig;
import gg.melty.cshudcraft.pmove.PlayerMoveState;
import gg.melty.cshudcraft.pmove.UserCmd;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of CS movement. Scheme: the client predicts and sends its input every tick; the server runs the very same
 * deterministic core on that input (so legit 600+ u/s bhop never trips vanilla's "moved too quickly", whose position
 * check is skipped for these players) and is the authority: it never adopts the client's position. If its own result is
 * further than correction_tolerance from the position the client claims, or something outside pmove changed the player
 * (knockback, explosion, teleport) it sends its full state and the client replays the inputs the server has not seen.
 * Trade-off: a cheating client can choose any input (also unlimited view turns) but not speed, position or ticks; there
 * is no lag compensation or rewind beyond "inputs are simulated in order, at most max_cmds_per_tick per server tick".
 */
public final class CsMoveServer {
    private CsMoveServer() {}

    private static final class Slot {
        final MoveCtl ctl = new MoveCtl();
        final ArrayDeque<CsMoveNet.Cmd> queue = new ArrayDeque<>();
        int ack = -1;
        int lastCmdTick;
        int lastCorrectionTick = Integer.MIN_VALUE / 2;
        boolean correct;
        /** The next correction skips the cooldown (the server itself changed the player). */
        boolean force;
    }

    private static final Map<UUID, Slot> SLOTS = new ConcurrentHashMap<>();

    /** The controller driving this server-side player, or null (vanilla). */
    public static MoveCtl ctlOf(Entity e) {
        if (!(e instanceof ServerPlayerEntity)) return null;
        Slot s = SLOTS.get(e.getUuid());
        return s != null && s.ctl.started() ? s.ctl : null;
    }

    /** True while this player's position comes from CS movement (the vanilla move packet handler stands back). */
    public static boolean controls(ServerPlayerEntity p) { return ctlOf(p) != null; }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(CsMoveNet.Cmd.ID, (payload, ctx) -> onCmd(ctx.player(), payload));
        ServerTickEvents.START_SERVER_TICK.register(CsMoveServer::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (CsMove.master()) sender.sendPacket(new CsMoveNet.Mode());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SLOTS.remove(handler.player.getUuid()));
    }

    private static void onCmd(ServerPlayerEntity p, CsMoveNet.Cmd c) {
        if (!CsMove.master()) return;
        if ((c.buttons() & CsMove.Buttons.LEAVE) != 0) {
            Slot s = SLOTS.get(p.getUuid());
            if (s != null) handBack(p, s);
            return;
        }
        CsState cs = p.getAttached(CsNet.STATE);
        if (cs != null && !cs.csMode) return; // the player switched CS mode off
        if (CsMove.exempt(p) || c.v().size() < 9) return;
        MovementConfig cfg = CsMove.cfg();
        Slot s = SLOTS.computeIfAbsent(p.getUuid(), k -> new Slot());
        s.lastCmdTick = p.getEntityWorld().getServer().getTicks();
        if (s.queue.size() >= (int) cfg.cmdQueueLimit) {
            // hopelessly behind (a stall on the server): skip ahead and tell the client where the server is
            s.queue.clear();
            s.ack = c.seq();
            s.correct = true;
            s.force = true;
            return;
        }
        s.queue.add(c);
    }

    private static void tick(MinecraftServer server) {
        if (SLOTS.isEmpty()) return;
        MovementConfig cfg = CsMove.cfg();
        int now = server.getTicks();
        boolean master = CsMove.master();
        List<UUID> gone = new ArrayList<>();
        for (Map.Entry<UUID, Slot> e : SLOTS.entrySet()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            Slot s = e.getValue();
            if (p == null || p.isRemoved()) { gone.add(e.getKey()); continue; }
            CsState cs = p.getAttached(CsNet.STATE);
            boolean csOff = cs != null && !cs.csMode; // F6 off: the server's own record, in case the client's LEAVE never arrives
            if (!master || csOff || CsMove.exempt(p) || now - s.lastCmdTick > (int) cfg.value("cmd_timeout_ticks")) {
                handBack(p, s);
                continue;
            }
            run(p, s, cfg, now);
        }
        for (UUID id : gone) SLOTS.remove(id);
    }

    private static void run(ServerPlayerEntity p, Slot s, MovementConfig cfg, int now) {
        MoveCtl ctl = s.ctl;
        PlayerMoveState st = ctl.state;
        if (!ctl.started()) {
            ctl.adopt(p);
            s.correct = true;
            s.force = true;
        } else if (ctl.teleported(p)) {
            ctl.adopt(p); // /tp, portal, respawn point: the new place and velocity are the truth
            s.correct = true;
            s.force = true;
        } else {
            double[] imp = ctl.externalImpulse(p);
            if (imp != null) { // knockback, explosion, C4 shockwave, bullet / HE push: kept, not overwritten
                st.vx += imp[0];
                st.vy += imp[1];
                st.vz += imp[2];
                s.correct = true;
                s.force = true;
            }
        }
        ctl.csFall = CsMove.sheet().csFallDamage();
        CsState cs = p.getAttached(CsNet.STATE);
        double tol = cfg.correctionTolerance;
        for (int n = 0; n < cfg.maxCmdsPerTick && !s.queue.isEmpty(); n++) {
            CsMoveNet.Cmd c = s.queue.poll();
            List<Double> v = c.v();
            UserCmd u = CsMove.makeCmd(p, cs, c.buttons(), v.get(0).floatValue(), v.get(1).floatValue(),
                    v.get(2).floatValue(), v.get(3).floatValue(), v.get(4).floatValue(), v.get(5).floatValue());
            ctl.tick(p, u);
            s.ack = c.seq();
            fallDamage(p, ctl, cfg);
            double dx = st.x - v.get(6), dy = st.y - v.get(7), dz = st.z - v.get(8);
            if (dx * dx + dy * dy + dz * dz > tol * tol) s.correct = true;
        }
        int mode = st.ducking ? 2 : 1;
        if (p.getAttachedOrElse(CsNet.MOVE, 0) != mode) p.setAttached(CsNet.MOVE, mode);
        p.getEntityWorld().getChunkManager().updatePosition(p);
        if (s.correct && (s.force || now - s.lastCorrectionTick >= (int) cfg.value("correction_cooldown"))) {
            s.correct = false;
            s.force = false;
            s.lastCorrectionTick = now;
            List<Double> pack = new ArrayList<>(PlayerMoveState.PACK);
            for (double d : st.pack()) pack.add(d);
            ServerPlayNetworking.send(p, new CsMoveNet.Correct(s.ack, pack));
        }
    }

    private static void fallDamage(ServerPlayerEntity p, MoveCtl ctl, MovementConfig cfg) {
        if (!ctl.csFall || !ctl.landedThisTick || p.isCreative()) return;
        double frac = cfg.csFallFraction(cfg.toUnits(ctl.landSpeedBps));
        float amount = (float) (frac * cfg.value("fall_fatal_damage"));
        if (amount > 0) p.damage(p.getEntityWorld(), p.getDamageSources().fall(), amount);
    }

    /** Gives the player back to Minecraft: vanilla move validation resumes from where pmove left them. */
    private static void handBack(ServerPlayerEntity p, Slot s) {
        SLOTS.remove(p.getUuid());
        s.ctl.release(p);
        p.setAttached(CsNet.MOVE, 0);
        if (!p.hasVehicle() && p.isAlive() && p.networkHandler != null) {
            // vanilla's "moved too quickly" check remembers the last position it accepted: tell it where we are now
            p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
        }
    }
}
