package com.datastandard.modules.profiling;

import com.datastandard.modules.profiling.dto.ProfilingReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class FlameGraphGenerator {

    @Value("${profiling.output.directory:/tmp/profiling}")
    private String outputDirectory;

    private final AsyncProfilerBridge asyncProfilerBridge;

    public ProfilingReport.FlameGraphReport generateFromJfr(String jfrFile, String sessionId) throws IOException {
        ensureOutputDirectory();
        String outputFile = Paths.get(outputDirectory, sessionId + "_flame.svg").toString();

        asyncProfilerBridge.generateFlameGraph(jfrFile, outputFile, "cpu");

        String svgContent = asyncProfilerBridge.readFlameGraphSvg(outputFile);

        return ProfilingReport.FlameGraphReport.builder()
                .filePath(outputFile)
                .svgContent(svgContent)
                .totalFrames(countFrames(svgContent))
                .rootFrame("java.lang.Thread.run")
                .build();
    }

    public ProfilingReport.FlameGraphReport generateFromStackTraces(
            Map<Long, StackTraceElement[]> stackTraces, String sessionId) throws IOException {
        ensureOutputDirectory();
        String outputFile = Paths.get(outputDirectory, sessionId + "_flame.svg").toString();

        Map<String, Integer> frameCounts = new HashMap<>();
        for (StackTraceElement[] stackTrace : stackTraces.values()) {
            StringBuilder key = new StringBuilder();
            for (int i = stackTrace.length - 1; i >= 0; i--) {
                StackTraceElement element = stackTrace[i];
                if (key.length() > 0) {
                    key.append(";");
                }
                key.append(element.getClassName()).append(".").append(element.getMethodName());
            }
            if (key.length() > 0) {
                frameCounts.merge(key.toString(), 1, Integer::sum);
            }
        }

        String svgContent = generateSvg(frameCounts);
        Files.writeString(Paths.get(outputFile), svgContent);

        return ProfilingReport.FlameGraphReport.builder()
                .filePath(outputFile)
                .svgContent(svgContent)
                .totalFrames(frameCounts.size())
                .totalSamples(frameCounts.values().stream().mapToInt(Integer::intValue).sum())
                .rootFrame(frameCounts.keySet().stream()
                        .min((a, b) -> Integer.compare(a.split(";").length, b.split(";").length))
                        .orElse("root"))
                .build();
    }

    private String generateSvg(Map<String, Integer> frameCounts) {
        int width = 1200;
        int rowHeight = 16;
        int totalSamples = frameCounts.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, Integer> prefixCounts = new HashMap<>();
        List<String> sortedKeys = new ArrayList<>(frameCounts.keySet());
        Collections.sort(sortedKeys);

        Map<String, Double> prefixX = new HashMap<>();
        for (String key : sortedKeys) {
            String[] parts = key.split(";");
            int count = frameCounts.get(key);
            double frameWidth = (count * 1.0 / totalSamples) * width;

            for (int i = 0; i < parts.length; i++) {
                String prefix = String.join(";", Arrays.copyOfRange(parts, 0, i + 1));
                double x = prefixX.getOrDefault(prefix, 0.0);
                prefixX.merge(prefix, frameWidth, Double::sum);
                prefixCounts.merge(prefix, count, Integer::sum);
            }
        }

        Map<String, Double> prefixCurrentX = new HashMap<>();
        List<Frame> frames = new ArrayList<>();
        Set<String> addedFrames = new HashSet<>();

        for (String key : sortedKeys) {
            String[] parts = key.split(";");
            int count = frameCounts.get(key);
            double frameWidth = (count * 1.0 / totalSamples) * width;

            for (int i = 0; i < parts.length; i++) {
                String prefix = String.join(";", Arrays.copyOfRange(parts, 0, i + 1));
                String parentPrefix = i > 0 ? String.join(";", Arrays.copyOfRange(parts, 0, i)) : "";

                double x;
                if (i == 0) {
                    x = prefixCurrentX.getOrDefault("", 0.0);
                } else {
                    x = prefixCurrentX.getOrDefault(parentPrefix, 0.0);
                }

                String frameKey = prefix + "_" + x;
                if (!addedFrames.contains(frameKey)) {
                    int y = (parts.length - 1 - i) * rowHeight;
                    double totalWidth = (prefixCounts.getOrDefault(prefix, count) * 1.0 / totalSamples) * width;
                    frames.add(new Frame(parts[i], x, y, totalWidth, prefixCounts.getOrDefault(prefix, count)));
                    addedFrames.add(frameKey);
                }
            }

            String rootPrefix = String.join(";", Arrays.copyOfRange(parts, 0, 1));
            prefixCurrentX.merge("", frameWidth, Double::sum);
            for (int i = 1; i <= parts.length; i++) {
                String p = String.join(";", Arrays.copyOfRange(parts, 0, i));
                prefixCurrentX.merge(p, frameWidth, Double::sum);
            }
        }

        StringBuilder svg = new StringBuilder();
        svg.append("<?xml version=\"1.0\" standalone=\"no\"?>\n");
        svg.append("<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" ");
        svg.append("\"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n");
        int height = frames.isEmpty() ? 100 : (int) frames.stream().mapToDouble(f -> f.y).max().orElse(0) + rowHeight + 20;
        svg.append("<svg width=\"").append(width).append("\" height=\"").append(height).append("\" ");
        svg.append("xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">\n");
        svg.append("<style>").append(getCss()).append("</style>\n");

        for (Frame frame : frames) {
            if (frame.width < 0.5) continue;
            String color = getColor(frame.name);
            svg.append("<g class=\"frame\">\n");
            svg.append("  <title>").append(escapeXml(frame.name)).append(" (").append(frame.samples)
                    .append(" samples, ").append(String.format("%.2f", frame.width / width * 100)).append("%)</title>\n");
            svg.append("  <rect x=\"").append(String.format("%.2f", frame.x))
                    .append("\" y=\"").append(frame.y)
                    .append("\" width=\"").append(String.format("%.2f", frame.width))
                    .append("\" height=\"").append(rowHeight - 1)
                    .append("\" fill=\"").append(color).append("\" rx=\"2\"/>\n");
            if (frame.width > 30) {
                svg.append("  <text x=\"").append(String.format("%.2f", frame.x + 3))
                        .append("\" y=\"").append(frame.y + 12)
                        .append("\" font-size=\"11\" fill=\"#000\">").append(escapeXml(truncate(frame.name, (int) frame.width / 6))).append("</text>\n");
            }
            svg.append("</g>\n");
        }

        svg.append("</svg>");
        return svg.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    private String getColor(String frameName) {
        if (frameName.contains("java.") || frameName.contains("javax.")) {
            return "#e0d060";
        } else if (frameName.contains("sun.") || frameName.contains("com.sun.")) {
            return "#80c080";
        } else if (frameName.contains("com.datastandard")) {
            return "#e08080";
        } else if (frameName.contains("org.springframework")) {
            return "#8080e0";
        } else {
            int hash = frameName.hashCode();
            int r = 180 + (hash & 0x3F);
            int g = 180 + ((hash >> 8) & 0x3F);
            int b = 200 + ((hash >> 16) & 0x1F);
            return String.format("#%02x%02x%02x", r, g, b);
        }
    }

    private String getCss() {
        return ".frame:hover rect { opacity: 0.8; stroke: #000; stroke-width: 1px; }\n" +
                "text { pointer-events: none; }";
    }

    private String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private int countFrames(String svgContent) {
        if (svgContent == null) {
            return 0;
        }
        return (int) svgContent.chars().filter(ch -> ch == '<').count() / 4;
    }

    private void ensureOutputDirectory() throws IOException {
        Path path = Paths.get(outputDirectory);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    private static class Frame {
        final String name;
        final double x;
        final double y;
        final double width;
        final int samples;

        Frame(String name, double x, double y, double width, int samples) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.width = width;
            this.samples = samples;
        }
    }
}
