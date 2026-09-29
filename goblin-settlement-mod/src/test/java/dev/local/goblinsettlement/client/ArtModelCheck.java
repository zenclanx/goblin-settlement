package dev.local.goblinsettlement.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.local.goblinsettlement.client.model.GoblinBodies;
import dev.local.goblinsettlement.client.model.GoblinBodyModel;
import dev.local.goblinsettlement.client.model.GolemBodies;
import dev.local.goblinsettlement.defense.GolemTier;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.server.Bootstrap;

/**
 * Checks the art before the client ever renders it, in two halves that both walk the same crop lists:
 * one list per table, the seventeen goblin bodies under {@code tools/models/} and the five custom
 * golems under {@code tools/models_golem/}, each crop held against the table that renders it. The two
 * lists are separate directories because a crop directory is read as "every crop here is a row of one
 * table", and a golem is not a goblin body; everything the two passes assert is the same.
 *
 * <p>The first half reads the committed crops: the six required groups, the feet on the art ground
 * line, every cube's box-uv footprint inside the declared texture, box uv only, no rotated group, a
 * texture whose pixels are exactly twice the crop's logical resolution, and a texture whose painted
 * area covers every cube's uv rectangle. A golem ships one more file -- a transparent mask of just its
 * glowing core -- and the same half requires that mask committed, the same size, actually lit, and lit
 * only where some cube samples. The crop is what the mod's geometry is regenerated from -- the art
 * workspace is not in version control -- so this is where a bad model is caught before it reaches a
 * screen.
 *
 * <p>The second half bakes the generated mesh each crop maps to and checks the baked parts against
 * that same crop: each of the six names exists, and its {@code PartPose} position is exactly what the
 * crop's group origin implies. That position check is what guards the generator's art-space-to-model-
 * space mapping -- a generator that mirrored an axis or dropped the ground offset would move every
 * part, and nothing else in the build reads a baked pose.
 *
 * <p>It deliberately does <b>not</b> guard the ground line. Since the 0.5 the art is built at moved
 * to render time, that transform lives on the root pose in {@code GoblinBodyModel} and these
 * assertions -- which read the six child groups -- never see it. A wrong root translate (scaling
 * about y = 0 rather than the ground line) would float every model 0.75 blocks and ship silently;
 * see the root-pose assertion below, which is the one that watches it.
 */
public final class ArtModelCheck {
    private static final Set<String> REQUIRED = Set.of(
            "head", "body", "left_arm", "right_arm", "left_leg", "right_leg");
    /** Mirrors generate_models.py: art space -> model space, one art unit to one model unit. The 0.5
     * the art is built at is applied at render time by GoblinBodyModel, not here. */
    private static final double SCALE = 1.0;
    private static final double GROUND = 24.0;
    private static final float TOLERANCE = 1e-3F;
    /** One crop directory per table: the goblin bodies in the first, the custom golems in the second. */
    private static final Path CROP_DIR = Path.of("tools", "models");
    private static final Path GOLEM_CROP_DIR = Path.of("tools", "models_golem");
    // "Absurdly tall" is per table, because the golems are built on a bigger grid than the goblins: the
    // tallest goblin crop tops out at 52 art units, the tallest golem at 86. Both bounds exist to catch a
    // crop that is not a character at all, not to pin a height the art is free to change.
    private static final double MAX_GOBLIN_ART_HEIGHT = 64;
    private static final double MAX_GOLEM_ART_HEIGHT = 128;

    public static void main(String[] args) throws IOException {
        // Both tables are read here rather than into fields, because a field's initializer runs before
        // this method could boot anything -- and the golem table is keyed on GolemTier, whose constants
        // carry item costs, so reading it needs vanilla's registries up. The same two lines the material
        // checks use.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Map<String, GoblinBodies.Body> bodies = byCrop(GoblinBodies.BODIES, GoblinBodies.Body::crop);
        Map<String, GolemBodies.Body> golemBodies = byCrop(GolemBodies.BODIES, GolemBodies.Body::crop);
        List<Path> crops = cropsIn(CROP_DIR);
        List<Path> golemCrops = cropsIn(GOLEM_CROP_DIR);
        checkBodiesAgreeWithTheirCrops();
        checkGolemBodiesAgreeWithTheirCrops();
        for (Path crop : crops) {
            JsonObject root = checkCrop(crop, MAX_GOBLIN_ART_HEIGHT);
            String name = cropName(crop);
            GoblinBodies.Body body = bodies.get(name);
            require(body != null, name + ": no renderable body is mapped to this crop -- add it to"
                    + " GoblinBodies.BODIES so the baked half checks it too");
            checkTexture(name, root, body.texture());
            checkBaked(name, body.layer().get(), root, body.model());
        }
        for (Path crop : golemCrops) {
            JsonObject root = checkCrop(crop, MAX_GOLEM_ART_HEIGHT);
            String name = cropName(crop);
            GolemBodies.Body body = golemBodies.get(name);
            require(body != null, name + ": no renderable golem is mapped to this crop -- add it to"
                    + " GolemBodies.BODIES so the baked half checks it too");
            checkTexture(name, root, body.texture());
            checkEmissive(name, root, body.emissive());
            checkBaked(name, body.layer().get(), root, body.model());
        }
        System.out.println("ArtModelCheck passed (" + crops.size() + " goblin crops, "
                + golemCrops.size() + " golem crops, " + (crops.size() + golemCrops.size())
                + " baked models)");
    }

    /** One table's rows, keyed by the crop the check validates them from. */
    private static <T> Map<String, T> byCrop(List<T> bodies, Function<T, String> crop) {
        return bodies.stream().collect(Collectors.toUnmodifiableMap(crop, body -> body));
    }

    /** The committed crops of one pipeline, sorted. An absent or empty directory fails the build. */
    private static List<Path> cropsIn(Path dir) throws IOException {
        require(Files.isDirectory(dir), "the crop directory exists: " + dir.toAbsolutePath());
        List<Path> crops = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted()
                    .forEach(crops::add);
        }
        require(!crops.isEmpty(), "at least one cropped model is committed under " + dir);
        return crops;
    }

    private static String cropName(Path crop) {
        return crop.getFileName().toString().replaceFirst("\\.json$", "");
    }

    private static JsonObject checkCrop(Path path, double maxArtHeight) throws IOException {
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
        require(highest <= maxArtHeight + TOLERANCE,
                name + ": the model is not absurdly tall (" + highest + " art units, bound "
                        + maxArtHeight + ")");
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
        Path path = texturePath(texture);
        require(Files.isRegularFile(path), name + ": the texture " + path.toAbsolutePath() + " exists");
        int[] size = readPngSize(path);
        int width = size[0];
        int height = size[1];
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

    /** Where the entity texture of this basename lives. The skins and the golem masks share a folder. */
    private static Path texturePath(String name) {
        return Path.of("src", "main", "resources", "assets", "goblin_settlement",
                "textures", "entity", name + ".png");
    }

    /** The pixel size a png declares in the IHDR chunk it starts with, read without decoding it. */
    private static int[] readPngSize(Path path) throws IOException {
        byte[] header = new byte[24];
        try (var in = Files.newInputStream(path)) {
            require(in.readNBytes(header, 0, header.length) == header.length,
                    "the png " + path + " has a complete header");
        }
        return new int[] {readIntBigEndian(header, 16), readIntBigEndian(header, 20)};
    }

    /**
     * A golem's second texture: a transparent-background mask of just the glowing core and the eyes,
     * which the render layer draws full-bright over the skin. It has to be committed, the same size and
     * the same uv layout as the skin, actually lit, and lit only where some cube samples.
     *
     * <p>Each of the two ends of that is a real failure the size check alone would not see. An
     * all-transparent mask renders nothing, so the golem quietly loses its glow; a mask shifted off its
     * rectangles glows beside the model instead of on it, and a whole-image mask makes the whole golem
     * glow. The last one is not caught here -- a mask that lights every texel some cube samples is a
     * legible drawing decision -- but the first two are, and both would otherwise reach a screen.
     */
    private static void checkEmissive(String name, JsonObject root, String emissive) throws IOException {
        JsonObject resolution = root.getAsJsonObject("resolution");
        int u = resolution.get("width").getAsInt();
        int v = resolution.get("height").getAsInt();
        Path path = texturePath(emissive);
        require(Files.isRegularFile(path),
                name + ": the emissive mask " + path.toAbsolutePath() + " exists");
        int[] size = readPngSize(path);
        int width = size[0];
        int height = size[1];
        require(width == 2 * u && height == 2 * v,
                name + ": the emissive mask is " + width + "x" + height + ", expected twice the crop's "
                        + u + "x" + v + " logical uv");
        BufferedImage image = ImageIO.read(path.toFile());
        require(image != null && image.getWidth() == width && image.getHeight() == height,
                name + ": the emissive mask " + path + " decodes to a " + width + "x" + height
                        + " image");

        List<double[]> rectangles = new ArrayList<>();
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
            // The box-uv footprint of checkTexture, in bitmap pixels: logical uv -> pixels is x2. The ends
            // stay unfloored here, so a texel a fractional far edge only just reaches counts as sampled --
            // the same leniency checkTexture grants that edge, and the fraction of a texel a cube's face
            // overhangs its last whole texel is not a drawing mistake.
            rectangles.add(new double[] {2.0 * u0, 2.0 * v0,
                    2.0 * (u0 + 2 * (cubeWidth + cubeDepth)),
                    2.0 * (v0 + (cubeHeight + cubeDepth))});
        }
        int lit = 0;
        int stray = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((image.getRGB(x, y) >>> 24) == 0) {
                    continue;
                }
                lit++;
                if (!sampled(rectangles, x, y)) {
                    stray++;
                }
            }
        }
        require(lit > 0, name + ": the emissive mask " + path + " lights at least one pixel");
        require(stray == 0, name + ": the emissive mask " + path + " lights " + stray
                + " pixel(s) that no cube samples, so the glow would show beside the model");
    }

    /** Whether any of the cube uv rectangles contains this texel's index. */
    private static boolean sampled(List<double[]> rectangles, int x, int y) {
        for (double[] rectangle : rectangles) {
            if (x >= rectangle[0] && x < rectangle[2] && y >= rectangle[1] && y < rectangle[3]) {
                return true;
            }
        }
        return false;
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
    private static void checkBaked(String label, LayerDefinition layer, JsonObject crop,
                                   Function<ModelPart, ?> modelFactory) {
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

        // The six assertions above read the child groups, which the generator's art-space mapping sets.
        // The 0.5 the art is built at is applied at RENDER time instead, on the root pose -- and a pose
        // the assertions above never see. Guard it: the root must scale the art-scale mesh about the
        // ground line, so the feet stay planted. Scaling about y = 0 instead floats every model 0.75
        // blocks, and nothing else in the build would notice.
        Object built = modelFactory.apply(root);
        require(built instanceof GoblinBodyModel, label + ": the row builds a GoblinBodyModel");
        PartPose pose = ((GoblinBodyModel) built).root().getInitialPose();
        float expectedY = GoblinBodyModel.GROUND * (1.0F - GoblinBodyModel.RENDER_SCALE);
        require(Math.abs(pose.x()) <= TOLERANCE && Math.abs(pose.z()) <= TOLERANCE
                        && Math.abs(pose.y() - expectedY) <= TOLERANCE
                        && Math.abs(pose.xScale() - GoblinBodyModel.RENDER_SCALE) <= TOLERANCE
                        && Math.abs(pose.yScale() - GoblinBodyModel.RENDER_SCALE) <= TOLERANCE
                        && Math.abs(pose.zScale() - GoblinBodyModel.RENDER_SCALE) <= TOLERANCE,
                label + ": the root pose is (" + pose.x() + ", " + pose.y() + ", " + pose.z()
                        + ") scale " + pose.xScale() + ", but halving the art-scale mesh about the"
                        + " ground line needs y = " + expectedY + " and scale "
                        + GoblinBodyModel.RENDER_SCALE);
    }

    /**
     * Whether a crop's name says female. The art spells an adult's sex with {@code male}/{@code female}
     * and a child's with {@code boy}/{@code girl}, so both vocabularies count. The flag must still agree
     * with the name -- only the words the art uses differ, the check itself is unchanged.
     */
    private static boolean namedFemale(String crop) {
        return crop.contains("female") || crop.contains("girl");
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("ArtModelCheck failed: " + message);
        }
    }

    /**
     * The table itself must be sane before anything reads it: no two rows may serve the same
     * (child, sex, profession), no row may claim a crop that is not committed, a row's sex must agree with
     * its own crop's name (the art names an adult {@code goblin_..._male}/{@code ..._female} and a child
     * {@code goblin_child_boy_...}/{@code ..._girl_...}, so a copy-paste slip that flips the flag but not
     * the name is caught here rather than in game), and the same for its age (every child crop is called
     * {@code goblin_child_...}). A child row may not name a profession: the roster only ever hands a trade
     * to an adult, so such a row is unreachable and wrong. A duplicate row would silently shadow another
     * one, and the reader that loses would never notice.
     */
    private static void checkBodiesAgreeWithTheirCrops() throws IOException {
        Set<String> served = new HashSet<>();
        for (GoblinBodies.Body body : GoblinBodies.BODIES) {
            String key = body.child() + "/" + body.profession().map(Enum::name).orElse("BASE") + "/"
                    + body.female();
            require(served.add(key), "two bodies claim " + key);
            require(body.female() == namedFemale(body.crop()),
                    "the body " + body.crop() + " disagrees with its own name about sex");
            require(body.child() == body.crop().contains("child"),
                    "the body " + body.crop() + " disagrees with its own name about age");
            require(!body.child() || body.profession().isEmpty(),
                    "the child body " + body.crop() + " names a profession, which the roster never gives"
                            + " a child");
            Path crop = Path.of("tools", "models", body.crop() + ".json");
            require(Files.isRegularFile(crop),
                    "the body " + body.crop() + " has a committed crop at " + crop);
        }
    }

    /**
     * The golem table's own sanity pass, the twin of the one above. A row is built by its class's
     * {@code body()} and takes every field from that class's constants, so the ways a row can point at
     * the wrong art are narrow and each is named here: two rows claiming one tier (which would silently
     * shadow a mesh), a row whose crop name disagrees with its own tier, and a row with no committed crop.
     * That crop-name rule is the goblin rule -- "a row names its class once" -- applied to the axis a golem
     * is keyed on instead of sex and trade: a row cannot pair one tier's name with another tier's crop.
     *
     * <p>The last loop ties the table to {@link GolemTier#hasCustomArt()}: the rows must cover exactly the
     * tiers that claim custom art, no more and no fewer. That equality is what makes the entity's synced
     * reader safe -- it hands the renderer a tier only when {@code hasCustomArt()} is true -- so this is the
     * proof that every such tier, and only such a tier, has a mesh waiting for it.
     */
    private static void checkGolemBodiesAgreeWithTheirCrops() {
        Set<GolemTier> served = new HashSet<>();
        for (GolemBodies.Body body : GolemBodies.BODIES) {
            require(served.add(body.tier()), "two golem bodies claim the " + body.tier() + " tier");
            require(body.crop().contains(body.tier().name().toLowerCase(Locale.ROOT)),
                    "the golem body " + body.crop() + " disagrees with its own tier " + body.tier());
            Path crop = Path.of("tools", "models_golem", body.crop() + ".json");
            require(Files.isRegularFile(crop),
                    "the golem " + body.crop() + " has a committed crop at " + crop);
        }
        for (GolemTier tier : GolemTier.values()) {
            require(served.contains(tier) == tier.hasCustomArt(),
                    tier.hasCustomArt()
                            ? "no golem body has art for the " + tier + " tier, which the entity can reach"
                            : "a golem body claims the " + tier
                                    + " tier, which has no custom art of its own");
        }
    }
}
