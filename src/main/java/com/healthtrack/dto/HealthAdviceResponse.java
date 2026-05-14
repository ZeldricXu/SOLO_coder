package com.healthtrack.dto;

import java.time.LocalDateTime;
import java.util.List;

public class HealthAdviceResponse {
    private List<AdviceInfo> advices;

    public HealthAdviceResponse() {}

    public HealthAdviceResponse(List<AdviceInfo> advices) {
        this.advices = advices;
    }

    public List<AdviceInfo> getAdvices() { return advices; }
    public void setAdvices(List<AdviceInfo> advices) { this.advices = advices; }

    public static class AdviceInfo {
        private String adviceId;
        private String adviceType;
        private String adviceContent;
        private String priority;
        private LocalDateTime generatedAt;
        private String readStatus;

        public AdviceInfo() {}

        public AdviceInfo(String adviceId, String adviceType, String adviceContent, 
                         String priority, LocalDateTime generatedAt, String readStatus) {
            this.adviceId = adviceId;
            this.adviceType = adviceType;
            this.adviceContent = adviceContent;
            this.priority = priority;
            this.generatedAt = generatedAt;
            this.readStatus = readStatus;
        }

        public String getAdviceId() { return adviceId; }
        public void setAdviceId(String adviceId) { this.adviceId = adviceId; }
        public String getAdviceType() { return adviceType; }
        public void setAdviceType(String adviceType) { this.adviceType = adviceType; }
        public String getAdviceContent() { return adviceContent; }
        public void setAdviceContent(String adviceContent) { this.adviceContent = adviceContent; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public LocalDateTime getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
        public String getReadStatus() { return readStatus; }
        public void setReadStatus(String readStatus) { this.readStatus = readStatus; }
    }
}
