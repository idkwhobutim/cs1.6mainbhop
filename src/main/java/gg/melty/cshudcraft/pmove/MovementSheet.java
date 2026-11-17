package gg.melty.cshudcraft.pmove;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads sheets/movement.json (and the optional speedScoped column of weapons.json). No Minecraft types: unit tests use it too. */
public final class MovementSheet {
    private static final String BASE = "/cshudcraft/sheets/";

    public final MovementConfig config;
    public final Map<String, String> text = new LinkedHashMap<>();
    public final Map<String, Double> blockSpeed = new LinkedHashMap<>();
    public final Map<String, Double> scopedSpeed = new LinkedHashMap<>();

    private MovementSheet(MovementConfig config) { this.config = config; }

    private static MovementSheet cached;

    /** Loaded once from the jar (sheets/ is packed into cshudcraft/sheets). */
    public static synchronized MovementSheet get() {
        if (cached == null) cached = load(read("movement"), read("weapons"));
        return cached;
    }

    public static MovementSheet load(JsonObject movement, JsonObject weapons) {
        JsonObject settings = movement.getAsJsonObject("settings");
        double scale = 0;
        for (JsonElement e : settings.getAsJsonArray("rows")) {
            JsonObject r = e.getAsJsonObject();
            if (r.get("key").getAsString().equals("unit_scale")) scale = r.get("value").getAsDouble();
        }
        if (scale <= 0) throw new IllegalStateException("movement.json has no unit_scale");
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, String> text = new LinkedHashMap<>();
        for (JsonElement e : settings.getAsJsonArray("rows")) {
            JsonObject r = e.getAsJsonObject();
            String key = r.get("key").getAsString(), unit = r.get("unit").getAsString();
            if (unit.equals("text")) { text.put(key, r.get("value").getAsString()); continue; }
            double val = r.get("value").getAsDouble();
            if (unit.equals("u") || unit.equals("u/s") || unit.equals("u/s^2")) val *= scale;
            values.put(key, val);
        }
        if (Math.abs(values.get("substep_ms") * values.get("substeps_per_tick") - 50) > 1e-9) {
            throw new IllegalStateException("movement.json: substep_ms x substeps_per_tick must be 50 ms (one Minecraft tick)");
        }
        MovementSheet s = new MovementSheet(MovementConfig.from(values));
        s.text.putAll(text);
        for (JsonElement e : movement.getAsJsonObject("block_speed").getAsJsonArray("rows")) {
            JsonObject r = e.getAsJsonObject();
            s.blockSpeed.put(r.get("block").getAsString(), r.get("scale").getAsDouble());
        }
        if (weapons != null) {
            for (JsonElement e : weapons.getAsJsonArray("rows")) {
                JsonObject r = e.getAsJsonObject();
                if (r.has("speedScoped")) s.scopedSpeed.put(r.get("id").getAsString(), r.get("speedScoped").getAsDouble());
            }
        }
        return s;
    }

    private static JsonObject read(String name) {
        try (InputStream in = MovementSheet.class.getResourceAsStream(BASE + name + ".json")) {
            if (in == null) throw new IllegalStateException("missing sheet " + name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("could not read sheet " + name, e);
        }
    }

    public boolean csFallDamage() { return "cs".equalsIgnoreCase(text.getOrDefault("fall_damage_mode", "vanilla")); }

    public boolean masterOn() { return config.value("cs_movement") != 0; }
}
