package com.datateam.loganalyzer.report;

import java.util.ArrayList;
import java.util.List;

public class AsciiChartRenderer {

    private static final char[] BAR_CHARS = {' ', '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
    private static final String[] BLOCK_CHARS = {"  ", "░░", "▒▒", "▓▓", "██"};

    public String renderBarChart(List<Double> values, int height, int width) {
        return renderBarChart(values, height, width, true);
    }

    public String renderBarChart(List<Double> values, int height, int width, boolean showLabels) {
        if (values == null || values.isEmpty()) {
            return "No data to display";
        }

        StringBuilder sb = new StringBuilder();

        double max = values.stream().mapToDouble(v -> v != null ? v : 0).max().orElse(1.0);
        double min = values.stream().mapToDouble(v -> v != null ? v : 0).min().orElse(0.0);
        double range = max - min;
        if (range == 0) range = 1;

        int dataWidth = Math.min(values.size(), width);
        List<Double> displayValues = values.size() > dataWidth ?
            downsample(values, dataWidth) : values;

        int[][] grid = new int[height][displayValues.size()];

        for (int i = 0; i < displayValues.size(); i++) {
            Double v = displayValues.get(i);
            if (v == null) v = 0.0;
            double normalized = (v - min) / range;
            int barHeight = (int) Math.round(normalized * (height - 1));
            for (int h = 0; h < height; h++) {
                int level = height - 1 - h;
                if (barHeight >= level) {
                    grid[h][i] = getBlockIndex(normalized, h, height, barHeight);
                } else {
                    grid[h][i] = 0;
                }
            }
        }

        if (showLabels) {
            for (int h = 0; h < height; h++) {
                double valueAtRow = max - (range * h / (height - 1));
                sb.append(String.format("%8.1f │", valueAtRow));
                for (int w = 0; w < displayValues.size(); w++) {
                    sb.append(BAR_CHARS[grid[h][w]]);
                }
                sb.append("\n");
            }
            sb.append("         └");
            for (int w = 0; w < displayValues.size(); w++) {
                sb.append("─");
            }
            sb.append("\n");
        } else {
            for (int h = 0; h < height; h++) {
                for (int w = 0; w < displayValues.size(); w++) {
                    sb.append(BAR_CHARS[grid[h][w]]);
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private int getBlockIndex(double normalized, int row, int height, int barHeight) {
        int barLevel = height - 1 - row;
        if (barLevel > barHeight) return 0;
        if (barLevel == barHeight && barHeight < height - 1) {
            double frac = (normalized * height) % 1.0;
            if (frac < 0.125) return 0;
            if (frac < 0.25) return 1;
            if (frac < 0.375) return 2;
            if (frac < 0.5) return 3;
            if (frac < 0.625) return 4;
            if (frac < 0.75) return 5;
            if (frac < 0.875) return 6;
            return 7;
        }
        return 8;
    }

    public String renderHorizontalBarChart(String label, double value, double maxValue, int width) {
        return renderHorizontalBarChart(label, value, maxValue, width, false);
    }

    public String renderHorizontalBarChart(String label, double value, double maxValue, int width, boolean showValue) {
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("%-20s ", label));

        if (maxValue > 0) {
            double ratio = value / maxValue;
            int filled = (int) Math.round(ratio * width);
            filled = Math.min(filled, width);

            for (int i = 0; i < filled; i++) {
                if (ratio > 0.9) {
                    sb.append(BLOCK_CHARS[4]);
                } else if (ratio > 0.7) {
                    sb.append(BLOCK_CHARS[3]);
                } else if (ratio > 0.5) {
                    sb.append(BLOCK_CHARS[2]);
                } else if (ratio > 0.25) {
                    sb.append(BLOCK_CHARS[1]);
                } else {
                    sb.append(BLOCK_CHARS[0]);
                }
            }
            for (int i = filled; i < width; i++) {
                sb.append("  ");
            }
        }

        if (showValue) {
            sb.append(String.format("  %.0f", value));
        }

        return sb.toString();
    }

    public String renderSparkline(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        double max = values.stream().mapToDouble(v -> v != null ? v : 0).max().orElse(1.0);
        double min = values.stream().mapToDouble(v -> v != null ? v : 0).min().orElse(0.0);
        double range = max - min;
        if (range == 0) range = 1;

        for (Double v : values) {
            if (v == null) v = 0.0;
            double normalized = (v - min) / range;
            int idx = (int) Math.round(normalized * (BAR_CHARS.length - 2)) + 1;
            idx = Math.max(1, Math.min(idx, BAR_CHARS.length - 1));
            sb.append(BAR_CHARS[idx]);
        }

        return sb.toString();
    }

    private List<Double> downsample(List<Double> values, int targetSize) {
        List<Double> result = new ArrayList<>();
        int bucketSize = values.size() / targetSize;

        for (int i = 0; i < targetSize; i++) {
            int start = i * bucketSize;
            int end = Math.min(start + bucketSize, values.size());
            double sum = 0;
            int count = 0;
            for (int j = start; j < end; j++) {
                Double v = values.get(j);
                if (v != null) {
                    sum += v;
                    count++;
                }
            }
            result.add(count > 0 ? sum / count : 0);
        }

        return result;
    }

    public String renderTable(String[] headers, List<String[]> rows) {
        return renderTable(headers, rows, true);
    }

    public String renderTable(String[] headers, List<String[]> rows, boolean showBorder) {
        if (headers == null || headers.length == 0) {
            return "";
        }

        int[] colWidths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            colWidths[i] = headers[i].length();
        }

        if (rows != null) {
            for (String[] row : rows) {
                for (int i = 0; i < Math.min(row.length, headers.length); i++) {
                    if (row[i] != null && row[i].length() > colWidths[i]) {
                        colWidths[i] = row[i].length();
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();

        if (showBorder) {
            sb.append("+");
            for (int w : colWidths) {
                for (int i = 0; i < w + 2; i++) sb.append("-");
                sb.append("+");
            }
            sb.append("\n");
        }

        sb.append("|");
        for (int i = 0; i < headers.length; i++) {
            sb.append(String.format(" %-" + colWidths[i] + "s |", headers[i]));
        }
        sb.append("\n");

        if (showBorder) {
            sb.append("+");
            for (int w : colWidths) {
                for (int i = 0; i < w + 2; i++) sb.append("=");
                sb.append("+");
            }
            sb.append("\n");
        }

        if (rows != null) {
            for (String[] row : rows) {
                sb.append("|");
                for (int i = 0; i < headers.length; i++) {
                    String val = i < row.length && row[i] != null ? row[i] : "";
                    sb.append(String.format(" %-" + colWidths[i] + "s |", val));
                }
                sb.append("\n");
            }
        }

        if (showBorder) {
            sb.append("+");
            for (int w : colWidths) {
                for (int i = 0; i < w + 2; i++) sb.append("-");
                sb.append("+");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
