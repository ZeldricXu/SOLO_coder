package com.exam.fixture;

import com.exam.common.Constants;
import com.exam.entity.PaperTemplate;

import java.math.BigDecimal;

public class PaperTemplateFixture {

    public static PaperTemplateBuilder standardTemplate() {
        return new PaperTemplateBuilder()
                .templateName("Java基础标准试卷模板")
                .subjectId(1L)
                .paperMode(Constants.PAPER_MODE_RANDOM)
                .totalScore(new BigDecimal("100"))
                .totalMinutes(90)
                .singleCount(20)
                .singleScore(new BigDecimal("2"))
                .multipleCount(10)
                .multipleScore(new BigDecimal("4"))
                .judgeCount(10)
                .judgeScore(new BigDecimal("1"))
                .fillCount(5)
                .fillScore(new BigDecimal("4"))
                .shortCount(2)
                .shortScore(new BigDecimal("10"))
                .programCount(1)
                .programScore(new BigDecimal("15"))
                .easyRatio(new BigDecimal("0.3"))
                .mediumRatio(new BigDecimal("0.5"))
                .hardRatio(new BigDecimal("0.2"))
                .status(1);
    }

    public static PaperTemplateBuilder objectiveOnlyTemplate() {
        return new PaperTemplateBuilder()
                .templateName("纯客观题模板")
                .subjectId(1L)
                .paperMode(Constants.PAPER_MODE_RANDOM)
                .totalScore(new BigDecimal("100"))
                .totalMinutes(60)
                .singleCount(30)
                .singleScore(new BigDecimal("2"))
                .multipleCount(10)
                .multipleScore(new BigDecimal("3"))
                .judgeCount(10)
                .judgeScore(new BigDecimal("1"))
                .easyRatio(new BigDecimal("0.4"))
                .mediumRatio(new BigDecimal("0.4"))
                .hardRatio(new BigDecimal("0.2"))
                .status(1);
    }

    public static PaperTemplateBuilder insufficientTemplate() {
        return new PaperTemplateBuilder()
                .templateName("高难度超量题目模板")
                .subjectId(1L)
                .paperMode(Constants.PAPER_MODE_RANDOM)
                .totalScore(new BigDecimal("100"))
                .totalMinutes(120)
                .singleCount(100)
                .singleScore(new BigDecimal("1"))
                .multipleCount(50)
                .multipleScore(new BigDecimal("1"))
                .judgeCount(30)
                .judgeScore(new BigDecimal("1"))
                .easyRatio(new BigDecimal("0.1"))
                .mediumRatio(new BigDecimal("0.1"))
                .hardRatio(new BigDecimal("0.8"))
                .status(1);
    }

    public static class PaperTemplateBuilder {
        private Long id;
        private String templateName;
        private Long subjectId = 1L;
        private Integer paperMode;
        private BigDecimal totalScore;
        private Integer totalMinutes;
        private Integer singleCount = 0;
        private BigDecimal singleScore;
        private Integer multipleCount = 0;
        private BigDecimal multipleScore;
        private Integer judgeCount = 0;
        private BigDecimal judgeScore;
        private Integer fillCount = 0;
        private BigDecimal fillScore;
        private Integer shortCount = 0;
        private BigDecimal shortScore;
        private Integer programCount = 0;
        private BigDecimal programScore;
        private BigDecimal easyRatio;
        private BigDecimal mediumRatio;
        private BigDecimal hardRatio;
        private String knowledgeDistribution;
        private String description;
        private Integer status = 1;

        public PaperTemplateBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public PaperTemplateBuilder templateName(String templateName) {
            this.templateName = templateName;
            return this;
        }

        public PaperTemplateBuilder subjectId(Long subjectId) {
            this.subjectId = subjectId;
            return this;
        }

        public PaperTemplateBuilder paperMode(Integer paperMode) {
            this.paperMode = paperMode;
            return this;
        }

        public PaperTemplateBuilder totalScore(BigDecimal totalScore) {
            this.totalScore = totalScore;
            return this;
        }

        public PaperTemplateBuilder totalMinutes(Integer totalMinutes) {
            this.totalMinutes = totalMinutes;
            return this;
        }

        public PaperTemplateBuilder singleCount(Integer singleCount) {
            this.singleCount = singleCount;
            return this;
        }

        public PaperTemplateBuilder singleScore(BigDecimal singleScore) {
            this.singleScore = singleScore;
            return this;
        }

        public PaperTemplateBuilder multipleCount(Integer multipleCount) {
            this.multipleCount = multipleCount;
            return this;
        }

        public PaperTemplateBuilder multipleScore(BigDecimal multipleScore) {
            this.multipleScore = multipleScore;
            return this;
        }

        public PaperTemplateBuilder judgeCount(Integer judgeCount) {
            this.judgeCount = judgeCount;
            return this;
        }

        public PaperTemplateBuilder judgeScore(BigDecimal judgeScore) {
            this.judgeScore = judgeScore;
            return this;
        }

        public PaperTemplateBuilder fillCount(Integer fillCount) {
            this.fillCount = fillCount;
            return this;
        }

        public PaperTemplateBuilder fillScore(BigDecimal fillScore) {
            this.fillScore = fillScore;
            return this;
        }

        public PaperTemplateBuilder shortCount(Integer shortCount) {
            this.shortCount = shortCount;
            return this;
        }

        public PaperTemplateBuilder shortScore(BigDecimal shortScore) {
            this.shortScore = shortScore;
            return this;
        }

        public PaperTemplateBuilder programCount(Integer programCount) {
            this.programCount = programCount;
            return this;
        }

        public PaperTemplateBuilder programScore(BigDecimal programScore) {
            this.programScore = programScore;
            return this;
        }

        public PaperTemplateBuilder easyRatio(BigDecimal easyRatio) {
            this.easyRatio = easyRatio;
            return this;
        }

        public PaperTemplateBuilder mediumRatio(BigDecimal mediumRatio) {
            this.mediumRatio = mediumRatio;
            return this;
        }

        public PaperTemplateBuilder hardRatio(BigDecimal hardRatio) {
            this.hardRatio = hardRatio;
            return this;
        }

        public PaperTemplateBuilder status(Integer status) {
            this.status = status;
            return this;
        }

        public PaperTemplate build() {
            PaperTemplate t = new PaperTemplate();
            t.setId(id);
            t.setTemplateName(templateName);
            t.setSubjectId(subjectId);
            t.setPaperMode(paperMode);
            t.setTotalScore(totalScore);
            t.setTotalMinutes(totalMinutes);
            t.setSingleCount(singleCount);
            t.setSingleScore(singleScore);
            t.setMultipleCount(multipleCount);
            t.setMultipleScore(multipleScore);
            t.setJudgeCount(judgeCount);
            t.setJudgeScore(judgeScore);
            t.setFillCount(fillCount);
            t.setFillScore(fillScore);
            t.setShortCount(shortCount);
            t.setShortScore(shortScore);
            t.setProgramCount(programCount);
            t.setProgramScore(programScore);
            t.setEasyRatio(easyRatio);
            t.setMediumRatio(mediumRatio);
            t.setHardRatio(hardRatio);
            t.setKnowledgeDistribution(knowledgeDistribution);
            t.setDescription(description);
            t.setStatus(status);
            return t;
        }
    }
}
