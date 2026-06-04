package com.proteinviewer.dto;

import java.util.List;

public class StructureUploadResponse {
    private Long id;
    private String name;
    private String pdbId;
    private int atomCount;
    private int residueCount;
    private int bondCount;
    private List<ValidationWarningDto> warnings;

    public StructureUploadResponse() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPdbId() { return pdbId; }
    public void setPdbId(String pdbId) { this.pdbId = pdbId; }
    public int getAtomCount() { return atomCount; }
    public void setAtomCount(int atomCount) { this.atomCount = atomCount; }
    public int getResidueCount() { return residueCount; }
    public void setResidueCount(int residueCount) { this.residueCount = residueCount; }
    public int getBondCount() { return bondCount; }
    public void setBondCount(int bondCount) { this.bondCount = bondCount; }
    public List<ValidationWarningDto> getWarnings() { return warnings; }
    public void setWarnings(List<ValidationWarningDto> warnings) { this.warnings = warnings; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final StructureUploadResponse r = new StructureUploadResponse();
        public Builder id(Long v) { r.id = v; return this; }
        public Builder name(String v) { r.name = v; return this; }
        public Builder pdbId(String v) { r.pdbId = v; return this; }
        public Builder atomCount(int v) { r.atomCount = v; return this; }
        public Builder residueCount(int v) { r.residueCount = v; return this; }
        public Builder bondCount(int v) { r.bondCount = v; return this; }
        public Builder warnings(List<ValidationWarningDto> v) { r.warnings = v; return this; }
        public StructureUploadResponse build() { return r; }
    }
}
