package dev.local.goblinsettlement.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.local.goblinsettlement.client.model.GoblinBodies;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.LayerDefinition;

/**
 * Checks the art before the client ever renders it, in two halves that both walk the same crop list.
 *
 * <p>The first half reads the committed crops: the six required groups, the feet on the art ground
 * line, every cube's box-uv footprint inside the declared texture, box uv only, no rotated group, a
 * texture whose pixels are exactly twice the crop's logical resolution, and a texture whose painted
 * area covers every cube's uv rectangle. The crop is what the mod's geometry is regenerated from -- the
 * art workspace is not in version control -- so this is where a bad model is caught before it reaches a
 * screen.
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

    /** The bodies the client can render, keyed by the crop the check validates them from. */
    private static final Map<String, GoblinBodies.Body> BODIES = GoblinBodies.BODIES.stream()
            .collect(Collectors.toUnmodifiableMap(GoblinBodies.Body::crop, body -> body));

    public static void main(String[] args) throws IOException {
        Path dir = Path.of("tools", "models");
        require(Files.isDirectory(dir), "the crop directory exists: " + dir.toAbsolutePath());
        List<Path> crops = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted()
                    .forEach(crops::add);
        }
        require(!crops.isEmpty(), "at least one cropped model is committed");
        checkBodiesAgreeWithTheirCrops();
        for (Path crop : crops) {
            JsonObject root = checkCrop(crop);
            String name = crop.getFileName().toString().replaceFirst("\\.json$", "");
            GoblinBodies.Body body = BODIES.get(name);
            require(body != null, name + ": no renderable body is mapped to this crop -- add it to"
                    + " GoblinBodies.BODIES so the baked half checks it too");
            checkTexture(name, root, body.texture());
            checkBaked(name, body.layer().get(), root);
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
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (var entry : elements) {
            JsonObject element = entry.getAsJsonObject();
            require(element.get("box_uv").getAsBoolean(), name + ": every element uses box uv");
            var from = element.getAsJsonArray("from");
            var to = element.getAsJsonArray("to");
            // The art is not confined to the unit grid: the farmer's skull runs -3.975 .. 3.975 and its
            // hat crown tops out at 52.5. Reading these as ints would truncate them and fail a correct
            // crop, so they are doubles and the comparisons carry the check's tolerance.
            for (int axis = 0; axis < 3; axis++) {
                require(from.get(axis).getAsDouble() <= to.get(axis).getAsDouble() + TOLERANCE,
                        name + ": a cube has an inverted extent on axis " + axis);
            }
            // A box-uv cube unwraps into a 2*(w+d) by (h+d) rectangle anchored at its uv offset;
            // that rectangle -- not the cube's place in space -- is what has to fit in the texture.
            double width = to.get(0).getAsDouble() - from.get(0).getAsDouble();
            double height = to.get(1).getAsDouble() - from.get(1).getAsDouble();
            double depth = to.get(2).getAsDouble() - from.get(2).getAsDouble();
            var uv = element.getAsJsonArray("uv_offset");
            int u0 = uv.get(0).getAsInt();
            int v0 = uv.get(1).getAsInt();
            require(u0 >= 0 && u0 + 2 * (width + depth) <= u + TOLERANCE,
                    name + ": a cube's uv runs off the texture in u (offset " + u0
                            + ", needs " + 2 * (width + depth) + ")");
            require(v0 >= 0 && v0 + (height + depth) <= v + TOLERANCE,
                    name + ": a cube's uv runs off the texture in v (offset " + v0
                            + ", needs " + (height + depth) + ")");
            lowest = Math.min(lowest, from.get(1).getAsDouble());
            highest = Math.max(highest, to.get(1).getAsDouble());
        }
        require(Math.abs(lowest) <= TOLERANCE,
                name + ": the feet sit on the art ground line (lowest y is " + lowest + ")");
        require(highest <= 64 + TOLERANCE,
                name + ": the model is not absurdly tall (" + highest + " art units)");
        return root;
    }

    /**
     * The models declare 256 logical uv and ship a 512x512 bitmap (the professions declare 512 and ship
     * 1024). Read the PNG header and require exactly twice the crop's resolution, so the "512 texture
     * against 256 logical uv" pairing cannot be wrong without a running game to show it.
     *
     * <p>Then decode the bitmap and require every cube's referenced uv rectangle to land inside the
     * painted (non-transparent) area. The size check alone is blind to a texture of the right size that
     * is shifted -- this round's top risk -- and a shift moves the painted blob out from under some
     * rectangle, which this catches. The rectangle is the same box-uv footprint the crop half checks
     * against the declared resolution, scaled to the bitmap (x2); its far edge is floored so a
     * fractional element extent may overhang the last painted texel by less than one pixel.
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

        BufferedImage image = ImageIO.read(path.toFile());
        require(image != null && image.getWidth() == width && image.getHeight() == height,
                name + ": the texture " + path + " decodes to a " + width + "x" + height + " image");
        int[] painted = paintedBounds(image);
        require(painted != null, name + ": the texture " + path + " paints at least one pixel");
        for (var entry : root.getAsJsonArray("elements")) {
            JsonObject element = entry.getAsJsonObject();
            var from = element.getAsJsonArray("from");
            var to = element.getAsJsonArray("to");
            double cubeWidth = to.get(0).getAsDouble() - from.get(0).getAsDouble();
            double cubeHeight = to.get(1).getAsDouble() - from.get(1).getAsDouble();
            double cubeDepth = to.get(2).getAsDouble() - from.get(2).getAsDouble();
            var uv = element.getAsJsonArray("uv_offset");
            int u0 = uv.get(0).getAsInt();
            int v0 = uv.get(1).getAsInt();
            // Logical uv -> bitmap pixels is exactly x2 (the resolution check above), and a rectangle's
            // bound is floored to the last whole texel it reaches.
            int left = (int) Math.floor(2.0 * u0);
            int top = (int) Math.floor(2.0 * v0);
            int right = (int) Math.floor(2.0 * (u0 + 2 * (cubeWidth + cubeDepth)));
            int bottom = (int) Math.floor(2.0 * (v0 + (cubeHeight + cubeDepth)));
            require(left >= painted[0] && top >= painted[1]
                            && right <= painted[2] + 1 && bottom <= painted[3] + 1,
                    name + ": the uv rectangle of element " + element.get("name").getAsString()
                            + " (" + left + ", " + top + ")..(" + right + ", " + bottom
                            + ") leaves the painted area ("
                            + painted[0] + ", " + painted[1] + ")..(" + (painted[2] + 1) + ", "
                            + (painted[3] + 1) + ")");
        }
    }

    /**
     * The inclusive pixel bounds {@code (minX, minY, maxX, maxY)} of the image's non-transparent
     * pixels, or null if every pixel is transparent. A uv rectangle lies in the painted area when its
     * bounds fall inside {@code [minX, maxX + 1) x [minY, maxY + 1)}.
     */
    private static int[] paintedBounds(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        return maxX < 0 ? null : new int[] {minX, minY, maxX, maxY};
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

    /**
     * The table itself must be sane before anything reads it: no two rows may serve the same
     * (profession, sex), no row may claim a crop that is not committed, and a row's sex must agree with
     * its own crop's name (every crop is called {@code goblin_..._male} or {@code goblin_..._female}, so
     * a copy-paste slip that flips the flag but not the name is caught here rather than in game). A
     * duplicate row would silently shadow another one, and the reader that loses would never notice.
     */
    private static void checkBodiesAgreeWithTheirCrops() throws IOException {
        Set<String> served = new HashSet<>();
        for (GoblinBodies.Body body : GoblinBodies.BODIES) {
            String key = body.profession().map(Enum::name).orElse("BASE") + "/" + body.female();
            require(served.add(key), "two bodies claim " + key);
            require(body.female() == body.crop().contains("female"),
                    "the body " + body.crop() + " disagrees with its own name about sex");
            Path crop = Path.of("tools", "models", body.crop() + ".json");
            require(Files.isRegularFile(crop),
                    "the body " + body.crop() + " has a committed crop at " + crop);
        }
    }
}
