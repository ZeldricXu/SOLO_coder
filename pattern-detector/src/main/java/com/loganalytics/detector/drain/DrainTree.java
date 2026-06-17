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
    private final TokenEncoder tokenEncoder;
    private final EncodedDrainNode root;
    private final Map<String, LogPattern> patternMap;
    private final Cache<String, LogPattern> fastPathCache;
    private final Pattern variablePattern;
    private final Pattern digitPattern;
    private final Pattern hexPattern;
    private final Pattern ipPattern;
    private final Pattern uuidPattern;
    private final Pattern datePattern;

    public DrainTree(DetectorConfig config) {
        this.config = config;
        this.tokenEncoder = new TokenEncoder();
        this.root = new EncodedDrainNode(0, -1);
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

    public DrainTree(int maxDepth, int maxChildren, double similarityThreshold, java.util.Set<String> services) {
        this.config = new DetectorConfig();
        this.config.setMaxTreeDepth(maxDepth);
        this.config.setMaxChildren(maxChildren);
        this.config.setSimilarityThreshold(similarityThreshold);
        this.tokenEncoder = new TokenEncoder();
        this.root = new EncodedDrainNode(0, -1);
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
        int[] codes = tokenEncoder.encodeTokens(tokens);
        LogPattern pattern = match(codes);

        if (pattern == null) {
            pattern = createPattern(tokens, event);
            addToTree(codes, pattern);
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

    private LogPattern match(int[] codes) {
        if (codes.length == 0) return null;

        EncodedDrainNode current = root;

        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];
            int depth = i + 1;

            EncodedDrainNode child = current.children.get(code);

            if (child == null) {
                child = current.children.get(TokenEncoder.WILDCARD_CODE);
            }

            if (child == null) {
                if (depth >= config.getMaxTreeDepth()) {
                    double bestSimilarity = 0;
                    LogPattern bestPattern = null;

                    for (EncodedDrainNode node : current.children.values()) {
                        if (node.pattern != null) {
                            int[] patternCodes = tokenEncoder.encodeTokens(tokenize(node.pattern.getTemplate()));
                            double sim = calculateSimilarity(codes, patternCodes);
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
                int[] patternCodes = tokenEncoder.encodeTokens(tokenize(current.pattern.getTemplate()));
                double sim = calculateSimilarity(codes, patternCodes);
                if (sim >= config.getSimilarityThreshold()) {
                    return current.pattern;
                }
            }
        }

        return current.pattern;
    }

    private double calculateSimilarity(int[] codes1, int[] codes2) {
        if (codes1.length != codes2.length) {
            double ratio = (double) Math.min(codes1.length, codes2.length) / Math.max(codes1.length, codes2.length);
            if (ratio < 0.8) return 0.0;
        }

        int matches = 0;
        int total = 0;

        int minLen = Math.min(codes1.length, codes2.length);
        for (int i = 0; i < minLen; i++) {
            int c1 = codes1[i];
            int c2 = codes2[i];

            if (c1 == TokenEncoder.WILDCARD_CODE || c2 == TokenEncoder.WILDCARD_CODE) {
                continue;
            }

            total++;
            if (c1 == c2) {
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

    private void addToTree(int[] codes, LogPattern pattern) {
        EncodedDrainNode current = root;

        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];

            if (current.children.size() >= config.getMaxChildren() && !current.children.containsKey(code)) {
                code = TokenEncoder.WILDCARD_CODE;
            }

            int depth = i + 1;
            int finalCode = code;
            EncodedDrainNode child = current.children.computeIfAbsent(
                    finalCode, k -> new EncodedDrainNode(depth, k)
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
