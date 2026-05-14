package com.example.mailservice.service;

import com.example.mailservice.builder.TestDataBuilder;
import com.example.mailservice.model.ArchiveRecord;
import com.example.mailservice.model.CategoryRule;
import com.example.mailservice.model.MailRecord;
import com.example.mailservice.repository.ArchiveRecordRepository;
import com.example.mailservice.repository.CategoryRuleRepository;
import com.example.mailservice.repository.MailRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchiveServiceTest {

    @Mock
    private ArchiveRecordRepository archiveRecordRepository;

    @Mock
    private CategoryRuleRepository categoryRuleRepository;

    @Mock
    private MailRecordRepository mailRecordRepository;

    @InjectMocks
    private ArchiveService archiveService;

    @Captor
    private ArgumentCaptor<ArchiveRecord> archiveRecordCaptor;

    @Captor
    private ArgumentCaptor<MailRecord> mailRecordCaptor;

    @Captor
    private ArgumentCaptor<CategoryRule> categoryRuleCaptor;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounter();
    }

    @Test
    @DisplayName("规则优先级排序测试 - 降序排列")
    void testCategoryRulePriority_Sorting() {
        List<CategoryRule> unsortedRules = new ArrayList<>();
        unsortedRules.add(TestDataBuilder.CategoryRuleBuilder.create()
                .withRuleName("优先级1")
                .withPriority(1)
                .build());
        unsortedRules.add(TestDataBuilder.CategoryRuleBuilder.create()
                .withRuleName("优先级10")
                .withPriority(10)
                .build());
        unsortedRules.add(TestDataBuilder.CategoryRuleBuilder.create()
                .withRuleName("优先级5")
                .withPriority(5)
                .build());

        when(categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc())
                .thenReturn(sortRulesByPriorityDesc(unsortedRules));

        List<CategoryRule> activeRules = archiveService.getActiveRules();

        assertEquals(3, activeRules.size());
        assertEquals("优先级10", activeRules.get(0).getRuleName());
        assertEquals("优先级5", activeRules.get(1).getRuleName());
        assertEquals("优先级1", activeRules.get(2).getRuleName());
    }

    @Test
    @DisplayName("禁用规则不参与匹配测试")
    void testDisabledRules_NotIncluded() {
        List<CategoryRule> rules = TestDataBuilder.createPriorityTestRules();

        List<CategoryRule> enabledOnly = new ArrayList<>();
        for (CategoryRule rule : rules) {
            if (rule.getEnabled() != null && rule.getEnabled()) {
                enabledOnly.add(rule);
            }
        }
        Collections.sort(enabledOnly, (a, b) -> b.getRulePriority() - a.getRulePriority());

        when(categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc())
                .thenReturn(enabledOnly);

        List<CategoryRule> activeRules = archiveService.getActiveRules();

        assertEquals(3, activeRules.size());
        for (CategoryRule rule : activeRules) {
            assertTrue(rule.getEnabled());
        }
    }

    @Test
    @DisplayName("高优先级规则优先匹配测试")
    void testHighPriorityRule_MatchFirst() {
        List<CategoryRule> rules = TestDataBuilder.createPriorityTestRules();
        List<CategoryRule> enabledOnly = new ArrayList<>();
        for (CategoryRule rule : rules) {
            if (rule.getEnabled() != null && rule.getEnabled()) {
                enabledOnly.add(rule);
            }
        }
        Collections.sort(enabledOnly, (a, b) -> b.getRulePriority() - a.getRulePriority());

        when(categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc())
                .thenReturn(enabledOnly);

        String subject = "Urgent: Important meeting";
        String content = "This is an urgent project meeting.";

        String category = archiveService.matchCategory(subject, content);

        assertEquals("urgent", category);
    }

    @Test
    @DisplayName("中优先级规则匹配测试")
    void testMediumPriorityRule_Match() {
        List<CategoryRule> rules = TestDataBuilder.createPriorityTestRules();
        List<CategoryRule> enabledOnly = new ArrayList<>();
        for (CategoryRule rule : rules) {
            if (rule.getEnabled() != null && rule.getEnabled()) {
                enabledOnly.add(rule);
            }
        }
        Collections.sort(enabledOnly, (a, b) -> b.getRulePriority() - a.getRulePriority());

        when(categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc())
                .thenReturn(enabledOnly);

        String subject = "Project update";
        String content = "Please review the project meeting notes.";

        String category = archiveService.matchCategory(subject, content);

        assertEquals("work", category);
    }

    @Test
    @DisplayName("低优先级规则匹配测试")
    void testLowPriorityRule_Match() {
        List<CategoryRule> rules = TestDataBuilder.createPriorityTestRules();
        List<CategoryRule> enabledOnly = new ArrayList<>();
        for (CategoryRule rule : rules) {
            if (rule.getEnabled() != null && rule.getEnabled()) {
                enabledOnly.add(rule);
            }
        }
        Collections.sort(enabledOnly, (a, b) -> b.getRulePriority() - a.getRulePriority());

        when(categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc())
                .thenReturn(enabledOnly);

        String subject = "Team meeting agenda";
        String content = "Meeting at 3pm in conference room.";

        String category = archiveService.matchCategory(subject, content);

        assertEquals("work", category);
    }

    @Test
    @DisplayName("无规则匹配 - 应返回uncategorized")
    void testNoRuleMatch_ReturnsUncategorized() {
        List<CategoryRule> rules = TestDataBuilder.createPriorityTestRules();
        List<CategoryRule> enabledOnly = new ArrayList<>();
        for (CategoryRule rule : rules) {
            if (rule.getEnabled() != null && rule.getEnabled()) {
                enabledOnly.add(rule);
            }
        }
        Collections.sort(enabledOnly, (a, b) -> b.getRulePriority() - a.getRulePriority());

        when(categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc())
                .thenReturn(enabledOnly);

        String subject = "Holiday plans";
        String content = "Planning vacation trip.";

        String category = archiveService.matchCategory(subject, content);

        assertEquals("uncategorized", category);
    }

    @Test
    @DisplayName("同一邮件匹配多条规则 - 高优先级胜出")
    void testMultipleMatches_HighPriorityWins() {
        CategoryRule lowRule = TestDataBuilder.CategoryRuleBuilder.create()
                .withRuleName("会议规则")
                .withPattern("meeting")
                .withTargetCategory("meeting")
                .withPriority(1)
                .build();

        CategoryRule mediumRule = TestDataBuilder.CategoryRuleBuilder.create()
                .withRuleName("工作规则")
                .withPattern("project|meeting")
                .withTargetCategory("work")
                .withPriority(5)
                .build();

        CategoryRule highRule = TestDataBuilder.CategoryRuleBuilder.create()
                .withRuleName("紧急规则")
                .withPattern("urgent|meeting")
                .withTargetCategory("urgent")
                .withPriority(10)
                .build();

        List<CategoryRule> rules = Arrays.asList(highRule, mediumRule, lowRule);
        when(categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc())
                .thenReturn(rules);

        String subject = "Urgent meeting";
        String content = "Important meeting about project.";

        String category = archiveService.matchCategory(subject, content);

        assertEquals("urgent", category);
    }

    @Test
    @DisplayName("规则优先级动态调整测试")
    void testRulePriority_DynamicUpdate() {
        String ruleId = "rule_test_001";
        CategoryRule originalRule = TestDataBuilder.CategoryRuleBuilder.create()
                .withRuleId(ruleId)
                .withRuleName("测试规则")
                .withPattern("test")
                .withTargetCategory("test")
                .withPriority(1)
                .build();

        CategoryRule updatedRule = CategoryRule.builder()
                .ruleName("更新规则")
                .rulePattern("newtest")
                .targetCategory("newcat")
                .rulePriority(100)
                .enabled(true)
                .build();

        when(categoryRuleRepository.findByRuleId(ruleId)).thenReturn(Optional.of(originalRule));
        when(categoryRuleRepository.save(any(CategoryRule.class))).thenReturn(updatedRule);

        CategoryRule result = archiveService.updateCategoryRule(ruleId, updatedRule);

        verify(categoryRuleRepository, times(1)).save(categoryRuleCaptor.capture());

        CategoryRule savedRule = categoryRuleCaptor.getValue();
        assertEquals(100, savedRule.getRulePriority());
        assertEquals("newcat", savedRule.getTargetCategory());
        assertEquals("newtest", savedRule.getRulePattern());
    }

    @Test
    @DisplayName("禁用规则变为启用")
    void testRule_EnableDisabledRule() {
        String ruleId = "rule_disabled";
        CategoryRule disabledRule = TestDataBuilder.CategoryRuleBuilder.create()
                .withRuleId(ruleId)
                .withEnabled(false)
                .build();

        CategoryRule updateRequest = CategoryRule.builder()
                .enabled(true)
                .ruleName(disabledRule.getRuleName())
                .rulePattern(disabledRule.getRulePattern())
                .targetCategory(disabledRule.getTargetCategory())
                .rulePriority(disabledRule.getRulePriority())
                .build();

        when(categoryRuleRepository.findByRuleId(ruleId)).thenReturn(Optional.of(disabledRule));
        when(categoryRuleRepository.save(any(CategoryRule.class))).thenReturn(updateRequest);

        CategoryRule result = archiveService.updateCategoryRule(ruleId, updateRequest);

        verify(categoryRuleRepository, times(1)).save(categoryRuleCaptor.capture());
        assertTrue(categoryRuleCaptor.getValue().getEnabled());
    }

    @Test
    @DisplayName("创建新规则测试")
    void testCreateNewRule() {
        CategoryRule rule = CategoryRule.builder()
                .ruleName("新规则")
                .rulePattern("sales|order")
                .targetCategory("sales")
                .rulePriority(3)
                .enabled(true)
                .build();

        when(categoryRuleRepository.save(any(CategoryRule.class))).thenAnswer(invocation -> {
            CategoryRule saved = invocation.getArgument(0);
            return saved;
        });

        CategoryRule saved = archiveService.createCategoryRule(rule);

        assertNotNull(saved.getRuleId());
        assertTrue(saved.getRuleId().startsWith("rule_"));
        assertTrue(saved.getEnabled());
        assertEquals(3, saved.getRulePriority());
    }

    @Test
    @DisplayName("手动分类覆盖测试")
    void testManualCategorize_OverridesAuto() {
        String mailId = "mail_manual_001";
        MailRecord record = TestDataBuilder.MailRecordBuilder.create()
                .withMailId(mailId)
                .withCategory("uncategorized")
                .build();

        when(mailRecordRepository.findByMailId(mailId)).thenReturn(Optional.of(record));
        when(archiveRecordRepository.findByMailId(mailId)).thenReturn(Optional.empty());
        when(mailRecordRepository.save(any(MailRecord.class))).thenReturn(record);
        when(archiveRecordRepository.save(any(ArchiveRecord.class))).thenReturn(new ArchiveRecord());

        archiveService.manualCategorize(mailId, "manual_category");

        verify(mailRecordRepository, times(1)).save(mailRecordCaptor.capture());

        MailRecord saved = mailRecordCaptor.getValue();
        assertEquals("manual_category", saved.getCategory());
    }

    @Test
    @DisplayName("归档记录创建测试")
    void testArchiveRecord_Created() {
        String mailId = "mail_archive_001";
        MailRecord record = TestDataBuilder.MailRecordBuilder.create()
                .withMailId(mailId)
                .withCategory("work")
                .build();

        when(mailRecordRepository.findByMailId(mailId)).thenReturn(Optional.of(record));
        when(mailRecordRepository.save(any(MailRecord.class))).thenReturn(record);
        when(archiveRecordRepository.save(any(ArchiveRecord.class))).thenAnswer(invocation -> {
            ArchiveRecord ar = invocation.getArgument(0);
            return ar;
        });

        ArchiveRecord result = archiveService.archiveMail(mailId, "work");

        verify(archiveRecordRepository, times(1)).save(archiveRecordCaptor.capture());

        ArchiveRecord saved = archiveRecordCaptor.getValue();
        assertEquals(mailId, saved.getMailId());
        assertEquals("work", saved.getCategory());
        assertEquals("archived", saved.getArchiveStatus());
        assertNotNull(saved.getArchiveId());
        assertTrue(saved.getArchiveId().startsWith("archive_"));
    }

    @Test
    @DisplayName("归档 - 自动分类触发")
    void testArchiveWithAutoCategorization() {
        String mailId = "mail_auto_cat_001";
        MailRecord record = TestDataBuilder.MailRecordBuilder.create()
                .withMailId(mailId)
                .withCategory("uncategorized")
                .withSubject("Urgent project meeting")
                .withContent("Important meeting discussion")
                .build();

        List<CategoryRule> rules = TestDataBuilder.createPriorityTestRules();
        List<CategoryRule> enabledOnly = new ArrayList<>();
        for (CategoryRule rule : rules) {
            if (rule.getEnabled() != null && rule.getEnabled()) {
                enabledOnly.add(rule);
            }
        }
        Collections.sort(enabledOnly, (a, b) -> b.getRulePriority() - a.getRulePriority());

        when(categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc())
                .thenReturn(enabledOnly);

        when(mailRecordRepository.findByMailId(mailId)).thenReturn(Optional.of(record));
        when(mailRecordRepository.save(any(MailRecord.class))).thenReturn(record);
        when(archiveRecordRepository.save(any(ArchiveRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        archiveService.archiveMail(mailId, null);

        verify(mailRecordRepository, times(2)).save(mailRecordCaptor.capture());

        List<MailRecord> savedRecords = mailRecordCaptor.getAllValues();
        MailRecord finalRecord = savedRecords.get(savedRecords.size() - 1);
        assertNotNull(finalRecord.getCategory());
        assertEquals("urgent", finalRecord.getCategory());
    }

    @Test
    @DisplayName("规则模式匹配 - 忽略大小写")
    void testRuleMatching_CaseInsensitive() {
        CategoryRule rule = TestDataBuilder.CategoryRuleBuilder.create()
                .withPattern("URGENT|IMPORTANT")
                .withTargetCategory("urgent")
                .build();

        when(categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc())
                .thenReturn(Collections.singletonList(rule));

        String category1 = archiveService.matchCategory("urgent notice", "lowercase");
        String category2 = archiveService.matchCategory("URGENT NOTICE", "uppercase");
        String category3 = archiveService.matchCategory("Urgent Notice", "mixed case");

        assertEquals("urgent", category1);
        assertEquals("urgent", category2);
        assertEquals("urgent", category3);
    }

    @Test
    @DisplayName("无效正则模式 - 应跳过该规则")
    void testInvalidRegexPattern_ShouldSkip() {
        CategoryRule badRule = TestDataBuilder.CategoryRuleBuilder.create()
                .withPattern("[invalid(regex")
                .withTargetCategory("bad")
                .withPriority(10)
                .build();

        CategoryRule goodRule = TestDataBuilder.CategoryRuleBuilder.create()
                .withPattern("test")
                .withTargetCategory("good")
                .withPriority(1)
                .build();

        when(categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc())
                .thenReturn(Arrays.asList(badRule, goodRule));

        String category = archiveService.matchCategory("test content", "");

        assertEquals("good", category);
    }

    @Test
    @DisplayName("删除规则测试")
    void testDeleteRule() {
        String ruleId = "rule_delete_001";
        CategoryRule rule = TestDataBuilder.CategoryRuleBuilder.create()
                .withRuleId(ruleId)
                .build();

        when(categoryRuleRepository.findByRuleId(ruleId)).thenReturn(Optional.of(rule));
        doNothing().when(categoryRuleRepository).delete(any(CategoryRule.class));

        archiveService.deleteCategoryRule(ruleId);

        verify(categoryRuleRepository, times(1)).delete(rule);
    }

    @Test
    @DisplayName("删除不存在的规则 - 不应报错")
    void testDeleteNonExistentRule() {
        when(categoryRuleRepository.findByRuleId("nonexistent")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> {
            archiveService.deleteCategoryRule("nonexistent");
        });
    }

    @Test
    @DisplayName("获取归档记录测试")
    void testGetArchiveByMailId() {
        String mailId = "mail_get_001";
        ArchiveRecord archive = TestDataBuilder.ArchiveRecordBuilder.create()
                .withMailId(mailId)
                .withCategory("work")
                .build();

        when(archiveRecordRepository.findByMailId(mailId)).thenReturn(Optional.of(archive));

        Optional<ArchiveRecord> result = archiveService.getArchiveByMailId(mailId);

        assertTrue(result.isPresent());
        assertEquals(mailId, result.get().getMailId());
    }

    private List<CategoryRule> sortRulesByPriorityDesc(List<CategoryRule> rules) {
        List<CategoryRule> sorted = new ArrayList<>(rules);
        sorted.sort((a, b) -> {
            int priorityA = a.getRulePriority() != null ? a.getRulePriority() : 0;
            int priorityB = b.getRulePriority() != null ? b.getRulePriority() : 0;
            return priorityB - priorityA;
        });
        return sorted;
    }
}
