package com.proteinviewer.model;

import java.util.List;

public class ParsedPdb {

    private String pdbId;
    private String title;
    private String header;
    private List<AtomRecord> atoms;
    private List<BondRecord> bonds;
    private ValidationResult validation;
    private int totalAtoms;
    private int totalResidues;
    private List<String> chainIds;

    public ParsedPdb() {}

    public ParsedPdb(String pdbId, String title, String header, List<AtomRecord> atoms, List<BondRecord> bonds, ValidationResult validation, int totalAtoms, int totalResidues, List<String> chainIds) {
        this.pdbId = pdbId;
        this.title = title;
        this.header = header;
        this.atoms = atoms;
        this.bonds = bonds;
        this.validation = validation;
        this.totalAtoms = totalAtoms;
        this.totalResidues = totalResidues;
        this.chainIds = chainIds;
    }

    public String getPdbId() { return pdbId; }
    public void setPdbId(String pdbId) { this.pdbId = pdbId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getHeader() { return header; }
    public void setHeader(String header) { this.header = header; }
    public List<AtomRecord> getAtoms() { return atoms; }
    public void setAtoms(List<AtomRecord> atoms) { this.atoms = atoms; }
    public List<BondRecord> getBonds() { return bonds; }
    public void setBonds(List<BondRecord> bonds) { this.bonds = bonds; }
    public ValidationResult getValidation() { return validation; }
    public void setValidation(ValidationResult validation) { this.validation = validation; }
    public int getTotalAtoms() { return totalAtoms; }
    public void setTotalAtoms(int totalAtoms) { this.totalAtoms = totalAtoms; }
    public int getTotalResidues() { return totalResidues; }
    public void setTotalResidues(int totalResidues) { this.totalResidues = totalResidues; }
    public List<String> getChainIds() { return chainIds; }
    public void setChainIds(List<String> chainIds) { this.chainIds = chainIds; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final ParsedPdb r = new ParsedPdb();
        public Builder pdbId(String v) { r.pdbId = v; return this; }
        public Builder title(String v) { r.title = v; return this; }
        public Builder header(String v) { r.header = v; return this; }
        public Builder atoms(List<AtomRecord> v) { r.atoms = v; return this; }
        public Builder bonds(List<BondRecord> v) { r.bonds = v; return this; }
        public Builder validation(ValidationResult v) { r.validation = v; return this; }
        public Builder totalAtoms(int v) { r.totalAtoms = v; return this; }
        public Builder totalResidues(int v) { r.totalResidues = v; return this; }
        public Builder chainIds(List<String> v) { r.chainIds = v; return this; }
        public ParsedPdb build() { return r; }
    }
}
