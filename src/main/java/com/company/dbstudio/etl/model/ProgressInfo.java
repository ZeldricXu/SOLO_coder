package com.company.dbstudio.etl.model;

import java.time.LocalDateTime;

public class ProgressInfo {

    private final long currentRow;
    private final long totalRows;
    private final String message;
    private final LocalDateTime timestamp;
    private final int progressPercent;

    public ProgressInfo(long currentRow, long totalRows, String message) {
        this.currentRow = currentRow;
        this.totalRows = totalRows;
        this.message = message;
        this.timestamp = LocalDateTime.now();
        this.progressPercent = totalRows > 0 ? (int) ((currentRow * 100) / totalRows) : 0;
    }

    public ProgressInfo(int progressPercent, long currentRow, String message) {
        this.currentRow = currentRow;
        this.totalRows = -1;
        this.message = message;
        this.timestamp = LocalDateTime.now();
        this.progressPercent = progressPercent;
    }

    public long getCurrentRow() {
        return currentRow;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public boolean isComplete() {
        return totalRows > 0 && currentRow >= totalRows;
    }

    public String getProgressText() {
        if (totalRows > 0) {
            return String.format("%d/%d (%.1f%%)", currentRow, totalRows, (double) progressPercent);
        }
        return String.format("%d rows", currentRow);
    }

    @Override
    public String toString() {
        return "ProgressInfo{" +
                "currentRow=" + currentRow +
                ", totalRows=" + totalRows +
                ", progressPercent=" + progressPercent +
                ", message='" + message + '\'' +
                '}';
    }
}
