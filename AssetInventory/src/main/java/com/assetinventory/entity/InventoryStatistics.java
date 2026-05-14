package com.assetinventory.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "inventory_statistics")
public class InventoryStatistics {

    @Id
    @Column(name = "stat_id", nullable = false, length = 50)
    private String statId;

    @Column(name = "stat_month", nullable = false, length = 20)
    private String statMonth;

    @Column(name = "task_count", nullable = false)
    private int taskCount;

    @Column(name = "count_count", nullable = false)
    private int countCount;

    @Column(name = "diff_count", nullable = false)
    private int diffCount;

    @Column(name = "processed_diff_count", nullable = false)
    private int processedDiffCount;

    @Column(name = "accuracy_rate", nullable = false)
    private double accuracyRate;

    public InventoryStatistics() {
    }

    public String getStatId() {
        return statId;
    }

    public void setStatId(String statId) {
        this.statId = statId;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public int getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(int taskCount) {
        this.taskCount = taskCount;
    }

    public int getCountCount() {
        return countCount;
    }

    public void setCountCount(int countCount) {
        this.countCount = countCount;
    }

    public int getDiffCount() {
        return diffCount;
    }

    public void setDiffCount(int diffCount) {
        this.diffCount = diffCount;
    }

    public int getProcessedDiffCount() {
        return processedDiffCount;
    }

    public void setProcessedDiffCount(int processedDiffCount) {
        this.processedDiffCount = processedDiffCount;
    }

    public double getAccuracyRate() {
        return accuracyRate;
    }

    public void setAccuracyRate(double accuracyRate) {
        this.accuracyRate = accuracyRate;
    }
}
