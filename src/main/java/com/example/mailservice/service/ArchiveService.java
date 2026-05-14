package com.example.mailservice.service;

import com.example.mailservice.model.ArchiveRecord;
import com.example.mailservice.model.CategoryRule;
import com.example.mailservice.model.MailRecord;
import com.example.mailservice.repository.ArchiveRecordRepository;
import com.example.mailservice.repository.CategoryRuleRepository;
import com.example.mailservice.repository.MailRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveService {

    private final ArchiveRecordRepository archiveRecordRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final MailRecordRepository mailRecordRepository;
    private final RulePriorityService rulePriorityService;
    private final RuleConfigLoader ruleConfigLoader;

    @Transactional
    public ArchiveRecord archiveMail(String mailId, String category) {
        log.info("开始归档邮件，mailId: {}, 指定分类: {}", mailId, category);

        Optional<MailRecord> mailRecordOpt = mailRecordRepository.findByMailId(mailId);
        if (!mailRecordOpt.isPresent()) {
            log.warn("未找到邮件记录，mailId: {}", mailId);
            return null;
        }

        MailRecord mailRecord = mailRecordOpt.get();
        String finalCategory = category;

        if (finalCategory == null || finalCategory.equals("uncategorized")) {
            finalCategory = matchCategory(mailRecord);
        }

        mailRecord.setCategory(finalCategory);
        mailRecordRepository.save(mailRecord);

        ArchiveRecord archiveRecord = ArchiveRecord.builder()
                .archiveId("archive_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .mailId(mailId)
                .category(finalCategory)
                .archiveTime(LocalDateTime.now())
                .archiveStatus("archived")
                .build();

        archiveRecord = archiveRecordRepository.save(archiveRecord);
        log.info("邮件归档完成，mailId: {}, category: {}", mailId, finalCategory);

        return archiveRecord;
    }

    @Transactional
    public ArchiveRecord archiveInboundMail(String mailId, String subject, String content) {
        String category = matchCategory(subject, content);
        return archiveMail(mailId, category);
    }

    public String matchCategory(MailRecord mailRecord) {
        String searchText = (mailRecord.getSubject() != null ? mailRecord.getSubject() : "") + " " +
                            (mailRecord.getContent() != null ? mailRecord.getContent() : "");
        return matchCategory(searchText, "");
    }

    @Transactional
    public String matchCategory(String subject, String content) {
        String searchText = (subject != null ? subject : "") + " " + (content != null ? content : "");

        List<CategoryRule> rules = rulePriorityService.getActiveRulesSorted();

        for (CategoryRule rule : rules) {
            String pattern = rule.getRulePattern();
            if (pattern != null && !pattern.isEmpty()) {
                try {
                    if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(searchText).find()) {
                        log.debug("匹配分类规则，rule: {}, 分类: {}", rule.getRuleName(), rule.getTargetCategory());

                        rulePriorityService.recordMatchAndAdjust(rule.getRuleId());

                        return rule.getTargetCategory();
                    }
                } catch (Exception e) {
                    log.warn("分类规则匹配异常，ruleId: {}, error: {}", rule.getRuleId(), e.getMessage());
                }
            }
        }

        return "uncategorized";
    }

    @Transactional
    public ArchiveRecord manualCategorize(String mailId, String category) {
        Optional<MailRecord> mailRecordOpt = mailRecordRepository.findByMailId(mailId);
        if (!mailRecordOpt.isPresent()) {
            return null;
        }

        MailRecord mailRecord = mailRecordOpt.get();
        mailRecord.setCategory(category);
        mailRecordRepository.save(mailRecord);

        Optional<ArchiveRecord> existingArchive = archiveRecordRepository.findByMailId(mailId);
        if (existingArchive.isPresent()) {
            ArchiveRecord archiveRecord = existingArchive.get();
            archiveRecord.setCategory(category);
            archiveRecord.setArchiveTime(LocalDateTime.now());
            return archiveRecordRepository.save(archiveRecord);
        }

        return archiveMail(mailId, category);
    }

    public Optional<ArchiveRecord> getArchiveByMailId(String mailId) {
        return archiveRecordRepository.findByMailId(mailId);
    }

    @Transactional
    public CategoryRule createCategoryRule(CategoryRule rule) {
        rule.setRuleId("rule_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        if (rule.getEnabled() == null) {
            rule.setEnabled(true);
        }
        if (rule.getRulePriority() == null) {
            rule.setRulePriority(0);
        }
        if (rule.getDynamicPriority() == null) {
            rule.setDynamicPriority(rule.getRulePriority());
        }
        if (rule.getMatchCount() == null) {
            rule.setMatchCount(0);
        }
        return categoryRuleRepository.save(rule);
    }

    public List<CategoryRule> getActiveRules() {
        return rulePriorityService.getActiveRulesSorted();
    }

    @Transactional
    public CategoryRule updateCategoryRule(String ruleId, CategoryRule updatedRule) {
        Optional<CategoryRule> existingOpt = categoryRuleRepository.findByRuleId(ruleId);
        if (!existingOpt.isPresent()) {
            return null;
        }
        CategoryRule existing = existingOpt.get();
        existing.setRuleName(updatedRule.getRuleName());
        existing.setRulePattern(updatedRule.getRulePattern());
        existing.setTargetCategory(updatedRule.getTargetCategory());
        existing.setRulePriority(updatedRule.getRulePriority());
        if (updatedRule.getDynamicPriority() != null) {
            existing.setDynamicPriority(updatedRule.getDynamicPriority());
        }
        existing.setEnabled(updatedRule.getEnabled());
        return categoryRuleRepository.save(existing);
    }

    @Transactional
    public void deleteCategoryRule(String ruleId) {
        Optional<CategoryRule> ruleOpt = categoryRuleRepository.findByRuleId(ruleId);
        ruleOpt.ifPresent(categoryRuleRepository::delete);
    }

    public void reloadConfiguredRules() {
        ruleConfigLoader.reloadRules();
    }
}
