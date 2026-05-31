package com.contractai.document.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

public class DocumentDTO {

    @Data
    public static class DocumentCreateDTO {
        private String docCode;
        private String docTitle;
        private String docType;
        private String fileType;
        private Long fileSize;
        private String filePath;
        private String contentText;
        private Map<String, Object> metadata;
        private List<String> tags;
        private Long parentId;
        private Long createdBy;
    }

    @Data
    public static class DocumentUpdateDTO {
        private String docTitle;
        private String docType;
        private String status;
        private String contentText;
        private Map<String, Object> metadata;
        private List<String> tags;
    }

    @Data
    public static class ComparisonCreateDTO {
        private String comparisonName;
        private Long sourceDocId;
        private Long targetDocId;
        private String comparisonType;
        private String algorithm;
        private List<String> clauseTypes;
        private Boolean includeDetails;
        private Long createdBy;
    }

    @Data
    public static class ClauseExtractDTO {
        private Long documentId;
        private List<String> clauseTypes;
        private Boolean autoDetect;
    }

    @Data
    public static class DiffResultDTO {
        private String operation;
        private String text;
        private Integer startIndex;
        private Integer endIndex;
        private Integer length;
    }

    @Data
    public static class DiffStatsDTO {
        private Integer totalChars;
        private Integer insertedChars;
        private Integer deletedChars;
        private Integer modifiedChars;
        private Integer unchangedChars;
        private Integer totalLines;
        private Integer insertedLines;
        private Integer deletedLines;
        private Integer modifiedLines;
        private Integer unchangedLines;
    }

    @Data
    public static class HighlightDTO {
        private String clauseType;
        private String clauseTitle;
        private String diffStatus;
        private Integer importance;
        private String riskLevel;
        private List<DiffResultDTO> diffs;
        private String summary;
    }

    @Data
    public static class ComparisonResultDTO {
        private String comparisonCode;
        private String status;
        private Double similarityScore;
        private DiffStatsDTO diffStats;
        private List<HighlightDTO> highlights;
        private String changeSummary;
        private String detailedDiffs;
    }

    @Data
    public static class ClauseCreateDTO {
        private Long documentId;
        private String clauseCode;
        private String clauseTitle;
        private String clauseType;
        private String clauseContent;
        private Integer importance;
        private String riskLevel;
        private Map<String, Object> metadata;
    }
}
