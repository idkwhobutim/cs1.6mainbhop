package gg.melty.cshudcraft.csmove;

import gg.melty.cshudcraft.CsHudCraft;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/** The three payloads of CS movement. */
public final class CsMoveNet {
    private CsMoveNet() {}

    /**
     * Client -> server, once per client tick while CS movement is on: the input of that tick and where the client's
     * prediction ended up. seq counts up per client; buttons = CsMove.Buttons bits; v = forward, side, yawPrev, yaw,
     * pitchPrev, pitch, x, y, z (the last three are only compared against the server's own result, never adopted).
     */
    public record Cmd(int seq, int buttons, List<Double> v) implements CustomPayload {
        public static final Id<Cmd> ID = new Id<>(Identifier.of(CsHudCraft.MOD_ID, "move_cmd"));
        public static final PacketCodec<RegistryByteBuf, Cmd> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Cmd::seq,
                PacketCodecs.VAR_INT, Cmd::buttons,
                PacketCodecs.DOUBLE.collect(PacketCodecs.toList()), Cmd::v,
                Cmd::new);

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: the server's state after input ack (PlayerMoveState.pack()). The client sets it and replays the newer inputs. */
    public record Correct(int ack, List<Double> state) implements CustomPayload {
        public static final Id<Correct> ID = new Id<>(Identifier.of(CsHudCraft.MOD_ID, "move_correct"));
        public static final PacketCodec<RegistryByteBuf, Correct> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Correct::ack,
                PacketCodecs.DOUBLE.collect(PacketCodecs.toList()), Correct::state,
                Correct::new);

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client on join, only when this server runs CS movement (sheets/movement.json cs_movement = 1). Without it the client stays vanilla. */
    public record Mode() implements CustomPayload {
        public static final Id<Mode> ID = new Id<>(Identifier.of(CsHudCraft.MOD_ID, "move_mode"));
        public static final PacketCodec<RegistryByteBuf, Mode> CODEC = PacketCodec.unit(new Mode());

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Cmd.ID, Cmd.CODEC);
        PayloadTypeRegistry.playS2C().register(Correct.ID, Correct.CODEC);
        PayloadTypeRegistry.playS2C().register(Mode.ID, Mode.CODEC);
    }
}
