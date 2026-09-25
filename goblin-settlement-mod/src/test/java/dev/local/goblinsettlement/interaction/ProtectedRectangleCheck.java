package dev.local.goblinsettlement.interaction;

public final class ProtectedRectangleCheck {
    public static void main(String[] args) {
        var area = ProtectedRectangle.fromCorners(10, 20, -2, 3);
        require(area.contains(-4, 1), "buffer corner included");
        require(area.contains(12, 22), "upper buffer corner included");
        require(!area.contains(-5, 1), "outside x buffer rejected");
        require(!area.contains(12, 23), "outside z buffer rejected");
        require(area.intersects(12, 0, 19, 7), "plot touching buffer rejected");
        require(!area.intersects(13, 0, 20, 7), "plot outside buffer allowed");
        var edge = ProtectedRectangle.fromCorners(Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MIN_VALUE);
        require(edge.contains(Integer.MAX_VALUE, Integer.MIN_VALUE), "extreme position included");
        require(!edge.contains(Integer.MIN_VALUE, Integer.MAX_VALUE), "extreme position excluded");
        System.out.println("ProtectedRectangleCheck passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
