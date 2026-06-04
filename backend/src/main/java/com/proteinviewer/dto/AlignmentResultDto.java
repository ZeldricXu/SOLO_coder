package com.proteinviewer.dto;

import java.util.List;

public class AlignmentResultDto {
    private Long structure1Id;
    private Long structure2Id;
    private double rmsd;
    private double[][] rotationMatrix;
    private double[] translationVector;
    private List<ResidueRmsd> perResidueRmsd;
    private int alignedAtomCount;

    public AlignmentResultDto() {}

    public Long getStructure1Id() { return structure1Id; }
    public void setStructure1Id(Long structure1Id) { this.structure1Id = structure1Id; }
    public Long getStructure2Id() { return structure2Id; }
    public void setStructure2Id(Long structure2Id) { this.structure2Id = structure2Id; }
    public double getRmsd() { return rmsd; }
    public void setRmsd(double rmsd) { this.rmsd = rmsd; }
    public double[][] getRotationMatrix() { return rotationMatrix; }
    public void setRotationMatrix(double[][] rotationMatrix) { this.rotationMatrix = rotationMatrix; }
    public double[] getTranslationVector() { return translationVector; }
    public void setTranslationVector(double[] translationVector) { this.translationVector = translationVector; }
    public List<ResidueRmsd> getPerResidueRmsd() { return perResidueRmsd; }
    public void setPerResidueRmsd(List<ResidueRmsd> perResidueRmsd) { this.perResidueRmsd = perResidueRmsd; }
    public int getAlignedAtomCount() { return alignedAtomCount; }
    public void setAlignedAtomCount(int alignedAtomCount) { this.alignedAtomCount = alignedAtomCount; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final AlignmentResultDto r = new AlignmentResultDto();
        public Builder structure1Id(Long v) { r.structure1Id = v; return this; }
        public Builder structure2Id(Long v) { r.structure2Id = v; return this; }
        public Builder rmsd(double v) { r.rmsd = v; return this; }
        public Builder rotationMatrix(double[][] v) { r.rotationMatrix = v; return this; }
        public Builder translationVector(double[] v) { r.translationVector = v; return this; }
        public Builder perResidueRmsd(List<ResidueRmsd> v) { r.perResidueRmsd = v; return this; }
        public Builder alignedAtomCount(int v) { r.alignedAtomCount = v; return this; }
        public AlignmentResultDto build() { return r; }
    }

    public static class ResidueRmsd {
        private String residueName;
        private String chainId;
        private int resSeq;
        private double rmsd;

        public ResidueRmsd() {}

        public String getResidueName() { return residueName; }
        public void setResidueName(String residueName) { this.residueName = residueName; }
        public String getChainId() { return chainId; }
        public void setChainId(String chainId) { this.chainId = chainId; }
        public int getResSeq() { return resSeq; }
        public void setResSeq(int resSeq) { this.resSeq = resSeq; }
        public double getRmsd() { return rmsd; }
        public void setRmsd(double rmsd) { this.rmsd = rmsd; }

        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private final ResidueRmsd r = new ResidueRmsd();
            public Builder residueName(String v) { r.residueName = v; return this; }
            public Builder chainId(String v) { r.chainId = v; return this; }
            public Builder resSeq(int v) { r.resSeq = v; return this; }
            public Builder rmsd(double v) { r.rmsd = v; return this; }
            public ResidueRmsd build() { return r; }
        }
    }
}
