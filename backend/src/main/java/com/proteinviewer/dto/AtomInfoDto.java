package com.proteinviewer.dto;

public class AtomInfoDto {
    private int serialNumber;
    private String atomName;
    private String residueName;
    private String chainId;
    private int residueSeqNumber;
    private double x;
    private double y;
    private double z;
    private String element;
    private double tempFactor;
    private boolean isHetatm;

    public AtomInfoDto() {}

    public int getSerialNumber() { return serialNumber; }
    public void setSerialNumber(int serialNumber) { this.serialNumber = serialNumber; }
    public String getAtomName() { return atomName; }
    public void setAtomName(String atomName) { this.atomName = atomName; }
    public String getResidueName() { return residueName; }
    public void setResidueName(String residueName) { this.residueName = residueName; }
    public String getChainId() { return chainId; }
    public void setChainId(String chainId) { this.chainId = chainId; }
    public int getResidueSeqNumber() { return residueSeqNumber; }
    public void setResidueSeqNumber(int residueSeqNumber) { this.residueSeqNumber = residueSeqNumber; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; }
    public String getElement() { return element; }
    public void setElement(String element) { this.element = element; }
    public double getTempFactor() { return tempFactor; }
    public void setTempFactor(double tempFactor) { this.tempFactor = tempFactor; }
    public boolean isHetatm() { return isHetatm; }
    public void setHetatm(boolean hetatm) { isHetatm = hetatm; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final AtomInfoDto r = new AtomInfoDto();
        public Builder serialNumber(int v) { r.serialNumber = v; return this; }
        public Builder atomName(String v) { r.atomName = v; return this; }
        public Builder residueName(String v) { r.residueName = v; return this; }
        public Builder chainId(String v) { r.chainId = v; return this; }
        public Builder residueSeqNumber(int v) { r.residueSeqNumber = v; return this; }
        public Builder x(double v) { r.x = v; return this; }
        public Builder y(double v) { r.y = v; return this; }
        public Builder z(double v) { r.z = v; return this; }
        public Builder element(String v) { r.element = v; return this; }
        public Builder tempFactor(double v) { r.tempFactor = v; return this; }
        public Builder isHetatm(boolean v) { r.isHetatm = v; return this; }
        public AtomInfoDto build() { return r; }
    }
}
