package com.proteinviewer.dto;

import java.util.List;
import java.util.Map;

public class BatchAnalysisResultDto {
    private String taskId;
    private String status;
    private List<Long> structureIds;
    private double[][] rmsdMatrix;
    private List<String> structureNames;
    private List<DisulfideBond> disulfideBonds;
    private List<GlycosylationSite> glycosylationSites;
    private Map<Long, BFactorStats> bfactorStats;

    public BatchAnalysisResultDto() {}

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<Long> getStructureIds() { return structureIds; }
    public void setStructureIds(List<Long> structureIds) { this.structureIds = structureIds; }
    public double[][] getRmsdMatrix() { return rmsdMatrix; }
    public void setRmsdMatrix(double[][] rmsdMatrix) { this.rmsdMatrix = rmsdMatrix; }
    public List<String> getStructureNames() { return structureNames; }
    public void setStructureNames(List<String> structureNames) { this.structureNames = structureNames; }
    public List<DisulfideBond> getDisulfideBonds() { return disulfideBonds; }
    public void setDisulfideBonds(List<DisulfideBond> disulfideBonds) { this.disulfideBonds = disulfideBonds; }
    public List<GlycosylationSite> getGlycosylationSites() { return glycosylationSites; }
    public void setGlycosylationSites(List<GlycosylationSite> glycosylationSites) { this.glycosylationSites = glycosylationSites; }
    public Map<Long, BFactorStats> getBfactorStats() { return bfactorStats; }
    public void setBfactorStats(Map<Long, BFactorStats> bfactorStats) { this.bfactorStats = bfactorStats; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final BatchAnalysisResultDto r = new BatchAnalysisResultDto();
        public Builder taskId(String v) { r.taskId = v; return this; }
        public Builder status(String v) { r.status = v; return this; }
        public Builder structureIds(List<Long> v) { r.structureIds = v; return this; }
        public Builder rmsdMatrix(double[][] v) { r.rmsdMatrix = v; return this; }
        public Builder structureNames(List<String> v) { r.structureNames = v; return this; }
        public Builder disulfideBonds(List<DisulfideBond> v) { r.disulfideBonds = v; return this; }
        public Builder glycosylationSites(List<GlycosylationSite> v) { r.glycosylationSites = v; return this; }
        public Builder bfactorStats(Map<Long, BFactorStats> v) { r.bfactorStats = v; return this; }
        public BatchAnalysisResultDto build() { return r; }
    }

    public static class DisulfideBond {
        private Long structureId;
        private String chain1;
        private int resSeq1;
        private String chain2;
        private int resSeq2;
        private double distance;

        public DisulfideBond() {}

        public Long getStructureId() { return structureId; }
        public void setStructureId(Long structureId) { this.structureId = structureId; }
        public String getChain1() { return chain1; }
        public void setChain1(String chain1) { this.chain1 = chain1; }
        public int getResSeq1() { return resSeq1; }
        public void setResSeq1(int resSeq1) { this.resSeq1 = resSeq1; }
        public String getChain2() { return chain2; }
        public void setChain2(String chain2) { this.chain2 = chain2; }
        public int getResSeq2() { return resSeq2; }
        public void setResSeq2(int resSeq2) { this.resSeq2 = resSeq2; }
        public double getDistance() { return distance; }
        public void setDistance(double distance) { this.distance = distance; }

        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private final DisulfideBond r = new DisulfideBond();
            public Builder structureId(Long v) { r.structureId = v; return this; }
            public Builder chain1(String v) { r.chain1 = v; return this; }
            public Builder resSeq1(int v) { r.resSeq1 = v; return this; }
            public Builder chain2(String v) { r.chain2 = v; return this; }
            public Builder resSeq2(int v) { r.resSeq2 = v; return this; }
            public Builder distance(double v) { r.distance = v; return this; }
            public DisulfideBond build() { return r; }
        }
    }

    public static class GlycosylationSite {
        private Long structureId;
        private String chain;
        private int resSeq;
        private String residueName;
        private String type;
        private double confidence;

        public GlycosylationSite() {}

        public Long getStructureId() { return structureId; }
        public void setStructureId(Long structureId) { this.structureId = structureId; }
        public String getChain() { return chain; }
        public void setChain(String chain) { this.chain = chain; }
        public int getResSeq() { return resSeq; }
        public void setResSeq(int resSeq) { this.resSeq = resSeq; }
        public String getResidueName() { return residueName; }
        public void setResidueName(String residueName) { this.residueName = residueName; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private final GlycosylationSite r = new GlycosylationSite();
            public Builder structureId(Long v) { r.structureId = v; return this; }
            public Builder chain(String v) { r.chain = v; return this; }
            public Builder resSeq(int v) { r.resSeq = v; return this; }
            public Builder residueName(String v) { r.residueName = v; return this; }
            public Builder type(String v) { r.type = v; return this; }
            public Builder confidence(double v) { r.confidence = v; return this; }
            public GlycosylationSite build() { return r; }
        }
    }

    public static class BFactorStats {
        private Long structureId;
        private double mean;
        private double stdDev;
        private double min;
        private double max;
        private double median;

        public BFactorStats() {}

        public Long getStructureId() { return structureId; }
        public void setStructureId(Long structureId) { this.structureId = structureId; }
        public double getMean() { return mean; }
        public void setMean(double mean) { this.mean = mean; }
        public double getStdDev() { return stdDev; }
        public void setStdDev(double stdDev) { this.stdDev = stdDev; }
        public double getMin() { return min; }
        public void setMin(double min) { this.min = min; }
        public double getMax() { return max; }
        public void setMax(double max) { this.max = max; }
        public double getMedian() { return median; }
        public void setMedian(double median) { this.median = median; }

        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private final BFactorStats r = new BFactorStats();
            public Builder structureId(Long v) { r.structureId = v; return this; }
            public Builder mean(double v) { r.mean = v; return this; }
            public Builder stdDev(double v) { r.stdDev = v; return this; }
            public Builder min(double v) { r.min = v; return this; }
            public Builder max(double v) { r.max = v; return this; }
            public Builder median(double v) { r.median = v; return this; }
            public BFactorStats build() { return r; }
        }
    }
}
