package com.proteinviewer.domain;

import java.util.ArrayList;
import java.util.List;

public final class Structure {
    private final String pdbId;
    private final String title;
    private final List<Atom> atoms;
    private final List<Bond> bonds;
    private final List<Chain> chains;
    private final int totalResidues;
    private final ValidationResult validation;

    public Structure(String pdbId, String title, List<Atom> atoms, List<Bond> bonds,
                     List<Chain> chains, int totalResidues, ValidationResult validation) {
        this.pdbId = pdbId;
        this.title = title;
        this.atoms = atoms;
        this.bonds = bonds;
        this.chains = chains;
        this.totalResidues = totalResidues;
        this.validation = validation;
    }

    public String getPdbId() { return pdbId; }
    public String getTitle() { return title; }
    public List<Atom> getAtoms() { return atoms; }
    public List<Bond> getBonds() { return bonds; }
    public List<Chain> getChains() { return chains; }
    public int getTotalResidues() { return totalResidues; }
    public ValidationResult getValidation() { return validation; }

    public List<Atom> getAtomsByChain(String chainId) {
        List<Atom> result = new ArrayList<>();
        for (Atom atom : atoms) {
            if (chainId.equals(atom.getChainId())) {
                result.add(atom);
            }
        }
        return result;
    }

    public List<Atom> getAlphaCarbons() {
        List<Atom> result = new ArrayList<>();
        for (Atom atom : atoms) {
            if ("CA".equals(atom.getAtomName()) && !atom.isHetatm()) {
                result.add(atom);
            }
        }
        return result;
    }

    public Atom findAtomBySerial(int serial) {
        for (Atom atom : atoms) {
            if (atom.getSerialNumber() == serial) {
                return atom;
            }
        }
        return null;
    }
}
