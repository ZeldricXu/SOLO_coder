package com.example.mailservice.builder;

import com.example.mailservice.dto.MailSendRequest;
import com.example.mailservice.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TestDataBuilder {

    private static final AtomicInteger counter = new AtomicInteger(1);

    public static void resetCounter() {
        counter.set(1);
    }

    private static int nextId() {
        return counter.getAndIncrement();
    }

    public static class MailRecordBuilder {
        private String mailId;
        private String mailType = "outbound";
        private String sender = "test@example.com";
        private List<String> recipients = new ArrayList<>();
        private String subject;
        private String content;
        private List<String> attachments = new ArrayList<>();
        private String mailStatus = "pending";
        private String category = "uncategorized";
        private LocalDateTime sentAt;
        private LocalDateTime createdAt;

        public static MailRecordBuilder create() {
            return new MailRecordBuilder();
        }

        public MailRecordBuilder withMailId(String mailId) {
            this.mailId = mailId;
            return this;
        }

        public MailRecordBuilder withMailType(String mailType) {
            this.mailType = mailType;
            return this;
        }

        public MailRecordBuilder withSender(String sender) {
            this.sender = sender;
            return this;
        }

        public MailRecordBuilder withRecipient(String recipient) {
            this.recipients.add(recipient);
            return this;
        }

        public MailRecordBuilder withRecipients(List<String> recipients) {
            this.recipients = new ArrayList<>(recipients);
            return this;
        }

        public MailRecordBuilder withSubject(String subject) {
            this.subject = subject;
            return this;
        }

        public MailRecordBuilder withContent(String content) {
            this.content = content;
            return this;
        }

        public MailRecordBuilder withAttachment(String attachment) {
            this.attachments.add(attachment);
            return this;
        }

        public MailRecordBuilder withStatus(String status) {
            this.mailStatus = status;
            return this;
        }

        public MailRecordBuilder withCategory(String category) {
            this.category = category;
            return this;
        }

        public MailRecordBuilder withSentAt(LocalDateTime sentAt) {
            this.sentAt = sentAt;
            return this;
        }

        public MailRecordBuilder withCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public MailRecord build() {
            int id = nextId();
            return MailRecord.builder()
                    .id((long) id)
                    .mailId(this.mailId != null ? this.mailId : "mail_" + String.format("%03d", id))
                    .mailType(this.mailType)
                    .sender(this.sender)
                    .recipients(String.join(",", this.recipients))
                    .subject(this.subject != null ? this.subject : "测试邮件 #" + id)
                    .content(this.content != null ? this.content : "这是测试邮件的内容 #" + id)
                    .attachments(this.attachments.isEmpty() ? null : String.join(",", this.attachments))
                    .mailStatus(this.mailStatus)
                    .category(this.category)
                    .sentAt(this.sentAt != null ? this.sentAt : LocalDateTime.now())
                    .createdAt(this.createdAt != null ? this.createdAt : LocalDateTime.now())
                    .build();
        }
    }

    public static class CategoryRuleBuilder {
        private String ruleId;
        private String ruleName;
        private String rulePattern;
        private String targetCategory;
        private Integer rulePriority = 0;
        private Boolean enabled = true;
        private LocalDateTime createdAt;

        public static CategoryRuleBuilder create() {
            return new CategoryRuleBuilder();
        }

        public CategoryRuleBuilder withRuleId(String ruleId) {
            this.ruleId = ruleId;
            return this;
        }

        public CategoryRuleBuilder withRuleName(String ruleName) {
            this.ruleName = ruleName;
            return this;
        }

        public CategoryRuleBuilder withPattern(String pattern) {
            this.rulePattern = pattern;
            return this;
        }

        public CategoryRuleBuilder withTargetCategory(String category) {
            this.targetCategory = category;
            return this;
        }

        public CategoryRuleBuilder withPriority(int priority) {
            this.rulePriority = priority;
            return this;
        }

        public CategoryRuleBuilder withEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public CategoryRule build() {
            int id = nextId();
            return CategoryRule.builder()
                    .id((long) id)
                    .ruleId(this.ruleId != null ? this.ruleId : "rule_" + String.format("%03d", id))
                    .ruleName(this.ruleName != null ? this.ruleName : "分类规则 #" + id)
                    .rulePattern(this.rulePattern != null ? this.rulePattern : "test" + id)
                    .targetCategory(this.targetCategory != null ? this.targetCategory : "category" + id)
                    .rulePriority(this.rulePriority)
                    .enabled(this.enabled)
                    .createdAt(LocalDateTime.now())
                    .build();
        }
    }

    public static class MailSendRequestBuilder {
        private List<String> recipients = new ArrayList<>();
        private String subject;
        private String content;
        private String contentType;
        private String category;
        private List<MailSendRequest.AttachmentInfo> attachments = new ArrayList<>();
        private String templateId;

        public static MailSendRequestBuilder create() {
            return new MailSendRequestBuilder();
        }

        public MailSendRequestBuilder withRecipient(String recipient) {
            this.recipients.add(recipient);
            return this;
        }

        public MailSendRequestBuilder withRecipients(List<String> recipients) {
            this.recipients = new ArrayList<>(recipients);
            return this;
        }

        public MailSendRequestBuilder withSubject(String subject) {
            this.subject = subject;
            return this;
        }

        public MailSendRequestBuilder withContent(String content) {
            this.content = content;
            return this;
        }

        public MailSendRequestBuilder withContentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public MailSendRequestBuilder withCategory(String category) {
            this.category = category;
            return this;
        }

        public MailSendRequestBuilder withAttachment(String fileName, String contentType, byte[] content) {
            this.attachments.add(MailSendRequest.AttachmentInfo.builder()
                    .fileName(fileName)
                    .contentType(contentType)
                    .content(content)
                    .build());
            return this;
        }

        public MailSendRequest build() {
            return MailSendRequest.builder()
                    .recipients(this.recipients.isEmpty() ?
                            Arrays.asList("receiver@example.com") : this.recipients)
                    .subject(this.subject != null ? this.subject : "测试邮件")
                    .content(this.content != null ? this.content : "测试邮件内容")
                    .contentType(this.contentType != null ? this.contentType : "text/plain")
                    .category(this.category)
                    .attachments(this.attachments.isEmpty() ? null : this.attachments)
                    .templateId(this.templateId)
                    .build();
        }
    }

    public static class SendStatusBuilder {
        private String statusId;
        private String mailId;
        private String sendStatus;
        private String smtpResponse;
        private String errorMessage;
        private Integer sendAttempts = 1;
        private LocalDateTime lastAttempt;

        public static SendStatusBuilder create() {
            return new SendStatusBuilder();
        }

        public SendStatusBuilder withMailId(String mailId) {
            this.mailId = mailId;
            return this;
        }

        public SendStatusBuilder withStatus(String status) {
            this.sendStatus = status;
            return this;
        }

        public SendStatusBuilder withSmtpResponse(String response) {
            this.smtpResponse = response;
            return this;
        }

        public SendStatusBuilder withErrorMessage(String message) {
            this.errorMessage = message;
            return this;
        }

        public SendStatusBuilder withAttempts(int attempts) {
            this.sendAttempts = attempts;
            return this;
        }

        public SendStatus build() {
            int id = nextId();
            return SendStatus.builder()
                    .id((long) id)
                    .statusId(this.statusId != null ? this.statusId : "status_" + String.format("%03d", id))
                    .mailId(this.mailId != null ? this.mailId : "mail_" + String.format("%03d", id))
                    .sendStatus(this.sendStatus != null ? this.sendStatus : "success")
                    .smtpResponse(this.smtpResponse)
                    .errorMessage(this.errorMessage)
                    .sendAttempts(this.sendAttempts)
                    .lastAttempt(this.lastAttempt != null ? this.lastAttempt : LocalDateTime.now())
                    .build();
        }
    }

    public static class MailTemplateBuilder {
        private String templateId;
        private String templateName;
        private String templateSubject;
        private String templateContent;
        private String variables;
        private Boolean enabled = true;

        public static MailTemplateBuilder create() {
            return new MailTemplateBuilder();
        }

        public MailTemplateBuilder withTemplateId(String templateId) {
            this.templateId = templateId;
            return this;
        }

        public MailTemplateBuilder withTemplateName(String templateName) {
            this.templateName = templateName;
            return this;
        }

        public MailTemplateBuilder withSubject(String subject) {
            this.templateSubject = subject;
            return this;
        }

        public MailTemplateBuilder withContent(String content) {
            this.templateContent = content;
            return this;
        }

        public MailTemplateBuilder withVariables(String variables) {
            this.variables = variables;
            return this;
        }

        public MailTemplateBuilder withEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public MailTemplate build() {
            int id = nextId();
            return MailTemplate.builder()
                    .id((long) id)
                    .templateId(this.templateId != null ? this.templateId : "tpl_" + String.format("%03d", id))
                    .templateName(this.templateName != null ? this.templateName : "模板 #" + id)
                    .templateSubject(this.templateSubject != null ?
                            this.templateSubject : "模板主题 #" + id)
                    .templateContent(this.templateContent != null ?
                            this.templateContent : "模板内容 {{name}}, {{date}}")
                    .variables(this.variables)
                    .enabled(this.enabled)
                    .build();
        }
    }

    public static class ArchiveRecordBuilder {
        private String archiveId;
        private String mailId;
        private String category;
        private String archiveStatus = "archived";
        private LocalDateTime archiveTime;

        public static ArchiveRecordBuilder create() {
            return new ArchiveRecordBuilder();
        }

        public ArchiveRecordBuilder withMailId(String mailId) {
            this.mailId = mailId;
            return this;
        }

        public ArchiveRecordBuilder withCategory(String category) {
            this.category = category;
            return this;
        }

        public ArchiveRecordBuilder withStatus(String status) {
            this.archiveStatus = status;
            return this;
        }

        public ArchiveRecord build() {
            int id = nextId();
            return ArchiveRecord.builder()
                    .id((long) id)
                    .archiveId(this.archiveId != null ? this.archiveId : "archive_" + String.format("%03d", id))
                    .mailId(this.mailId != null ? this.mailId : "mail_" + String.format("%03d", id))
                    .category(this.category != null ? this.category : "work")
                    .archiveTime(this.archiveTime != null ? this.archiveTime : LocalDateTime.now())
                    .archiveStatus(this.archiveStatus)
                    .build();
        }
    }

    public static class MailStatisticsBuilder {
        private String statId;
        private LocalDate statDate;
        private Integer sentCount = 0;
        private Integer receivedCount = 0;
        private Integer failedCount = 0;
        private Integer avgResponseTime = 0;

        public static MailStatisticsBuilder create() {
            return new MailStatisticsBuilder();
        }

        public MailStatisticsBuilder withDate(LocalDate date) {
            this.statDate = date;
            return this;
        }

        public MailStatisticsBuilder withSentCount(int count) {
            this.sentCount = count;
            return this;
        }

        public MailStatisticsBuilder withReceivedCount(int count) {
            this.receivedCount = count;
            return this;
        }

        public MailStatisticsBuilder withFailedCount(int count) {
            this.failedCount = count;
            return this;
        }

        public MailStatistics build() {
            int id = nextId();
            return MailStatistics.builder()
                    .id((long) id)
                    .statId(this.statId != null ? this.statId : "stat_" + String.format("%03d", id))
                    .statDate(this.statDate != null ? this.statDate : LocalDate.now())
                    .sentCount(this.sentCount)
                    .receivedCount(this.receivedCount)
                    .failedCount(this.failedCount)
                    .avgResponseTime(this.avgResponseTime)
                    .build();
        }
    }

    public static List<CategoryRule> createPriorityTestRules() {
        List<CategoryRule> rules = new ArrayList<>();
        rules.add(CategoryRuleBuilder.create()
                .withRuleName("低优先级规则")
                .withPattern("meeting")
                .withTargetCategory("meeting")
                .withPriority(1)
                .build());
        rules.add(CategoryRuleBuilder.create()
                .withRuleName("中优先级规则")
                .withPattern("project|meeting")
                .withTargetCategory("work")
                .withPriority(5)
                .build());
        rules.add(CategoryRuleBuilder.create()
                .withRuleName("高优先级规则")
                .withPattern("urgent|important")
                .withTargetCategory("urgent")
                .withPriority(10)
                .build());
        rules.add(CategoryRuleBuilder.create()
                .withRuleName("禁用规则")
                .withPattern("test")
                .withTargetCategory("test")
                .withPriority(15)
                .withEnabled(false)
                .build());
        return rules;
    }

    public static Map<String, String> createTemplateVariables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("name", "张三");
        variables.put("company", "ABC公司");
        variables.put("date", "2026-05-10");
        variables.put("amount", "1,000.00");
        return variables;
    }
}
