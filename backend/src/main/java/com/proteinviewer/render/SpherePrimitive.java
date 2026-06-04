package com.proteinviewer.render;

public final class SpherePrimitive {
    private final double x;
    private final double y;
    private final double z;
    private final double radius;
    private final String colorHex;
    private final String label;
    private final String atomElement;
    private final int atomSerial;

    public SpherePrimitive(double x, double y, double z, double radius, String colorHex,
                           String label, String atomElement, int atomSerial) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.colorHex = colorHex;
        this.label = label;
        this.atomElement = atomElement;
        this.atomSerial = atomSerial;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public double getRadius() { return radius; }
    public String getColorHex() { return colorHex; }
    public String getLabel() { return label; }
    public String getAtomElement() { return atomElement; }
    public int getAtomSerial() { return atomSerial; }
}
