package com.proteinviewer.model;

import java.util.ArrayList;
import java.util.List;

public class AtomRecord {
    private int serialNumber;
    private String atomName;
    private char altLocation;
    private String residueName;
    private String chainId;
    private int residueSeqNumber;
    private char iCode;
    private double x;
    private double y;
    private double z;
    private double occupancy;
    private double tempFactor;
    private String element;
    private String charge;
    private int lineNumber;
    private boolean isHetatm;

    public AtomRecord() {}

    public int getSerialNumber() { return serialNumber; }
    public void setSerialNumber(int serialNumber) { this.serialNumber = serialNumber; }
    public String getAtomName() { return atomName; }
    public void setAtomName(String atomName) { this.atomName = atomName; }
    public char getAltLocation() { return altLocation; }
    public void setAltLocation(char altLocation) { this.altLocation = altLocation; }
    public String getResidueName() { return residueName; }
    public void setResidueName(String residueName) { this.residueName = residueName; }
    public String getChainId() { return chainId; }
    public void setChainId(String chainId) { this.chainId = chainId; }
    public int getResidueSeqNumber() { return residueSeqNumber; }
    public void setResidueSeqNumber(int residueSeqNumber) { this.residueSeqNumber = residueSeqNumber; }
    public char getICode() { return iCode; }
    public void setICode(char iCode) { this.iCode = iCode; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; }
    public double getOccupancy() { return occupancy; }
    public void setOccupancy(double occupancy) { this.occupancy = occupancy; }
    public double getTempFactor() { return tempFactor; }
    public void setTempFactor(double tempFactor) { this.tempFactor = tempFactor; }
    public String getElement() { return element; }
    public void setElement(String element) { this.element = element; }
    public String getCharge() { return charge; }
    public void setCharge(String charge) { this.charge = charge; }
    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }
    public boolean isHetatm() { return isHetatm; }
    public void setHetatm(boolean hetatm) { isHetatm = hetatm; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final AtomRecord record = new AtomRecord();
        public Builder serialNumber(int v) { record.serialNumber = v; return this; }
        public Builder atomName(String v) { record.atomName = v; return this; }
        public Builder altLocation(char v) { record.altLocation = v; return this; }
        public Builder residueName(String v) { record.residueName = v; return this; }
        public Builder chainId(String v) { record.chainId = v; return this; }
        public Builder residueSeqNumber(int v) { record.residueSeqNumber = v; return this; }
        public Builder iCode(char v) { record.iCode = v; return this; }
        public Builder x(double v) { record.x = v; return this; }
        public Builder y(double v) { record.y = v; return this; }
        public Builder z(double v) { record.z = v; return this; }
        public Builder occupancy(double v) { record.occupancy = v; return this; }
        public Builder tempFactor(double v) { record.tempFactor = v; return this; }
        public Builder element(String v) { record.element = v; return this; }
        public Builder charge(String v) { record.charge = v; return this; }
        public Builder lineNumber(int v) { record.lineNumber = v; return this; }
        public Builder isHetatm(boolean v) { record.isHetatm = v; return this; }
        public AtomRecord build() { return record; }
    }
}
