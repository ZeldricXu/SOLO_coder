package com.parking.platform.document.service;

import com.parking.platform.document.entity.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int DEFAULT_LIMIT = 100;
    private static final int INITIAL_RESULT_CAPACITY = 32;

    private static final String ROLE_ADMIN = "ADMIN";

    private static final double SCORE_WEIGHT_TITLE = 2.0;
    private static final double SCORE_WEIGHT_CONTENT = 1.0;
    private static final double SCORE_WEIGHT_TAG = 1.5;

    private final Map<String, Document> documentStore = new ConcurrentHashMap<>(1024);

    public Document create(Document doc) {
        doc.setLastIndexedAt(Instant.now());
        documentStore.put(doc.getId(), doc);
        log.info("Document created: {} from source: {}", doc.getId(), doc.getSource());
        return doc;
    }

    public Document get(String id) {
        return documentStore.get(id);
    }

    public Document update(String id, Document updates) {
        Document existing = documentStore.get(id);
        if (existing == null) {
            return null;
        }
        if (updates.getTitle() != null) existing.setTitle(updates.getTitle());
        if (updates.getContent() != null) existing.setContent(updates.getContent());
        if (updates.getSummary() != null) existing.setSummary(updates.getSummary());
        if (updates.getTags() != null) existing.setTags(updates.getTags());
        if (updates.getAllowedRoles() != null) existing.setAllowedRoles(updates.getAllowedRoles());
        existing.setLastIndexedAt(Instant.now());
        existing.touch();
        log.info("Document updated: {}", id);
        return existing;
    }

    public boolean delete(String id) {
        Document removed = documentStore.remove(id);
        if (removed != null) {
            log.info("Document deleted: {}", id);
            return true;
        }
        return false;
    }

    public List<Document> search(String query, String source, List<String> userRoles, Integer limit) {
        String normalizedQuery = normalizeQuery(query);
        List<Document> matches = new ArrayList<>(INITIAL_RESULT_CAPACITY);

        for (Document doc : documentStore.values()) {
            if (source != null && !source.equals(doc.getSource())) {
                continue;
            }
            if (!hasPermission(doc, userRoles)) {
                continue;
            }
            if (normalizedQuery.isEmpty() || matchesQuery(doc, normalizedQuery)) {
                doc.setScore(calculateScore(doc, normalizedQuery));
                matches.add(doc);
            }
        }

        if (!matches.isEmpty()) {
            matches.sort(SCORE_DESC_COMPARATOR);
        }

        int actualLimit = limit != null ? Math.min(limit, matches.size()) : matches.size();
        if (actualLimit <= 0) {
            return Collections.emptyList();
        }
        return new ArrayList<>(matches.subList(0, actualLimit));
    }

    private static final java.util.Comparator<Document> SCORE_DESC_COMPARATOR =
            (a, b) -> Double.compare(scoreOrDefault(b), scoreOrDefault(a));

    private static double scoreOrDefault(Document doc) {
        Double s = doc.getScore();
        return s != null ? s : 0.0;
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.toLowerCase();
    }

    private boolean hasPermission(Document doc, List<String> userRoles) {
        List<String> allowed = doc.getAllowedRoles();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (userRoles == null || userRoles.isEmpty()) {
            return false;
        }
        if (userRoles.contains(ROLE_ADMIN)) {
            return true;
        }
        for (String allowedRole : allowed) {
            if (userRoles.contains(allowedRole)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesQuery(Document doc, String query) {
        if (containsIgnoreCase(doc.getTitle(), query)) return true;
        if (containsIgnoreCase(doc.getContent(), query)) return true;
        if (containsIgnoreCase(doc.getSummary(), query)) return true;
        if (doc.getTags() != null) {
            for (String tag : doc.getTags()) {
                if (containsIgnoreCase(tag, query)) return true;
            }
        }
        return false;
    }

    private double calculateScore(Document doc, String query) {
        if (query.isEmpty()) return 1.0;
        double score = 0.0;
        if (doc.getTitle() != null) {
            score += countMatches(doc.getTitle(), query) * SCORE_WEIGHT_TITLE;
        }
        if (doc.getContent() != null) {
            score += countMatches(doc.getContent(), query) * SCORE_WEIGHT_CONTENT;
        }
        if (doc.getTags() != null) {
            for (String tag : doc.getTags()) {
                if (containsIgnoreCase(tag, query)) {
                    score += SCORE_WEIGHT_TAG;
                }
            }
        }
        return score;
    }

    private static boolean containsIgnoreCase(String text, String query) {
        if (text == null) {
            return false;
        }
        int len = text.length();
        int qlen = query.length();
        if (qlen == 0) {
            return true;
        }
        if (qlen > len) {
            return false;
        }
        for (int i = 0; i <= len - qlen; i++) {
            if (regionMatchesIgnoreCase(text, i, query, 0, qlen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean regionMatchesIgnoreCase(String a, int ai, String b, int bi, int len) {
        for (int i = 0; i < len; i++) {
            char c1 = a.charAt(ai + i);
            char c2 = b.charAt(bi + i);
            if (c1 != c2 && Character.toLowerCase(c1) != Character.toLowerCase(c2)) {
                return false;
            }
        }
        return true;
    }

    private int countMatches(String text, String query) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        int len = text.length();
        int qlen = query.length();
        if (qlen == 0 || qlen > len) {
            return 0;
        }
        while (idx <= len - qlen) {
            if (regionMatchesIgnoreCase(text, idx, query, 0, qlen)) {
                count++;
                idx += qlen;
            } else {
                idx++;
            }
        }
        return count;
    }

    public List<Document> listBySource(String source, Integer page, Integer size) {
        List<Document> allDocs = new ArrayList<>(documentStore.values());

        allDocs.sort(INDEXED_AT_DESC);

        List<Document> filtered;
        if (source == null) {
            filtered = allDocs;
        } else {
            filtered = new ArrayList<>(allDocs.size());
            for (Document d : allDocs) {
                if (source.equals(d.getSource())) {
                    filtered.add(d);
                }
            }
        }

        int pageNum = page != null ? page : 1;
        int sizeNum = size != null ? size : DEFAULT_PAGE_SIZE;
        int start = (pageNum - 1) * sizeNum;
        int end = Math.min(start + sizeNum, filtered.size());

        if (start >= filtered.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(filtered.subList(start, end));
    }

    private static final java.util.Comparator<Document> INDEXED_AT_DESC =
            (a, b) -> {
                Instant ta = a.getLastIndexedAt();
                Instant tb = b.getLastIndexedAt();
                if (ta == null && tb == null) return 0;
                if (ta == null) return 1;
                if (tb == null) return -1;
                return tb.compareTo(ta);
            };

    public Map<String, Long> getStatistics() {
        Map<String, Long> stats = new HashMap<>(4);
        stats.put("totalDocuments", (long) documentStore.size());
        long sourceCount = documentStore.values().stream()
                .map(Document::getSource)
                .distinct()
                .count();
        stats.put("sources", sourceCount);
        return stats;
    }

    public Document index(Document doc) {
        return create(doc);
    }

    public int reindex(String source) {
        int count = 0;
        Instant now = Instant.now();
        for (Document doc : documentStore.values()) {
            if (source == null || source.equals(doc.getSource())) {
                doc.setLastIndexedAt(now);
                count++;
            }
        }
        log.info("Reindexed {} documents", count);
        return count;
    }
}
