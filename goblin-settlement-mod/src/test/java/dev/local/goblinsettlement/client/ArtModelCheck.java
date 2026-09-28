package dev.local.goblinsettlement.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.local.goblinsettlement.client.model.GoblinFemaleModel;
import dev.local.goblinsettlement.client.model.GoblinMaleModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.LayerDefinition;

/**
 * Checks the art before the client ever renders it, in two halves that both walk the same crop list.
 *
 * <p>The first half reads the committed crops: the six required groups, the feet on the art ground
 * line, every cube's box-uv footprint inside the declared texture, box uv only, no rotated group, and
 * a texture whose pixels are exactly twice the crop's logical resolution. The crop is what the mod's
 * geometry is regenerated from -- the art workspace is not in version control -- so this is where a
 * bad model is caught before it reaches a screen.
 *
 * <p>The second half bakes the generated mesh each crop maps to and checks the baked parts against
 * that same crop: each of the six names exists, and its {@code PartPose} position is exactly what the
 * crop's group origin implies. That position check is the one assertion the round's top risk -- "the
 * generator is wrong, so both models are wrong" -- actually needs: a generator that scaled about
 * y = 0 instead of the ground line would float every model 0.75 blocks, and the {@code SCALE = 1}
 * cross-check is structurally blind to it (the two rules coincide at {@code SCALE = 1}). Nothing else
 * in the build would notice: a mispositioned mesh compiles and renders somewhere wrong.
 */
public final class ArtModelCheck {
    private static final Set<String> REQUIRED = Set.of(
            "head", "body", "left_arm", "right_arm", "left_leg", "right_leg");
    /** Mirrors generate_models.py: art space -> model space, scaled about the ground line at y = 24. */
    private static final double SCALE = 0.5;
    private static final double GROUND = 24.0;
    private static final float TOLERANCE = 1e-3F;

    /**
     * Crop file name (without {@code .json}) -> the generated model class and its committed texture.
     * The crop format keeps no texture name of its own, so this mapping declares one. A crop with no
     * entry here fails loudly rather than being checked by the crop half alone and silently skipped by
     * the baked half -- which is exactly what a third crop (the coming professions and children) would
     * otherwise do.
     */
    private static final Map<String, Baked> BAKED = Map.of(
            "goblin_male_a", new Baked(GoblinMaleModel::createLayer, "goblin_male"),
            "goblin_female_a", new Baked(GoblinFemaleModel::createLayer, "goblin_female"));

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
            JsonObject root = checkCrop(crop);
            String name = crop.getFileName().toString().replaceFirst("\\.json$", "");
            Baked baked = BAKED.get(name);
            require(baked != null, name + ": no generated model is mapped to this crop -- add it to"
                    + " ArtModelCheck.BAKED so the baked half checks it too");
            checkTexture(name, root, baked.texture());
            checkBaked(name, baked.layer().get(), root);
        }
        System.out.println("ArtModelCheck passed (" + crops.size() + " crops, "
                + crops.size() + " baked models)");
    }

    private static JsonObject checkCrop(Path path) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
        String name = path.getFileName().toString();
        require(root.has("resolution"), name + ": declares a resolution");
        JsonObject resolution = root.getAsJsonObject("resolution");
        int u = resolution.get("width").getAsInt();
        int v = resolution.get("height").getAsInt();

        Set<String> groups = new TreeSet<>();
        for (var group : root.getAsJsonArray("groups")) {
            JsonObject entry = group.getAsJsonObject();
            // The generator refuses a rotated group at crop time and the emitter reads none of a
            // group's rotation, so a crop that carries one would be drawn wrong with no complaint.
            require(!entry.has("rotation"),
                    name + ": group " + entry.get("name").getAsString()
                            + " carries a rotation, which the emitter ignores");
            groups.add(entry.get("name").getAsString());
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
        return root;
    }

    /**
     * The models declare 256 logical uv and ship a 512x512 bitmap. Read the PNG header and require
     * exactly twice the crop's resolution, so the "512 texture against 256 logical uv" pairing cannot
     * be wrong without a running game to show it.
     */
    private static void checkTexture(String name, JsonObject root, String texture) throws IOException {
        JsonObject resolution = root.getAsJsonObject("resolution");
        int u = resolution.get("width").getAsInt();
        int v = resolution.get("height").getAsInt();
        Path path = Path.of("src", "main", "resources", "assets", "goblin_settlement",
                "textures", "entity", texture + ".png");
        require(Files.isRegularFile(path), name + ": the texture " + path.toAbsolutePath() + " exists");
        byte[] header = new byte[24];
        try (var in = Files.newInputStream(path)) {
            require(in.readNBytes(header, 0, header.length) == header.length,
                    name + ": the texture " + path + " has a complete png header");
        }
        int width = readIntBigEndian(header, 16);
        int height = readIntBigEndian(header, 20);
        require(width == 2 * u && height == 2 * v,
                name + ": the texture is " + width + "x" + height + ", expected twice the crop's "
                        + u + "x" + v + " logical uv");
    }

    /**
     * The design's second half: build the real mesh and check every required part against the crop it
     * came from. A {@link LayerDefinition} does not expose the {@link net.minecraft.client.model.geom
     * .builders.MeshDefinition} it was made from, so the names cannot be read back before baking;
     * after {@code bakeRoot()} they are the children map's keys, which is exactly what {@code
     * GoblinBodyModel} looks up when it is constructed. The negative control is there so a {@code
     * hasChild} that answered true for everything could not make the loop vacuous. {@code
     * getInitialPose()} holds the pose the mesh was baked with and cannot be perturbed by a later
     * {@code setupAnim}.
     */
    private static void checkBaked(String label, LayerDefinition layer, JsonObject crop) {
        ModelPart root = layer.bakeRoot();
        require(root != null, label + ": bakeRoot() returns a root");
        require(!root.hasChild("no_such_part"), label + ": hasChild discriminates (negative control)");

        Map<String, JsonArray> origins = new HashMap<>();
        for (var group : crop.getAsJsonArray("groups")) {
            JsonObject entry = group.getAsJsonObject();
            origins.put(entry.get("name").getAsString(), entry.getAsJsonArray("origin"));
        }
        for (String name : new TreeSet<>(REQUIRED)) {
            require(root.hasChild(name), label + ": the baked root has a part named " + name);
            // The crop's group origin is the art-space pivot; the generator maps it to model space as
            // y = GROUND - SCALE*origin.y with x = -SCALE*origin.x and z = SCALE*origin.z. All six
            // top-level groups hang directly off the mesh root, so their PartPose offsets are absolute.
            JsonArray origin = origins.get(name);
            float x = (float) (-SCALE * origin.get(0).getAsInt());
            float y = (float) (GROUND - SCALE * origin.get(1).getAsInt());
            float z = (float) (SCALE * origin.get(2).getAsInt());
            PartPose pose = root.getChild(name).getInitialPose();
            require(Math.abs(pose.x() - x) <= TOLERANCE && Math.abs(pose.y() - y) <= TOLERANCE
                            && Math.abs(pose.z() - z) <= TOLERANCE,
                    label + ": part " + name + " is at (" + pose.x() + ", " + pose.y() + ", "
                            + pose.z() + "), the crop's pivot implies (" + x + ", " + y + ", " + z + ")");
        }
        require(root.getAllParts().stream().anyMatch(part -> !part.isEmpty()),
                label + ": the baked mesh carries cube geometry");
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("ArtModelCheck failed: " + message);
        }
    }

    private record Baked(Supplier<LayerDefinition> layer, String texture) {
    }
}
