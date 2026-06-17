package com.loganalytics.detector.drain;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.detector.config.DetectorConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class PartitionedDrainDetector {
    private final int partitionCount;
    private final DrainTree[] partitions;
    private final DetectorConfig config;

    public PartitionedDrainDetector(DetectorConfig config, int partitionCount) {
        this.partitionCount = partitionCount;
        this.config = config;
        this.partitions = new DrainTree[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            partitions[i] = new DrainTree(config);
        }
    }

    private int getPartition(String serviceName) {
        if (serviceName == null) return 0;
        return Math.abs(serviceName.hashCode()) % partitionCount;
    }

    public LogPattern process(LogEvent event) {
        int partition = getPartition(event.getServiceName());
        return partitions[partition].process(event);
    }

    public LogPattern getPattern(String patternId) {
        for (DrainTree partition : partitions) {
            LogPattern pattern = partition.getPattern(patternId);
            if (pattern != null) {
                return pattern;
            }
        }
        return null;
    }

    public Collection<LogPattern> getAllPatterns() {
        List<LogPattern> all = new ArrayList<>();
        for (DrainTree partition : partitions) {
            all.addAll(partition.getAllPatterns());
        }
        return all;
    }

    public int getPatternCount() {
        int total = 0;
        for (DrainTree partition : partitions) {
            total += partition.getPatternCount();
        }
        return total;
    }

    public List<LogPattern> getTopKPatterns(int k) {
        List<LogPattern> all = new ArrayList<>();
        for (DrainTree partition : partitions) {
            all.addAll(partition.getAllPatterns());
        }
        return all.stream()
                .sorted((a, b) -> Long.compare(b.getTotalCount(), a.getTotalCount()))
                .limit(k)
                .toList();
    }

    public List<LogPattern> getNewPatterns() {
        List<LogPattern> all = new ArrayList<>();
        for (DrainTree partition : partitions) {
            all.addAll(partition.getNewPatterns());
        }
        return all;
    }
}
