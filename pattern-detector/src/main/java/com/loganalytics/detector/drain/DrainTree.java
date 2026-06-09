package com.loganalytics.detector.drain;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.common.util.IdUtils;
import com.loganalytics.detector.config.DetectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class DrainTree {
    private static final Logger log = LoggerFactory.getLogger(DrainTree.class);

    private final DetectorConfig config;
    private final DrainNode root;
    private final Map<String, LogPattern> patternMap;
    private final Cache<String, LogPattern> fastPathCache;
    private final Pattern variablePattern;
    private final Pattern digitPattern;
    private final Pattern hexPattern;
    private final Pattern ipPattern;
    private final Pattern uuidPattern;
    private final Pattern datePattern;

    static class DrainNode {
        final int depth;
        final String token;
        final Map<String, DrainNode> children;
        LogPattern pattern;
        long lastAccessTime;

        DrainNode(int depth, String token) {
            this.depth = depth;
            this.token = token;
            this.children = new ConcurrentHashMap<>();
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    public DrainTree(DetectorConfig config) {
        this.config = config;
        this.root = new DrainNode(0, null);
        this.patternMap = new ConcurrentHashMap<>();
        this.fastPathCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
        this.variablePattern = Pattern.compile("^[<\\[{].*[>\\]}]$|^\\*$|^\\?$");
        this.digitPattern = Pattern.compile("^\\d+$");
        this.hexPattern = Pattern.compile("^[0-9a-fA-F]+$");
        this.ipPattern = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
        this.uuidPattern = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);
        this.datePattern = Pattern.compile("^\\d{4}[-/]\\d{2}[-/]\\d{2}[T ]?\\d{2}:\\d{2}:\\d{2}.*$");
    }

    public LogPattern process(LogEvent event) {
        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            return null;
        }

        LogPattern cached = fastPathCache.getIfPresent(message);
        if (cached != null) {
            cached.incrementCount(event.getServiceName(), event.getLevel());
            return cached;
        }

        List<String> tokens = tokenize(message);
        LogPattern pattern = match(tokens);

        if (pattern == null) {
            pattern = createPattern(tokens, event);
            addToTree(tokens, pattern);
            log.debug("Created new pattern: {} (total: {})", pattern.getTemplate(), patternMap.size());
        } else {
            pattern.incrementCount(event.getServiceName(), event.getLevel());
            updateTree(tokens, pattern);
        }

        fastPathCache.put(message, pattern);

        event.setPatternId(pattern.getId());
        event.setPatternTemplate(pattern.getTemplate());

        return pattern;
    }

    private List<String> tokenize(String message) {
        List<String> tokens = new ArrayList<>();
        String[] rawTokens = message.split("\\s+");

        for (String token : rawTokens) {
            if (token.isEmpty()) continue;

            if (isVariable(token)) {
                tokens.add("<*>");
            } else {
                tokens.add(token);
            }
        }

        int maxTokens = Math.min(tokens.size(), config.getMaxTreeDepth() * 10);
        return tokens.subList(0, maxTokens);
    }

    private boolean isVariable(String token) {
        if (token.length() > 100) return true;
        if (variablePattern.matcher(token).matches()) return true;
        if (digitPattern.matcher(token).matches() && token.length() > 4) return true;
        if (hexPattern.matcher(token).matches() && token.length() > 8) return true;
        if (ipPattern.matcher(token).matches()) return true;
        if (uuidPattern.matcher(token).matches()) return true;
        if (datePattern.matcher(token).matches()) return true;
        return false;
    }

    private LogPattern match(List<String> tokens) {
        if (tokens.isEmpty()) return null;

        DrainNode current = root;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int depth = i + 1;

            DrainNode child = current.children.get(token);

            if (child == null) {
                child = current.children.get("<*>");
            }

            if (child == null) {
                if (depth >= config.getMaxTreeDepth()) {
                    double bestSimilarity = 0;
                    LogPattern bestPattern = null;

                    for (DrainNode node : current.children.values()) {
                        if (node.pattern != null) {
                            double sim = calculateSimilarity(tokens, tokenize(node.pattern.getTemplate()));
                            if (sim > bestSimilarity && sim >= config.getSimilarityThreshold()) {
                                bestSimilarity = sim;
                                bestPattern = node.pattern;
                            }
                        }
                    }

                    return bestPattern;
                }
                return null;
            }

            current = child;
            current.lastAccessTime = System.currentTimeMillis();

            if (current.pattern != null) {
                double sim = calculateSimilarity(tokens, tokenize(current.pattern.getTemplate()));
                if (sim >= config.getSimilarityThreshold()) {
                    return current.pattern;
                }
            }
        }

        return current.pattern;
    }

    private double calculateSimilarity(List<String> tokens1, List<String> tokens2) {
        if (tokens1.size() != tokens2.size()) {
            double ratio = (double) Math.min(tokens1.size(), tokens2.size()) / Math.max(tokens1.size(), tokens2.size());
            if (ratio < 0.8) return 0.0;
        }

        int matches = 0;
        int total = 0;

        int minLen = Math.min(tokens1.size(), tokens2.size());
        for (int i = 0; i < minLen; i++) {
            String t1 = tokens1.get(i);
            String t2 = tokens2.get(i);

            if (t1.equals("<*>") || t2.equals("<*>")) {
                continue;
            }

            total++;
            if (t1.equals(t2)) {
                matches++;
            }
        }

        if (total == 0) return 0.5;
        return (double) matches / total;
    }

    private LogPattern createPattern(List<String> tokens, LogEvent event) {
        String template = String.join(" ", tokens);
        String id = IdUtils.newPatternId();

        LogPattern pattern = new LogPattern(id, template);
        pattern.setSampleLevel(event.getLevel());
        pattern.setSampleService(event.getServiceName());
        pattern.setSimilarityThreshold(config.getSimilarityThreshold());

        List<String> staticTokens = new ArrayList<>();
        List<String> variableSlots = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("<*>")) {
                variableSlots.add("var_" + i);
            } else {
                staticTokens.add(token);
            }
        }
        pattern.setStaticTokens(staticTokens);
        pattern.setVariableSlots(variableSlots);

        patternMap.put(id, pattern);
        return pattern;
    }

    private void addToTree(List<String> tokens, LogPattern pattern) {
        DrainNode current = root;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if (current.children.size() >= config.getMaxChildren() && !current.children.containsKey(token)) {
                token = "<*>";
            }

            DrainNode child = current.children.computeIfAbsent(
                    token, k -> new DrainNode(i + 1, k)
            );
            current = child;
            current.lastAccessTime = System.currentTimeMillis();
        }

        current.pattern = pattern;
    }

    private void updateTree(List<String> tokens, LogPattern pattern) {
        List<String> existingTokens = tokenize(pattern.getTemplate());
        boolean needsUpdate = false;
        List<String> newTokens = new ArrayList<>();

        if (tokens.size() != existingTokens.size()) {
            int minSize = Math.min(tokens.size(), existingTokens.size());
            for (int i = 0; i < minSize; i++) {
                String t1 = tokens.get(i);
                String t2 = existingTokens.get(i);
                if (!t1.equals(t2) && !t1.equals("<*>") && !t2.equals("<*>")) {
                    newTokens.add("<*>");
                    needsUpdate = true;
                } else {
                    newTokens.add(t1.equals("<*>") ? t1 : t2);
                }
            }
            for (int i = minSize; i < Math.max(tokens.size(), existingTokens.size()); i++) {
                newTokens.add("<*>");
                needsUpdate = true;
            }
        } else {
            for (int i = 0; i < tokens.size(); i++) {
                String t1 = tokens.get(i);
                String t2 = existingTokens.get(i);
                if (t1.equals(t2)) {
                    newTokens.add(t1);
                } else if (t1.equals("<*>") || t2.equals("<*>")) {
                    newTokens.add("<*>");
                    needsUpdate = true;
                } else {
                    newTokens.add("<*>");
                    needsUpdate = true;
                }
            }
        }

        if (needsUpdate) {
            String newTemplate = String.join(" ", newTokens);
            pattern.setTemplate(newTemplate);

            List<String> staticTokens = new ArrayList<>();
            List<String> variableSlots = new ArrayList<>();
            for (int i = 0; i < newTokens.size(); i++) {
                String token = newTokens.get(i);
                if (token.equals("<*>")) {
                    variableSlots.add("var_" + i);
                } else {
                    staticTokens.add(token);
                }
            }
            pattern.setStaticTokens(staticTokens);
            pattern.setVariableSlots(variableSlots);
        }
    }

    public LogPattern getPattern(String patternId) {
        return patternMap.get(patternId);
    }

    public Collection<LogPattern> getAllPatterns() {
        return patternMap.values();
    }

    public int getPatternCount() {
        return patternMap.size();
    }

    public List<LogPattern> getTopKPatterns(int k) {
        return patternMap.values().stream()
                .sorted((a, b) -> Long.compare(b.getTotalCount(), a.getTotalCount()))
                .limit(k)
                .toList();
    }

    public List<LogPattern> getNewPatterns() {
        return patternMap.values().stream()
                .filter(LogPattern::isNew)
                .toList();
    }
}
