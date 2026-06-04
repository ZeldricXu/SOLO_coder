package com.proteinviewer.dto;

import java.util.List;

public class InteractionResultDto {
    private String centerResidue;
    private String centerChain;
    private int centerResSeq;
    private List<NeighborInteraction> interactions;

    public InteractionResultDto() {}

    public String getCenterResidue() { return centerResidue; }
    public void setCenterResidue(String centerResidue) { this.centerResidue = centerResidue; }
    public String getCenterChain() { return centerChain; }
    public void setCenterChain(String centerChain) { this.centerChain = centerChain; }
    public int getCenterResSeq() { return centerResSeq; }
    public void setCenterResSeq(int centerResSeq) { this.centerResSeq = centerResSeq; }
    public List<NeighborInteraction> getInteractions() { return interactions; }
    public void setInteractions(List<NeighborInteraction> interactions) { this.interactions = interactions; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final InteractionResultDto r = new InteractionResultDto();
        public Builder centerResidue(String v) { r.centerResidue = v; return this; }
        public Builder centerChain(String v) { r.centerChain = v; return this; }
        public Builder centerResSeq(int v) { r.centerResSeq = v; return this; }
        public Builder interactions(List<NeighborInteraction> v) { r.interactions = v; return this; }
        public InteractionResultDto build() { return r; }
    }

    public static class NeighborInteraction {
        private String residue;
        private String chain;
        private int resSeq;
        private String type;
        private double distance;
        private String details;

        public NeighborInteraction() {}

        public String getResidue() { return residue; }
        public void setResidue(String residue) { this.residue = residue; }
        public String getChain() { return chain; }
        public void setChain(String chain) { this.chain = chain; }
        public int getResSeq() { return resSeq; }
        public void setResSeq(int resSeq) { this.resSeq = resSeq; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public double getDistance() { return distance; }
        public void setDistance(double distance) { this.distance = distance; }
        public String getDetails() { return details; }
        public void setDetails(String details) { this.details = details; }

        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private final NeighborInteraction r = new NeighborInteraction();
            public Builder residue(String v) { r.residue = v; return this; }
            public Builder chain(String v) { r.chain = v; return this; }
            public Builder resSeq(int v) { r.resSeq = v; return this; }
            public Builder type(String v) { r.type = v; return this; }
            public Builder distance(double v) { r.distance = v; return this; }
            public Builder details(String v) { r.details = v; return this; }
            public NeighborInteraction build() { return r; }
        }
    }
}
