package com.proteinviewer.domain;

import java.util.List;

public final class Residue {
    private final String name;
    private final String chainId;
    private final int seqNumber;
    private final char iCode;
    private final List<Atom> atoms;

    public Residue(String name, String chainId, int seqNumber, char iCode, List<Atom> atoms) {
        this.name = name;
        this.chainId = chainId;
        this.seqNumber = seqNumber;
        this.iCode = iCode;
        this.atoms = atoms;
    }

    public String getName() { return name; }
    public String getChainId() { return chainId; }
    public int getSeqNumber() { return seqNumber; }
    public char getICode() { return iCode; }
    public List<Atom> getAtoms() { return atoms; }

    public Atom getAlphaCarbon() {
        for (Atom atom : atoms) {
            if ("CA".equals(atom.getAtomName()) && !atom.isHetatm()) {
                return atom;
            }
        }
        return null;
    }

    public double[] getCentroid() {
        double sumX = 0, sumY = 0, sumZ = 0;
        for (Atom atom : atoms) {
            sumX += atom.getX();
            sumY += atom.getY();
            sumZ += atom.getZ();
        }
        int n = atoms.size();
        return new double[]{sumX / n, sumY / n, sumZ / n};
    }
}
