package gg.melty.cshudcraft.pmove;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class TestSupport {
    private TestSupport() {}

    static MovementSheet sheet() {
        try {
            Path dir = Paths.get("sheets");
            JsonObject mv = JsonParser.parseString(Files.readString(dir.resolve("movement.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject wp = JsonParser.parseString(Files.readString(dir.resolve("weapons.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            return MovementSheet.load(mv, wp);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static MovementConfig cfg() { return sheet().config; }

    /** u/s -> blocks/s with this config. */
    static double bps(MovementConfig c, double units) { return units * c.unitScale; }

    static UserCmd cmd(int buttons, float fwd, float side, float yaw, float maxSpeedUnits) {
        return new UserCmd(buttons, fwd, side, yaw, yaw, 0f, 0f, maxSpeedUnits, 1f, 1f, 0f);
    }

    static UserCmd idle(float maxSpeedUnits) { return cmd(0, 0, 0, 0f, maxSpeedUnits); }
}
