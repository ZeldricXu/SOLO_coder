package com.proteinviewer.model;

import java.util.List;

public class BondRecord {
    private int atomSerial;
    private List<Integer> bondedAtoms;
    private int lineNumber;

    public BondRecord() {}

    public int getAtomSerial() { return atomSerial; }
    public void setAtomSerial(int atomSerial) { this.atomSerial = atomSerial; }
    public List<Integer> getBondedAtoms() { return bondedAtoms; }
    public void setBondedAtoms(List<Integer> bondedAtoms) { this.bondedAtoms = bondedAtoms; }
    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final BondRecord r = new BondRecord();
        public Builder atomSerial(int v) { r.atomSerial = v; return this; }
        public Builder bondedAtoms(List<Integer> v) { r.bondedAtoms = v; return this; }
        public Builder lineNumber(int v) { r.lineNumber = v; return this; }
        public BondRecord build() { return r; }
    }
}
