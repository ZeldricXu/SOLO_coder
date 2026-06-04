package com.proteinviewer.dto;

public class AngleResultDto {
    private int atom1Serial;
    private int atom2Serial;
    private int atom3Serial;
    private double angle;
    private String unit;

    public AngleResultDto() {}

    public int getAtom1Serial() { return atom1Serial; }
    public void setAtom1Serial(int atom1Serial) { this.atom1Serial = atom1Serial; }
    public int getAtom2Serial() { return atom2Serial; }
    public void setAtom2Serial(int atom2Serial) { this.atom2Serial = atom2Serial; }
    public int getAtom3Serial() { return atom3Serial; }
    public void setAtom3Serial(int atom3Serial) { this.atom3Serial = atom3Serial; }
    public double getAngle() { return angle; }
    public void setAngle(double angle) { this.angle = angle; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final AngleResultDto r = new AngleResultDto();
        public Builder atom1Serial(int v) { r.atom1Serial = v; return this; }
        public Builder atom2Serial(int v) { r.atom2Serial = v; return this; }
        public Builder atom3Serial(int v) { r.atom3Serial = v; return this; }
        public Builder angle(double v) { r.angle = v; return this; }
        public Builder unit(String v) { r.unit = v; return this; }
        public AngleResultDto build() { return r; }
    }
}
