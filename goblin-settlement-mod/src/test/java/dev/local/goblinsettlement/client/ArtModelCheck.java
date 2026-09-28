package dev.local.goblinsettlement.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks the committed art crops before the client ever bakes them: the six required groups, the feet
 * on the art ground line, every cube inside the declared texture, and box uv only.
 *
 * The crop is what this module's geometry is regenerated from -- the art workspace is not in version
 * control -- so this is the one place a bad model is caught before it reaches a screen.
 */
public final class ArtModelCheck {
    private static final Set<String> REQUIRED = Set.of(
            "head", "body", "left_arm", "right_arm", "left_leg", "right_leg");

    public static void main(String[] args) throws IOException {
        Path dir = Path.of("tools", "models");
        require(Files.isDirectory(dir), "the crop directory exists: " + dir.toAbsolutePath());
        List<Path> crops = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted()
                    .forEach(crops::add);
        }
        require(!crops.isEmpty(), "at least one cropped model is committed");
        for (Path crop : crops) {
            checkOne(crop);
        }
        System.out.println("ArtModelCheck passed (" + crops.size() + " models)");
    }

    private static void checkOne(Path path) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
        String name = path.getFileName().toString();
        require(root.has("resolution"), name + ": declares a resolution");
        JsonObject resolution = root.getAsJsonObject("resolution");
        int u = resolution.get("width").getAsInt();
        int v = resolution.get("height").getAsInt();

        Set<String> groups = new TreeSet<>();
        for (var group : root.getAsJsonArray("groups")) {
            groups.add(group.getAsJsonObject().get("name").getAsString());
        }
        require(groups.equals(new TreeSet<>(REQUIRED)),
                name + ": groups are " + groups + ", expected " + new TreeSet<>(REQUIRED));

        JsonArray elements = root.getAsJsonArray("elements");
        require(elements.size() > 0, name + ": has elements");
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (var entry : elements) {
            JsonObject element = entry.getAsJsonObject();
            require(element.get("box_uv").getAsBoolean(), name + ": every element uses box uv");
            require(!element.has("faces"), name + ": no element carries per-face uv");
            var from = element.getAsJsonArray("from");
            var to = element.getAsJsonArray("to");
            for (int axis = 0; axis < 3; axis++) {
                require(from.get(axis).getAsInt() <= to.get(axis).getAsInt(),
                        name + ": a cube has an inverted extent on axis " + axis);
            }
            // A box-uv cube unwraps into a 2*(w+d) by (h+d) rectangle anchored at its uv offset;
            // that rectangle -- not the cube's place in space -- is what has to fit in the texture.
            int width = to.get(0).getAsInt() - from.get(0).getAsInt();
            int height = to.get(1).getAsInt() - from.get(1).getAsInt();
            int depth = to.get(2).getAsInt() - from.get(2).getAsInt();
            var uv = element.getAsJsonArray("uv_offset");
            int u0 = uv.get(0).getAsInt();
            int v0 = uv.get(1).getAsInt();
            require(u0 >= 0 && u0 + 2 * (width + depth) <= u,
                    name + ": a cube's uv runs off the texture in u (offset " + u0
                            + ", needs " + 2 * (width + depth) + ")");
            require(v0 >= 0 && v0 + (height + depth) <= v,
                    name + ": a cube's uv runs off the texture in v (offset " + v0
                            + ", needs " + (height + depth) + ")");
            lowest = Math.min(lowest, from.get(1).getAsInt());
            highest = Math.max(highest, to.get(1).getAsInt());
        }
        require(lowest == 0, name + ": the feet sit on the art ground line (lowest y is " + lowest + ")");
        require(highest <= 64, name + ": the model is not absurdly tall (" + highest + " art units)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("ArtModelCheck failed: " + message);
        }
    }
}
