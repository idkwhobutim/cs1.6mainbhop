package gg.melty.cshudcraft.csmove;

import com.mojang.brigadier.Command;
import gg.melty.cshudcraft.ClientCs;
import gg.melty.cshudcraft.CsHudCraft;
import gg.melty.cshudcraft.CsNet;
import gg.melty.cshudcraft.pmove.MovementConfig;
import gg.melty.cshudcraft.pmove.PlayerMoveState;
import gg.melty.cshudcraft.pmove.UserCmd;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import gg.melty.cshudcraft.mixin.KeyBindingAccessor;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.StickyKeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Client side of CS movement: samples the keys, predicts with the same core the server runs, commits the result to the
 * local player, sends the input, and applies the server's corrections (set state, replay the inputs it has not seen).
 * Controls: WASD, Space (jump), the vanilla Sneak key (Shift) = duck, the vanilla Sprint key (Ctrl) = walk. They stay
 * rebindable in Controls; in CS movement those two keys no longer sprint or sneak.
 */
public final class CsMoveClient {
    private CsMoveClient() {}

    private record Rec(int seq, UserCmd cmd) {}

    private static MoveCtl ctl;
    private static ClientPlayerEntity owner;
    private static final ArrayDeque<Rec> HISTORY = new ArrayDeque<>();
    private static int seq;
    private static boolean haveLast;
    private static float lastYaw, lastPitch;
    private static boolean serverMove;
    private static boolean driving;
    private static boolean debug;
    private static boolean sentLeave = true;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(CsMoveNet.Mode.ID, (payload, ctx) -> serverMove = true);
        ClientPlayNetworking.registerGlobalReceiver(CsMoveNet.Correct.ID, (payload, ctx) -> onCorrect(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            serverMove = false;
            reset();
        });
        CsMove.clientTravel = e -> e instanceof ClientPlayerEntity p && travel(p);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("csmove")
                        .then(ClientCommandManager.literal("debug").executes(c -> {
                            debug = !debug;
                            c.getSource().sendFeedback(Text.literal("csmove debug " + (debug ? "on" : "off")));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("on").executes(c -> {
                            CsHudCraft.CONFIG.setCsMovement(true);
                            c.getSource().sendFeedback(Text.literal("CS movement on"));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("off").executes(c -> {
                            CsHudCraft.CONFIG.setCsMovement(false);
                            c.getSource().sendFeedback(Text.literal("CS movement off (vanilla movement)"));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("autobhop").executes(c -> {
                            boolean on = !CsHudCraft.CONFIG.autobhop();
                            CsHudCraft.CONFIG.setAutobhop(on);
                            String note = CsMove.sheet().value("autobhop_allowed") == 0 ? " (but sheets/movement.json autobhop_allowed = 0)" : "";
                            c.getSource().sendFeedback(Text.literal("autobhop " + (on ? "on: hold Space to hop on landing" : "off") + note));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("status").executes(c -> {
                            String why = !CsMove.master() ? "sheet cs_movement = 0"
                                    : !serverMove ? "this server does not run CS movement"
                                    : !CsHudCraft.CONFIG.csMovement() ? "switched off (/csmove off)"
                                    : !CsHudCraft.enabled() ? "Minecraft interface is on (F6)"
                                    : driving ? "active" : "idle (riding, flying, gliding, dead or chunk not loaded)";
                            c.getSource().sendFeedback(Text.literal("CS movement: " + why));
                            return Command.SINGLE_SUCCESS;
                        }))));
    }

    /** CS movement owns the local player right now: vanilla Shift / Ctrl must not sprint or sneak. */
    public static boolean driving() { return driving; }

    /** The local player's pmove state while CS movement drives them, else null (spread, HUD and the debug overlay read it). */
    public static PlayerMoveState state() { return driving && ctl != null ? ctl.state : null; }

    private static void reset() {
        CsMove.setLocal(null, null);
        ctl = null;
        owner = null;
        driving = false;
        HISTORY.clear();
        haveLast = false;
        sentLeave = true;
    }

    private static boolean wants(MinecraftClient mc, ClientPlayerEntity p) {
        return serverMove && CsMove.master() && CsHudCraft.CONFIG.csMovement() && CsHudCraft.enabled()
                && mc.world != null && mc.player == p && !CsMove.exempt(p) && mc.world.isPosLoaded(p.getBlockPos())
                && ClientPlayNetworking.canSend(CsMoveNet.Cmd.ID);
    }

    private static void leave(ClientPlayerEntity p) {
        driving = false;
        if (ctl == null) return;
        MoveCtl old = ctl;
        CsMove.setLocal(null, null);
        ctl = null;
        HISTORY.clear();
        haveLast = false;
        old.release(p);
        GameOptions o = MinecraftClient.getInstance().options;
        if (o.sneakKey instanceof StickyKeyBinding s) s.untoggle();
        if (o.sprintKey instanceof StickyKeyBinding s) s.untoggle();
        if (!sentLeave && ClientPlayNetworking.canSend(CsMoveNet.Cmd.ID)) {
            sentLeave = true;
            ClientPlayNetworking.send(new CsMoveNet.Cmd(++seq, CsMove.Buttons.LEAVE, List.of(0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d)));
        }
    }

    /** Called from LivingEntity.travel for the local player. True = pmove handled this tick, vanilla travel is skipped. */
    public static boolean travel(ClientPlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!wants(mc, p)) {
            if (owner == p || ctl != null) leave(p);
            return false;
        }
        if (ctl == null || owner != p) {
            reset();
            ctl = new MoveCtl();
            owner = p;
            CsMove.setLocal(p, ctl);
        }
        MovementConfig cfg = CsMove.cfg();
        if (!ctl.started()) {
            ctl.adopt(p);
        } else if (ctl.teleported(p)) { // the server (or a command) put the player somewhere else
            ctl.adopt(p);
            HISTORY.clear();
        }
        driving = true;
        sentLeave = false;

        GameOptions o = mc.options;
        int buttons = 0;
        if (o.jumpKey.isPressed()) buttons |= UserCmd.JUMP;
        if (held(mc, o.sneakKey)) buttons |= UserCmd.DUCK;
        if (held(mc, o.sprintKey)) buttons |= UserCmd.WALK;
        if (CsHudCraft.CONFIG.autobhop() && CsMove.sheet().value("autobhop_allowed") != 0) buttons |= UserCmd.AUTOBHOP;
        if (ClientCs.scoped()) buttons |= CsMove.Buttons.SCOPED;
        float forward = (o.forwardKey.isPressed() ? 1 : 0) - (o.backKey.isPressed() ? 1 : 0);
        float side = (o.rightKey.isPressed() ? 1 : 0) - (o.leftKey.isPressed() ? 1 : 0);
        float yaw = p.getYaw(), pitch = p.getPitch();
        if (!haveLast) { lastYaw = yaw; lastPitch = pitch; haveLast = true; }
        UserCmd cmd = CsMove.makeCmd(p, p.getAttached(CsNet.STATE), buttons, forward, side, lastYaw, yaw, lastPitch, pitch);
        lastYaw = yaw;
        lastPitch = pitch;

        ctl.csFall = CsMove.sheet().csFallDamage();
        ctl.tick(p, cmd);

        int n = ++seq;
        HISTORY.addLast(new Rec(n, cmd));
        while (HISTORY.size() > (int) cfg.cmdQueueLimit * 4) HISTORY.pollFirst();
        PlayerMoveState s = ctl.state;
        ClientPlayNetworking.send(new CsMoveNet.Cmd(n, buttons,
                List.of((double) forward, (double) side, (double) cmd.yawPrev, (double) cmd.yawCur, (double) cmd.pitchPrev,
                        (double) cmd.pitchCur, s.x, s.y, s.z)));
        return true;
    }

    /**
     * Is the physical key held? Duck and walk are hold keys: Minecraft's own "Toggle Sneak / Sprint" options would
     * make isPressed() latch on and off, so the key itself is read (keyboard or mouse button). Falls back to
     * isPressed() if the binding's key can't be read.
     */
    private static boolean held(MinecraftClient mc, KeyBinding k) {
        if (!(k instanceof KeyBindingAccessor a) || a.cshudcraft$getBoundKey() == null) return k.isPressed();
        if (k.isUnbound() || mc.currentScreen != null || !mc.isWindowFocused()) return false;
        InputUtil.Key key = a.cshudcraft$getBoundKey();
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), key.getCode()) == GLFW.GLFW_PRESS;
        }
        return InputUtil.isKeyPressed(mc.getWindow(), key.getCode());
    }

    private static void onCorrect(CsMoveNet.Correct c) {
        if (ctl == null || owner == null || !ctl.started() || c.state().size() < PlayerMoveState.PACK) return;
        while (!HISTORY.isEmpty() && HISTORY.peekFirst().seq() <= c.ack()) HISTORY.pollFirst();
        double[] a = new double[PlayerMoveState.PACK];
        for (int i = 0; i < a.length; i++) a[i] = c.state().get(i);
        ctl.state.unpack(a);
        for (Rec r : new ArrayList<>(HISTORY)) ctl.simulate(owner, r.cmd());
    }

    // ------------------------------------------------------------------ /csmove debug

    public static void renderDebug(DrawContext ctx) {
        if (!debug) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerMoveState s = state();
        MovementConfig c = CsMove.cfg();
        int x = (int) c.value("debug_hud_x"), y = (int) c.value("debug_hud_y");
        List<String> lines = new ArrayList<>();
        if (s == null) {
            lines.add("csmove: vanilla movement");
        } else {
            lines.add(String.format("speed %.1f u/s", c.toUnits(s.speed2D())));
            lines.add("onGround " + s.onGround);
            lines.add("ducking " + s.ducking + String.format(" (view %.2f)", s.duckProgress));
            lines.add("waterLevel " + s.waterLevel + (s.onLadder ? " ladder" : ""));
            lines.add(String.format("vz %.1f u/s", c.toUnits(s.vy)));
        }
        for (String l : lines) {
            ctx.drawTextWithShadow(mc.textRenderer, l, x, y, 0xFFFFFFFF);
            y += mc.textRenderer.fontHeight + 1;
        }
    }
}
