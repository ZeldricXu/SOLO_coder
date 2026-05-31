package com.datastandard.modules.profiling;

import com.datastandard.modules.profiling.dto.FlameGraphDiff;
import com.datastandard.modules.profiling.dto.ProfilingReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfilingResultExporter {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Value("${profiling.output.directory:/tmp/profiling}")
    private String outputDirectory;

    @Value("${profiling.export.formats:json,html,txt}")
    private List<String> exportFormats;

    private final ObjectMapper objectMapper;

    public void exportReport(ProfilingReport report) throws IOException {
        ensureOutputDirectory();

        for (String format : exportFormats) {
            switch (format.trim().toLowerCase()) {
                case "json":
                    exportJson(report);
                    break;
                case "html":
                    exportHtml(report);
                    break;
                case "txt":
                    exportText(report);
                    break;
                default:
                    log.warn("Unsupported export format: {}", format);
            }
        }

        log.info("Profiling report exported: {}", report.getSessionId());
    }

    public void exportDiff(FlameGraphDiff diff, String sessionId) throws IOException {
        ensureOutputDirectory();
        String fileName = sessionId + "_diff";

        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        String json = objectMapper.writeValueAsString(diff);
        Path jsonPath = Paths.get(outputDirectory, fileName + ".json");
        Files.writeString(jsonPath, json, StandardCharsets.UTF_8);
        log.info("Flame graph diff exported to: {}", jsonPath);
    }

    private void exportJson(ProfilingReport report) throws IOException {
        String fileName = report.getSessionId() + "_report.json";
        Path path = Paths.get(outputDirectory, fileName);

        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        String json = objectMapper.writeValueAsString(report);
        Files.writeString(path, json, StandardCharsets.UTF_8);

        log.info("JSON report exported to: {}", path);
    }

    private void exportHtml(ProfilingReport report) throws IOException {
        String fileName = report.getSessionId() + "_report.html";
        Path path = Paths.get(outputDirectory, fileName);

        String html = generateHtmlReport(report);
        Files.writeString(path, html, StandardCharsets.UTF_8);

        log.info("HTML report exported to: {}", path);
    }

    private void exportText(ProfilingReport report) throws IOException {
        String fileName = report.getSessionId() + "_report.txt";
        Path path = Paths.get(outputDirectory, fileName);

        String text = generateTextReport(report);
        Files.writeString(path, text, StandardCharsets.UTF_8);

        log.info("Text report exported to: {}", path);
    }

    private String generateHtmlReport(ProfilingReport report) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head>\n");
        html.append("<title>Profiling Report - ").append(report.getSessionName()).append("</title>\n");
        html.append("<style>").append(getCss()).append("</style>\n");
        html.append("</head>\n<body>\n");

        html.append("<div class=\"container\">\n");
        html.append("<h1>Profiling Report</h1>\n");

        html.append("<div class=\"section\">\n");
        html.append("<h2>Session Info</h2>\n");
        html.append("<table class=\"info-table\">\n");
        html.append("<tr><th>Session ID</th><td>").append(report.getSessionId()).append("</td></tr>\n");
        html.append("<tr><th>Name</th><td>").append(report.getSessionName()).append("</td></tr>\n");
        html.append("<tr><th>Description</th><td>").append(report.getDescription()).append("</td></tr>\n");
        html.append("<tr><th>Start Time</th><td>").append(formatInstant(report.getStartTime())).append("</td></tr>\n");
        html.append("<tr><th>End Time</th><td>").append(formatInstant(report.getEndTime())).append("</td></tr>\n");
        html.append("<tr><th>Duration</th><td>").append(report.getActualDuration().toMillis()).append(" ms</td></tr>\n");
        html.append("<tr><th>JVM Version</th><td>").append(report.getJvmVersion()).append("</td></tr>\n");
        html.append("</table>\n</div>\n");

        if (report.getCpuReport() != null) {
            html.append("<div class=\"section\">\n");
            html.append("<h2>CPU Report</h2>\n");
            html.append("<div class=\"metrics-grid\">\n");
            html.append("<div class=\"metric\"><span class=\"label\">Avg CPU</span><span class=\"value\">")
                    .append(String.format("%.2f%%", report.getCpuReport().getAverageCpuUsage())).append("</span></div>\n");
            html.append("<div class=\"metric\"><span class=\"label\">Max CPU</span><span class=\"value\">")
                    .append(String.format("%.2f%%", report.getCpuReport().getMaxCpuUsage())).append("</span></div>\n");
            html.append("<div class=\"metric\"><span class=\"label\">P95 CPU</span><span class=\"value\">")
                    .append(String.format("%.2f%%", report.getCpuReport().getP95CpuUsage())).append("</span></div>\n");
            html.append("<div class=\"metric\"><span class=\"label\">Total Samples</span><span class=\"value\">")
                    .append(report.getCpuReport().getTotalSamples()).append("</span></div>\n");
            html.append("</div>\n");

            if (report.getCpuReport().getHotMethods() != null && !report.getCpuReport().getHotMethods().isEmpty()) {
                html.append("<h3>Top Hot Methods</h3>\n");
                html.append("<table class=\"data-table\">\n");
                html.append("<tr><th>Method</th><th>Package</th><th>Samples</th><th>%</th></tr>\n");
                for (ProfilingReport.HotMethod method : report.getCpuReport().getHotMethods()) {
                    html.append("<tr><td>").append(method.getClassName()).append(".").append(method.getMethodName())
                            .append("</td><td>").append(method.getPackageName())
                            .append("</td><td>").append(method.getSamples())
                            .append("</td><td>").append(String.format("%.2f%%", method.getPercentage()))
                            .append("</td></tr>\n");
                }
                html.append("</table>\n");
            }
            html.append("</div>\n");
        }

        if (report.getMemoryReport() != null) {
            html.append("<div class=\"section\">\n");
            html.append("<h2>Memory Report</h2>\n");
            html.append("<div class=\"metrics-grid\">\n");
            html.append("<div class=\"metric\"><span class=\"label\">Avg Heap</span><span class=\"value\">")
                    .append(String.format("%.2f%%", report.getMemoryReport().getAverageHeapUsage())).append("</span></div>\n");
            html.append("<div class=\"metric\"><span class=\"label\">Max Heap</span><span class=\"value\">")
                    .append(String.format("%.2f%%", report.getMemoryReport().getMaxHeapUsage())).append("</span></div>\n");
            html.append("<div class=\"metric\"><span class=\"label\">GC Count</span><span class=\"value\">")
                    .append(report.getMemoryReport().getGcCount()).append("</span></div>\n");
            html.append("<div class=\"metric\"><span class=\"label\">GC Throughput</span><span class=\"value\">")
                    .append(String.format("%.2f%%", report.getMemoryReport().getGcThroughput())).append("</span></div>\n");
            html.append("</div>\n");
            html.append("</div>\n");
        }

        if (report.getRecommendations() != null && !report.getRecommendations().isEmpty()) {
            html.append("<div class=\"section\">\n");
            html.append("<h2>Recommendations</h2>\n");
            html.append("<ul class=\"recommendations\">\n");
            for (String rec : report.getRecommendations()) {
                html.append("<li>").append(rec).append("</li>\n");
            }
            html.append("</ul>\n");
            html.append("</div>\n");
        }

        if (report.getFlameGraphReport() != null && report.getFlameGraphReport().getSvgContent() != null) {
            html.append("<div class=\"section\">\n");
            html.append("<h2>Flame Graph</h2>\n");
            html.append(report.getFlameGraphReport().getSvgContent());
            html.append("</div>\n");
        }

        html.append("</div>\n</body>\n</html>");
        return html.toString();
    }

    private String generateTextReport(ProfilingReport report) {
        StringBuilder text = new StringBuilder();
        text.append("=".repeat(80)).append("\n");
        text.append("PROFILING REPORT\n");
        text.append("=".repeat(80)).append("\n\n");

        text.append("Session Info:\n");
        text.append("-".repeat(40)).append("\n");
        text.append(String.format("%-20s: %s%n", "Session ID", report.getSessionId()));
        text.append(String.format("%-20s: %s%n", "Name", report.getSessionName()));
        text.append(String.format("%-20s: %s%n", "Start Time", formatInstant(report.getStartTime())));
        text.append(String.format("%-20s: %s%n", "Duration", report.getActualDuration().toMillis() + " ms"));
        text.append("\n");

        if (report.getCpuReport() != null) {
            text.append("CPU Report:\n");
            text.append("-".repeat(40)).append("\n");
            text.append(String.format("%-20s: %.2f%%%n", "Average CPU", report.getCpuReport().getAverageCpuUsage()));
            text.append(String.format("%-20s: %.2f%%%n", "Max CPU", report.getCpuReport().getMaxCpuUsage()));
            text.append(String.format("%-20s: %d%n", "Total Samples", report.getCpuReport().getTotalSamples()));
            text.append("\nTop Hot Methods:\n");
            for (ProfilingReport.HotMethod method : report.getCpuReport().getHotMethods()) {
                text.append(String.format("  %6.2f%% %s.%s%n",
                        method.getPercentage(), method.getClassName(), method.getMethodName()));
            }
            text.append("\n");
        }

        if (report.getMemoryReport() != null) {
            text.append("Memory Report:\n");
            text.append("-".repeat(40)).append("\n");
            text.append(String.format("%-20s: %.2f%%%n", "Average Heap", report.getMemoryReport().getAverageHeapUsage()));
            text.append(String.format("%-20s: %d%n", "GC Count", report.getMemoryReport().getGcCount()));
            text.append(String.format("%-20s: %.2f%%%n", "GC Throughput", report.getMemoryReport().getGcThroughput()));
            text.append("\n");
        }

        if (report.getRecommendations() != null && !report.getRecommendations().isEmpty()) {
            text.append("Recommendations:\n");
            text.append("-".repeat(40)).append("\n");
            for (String rec : report.getRecommendations()) {
                text.append("  - ").append(rec).append("\n");
            }
        }

        return text.toString();
    }

    private String getCss() {
        return "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; " +
                "margin: 0; padding: 20px; background: #f5f5f5; }\n" +
                ".container { max-width: 1400px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; " +
                "box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n" +
                "h1 { color: #333; border-bottom: 3px solid #007bff; padding-bottom: 10px; }\n" +
                "h2 { color: #444; margin-top: 30px; }\n" +
                "h3 { color: #555; margin-top: 20px; }\n" +
                ".section { margin-bottom: 30px; }\n" +
                ".metrics-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 15px; margin: 15px 0; }\n" +
                ".metric { background: #f8f9fa; padding: 15px; border-radius: 6px; text-align: center; }\n" +
                ".metric .label { display: block; font-size: 12px; color: #666; margin-bottom: 5px; }\n" +
                ".metric .value { font-size: 24px; font-weight: bold; color: #007bff; }\n" +
                "table { width: 100%; border-collapse: collapse; margin: 15px 0; }\n" +
                "th, td { padding: 10px; text-align: left; border-bottom: 1px solid #ddd; }\n" +
                "th { background: #f8f9fa; font-weight: 600; }\n" +
                ".info-table { max-width: 500px; }\n" +
                ".recommendations li { margin: 8px 0; line-height: 1.6; }\n" +
                "svg { width: 100%; height: auto; }";
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "N/A";
        }
        return DATE_FORMATTER.format(instant);
    }

    private void ensureOutputDirectory() throws IOException {
        Path path = Paths.get(outputDirectory);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }
}
