package com.proteinviewer.domain;

import java.util.List;

public final class Bond {
    private final int atomSerial;
    private final List<Integer> bondedAtoms;

    public Bond(int atomSerial, List<Integer> bondedAtoms) {
        this.atomSerial = atomSerial;
        this.bondedAtoms = bondedAtoms;
    }

    public int getAtomSerial() { return atomSerial; }
    public List<Integer> getBondedAtoms() { return bondedAtoms; }
}
