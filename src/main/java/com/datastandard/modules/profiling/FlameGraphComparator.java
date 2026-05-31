package com.datastandard.modules.profiling;

import com.datastandard.modules.profiling.dto.FlameGraphDiff;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class FlameGraphComparator {

    private static final Pattern FRAME_PATTERN = Pattern.compile(
            "<title>([^(]+)\\s+\\((\\d+)\\s+samples,\\s+([^%]+)%\\)</title>");

    public FlameGraphDiff compare(String baseSessionId, String baseSvg,
                                  String targetSessionId, String targetSvg) {
        Map<String, FrameData> baseFrames = parseFrames(baseSvg);
        Map<String, FrameData> targetFrames = parseFrames(targetSvg);

        Set<String> allFrames = new HashSet<>();
        allFrames.addAll(baseFrames.keySet());
        allFrames.addAll(targetFrames.keySet());

        int baseTotal = baseFrames.values().stream().mapToInt(f -> f.samples).sum();
        int targetTotal = targetFrames.values().stream().mapToInt(f -> f.samples).sum();

        List<FlameGraphDiff.FrameDiff> frameDiffs = new ArrayList<>();

        for (String frameName : allFrames) {
            FrameData base = baseFrames.getOrDefault(frameName, new FrameData(0, 0));
            FrameData target = targetFrames.getOrDefault(frameName, new FrameData(0, 0));

            double basePercent = baseTotal > 0 ? (base.samples * 100.0 / baseTotal) : 0;
            double targetPercent = targetTotal > 0 ? (target.samples * 100.0 / targetTotal) : 0;

            FlameGraphDiff.FrameDiff diff = FlameGraphDiff.FrameDiff.builder()
                    .frameName(frameName)
                    .basePercentage(basePercent)
                    .targetPercentage(targetPercent)
                    .percentageDiff(targetPercent - basePercent)
                    .absoluteDiff(Math.abs(targetPercent - basePercent))
                    .baseSamples(base.samples)
                    .targetSamples(target.samples)
                    .samplesDiff(target.samples - base.samples)
                    .changeType(determineChangeType(base, target))
                    .category(categorizeFrame(frameName))
                    .build();

            frameDiffs.add(diff);
        }

        frameDiffs.sort((a, b) -> Double.compare(b.getAbsoluteDiff(), a.getAbsoluteDiff()));

        FlameGraphDiff.Summary summary = buildSummary(frameDiffs);

        return FlameGraphDiff.builder()
                .baseSessionId(baseSessionId)
                .targetSessionId(targetSessionId)
                .diffType("CPU")
                .createdAt(Instant.now())
                .frameDiffs(frameDiffs)
                .summary(summary)
                .build();
    }

    private Map<String, FrameData> parseFrames(String svgContent) {
        Map<String, FrameData> frames = new HashMap<>();
        if (svgContent == null) {
            return frames;
        }

        Matcher matcher = FRAME_PATTERN.matcher(svgContent);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            int samples = Integer.parseInt(matcher.group(2));
            double percent = Double.parseDouble(matcher.group(3).trim());
            frames.put(name, new FrameData(samples, percent));
        }

        return frames;
    }

    private String determineChangeType(FrameData base, FrameData target) {
        if (base.samples == 0 && target.samples > 0) {
            return "NEW";
        }
        if (base.samples > 0 && target.samples == 0) {
            return "REMOVED";
        }
        double ratio = base.samples > 0 ? (target.samples * 1.0 / base.samples) : 0;
        if (ratio > 1.5) {
            return "INCREASED";
        }
        if (ratio < 0.5) {
            return "DECREASED";
        }
        return "UNCHANGED";
    }

    private String categorizeFrame(String frameName) {
        if (frameName.contains("com.datastandard")) {
            return "APPLICATION";
        } else if (frameName.contains("org.springframework")) {
            return "FRAMEWORK_SPRING";
        } else if (frameName.contains("io.netty")) {
            return "FRAMEWORK_NETTY";
        } else if (frameName.contains("java.") || frameName.contains("javax.")) {
            return "JDK";
        } else if (frameName.contains("sun.misc") || frameName.contains("jdk.internal")) {
            return "JVM_INTERNAL";
        } else if (frameName.contains("com.baomidou") || frameName.contains("org.apache.ibatis")) {
            return "DATABASE";
        } else if (frameName.contains("com.fasterxml")) {
            return "SERIALIZATION";
        } else {
            return "OTHER";
        }
    }

    private FlameGraphDiff.Summary buildSummary(List<FlameGraphDiff.FrameDiff> diffs) {
        int increased = 0, decreased = 0, newFrames = 0, removed = 0, unchanged = 0;
        double maxIncrease = 0, maxDecrease = 0, totalChange = 0;

        Map<String, Integer> changesByCategory = new HashMap<>();

        for (FlameGraphDiff.FrameDiff diff : diffs) {
            String changeType = diff.getChangeType();
            switch (changeType) {
                case "INCREASED":
                    increased++;
                    maxIncrease = Math.max(maxIncrease, diff.getPercentageDiff());
                    break;
                case "DECREASED":
                    decreased++;
                    maxDecrease = Math.max(maxDecrease, Math.abs(diff.getPercentageDiff()));
                    break;
                case "NEW":
                    newFrames++;
                    break;
                case "REMOVED":
                    removed++;
                    break;
                default:
                    unchanged++;
            }
            totalChange += Math.abs(diff.getPercentageDiff());
            changesByCategory.merge(diff.getCategory(), 1, Integer::sum);
        }

        return FlameGraphDiff.Summary.builder()
                .totalFrames(diffs.size())
                .increasedFrames(increased)
                .decreasedFrames(decreased)
                .newFrames(newFrames)
                .removedFrames(removed)
                .unchangedFrames(unchanged)
                .maxIncrease(maxIncrease)
                .maxDecrease(maxDecrease)
                .averageChange(diffs.size() > 0 ? totalChange / diffs.size() : 0)
                .changesByCategory(changesByCategory)
                .build();
    }

    public List<String> generateRecommendations(FlameGraphDiff diff) {
        List<String> recommendations = new ArrayList<>();

        FlameGraphDiff.Summary summary = diff.getSummary();

        if (summary.getMaxIncrease() > 20) {
            recommendations.add("检测到性能显著下降 (>20%)，建议审查以下热点方法的变更:");
            diff.getFrameDiffs().stream()
                    .filter(f -> "INCREASED".equals(f.getChangeType()) && f.getPercentageDiff() > 10)
                    .limit(5)
                    .forEach(f -> recommendations.add("  - " + f.getFrameName() +
                            " (+)" + String.format("%.1f", f.getPercentageDiff()) + "%)"));
        }

        if (summary.getNewFrames() > 10) {
            recommendations.add("新增了 " + summary.getNewFrames() + " 个调用栈帧，建议检查是否引入了新的计算密集型逻辑");
        }

        List<FlameGraphDiff.FrameDiff> appIncreases = diff.getFrameDiffs().stream()
                .filter(f -> "APPLICATION".equals(f.getCategory()) && "INCREASED".equals(f.getChangeType()))
                .limit(3)
                .toList();

        if (!appIncreases.isEmpty()) {
            recommendations.add("应用代码中以下方法CPU占用显著增加，建议优化:");
            appIncreases.forEach(f -> recommendations.add("  - " + f.getFrameName() +
                    " (+)" + String.format("%.1f", f.getPercentageDiff()) + "%)"));
        }

        if (summary.getMaxDecrease() > 10) {
            recommendations.add("好消息: 以下方法的CPU占用显著降低，性能有所改善:");
            diff.getFrameDiffs().stream()
                    .filter(f -> "DECREASED".equals(f.getChangeType()) && f.getPercentageDiff() < -10)
                    .limit(3)
                    .forEach(f -> recommendations.add("  - " + f.getFrameName() +
                            " (" + String.format("%.1f", f.getPercentageDiff()) + "%)"));
        }

        return recommendations;
    }

    private static class FrameData {
        final int samples;
        final double percentage;

        FrameData(int samples, double percentage) {
            this.samples = samples;
            this.percentage = percentage;
        }
    }
}
