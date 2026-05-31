package com.smartflow.documentcompare.service;

import com.smartflow.common.exception.BusinessException;
import com.smartflow.common.utils.IdGenerator;
import com.smartflow.common.utils.JsonUtils;
import com.smartflow.persistence.entity.Document;
import com.smartflow.persistence.entity.DocumentCompare;
import com.smartflow.persistence.mapper.DocumentCompareMapper;
import com.smartflow.persistence.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DocumentCompareService {

    private final DocumentMapper documentMapper;
    private final DocumentCompareMapper compareMapper;

    private static final List<String> KEY_TERMS = Arrays.asList(
        "保密", "责任", "违约", "赔偿", "终止", "期限", "金额", "保密", "知识产权",
        "管辖", "仲裁", "保证", "承诺", "义务", "权利", "期限", "终止"
    );

    @Transactional
    public Document createDocument(Document document) {
        document.setId(IdGenerator.generateId());
        document.setStatus(1);
        documentMapper.insert(document);
        return document;
    }

    public Document getDocument(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        return document;
    }

    public List<Document> listDocuments(String category, Integer status) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Document> query =
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Document>()
                .orderByDesc(Document::getCreatedAt);

        if (category != null && !category.isEmpty()) {
            query.eq(Document::getCategory, category);
        }
        if (status != null) {
            query.eq(Document::getStatus, status);
        }

        return documentMapper.selectList(query);
    }

    @Transactional
    public Map<String, Object> compareDocuments(Long leftDocId, Long rightDocId, Map<String, Object> options) {
        Document leftDoc = getDocument(leftDocId);
        Document rightDoc = getDocument(rightDocId);

        String leftContent = leftDoc.getContent() != null ? leftDoc.getContent() : "";
        String rightContent = rightDoc.getContent() != null ? rightDoc.getContent() : "";

        List<Map<String, Object>> diffResult = computeDiff(leftContent, rightContent);
        String changeSummary = generateChangeSummary(diffResult);
        List<Map<String, Object>> keyClauses = extractKeyClauses(leftContent, rightContent);
        int similarity = calculateSimilarity(leftContent, rightContent);

        DocumentCompare compare = new DocumentCompare();
        compare.setId(IdGenerator.generateId());
        compare.setLeftDocumentId(leftDocId);
        compare.setLeftTitle(leftDoc.getTitle());
        compare.setLeftVersion(leftDoc.getVersion());
        compare.setRightDocumentId(rightDocId);
        compare.setRightTitle(rightDoc.getTitle());
        compare.setRightVersion(rightDoc.getVersion());
        compare.setDiffResult(JsonUtils.toJson(diffResult));
        compare.setChangeSummary(changeSummary);
        compare.setKeyClauses(JsonUtils.toJson(keyClauses));
        compare.setSimilarity(similarity);
        compare.setComparedAt(LocalDateTime.now());
        compare.setCompareOptions(JsonUtils.toJson(options));
        compareMapper.insert(compare);

        Map<String, Object> result = new HashMap<>();
        result.put("compareId", compare.getId());
        result.put("leftDocument", leftDoc);
        result.put("rightDocument", rightDoc);
        result.put("diffResult", diffResult);
        result.put("changeSummary", changeSummary);
        result.put("keyClauses", keyClauses);
        result.put("similarity", similarity);
        result.put("comparedAt", compare.getComparedAt());
        return result;
    }

    private List<Map<String, Object>> computeDiff(String left, String right) {
        List<Map<String, Object>> diffs = new ArrayList<>();

        String[] leftLines = left.split("\n");
        String[] rightLines = right.split("\n");

        int maxLen = Math.max(leftLines.length, rightLines.length);

        for (int i = 0; i < maxLen; i++) {
            String leftLine = i < leftLines.length ? leftLines[i] : null;
            String rightLine = i < rightLines.length ? rightLines[i] : null;

            if (leftLine != null && rightLine != null && leftLine.equals(rightLine)) {
                Map<String, Object> diff = new HashMap<>();
                diff.put("line", i + 1);
                diff.put("type", "EQUAL");
                diff.put("content", leftLine);
                diffs.add(diff);
            } else if (leftLine != null && rightLine != null) {
                Map<String, Object> diff = new HashMap<>();
                diff.put("line", i + 1);
                diff.put("type", "MODIFIED");
                diff.put("oldContent", leftLine);
                diff.put("newContent", rightLine);
                diff.put("changes", computeInlineDiff(leftLine, rightLine));
                diffs.add(diff);
            } else if (leftLine != null) {
                Map<String, Object> diff = new HashMap<>();
                diff.put("line", i + 1);
                diff.put("type", "DELETED");
                diff.put("content", leftLine);
                diffs.add(diff);
            } else {
                Map<String, Object> diff = new HashMap<>();
                diff.put("line", i + 1);
                diff.put("type", "INSERTED");
                diff.put("content", rightLine);
                diffs.add(diff);
            }
        }

        return diffs;
    }

    private List<Map<String, Object>> computeInlineDiff(String oldStr, String newStr) {
        List<Map<String, Object>> changes = new ArrayList<>();
        Set<Character> oldChars = new HashSet<>();
        for (char c : oldStr.toCharArray()) oldChars.add(c);
        Set<Character> newChars = new HashSet<>();
        for (char c : newStr.toCharArray()) newChars.add(c);

        StringBuilder deleted = new StringBuilder();
        for (char c : oldStr.toCharArray()) {
            if (!newChars.contains(c)) deleted.append(c);
        }
        StringBuilder inserted = new StringBuilder();
        for (char c : newStr.toCharArray()) {
            if (!oldChars.contains(c)) inserted.append(c);
        }

        if (deleted.length() > 0) {
            Map<String, Object> change = new HashMap<>();
            change.put("type", "DELETED");
            change.put("text", deleted.toString());
            changes.add(change);
        }
        if (inserted.length() > 0) {
            Map<String, Object> change = new HashMap<>();
            change.put("type", "INSERTED");
            change.put("text", inserted.toString());
            changes.add(change);
        }

        return changes;
    }

    private String generateChangeSummary(List<Map<String, Object>> diffs) {
        int inserted = 0;
        int deleted = 0;
        int modified = 0;

        for (Map<String, Object> diff : diffs) {
            String type = (String) diff.get("type");
            if ("INSERTED".equals(type)) inserted++;
            else if ("DELETED".equals(type)) deleted++;
            else if ("MODIFIED".equals(type)) modified++;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("本次对比共发现 ");
        summary.append(diffs.size());
        summary.append(" 处差异。");
        if (inserted > 0) summary.append(" 新增 ").append(inserted).append(" 行，");
        if (deleted > 0) summary.append(" 删除 ").append(deleted).append(" 行，");
        if (modified > 0) summary.append(" 修改 ").append(modified).append(" 行。");

        return summary.toString();
    }

    private List<Map<String, Object>> extractKeyClauses(String left, String right) {
        List<Map<String, Object>> keyClauses = new ArrayList<>();

        String[] leftSentences = left.split("[。.!?！？]");
        String[] rightSentences = right.split("[。.!?！？]");

        Set<String> foundTerms = new HashSet<>();

        for (String sentence : rightSentences) {
            for (String term : KEY_TERMS) {
                if (sentence.contains(term)) {
                    boolean foundInLeft = false;
                    for (String leftSentence : leftSentences) {
                        if (leftSentence.contains(term) && 
                            calculateSimilarity(leftSentence, sentence) < 80) {
                            foundInLeft = true;
                            break;
                        }
                    }
                    if (!foundInLeft) {
                        Map<String, Object> clause = new HashMap<>();
                        clause.put("term", term);
                        clause.put("content", sentence.trim());
                        clause.put("highlight", true);
                        keyClauses.add(clause);
                        foundTerms.add(term + "|" + sentence.trim());
                    }
                }
        }

        return keyClauses;
    }

    private int calculateSimilarity(String left, String right) {
        if (left == null || right == null) return 0;
        if (left.equals(right)) return 100;

        Set<Character> leftSet = new HashSet<>();
        Set<Character> rightSet = new HashSet<>();

        for (char c : left.toCharArray()) leftSet.add(c);
        for (char c : right.toCharArray()) rightSet.add(c);

        Set<Character> intersection = new HashSet<>(leftSet);
        intersection.retainAll(rightSet);

        Set<Character> union = new HashSet<>(leftSet);
        union.addAll(rightSet);

        if (union.isEmpty()) return 0;

        return (int) ((double) intersection.size() / union.size() * 100);
    }

    public DocumentCompare getCompareResult(Long compareId) {
        DocumentCompare compare = compareMapper.selectById(compareId);
        if (compare == null) {
            throw new BusinessException("对比记录不存在");
        }
        return compare;
    }

    public Map<String, Object> getCompareDetail(Long compareId) {
        DocumentCompare compare = getCompareResult(compareId);
        Map<String, Object> result = new HashMap<>();
        result.put("compare", compare);
        result.put("diffResult", JsonUtils.parseList(compare.getDiffResult(), Map.class));
        result.put("keyClauses", JsonUtils.parseList(compare.getKeyClauses(), Map.class));
        return result;
    }

    public List<DocumentCompare> listCompareResults(Long leftDocId, Long rightDocId) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentCompare> query =
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentCompare>()
                .orderByDesc(DocumentCompare::getComparedAt);

        if (leftDocId != null) {
            query.eq(DocumentCompare::getLeftDocumentId, leftDocId);
        }
        if (rightDocId != null) {
            query.eq(DocumentCompare::getRightDocumentId, rightDocId);
        }

        return compareMapper.selectList(query);
    }

    public String generateHighlightHtml(String content, List<String> highlightTerms) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        String result = content;
        for (String term : highlightTerms) {
            result = result.replace(term, "<mark class='highlight'>" + term + "</mark>");
        }
        return result;
    }
}
