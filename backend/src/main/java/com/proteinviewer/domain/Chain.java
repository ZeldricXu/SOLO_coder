package com.proteinviewer.domain;

import java.util.List;

public final class Chain {
    private final String id;
    private final List<Residue> residues;

    public Chain(String id, List<Residue> residues) {
        this.id = id;
        this.residues = residues;
    }

    public String getId() { return id; }
    public List<Residue> getResidues() { return residues; }
}
