package com.proteinviewer.render;

public final class CylinderPrimitive {
    private final double x1;
    private final double y1;
    private final double z1;
    private final double x2;
    private final double y2;
    private final double z2;
    private final double radius;
    private final String colorHex;

    public CylinderPrimitive(double x1, double y1, double z1, double x2, double y2, double z2,
                             double radius, String colorHex) {
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.x2 = x2;
        this.y2 = y2;
        this.z2 = z2;
        this.radius = radius;
        this.colorHex = colorHex;
    }

    public double getX1() { return x1; }
    public double getY1() { return y1; }
    public double getZ1() { return z1; }
    public double getX2() { return x2; }
    public double getY2() { return y2; }
    public double getZ2() { return z2; }
    public double getRadius() { return radius; }
    public String getColorHex() { return colorHex; }
}
