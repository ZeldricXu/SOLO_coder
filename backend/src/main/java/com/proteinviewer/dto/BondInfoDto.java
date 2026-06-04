package com.proteinviewer.dto;

import java.util.List;

public class BondInfoDto {
    private int atomSerial;
    private List<Integer> bondedAtoms;

    public BondInfoDto() {}

    public int getAtomSerial() { return atomSerial; }
    public void setAtomSerial(int atomSerial) { this.atomSerial = atomSerial; }
    public List<Integer> getBondedAtoms() { return bondedAtoms; }
    public void setBondedAtoms(List<Integer> bondedAtoms) { this.bondedAtoms = bondedAtoms; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final BondInfoDto r = new BondInfoDto();
        public Builder atomSerial(int v) { r.atomSerial = v; return this; }
        public Builder bondedAtoms(List<Integer> v) { r.bondedAtoms = v; return this; }
        public BondInfoDto build() { return r; }
    }
}
