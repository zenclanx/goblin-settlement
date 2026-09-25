package dev.local.goblinsettlement.interaction;

/** Inclusive X/Z player area; protection applies at every Y level. */
public record ProtectedRectangle(int minX, int minZ, int maxX, int maxZ) {
    public static final int BUFFER = 2;

    public ProtectedRectangle {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Invalid rectangle bounds");
        }
    }

    public static ProtectedRectangle fromCorners(int x1, int z1, int x2, int z2) {
        return new ProtectedRectangle(Math.min(x1, x2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(z1, z2));
    }

    public boolean contains(int x, int z) {
        return intersects(x, z, x, z);
    }

    public boolean intersects(long otherMinX, long otherMinZ, long otherMaxX, long otherMaxZ) {
        return otherMaxX >= (long) minX - BUFFER && otherMinX <= (long) maxX + BUFFER
                && otherMaxZ >= (long) minZ - BUFFER && otherMinZ <= (long) maxZ + BUFFER;
    }
}
