package com.contractai.document.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.common.context.TenantContext;
import com.contractai.common.exception.BusinessException;
import com.contractai.common.exception.ValidationException;
import com.contractai.document.dto.DocumentDTO;
import com.contractai.document.entity.Document;
import com.contractai.document.entity.DocumentClause;
import com.contractai.document.entity.DocumentComparison;
import com.contractai.document.entity.DocumentContent;
import com.contractai.document.mapper.DocumentClauseMapper;
import com.contractai.document.mapper.DocumentComparisonMapper;
import com.contractai.document.mapper.DocumentContentMapper;
import com.contractai.document.mapper.DocumentMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final DocumentContentMapper contentMapper;
    private final DocumentComparisonMapper comparisonMapper;
    private final DocumentClauseMapper clauseMapper;
    private final ObjectMapper objectMapper;

    private static final Map<String, Pattern> CLAUSE_PATTERNS = new HashMap<>();

    static {
        CLAUSE_PATTERNS.put("confidentiality", Pattern.compile("(保密|机密|隐私|非披露|confidential|non-disclosure|NDA)", Pattern.CASE_INSENSITIVE));
        CLAUSE_PATTERNS.put("liability", Pattern.compile("(责任|赔偿|违约金|liability|indemnification|damages)", Pattern.CASE_INSENSITIVE));
        CLAUSE_PATTERNS.put("warranty", Pattern.compile("(保证|担保|质保|warranty|guarantee)", Pattern.CASE_INSENSITIVE));
        CLAUSE_PATTERNS.put("termination", Pattern.compile("(终止|解除|termination|cancel|rescind)", Pattern.CASE_INSENSITIVE));
        CLAUSE_PATTERNS.put("payment", Pattern.compile("(付款|支付|费用|价格|payment|fee|price|consideration)", Pattern.CASE_INSENSITIVE));
        CLAUSE_PATTERNS.put("intellectual_property", Pattern.compile("(知识产权|专利|商标|版权|IP|intellectual property|patent|trademark|copyright)", Pattern.CASE_INSENSITIVE));
        CLAUSE_PATTERNS.put("force_majeure", Pattern.compile("(不可抗力|force majeure)", Pattern.CASE_INSENSITIVE));
        CLAUSE_PATTERNS.put("dispute_resolution", Pattern.compile("(争议|仲裁|诉讼|管辖|dispute|arbitration|litigation|jurisdiction)", Pattern.CASE_INSENSITIVE));
    }

    @Transactional
    public Document createDocument(DocumentDTO.DocumentCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateDocumentCreate(dto, tenantId);

        Integer latestVersion = getLatestVersion(dto.getDocCode(), tenantId);

        Document document = new Document();
        document.setId(IdUtil.getSnowflakeNextId());
        document.setTenantId(tenantId);
        document.setDocCode(dto.getDocCode());
        document.setDocTitle(dto.getDocTitle());
        document.setDocType(dto.getDocType() != null ? dto.getDocType() : "contract");
        document.setFileType(dto.getFileType() != null ? dto.getFileType() : "txt");
        document.setFileSize(dto.getFileSize());
        document.setFilePath(dto.getFilePath());
        document.setVersion(latestVersion + 1);
        document.setStatus("draft");
        document.setMetadata(dto.getMetadata());
        document.setTags(dto.getTags());
        document.setParentId(dto.getParentId() != null ? dto.getParentId() : 0L);
        document.setCreatedBy(dto.getCreatedBy());

        if (dto.getContentText() != null && !dto.getContentText().isEmpty()) {
            String contentHash = DigestUtil.sha256Hex(dto.getContentText());
            document.setContentHash(contentHash);
        }

        documentMapper.insert(document);

        if (dto.getContentText() != null && !dto.getContentText().isEmpty()) {
            DocumentContent content = new DocumentContent();
            content.setId(IdUtil.getSnowflakeNextId());
            content.setTenantId(tenantId);
            content.setDocumentId(document.getId());
            content.setContentText(dto.getContentText());
            content.setContentHash(document.getContentHash());
            content.setParsedAt(LocalDateTime.now());
            content.setEntities(new ArrayList<>());
            content.setKeyClauses(new ArrayList<>());
            contentMapper.insert(content);

            document.setContent(content);
        }

        return document;
    }

    @Transactional
    public Document updateDocument(Long id, DocumentDTO.DocumentUpdateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        Document document = getDocument(id);

        if (dto.getDocTitle() != null) document.setDocTitle(dto.getDocTitle());
        if (dto.getDocType() != null) document.setDocType(dto.getDocType());
        if (dto.getStatus() != null) document.setStatus(dto.getStatus());
        if (dto.getMetadata() != null) document.setMetadata(dto.getMetadata());
        if (dto.getTags() != null) document.setTags(dto.getTags());

        if (dto.getContentText() != null && !dto.getContentText().isEmpty()) {
            String contentHash = DigestUtil.sha256Hex(dto.getContentText());
            if (!contentHash.equals(document.getContentHash())) {
                document.setContentHash(contentHash);
                document.setVersion(document.getVersion() + 1);
            }

            DocumentContent content = contentMapper.selectOne(
                    new LambdaQueryWrapper<DocumentContent>()
                            .eq(DocumentContent::getDocumentId, id)
                            .eq(DocumentContent::getTenantId, tenantId)
            );

            if (content == null) {
                content = new DocumentContent();
                content.setId(IdUtil.getSnowflakeNextId());
                content.setTenantId(tenantId);
                content.setDocumentId(document.getId());
                content.setContentText(dto.getContentText());
                content.setContentHash(contentHash);
                content.setParsedAt(LocalDateTime.now());
                content.setEntities(new ArrayList<>());
                content.setKeyClauses(new ArrayList<>());
                contentMapper.insert(content);
            } else {
                content.setContentText(dto.getContentText());
                content.setContentHash(contentHash);
                content.setParsedAt(LocalDateTime.now());
                contentMapper.updateById(content);
            }
            document.setContent(content);
        }

        documentMapper.updateById(document);
        return document;
    }

    public Page<Document> listDocuments(int page, int size, String docType, String status, String keyword) {
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getTenantId, tenantId);

        if (docType != null) wrapper.eq(Document::getDocType, docType);
        if (status != null) wrapper.eq(Document::getStatus, status);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(Document::getDocTitle, keyword);
        }

        wrapper.orderByDesc(Document::getCreatedAt);
        return documentMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public Document getDocument(Long id) {
        Long tenantId = TenantContext.getTenantId();
        Document document = documentMapper.selectById(id);
        if (document == null || !document.getTenantId().equals(tenantId)) {
            throw new BusinessException("文档不存在");
        }

        DocumentContent content = contentMapper.selectOne(
                new LambdaQueryWrapper<DocumentContent>()
                        .eq(DocumentContent::getDocumentId, id)
                        .eq(DocumentContent::getTenantId, tenantId)
        );
        document.setContent(content);

        List<DocumentClause> clauses = clauseMapper.findByDocumentId(id, tenantId);
        document.setClauses(clauses);

        return document;
    }

    @Transactional
    public void deleteDocument(Long id) {
        Long tenantId = TenantContext.getTenantId();
        Document document = documentMapper.selectById(id);
        if (document == null || !document.getTenantId().equals(tenantId)) {
            throw new BusinessException("文档不存在");
        }
        documentMapper.deleteById(id);
    }

    @Transactional
    public DocumentComparison createComparison(DocumentDTO.ComparisonCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateComparisonCreate(dto, tenantId);

        Document sourceDoc = getDocument(dto.getSourceDocId());
        Document targetDoc = getDocument(dto.getTargetDocId());

        if (sourceDoc.getContent() == null || sourceDoc.getContent().getContentText() == null) {
            throw new BusinessException("源文档内容为空，无法比对");
        }
        if (targetDoc.getContent() == null || targetDoc.getContent().getContentText() == null) {
            throw new BusinessException("目标文档内容为空，无法比对");
        }

        DocumentComparison comparison = new DocumentComparison();
        comparison.setId(IdUtil.getSnowflakeNextId());
        comparison.setTenantId(tenantId);
        comparison.setComparisonCode("COMP_" + IdUtil.getSnowflakeNextIdStr());
        comparison.setComparisonName(dto.getComparisonName());
        comparison.setSourceDocId(dto.getSourceDocId());
        comparison.setTargetDocId(dto.getTargetDocId());
        comparison.setComparisonType(dto.getComparisonType() != null ? dto.getComparisonType() : "full");
        comparison.setStatus("processing");
        comparison.setAlgorithm(dto.getAlgorithm() != null ? dto.getAlgorithm() : "diff-match-patch");
        comparison.setStartedAt(LocalDateTime.now());
        comparison.setCreatedBy(dto.getCreatedBy());
        comparison.setSourceDocument(sourceDoc);
        comparison.setTargetDocument(targetDoc);

        comparisonMapper.insert(comparison);

        executeComparisonAsync(comparison, dto);

        return comparison;
    }

    @Async
    @Transactional
    public void executeComparisonAsync(DocumentComparison comparison, DocumentDTO.ComparisonCreateDTO dto) {
        try {
            TenantContext.setTenantId(comparison.getTenantId());
            executeComparison(comparison, dto);
        } catch (Exception e) {
            log.error("文档比对失败: {}", e.getMessage(), e);
            comparison.setStatus("failed");
            comparison.setErrorDetail(e.getMessage());
            comparisonMapper.updateById(comparison);
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    public DocumentComparison executeComparison(DocumentComparison comparison, DocumentDTO.ComparisonCreateDTO dto) {
        Document sourceDoc = getDocument(comparison.getSourceDocId());
        Document targetDoc = getDocument(comparison.getTargetDocId());

        String sourceText = sourceDoc.getContent().getContentText();
        String targetText = targetDoc.getContent().getContentText();

        List<DocumentDTO.DiffResultDTO> charDiffs = computeDiff(sourceText, targetText);
        DocumentDTO.DiffStatsDTO diffStats = calculateDiffStats(sourceText, targetText, charDiffs);

        BigDecimal similarityScore = calculateSimilarity(sourceText, targetText, diffStats);

        List<DocumentClause> sourceClauses = clauseMapper.findByDocumentId(comparison.getSourceDocId(), comparison.getTenantId());
        List<DocumentClause> targetClauses = clauseMapper.findByDocumentId(comparison.getTargetDocId(), comparison.getTenantId());

        if (sourceClauses.isEmpty()) {
            sourceClauses = autoExtractClauses(sourceDoc, sourceText);
        }
        if (targetClauses.isEmpty()) {
            targetClauses = autoExtractClauses(targetDoc, targetText);
        }

        List<DocumentDTO.HighlightDTO> highlights = generateHighlights(
                sourceClauses, targetClauses, dto.getClauseTypes());

        String changeSummary = generateChangeSummary(diffStats, highlights, similarityScore);

        String detailedDiffs = serializeDetailedDiffs(charDiffs, sourceClauses, targetClauses);

        comparison.setStatus("completed");
        comparison.setSimilarityScore(similarityScore);
        comparison.setDiffStats(convertDiffStatsToMap(diffStats));
        comparison.setHighlights(convertHighlightsToMapList(highlights));
        comparison.setChangeSummary(changeSummary);
        comparison.setDetailedDiffs(detailedDiffs);
        comparison.setCompletedAt(LocalDateTime.now());

        comparisonMapper.updateById(comparison);
        return comparison;
    }

    public List<DocumentDTO.DiffResultDTO> computeDiff(String source, String target) {
        List<DocumentDTO.DiffResultDTO> diffs = new ArrayList<>();

        if (Objects.equals(source, target)) {
            DocumentDTO.DiffResultDTO unchanged = new DocumentDTO.DiffResultDTO();
            unchanged.setOperation("unchanged");
            unchanged.setText(source);
            unchanged.setStartIndex(0);
            unchanged.setEndIndex(source.length());
            unchanged.setLength(source.length());
            diffs.add(unchanged);
            return diffs;
        }

        int[][] dp = computeLcsTable(source, target);
        int i = source.length();
        int j = target.length();

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && source.charAt(i - 1) == target.charAt(j - 1)) {
                int startI = i;
                int startJ = j;
                while (i > 0 && j > 0 && source.charAt(i - 1) == target.charAt(j - 1)) {
                    i--;
                    j--;
                }
                DocumentDTO.DiffResultDTO unchanged = new DocumentDTO.DiffResultDTO();
                unchanged.setOperation("unchanged");
                unchanged.setText(source.substring(i, startI));
                unchanged.setStartIndex(i);
                unchanged.setEndIndex(startI);
                unchanged.setLength(startI - i);
                diffs.add(0, unchanged);
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                int startJ = j;
                while (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                    if (i > 0 && j > 0 && source.charAt(i - 1) == target.charAt(j - 1)) break;
                    j--;
                }
                DocumentDTO.DiffResultDTO inserted = new DocumentDTO.DiffResultDTO();
                inserted.setOperation("insert");
                inserted.setText(target.substring(j, startJ));
                inserted.setStartIndex(i);
                inserted.setEndIndex(i);
                inserted.setLength(startJ - j);
                diffs.add(0, inserted);
            } else if (i > 0) {
                int startI = i;
                while (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                    if (i > 0 && j > 0 && source.charAt(i - 1) == target.charAt(j - 1)) break;
                    i--;
                }
                DocumentDTO.DiffResultDTO deleted = new DocumentDTO.DiffResultDTO();
                deleted.setOperation("delete");
                deleted.setText(source.substring(i, startI));
                deleted.setStartIndex(i);
                deleted.setEndIndex(startI);
                deleted.setLength(startI - i);
                diffs.add(0, deleted);
            }
        }

        return mergeAdjacentDiffs(diffs);
    }

    private int[][] computeLcsTable(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp;
    }

    private List<DocumentDTO.DiffResultDTO> mergeAdjacentDiffs(List<DocumentDTO.DiffResultDTO> diffs) {
        if (diffs.size() <= 1) return diffs;

        List<DocumentDTO.DiffResultDTO> merged = new ArrayList<>();
        DocumentDTO.DiffResultDTO current = diffs.get(0);

        for (int i = 1; i < diffs.size(); i++) {
            DocumentDTO.DiffResultDTO next = diffs.get(i);
            if (current.getOperation().equals(next.getOperation())) {
                current.setText(current.getText() + next.getText());
                current.setEndIndex(next.getEndIndex());
                current.setLength(current.getLength() + next.getLength());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private DocumentDTO.DiffStatsDTO calculateDiffStats(String source, String target, List<DocumentDTO.DiffResultDTO> diffs) {
        DocumentDTO.DiffStatsDTO stats = new DocumentDTO.DiffStatsDTO();

        stats.setTotalChars(Math.max(source.length(), target.length()));

        int insertedChars = 0, deletedChars = 0, unchangedChars = 0;
        for (DocumentDTO.DiffResultDTO diff : diffs) {
            switch (diff.getOperation()) {
                case "insert":
                    insertedChars += diff.getLength();
                    break;
                case "delete":
                    deletedChars += diff.getLength();
                    break;
                case "unchanged":
                    unchangedChars += diff.getLength();
                    break;
            }
        }
        stats.setInsertedChars(insertedChars);
        stats.setDeletedChars(deletedChars);
        stats.setUnchangedChars(unchangedChars);
        stats.setModifiedChars(Math.min(insertedChars, deletedChars));

        String[] sourceLines = source.split("\n", -1);
        String[] targetLines = target.split("\n", -1);
        stats.setTotalLines(Math.max(sourceLines.length, targetLines.length));

        Set<String> sourceLineSet = new HashSet<>(Arrays.asList(sourceLines));
        Set<String> targetLineSet = new HashSet<>(Arrays.asList(targetLines));

        int insertedLines = 0, deletedLines = 0, unchangedLines = 0;
        for (String line : targetLines) {
            if (!sourceLineSet.contains(line)) insertedLines++;
        }
        for (String line : sourceLines) {
            if (!targetLineSet.contains(line)) deletedLines++;
            else unchangedLines++;
        }
        stats.setInsertedLines(insertedLines);
        stats.setDeletedLines(deletedLines);
        stats.setUnchangedLines(unchangedLines);
        stats.setModifiedLines(Math.min(insertedLines, deletedLines));

        return stats;
    }

    private BigDecimal calculateSimilarity(String source, String target, DocumentDTO.DiffStatsDTO stats) {
        if (source.isEmpty() && target.isEmpty()) {
            return new BigDecimal("100.00");
        }
        if (source.isEmpty() || target.isEmpty()) {
            return BigDecimal.ZERO;
        }

        int lcsLength = stats.getUnchangedChars();
        int maxLength = Math.max(source.length(), target.length());

        return BigDecimal.valueOf(lcsLength)
                .divide(BigDecimal.valueOf(maxLength), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private List<DocumentClause> autoExtractClauses(Document document, String content) {
        Long tenantId = document.getTenantId();
        List<DocumentClause> clauses = new ArrayList<>();

        Pattern clausePattern = Pattern.compile("(第[一二三四五六七八九十百零\\d]+[条款条章]|[1-9]\\d*\\.[1-9]\\d*\\.?[^\\n])\\s*([^\\n]+)");
        Matcher matcher = clausePattern.matcher(content);

        int clauseIndex = 0;
        while (matcher.find()) {
            String clauseCode = document.getDocCode() + "_CLAUSE_" + (++clauseIndex);
            String clauseTitle = matcher.group(0).trim();
            int startPos = matcher.start();

            int endPos = content.length();
            Matcher nextMatcher = clausePattern.matcher(content);
            if (nextMatcher.find(matcher.end())) {
                endPos = nextMatcher.start();
            }

            String clauseContent = content.substring(startPos, endPos).trim();

            String clauseType = detectClauseType(clauseTitle + " " + clauseContent);
            int importance = calculateImportance(clauseType, clauseContent);
            String riskLevel = assessRiskLevel(clauseType, clauseContent);

            DocumentClause clause = new DocumentClause();
            clause.setId(IdUtil.getSnowflakeNextId());
            clause.setTenantId(tenantId);
            clause.setDocumentId(document.getId());
            clause.setClauseCode(clauseCode);
            clause.setClauseTitle(truncate(clauseTitle, 256));
            clause.setClauseType(clauseType);
            clause.setClauseContent(clauseContent);
            clause.setStartPosition(startPos);
            clause.setEndPosition(endPos);
            clause.setImportance(importance);
            clause.setRiskLevel(riskLevel);
            clause.setMetadata(new HashMap<>());

            clauseMapper.insert(clause);
            clauses.add(clause);
        }

        return clauses;
    }

    private String detectClauseType(String text) {
        for (Map.Entry<String, Pattern> entry : CLAUSE_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(text).find()) {
                return entry.getKey();
            }
        }
        return "other";
    }

    private int calculateImportance(String clauseType, String content) {
        int importance = 1;

        switch (clauseType) {
            case "confidentiality":
            case "liability":
            case "termination":
            case "payment":
            case "intellectual_property":
                importance = 3;
                break;
            case "warranty":
            case "dispute_resolution":
                importance = 2;
                break;
            default:
                importance = 1;
        }

        if (content.contains("不承担") || content.contains("免责") || content.contains("赔偿") || content.contains("违约金")) {
            importance = Math.min(3, importance + 1);
        }

        return importance;
    }

    private String assessRiskLevel(String clauseType, String content) {
        String riskLevel = "low";

        switch (clauseType) {
            case "liability":
                riskLevel = "high";
                break;
            case "confidentiality":
            case "termination":
            case "payment":
            case "intellectual_property":
                riskLevel = "medium";
                break;
            default:
                riskLevel = "low";
        }

        if (content.contains("全部责任") || content.contains("无限责任") || content.contains("巨额赔偿")) {
            riskLevel = "high";
        }

        return riskLevel;
    }

    private List<DocumentDTO.HighlightDTO> generateHighlights(
            List<DocumentClause> sourceClauses,
            List<DocumentClause> targetClauses,
            List<String> filterClauseTypes) {

        List<DocumentDTO.HighlightDTO> highlights = new ArrayList<>();

        Map<String, DocumentClause> sourceClauseMap = sourceClauses.stream()
                .collect(Collectors.toMap(DocumentClause::getClauseCode, c -> c, (a, b) -> a));

        Map<String, DocumentClause> targetClauseMap = targetClauses.stream()
                .collect(Collectors.toMap(DocumentClause::getClauseCode, c -> c, (a, b) -> a));

        Set<String> allClauseCodes = new HashSet<>();
        allClauseCodes.addAll(sourceClauseMap.keySet());
        allClauseCodes.addAll(targetClauseMap.keySet());

        for (String clauseCode : allClauseCodes) {
            DocumentClause sourceClause = sourceClauseMap.get(clauseCode);
            DocumentClause targetClause = targetClauseMap.get(clauseCode);

            if (filterClauseTypes != null && !filterClauseTypes.isEmpty()) {
                String type = sourceClause != null ? sourceClause.getClauseType() :
                        (targetClause != null ? targetClause.getClauseType() : null);
                if (type == null || !filterClauseTypes.contains(type)) {
                    continue;
                }
            }

            DocumentDTO.HighlightDTO highlight = new DocumentDTO.HighlightDTO();

            if (sourceClause != null) {
                highlight.setClauseType(sourceClause.getClauseType());
                highlight.setClauseTitle(sourceClause.getClauseTitle());
                highlight.setImportance(sourceClause.getImportance());
                highlight.setRiskLevel(sourceClause.getRiskLevel());
            } else if (targetClause != null) {
                highlight.setClauseType(targetClause.getClauseType());
                highlight.setClauseTitle(targetClause.getClauseTitle());
                highlight.setImportance(targetClause.getImportance());
                highlight.setRiskLevel(targetClause.getRiskLevel());
            }

            String diffStatus;
            List<DocumentDTO.DiffResultDTO> clauseDiffs;
            String summary;

            if (sourceClause == null) {
                diffStatus = "added";
                clauseDiffs = Collections.singletonList(createDiff("insert", targetClause.getClauseContent(), 0, targetClause.getClauseContent().length()));
                summary = "新增条款: " + targetClause.getClauseTitle();
            } else if (targetClause == null) {
                diffStatus = "deleted";
                clauseDiffs = Collections.singletonList(createDiff("delete", sourceClause.getClauseContent(), 0, sourceClause.getClauseContent().length()));
                summary = "删除条款: " + sourceClause.getClauseTitle();
            } else if (Objects.equals(sourceClause.getClauseContent(), targetClause.getClauseContent())) {
                diffStatus = "unchanged";
                clauseDiffs = Collections.singletonList(createDiff("unchanged", sourceClause.getClauseContent(), 0, sourceClause.getClauseContent().length()));
                summary = "条款未变更";
            } else {
                diffStatus = "modified";
                clauseDiffs = computeDiff(sourceClause.getClauseContent(), targetClause.getClauseContent());
                summary = generateClauseDiffSummary(sourceClause, targetClause, clauseDiffs);
            }

            highlight.setDiffStatus(diffStatus);
            highlight.setDiffs(clauseDiffs);
            highlight.setSummary(summary);

            highlights.add(highlight);
        }

        highlights.sort((a, b) -> {
            int impCompare = Integer.compare(b.getImportance(), a.getImportance());
            if (impCompare != 0) return impCompare;

            Map<String, Integer> statusPriority = Map.of(
                    "deleted", 4,
                    "added", 3,
                    "modified", 2,
                    "unchanged", 1
            );
            return Integer.compare(statusPriority.getOrDefault(b.getDiffStatus(), 0),
                    statusPriority.getOrDefault(a.getDiffStatus(), 0));
        });

        return highlights;
    }

    private DocumentDTO.DiffResultDTO createDiff(String operation, String text, int start, int end) {
        DocumentDTO.DiffResultDTO diff = new DocumentDTO.DiffResultDTO();
        diff.setOperation(operation);
        diff.setText(text);
        diff.setStartIndex(start);
        diff.setEndIndex(end);
        diff.setLength(end - start);
        return diff;
    }

    private String generateClauseDiffSummary(DocumentClause source, DocumentClause target,
                                             List<DocumentDTO.DiffResultDTO> diffs) {
        StringBuilder sb = new StringBuilder();
        sb.append("条款\"").append(source.getClauseTitle()).append("\"已变更: ");

        int insertCount = 0, deleteCount = 0;
        int insertChars = 0, deleteChars = 0;

        for (DocumentDTO.DiffResultDTO diff : diffs) {
            if ("insert".equals(diff.getOperation())) {
                insertCount++;
                insertChars += diff.getLength();
            } else if ("delete".equals(diff.getOperation())) {
                deleteCount++;
                deleteChars += diff.getLength();
            }
        }

        if (insertCount > 0) {
            sb.append("新增").append(insertChars).append("字");
            if (deleteCount > 0) sb.append(", ");
        }
        if (deleteCount > 0) {
            sb.append("删除").append(deleteChars).append("字");
        }

        if (target.getImportance() > source.getImportance()) {
            sb.append("，重要程度提升");
        } else if (target.getImportance() < source.getImportance()) {
            sb.append("，重要程度降低");
        }

        if (!target.getRiskLevel().equals(source.getRiskLevel())) {
            sb.append("，风险等级由").append(source.getRiskLevel())
                    .append("变为").append(target.getRiskLevel());
        }

        return sb.toString();
    }

    private String generateChangeSummary(DocumentDTO.DiffStatsDTO stats,
                                         List<DocumentDTO.HighlightDTO> highlights,
                                         BigDecimal similarity) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 文档比对变更摘要\n\n");
        sb.append("### 基本信息\n");
        sb.append("- 相似度: ").append(similarity.toPlainString()).append("%\n");
        sb.append("- 字符变更: 新增").append(stats.getInsertedChars())
                .append("字, 删除").append(stats.getDeletedChars())
                .append("字, 未变更").append(stats.getUnchangedChars()).append("字\n");
        sb.append("- 行变更: 新增").append(stats.getInsertedLines())
                .append("行, 删除").append(stats.getDeletedLines())
                .append("行, 未变更").append(stats.getUnchangedLines()).append("行\n\n");

        sb.append("### 条款变更统计\n");
        Map<String, Long> statusCount = highlights.stream()
                .collect(Collectors.groupingBy(DocumentDTO.HighlightDTO::getDiffStatus, Collectors.counting()));
        sb.append("- 新增条款: ").append(statusCount.getOrDefault("added", 0L)).append("条\n");
        sb.append("- 删除条款: ").append(statusCount.getOrDefault("deleted", 0L)).append("条\n");
        sb.append("- 修改条款: ").append(statusCount.getOrDefault("modified", 0L)).append("条\n");
        sb.append("- 未变更条款: ").append(statusCount.getOrDefault("unchanged", 0L)).append("条\n\n");

        List<DocumentDTO.HighlightDTO> importantChanges = highlights.stream()
                .filter(h -> h.getImportance() >= 2 && !"unchanged".equals(h.getDiffStatus()))
                .limit(5)
                .collect(Collectors.toList());

        if (!importantChanges.isEmpty()) {
            sb.append("### 重要变更\n");
            for (int i = 0; i < importantChanges.size(); i++) {
                DocumentDTO.HighlightDTO h = importantChanges.get(i);
                sb.append(i + 1).append(". [").append(h.getDiffStatus()).append("] ")
                        .append(h.getClauseType()).append(" - ").append(h.getClauseTitle())
                        .append(" (重要度: ").append(h.getImportance())
                        .append(", 风险: ").append(h.getRiskLevel()).append(")\n");
                sb.append("   ").append(h.getSummary()).append("\n");
            }
            sb.append("\n");
        }

        List<DocumentDTO.HighlightDTO> highRiskChanges = highlights.stream()
                .filter(h -> "high".equals(h.getRiskLevel()) && !"unchanged".equals(h.getDiffStatus()))
                .collect(Collectors.toList());

        if (!highRiskChanges.isEmpty()) {
            sb.append("### ⚠️ 高风险条款变更\n");
            for (DocumentDTO.HighlightDTO h : highRiskChanges) {
                sb.append("- ").append(h.getClauseTitle()).append(": ").append(h.getSummary()).append("\n");
            }
        }

        return sb.toString();
    }

    private String serializeDetailedDiffs(List<DocumentDTO.DiffResultDTO> charDiffs,
                                          List<DocumentClause> sourceClauses,
                                          List<DocumentClause> targetClauses) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("characterDiffs", charDiffs);
            data.put("sourceClauses", sourceClauses);
            data.put("targetClauses", targetClauses);
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("序列化详细差异失败", e);
            return "[]";
        }
    }

    private Map<String, Object> convertDiffStatsToMap(DocumentDTO.DiffStatsDTO stats) {
        try {
            return objectMapper.convertValue(stats, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private List<Map<String, Object>> convertHighlightsToMapList(List<DocumentDTO.HighlightDTO> highlights) {
        try {
            String json = objectMapper.writeValueAsString(highlights);
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public DocumentComparison getComparison(Long id) {
        Long tenantId = TenantContext.getTenantId();
        DocumentComparison comparison = comparisonMapper.selectById(id);
        if (comparison == null || !comparison.getTenantId().equals(tenantId)) {
            throw new BusinessException("比对记录不存在");
        }

        comparison.setSourceDocument(getDocument(comparison.getSourceDocId()));
        comparison.setTargetDocument(getDocument(comparison.getTargetDocId()));

        return comparison;
    }

    public Page<DocumentComparison> listComparisons(int page, int size, String status, String comparisonType) {
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<DocumentComparison> wrapper = new LambdaQueryWrapper<DocumentComparison>()
                .eq(DocumentComparison::getTenantId, tenantId);

        if (status != null) wrapper.eq(DocumentComparison::getStatus, status);
        if (comparisonType != null) wrapper.eq(DocumentComparison::getComparisonType, comparisonType);

        wrapper.orderByDesc(DocumentComparison::getCreatedAt);
        return comparisonMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Transactional
    public DocumentClause createClause(DocumentDTO.ClauseCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        Document document = getDocument(dto.getDocumentId());

        DocumentClause clause = new DocumentClause();
        clause.setId(IdUtil.getSnowflakeNextId());
        clause.setTenantId(tenantId);
        clause.setDocumentId(dto.getDocumentId());
        clause.setClauseCode(dto.getClauseCode());
        clause.setClauseTitle(dto.getClauseTitle());
        clause.setClauseType(dto.getClauseType() != null ? dto.getClauseType() : "other");
        clause.setClauseContent(dto.getClauseContent());
        clause.setImportance(dto.getImportance() != null ? dto.getImportance() : 1);
        clause.setRiskLevel(dto.getRiskLevel() != null ? dto.getRiskLevel() : "low");
        clause.setMetadata(dto.getMetadata());

        clauseMapper.insert(clause);
        return clause;
    }

    public List<DocumentClause> getDocumentClauses(Long documentId) {
        Long tenantId = TenantContext.getTenantId();
        getDocument(documentId);
        return clauseMapper.findByDocumentId(documentId, tenantId);
    }

    @Transactional
    public List<DocumentClause> extractClauses(DocumentDTO.ClauseExtractDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        Document document = getDocument(dto.getDocumentId());

        if (document.getContent() == null || document.getContent().getContentText() == null) {
            throw new BusinessException("文档内容为空");
        }

        List<DocumentClause> existing = clauseMapper.findByDocumentId(dto.getDocumentId(), tenantId);
        if (!existing.isEmpty()) {
            clauseMapper.delete(
                    new LambdaQueryWrapper<DocumentClause>()
                            .eq(DocumentClause::getDocumentId, dto.getDocumentId())
                            .eq(DocumentClause::getTenantId, tenantId)
            );
        }

        List<DocumentClause> clauses = autoExtractClauses(document, document.getContent().getContentText());

        if (dto.getClauseTypes() != null && !dto.getClauseTypes().isEmpty()) {
            clauses = clauses.stream()
                    .filter(c -> dto.getClauseTypes().contains(c.getClauseType()))
                    .collect(Collectors.toList());
        }

        return clauses;
    }

    private Integer getLatestVersion(String docCode, Long tenantId) {
        Document latest = documentMapper.selectOne(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getTenantId, tenantId)
                        .eq(Document::getDocCode, docCode)
                        .orderByDesc(Document::getVersion)
                        .last("LIMIT 1")
        );
        return latest != null ? latest.getVersion() : 0;
    }

    private void validateDocumentCreate(DocumentDTO.DocumentCreateDTO dto, Long tenantId) {
        if (dto.getDocCode() == null || dto.getDocCode().trim().isEmpty()) {
            throw new ValidationException("文档编码不能为空");
        }
        if (dto.getDocTitle() == null || dto.getDocTitle().trim().isEmpty()) {
            throw new ValidationException("文档标题不能为空");
        }
    }

    private void validateComparisonCreate(DocumentDTO.ComparisonCreateDTO dto, Long tenantId) {
        if (dto.getSourceDocId() == null) {
            throw new ValidationException("源文档ID不能为空");
        }
        if (dto.getTargetDocId() == null) {
            throw new ValidationException("目标文档ID不能为空");
        }
        if (dto.getSourceDocId().equals(dto.getTargetDocId())) {
            throw new ValidationException("源文档和目标文档不能相同");
        }
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return null;
        return s.length() > maxLength ? s.substring(0, maxLength) : s;
    }
}
