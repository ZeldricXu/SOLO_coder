package com.proteinviewer.dto;

public class DistanceResultDto {
    private int atom1Serial;
    private int atom2Serial;
    private double distance;
    private String unit;

    public DistanceResultDto() {}

    public int getAtom1Serial() { return atom1Serial; }
    public void setAtom1Serial(int atom1Serial) { this.atom1Serial = atom1Serial; }
    public int getAtom2Serial() { return atom2Serial; }
    public void setAtom2Serial(int atom2Serial) { this.atom2Serial = atom2Serial; }
    public double getDistance() { return distance; }
    public void setDistance(double distance) { this.distance = distance; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final DistanceResultDto r = new DistanceResultDto();
        public Builder atom1Serial(int v) { r.atom1Serial = v; return this; }
        public Builder atom2Serial(int v) { r.atom2Serial = v; return this; }
        public Builder distance(double v) { r.distance = v; return this; }
        public Builder unit(String v) { r.unit = v; return this; }
        public DistanceResultDto build() { return r; }
    }
}
