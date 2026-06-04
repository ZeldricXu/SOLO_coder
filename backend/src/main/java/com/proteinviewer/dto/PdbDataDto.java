package com.proteinviewer.dto;

import java.util.List;

public class PdbDataDto {
    private Long structureId;
    private String pdbId;
    private String title;
    private List<AtomInfoDto> atoms;
    private List<BondInfoDto> bonds;
    private List<ValidationWarningDto> warnings;
    private List<String> chainIds;
    private int totalAtoms;
    private int totalResidues;

    public PdbDataDto() {}

    public Long getStructureId() { return structureId; }
    public void setStructureId(Long structureId) { this.structureId = structureId; }
    public String getPdbId() { return pdbId; }
    public void setPdbId(String pdbId) { this.pdbId = pdbId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<AtomInfoDto> getAtoms() { return atoms; }
    public void setAtoms(List<AtomInfoDto> atoms) { this.atoms = atoms; }
    public List<BondInfoDto> getBonds() { return bonds; }
    public void setBonds(List<BondInfoDto> bonds) { this.bonds = bonds; }
    public List<ValidationWarningDto> getWarnings() { return warnings; }
    public void setWarnings(List<ValidationWarningDto> warnings) { this.warnings = warnings; }
    public List<String> getChainIds() { return chainIds; }
    public void setChainIds(List<String> chainIds) { this.chainIds = chainIds; }
    public int getTotalAtoms() { return totalAtoms; }
    public void setTotalAtoms(int totalAtoms) { this.totalAtoms = totalAtoms; }
    public int getTotalResidues() { return totalResidues; }
    public void setTotalResidues(int totalResidues) { this.totalResidues = totalResidues; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final PdbDataDto r = new PdbDataDto();
        public Builder structureId(Long v) { r.structureId = v; return this; }
        public Builder pdbId(String v) { r.pdbId = v; return this; }
        public Builder title(String v) { r.title = v; return this; }
        public Builder atoms(List<AtomInfoDto> v) { r.atoms = v; return this; }
        public Builder bonds(List<BondInfoDto> v) { r.bonds = v; return this; }
        public Builder warnings(List<ValidationWarningDto> v) { r.warnings = v; return this; }
        public Builder chainIds(List<String> v) { r.chainIds = v; return this; }
        public Builder totalAtoms(int v) { r.totalAtoms = v; return this; }
        public Builder totalResidues(int v) { r.totalResidues = v; return this; }
        public PdbDataDto build() { return r; }
    }
}
