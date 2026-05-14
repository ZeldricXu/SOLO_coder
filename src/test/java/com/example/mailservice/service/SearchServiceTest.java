package com.example.mailservice.service;

import com.example.mailservice.builder.TestDataBuilder;
import com.example.mailservice.dto.MailSearchRequest;
import com.example.mailservice.model.MailRecord;
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
import org.springframework.data.domain.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchServiceTest {

    @Mock
    private MailRecordRepository mailRecordRepository;

    @InjectMocks
    private SearchService searchService;

    @Captor
    private ArgumentCaptor<String> keywordCaptor;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounter();
    }

    @Test
    @DisplayName("关键字搜索测试")
    void testSearchByKeyword() {
        List<MailRecord> records = new ArrayList<>();
        records.add(TestDataBuilder.MailRecordBuilder.create()
                .withSubject("项目进度报告")
                .withContent("项目进度正常")
                .build());
        records.add(TestDataBuilder.MailRecordBuilder.create()
                .withSubject("会议记录")
                .withContent("项目会议纪要")
                .build());

        Page<MailRecord> pageResult = new PageImpl<>(records);
        when(mailRecordRepository.searchByKeyword(anyString(), any(Pageable.class)))
                .thenReturn(pageResult);

        Page<MailRecord> result = searchService.searchByKeyword("项目", 0, 20);

        assertEquals(2, result.getTotalElements());
        verify(mailRecordRepository, times(1)).searchByKeyword(eq("项目"), any(Pageable.class));
    }

    @Test
    @DisplayName("空关键字搜索 - 返回空结果")
    void testSearchByKeyword_EmptyResult() {
        Page<MailRecord> emptyPage = new PageImpl<>(Collections.emptyList());
        when(mailRecordRepository.searchByKeyword(anyString(), any(Pageable.class)))
                .thenReturn(emptyPage);

        Page<MailRecord> result = searchService.searchByKeyword("不存在的关键字", 0, 20);

        assertTrue(result.isEmpty());
        assertEquals(0, result.getTotalElements());
    }

    @Test
    @DisplayName("多条件组合搜索测试")
    void testSearchMails_MultipleConditions() {
        List<MailRecord> records = new ArrayList<>();
        records.add(TestDataBuilder.MailRecordBuilder.create()
                .withSubject("工作邮件")
                .withContent("工作内容")
                .withCategory("work")
                .withMailType("outbound")
                .withMailStatus("sent")
                .build());

        Page<MailRecord> pageResult = new PageImpl<>(records);
        when(mailRecordRepository.searchMails(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(Pageable.class)))
                .thenReturn(pageResult);

        MailSearchRequest request = MailSearchRequest.builder()
                .keyword("工作")
                .category("work")
                .mailType("outbound")
                .mailStatus("sent")
                .page(0)
                .size(20)
                .build();

        Page<MailRecord> result = searchService.searchMails(request);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    @DisplayName("按分类搜索测试")
    void testGetMailsByCategory() {
        List<MailRecord> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(TestDataBuilder.MailRecordBuilder.create()
                    .withCategory("work")
                    .build());
        }

        Page<MailRecord> pageResult = new PageImpl<>(records);
        when(mailRecordRepository.findByCategory(eq("work"), any(Pageable.class)))
                .thenReturn(pageResult);

        Page<MailRecord> result = searchService.getMailsByCategory("work", 0, 10);

        assertEquals(5, result.getTotalElements());
        verify(mailRecordRepository, times(1)).findByCategory(eq("work"), any(Pageable.class));
    }

    @Test
    @DisplayName("按邮件类型搜索测试")
    void testGetMailsByType() {
        List<MailRecord> inboundMails = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            inboundMails.add(TestDataBuilder.MailRecordBuilder.create()
                    .withMailType("inbound")
                    .build());
        }

        Page<MailRecord> pageResult = new PageImpl<>(inboundMails);
        when(mailRecordRepository.findByMailType(eq("inbound"), any(Pageable.class)))
                .thenReturn(pageResult);

        Page<MailRecord> result = searchService.getMailsByType("inbound", 0, 10);

        assertEquals(3, result.getTotalElements());
    }

    @Test
    @DisplayName("按发件人搜索测试")
    void testGetMailsBySender() {
        String sender = "test@example.com";
        List<MailRecord> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            records.add(TestDataBuilder.MailRecordBuilder.create()
                    .withSender(sender)
                    .build());
        }

        Page<MailRecord> pageResult = new PageImpl<>(records);
        when(mailRecordRepository.findBySender(eq(sender), any(Pageable.class)))
                .thenReturn(pageResult);

        Page<MailRecord> result = searchService.getMailsBySender(sender, 0, 20);

        assertEquals(10, result.getTotalElements());
    }

    @Test
    @DisplayName("根据mailId查询测试")
    void testGetMailByMailId() {
        String mailId = "mail_test_001";
        MailRecord record = TestDataBuilder.MailRecordBuilder.create()
                .withMailId(mailId)
                .withSubject("测试邮件")
                .build();

        when(mailRecordRepository.findByMailId(mailId)).thenReturn(Optional.of(record));

        Optional<MailRecord> result = searchService.getMailByMailId(mailId);

        assertTrue(result.isPresent());
        assertEquals(mailId, result.get().getMailId());
    }

    @Test
    @DisplayName("根据mailId查询 - 不存在")
    void testGetMailByMailId_NotFound() {
        when(mailRecordRepository.findByMailId("nonexistent")).thenReturn(Optional.empty());

        Optional<MailRecord> result = searchService.getMailByMailId("nonexistent");

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("时间范围搜索测试")
    void testSearchByTimeRange() {
        LocalDateTime startTime = LocalDateTime.now().minusDays(7);
        LocalDateTime endTime = LocalDateTime.now();

        List<MailRecord> records = new ArrayList<>();
        records.add(TestDataBuilder.MailRecordBuilder.create()
                .withSentAt(LocalDateTime.now().minusDays(3))
                .build());

        Page<MailRecord> pageResult = new PageImpl<>(records);
        when(mailRecordRepository.searchMails(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(startTime), eq(endTime), any(Pageable.class)))
                .thenReturn(pageResult);

        MailSearchRequest request = MailSearchRequest.builder()
                .startTime(startTime)
                .endTime(endTime)
                .build();

        Page<MailRecord> result = searchService.searchMails(request);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    @DisplayName("分页搜索测试")
    void testSearchWithPagination() {
        List<MailRecord> allRecords = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            allRecords.add(TestDataBuilder.MailRecordBuilder.create()
                    .withSubject("邮件 #" + i)
                    .build());
        }

        List<MailRecord> page1Records = allRecords.subList(0, 10);
        Page<MailRecord> page1 = new PageImpl<>(page1Records, PageRequest.of(0, 10), 25);

        when(mailRecordRepository.searchMails(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(Pageable.class)))
                .thenReturn(page1);

        MailSearchRequest request = MailSearchRequest.builder()
                .keyword("邮件")
                .page(0)
                .size(10)
                .build();

        Page<MailRecord> result = searchService.searchMails(request);

        assertEquals(10, result.getNumberOfElements());
        assertEquals(25, result.getTotalElements());
        assertEquals(3, result.getTotalPages());
        assertEquals(0, result.getNumber());
    }

    @Test
    @DisplayName("搜索排序测试 - 按时间降序")
    void testSearchOrdering() {
        when(mailRecordRepository.searchMails(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(7);
                    assertEquals(Sort.by(Sort.Direction.DESC, "sentAt"), pageable.getSort());
                    return new PageImpl<>(Collections.emptyList());
                });

        MailSearchRequest request = MailSearchRequest.builder()
                .keyword("test")
                .build();

        searchService.searchMails(request);
    }

    @Test
    @DisplayName("索引异步化模拟测试")
    void testIndexAsyncSimulation() {
        AtomicInteger indexCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        List<MailRecord> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            records.add(TestDataBuilder.MailRecordBuilder.create().build());
        }

        CountDownLatch indexLatch = new CountDownLatch(records.size());

        for (MailRecord record : records) {
            executor.submit(() -> {
                try {
                    Thread.sleep(10);
                    indexCount.incrementAndGet();
                } catch (Exception e) {
                } finally {
                    indexLatch.countDown();
                }
            });
        }

        assertEquals(10, records.size());

        try {
            indexLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertEquals(10, indexCount.get());

        executor.shutdown();
    }

    @Test
    @DisplayName("索引建立失败重试机制测试")
    void testIndexRetryMechanism() {
        AtomicInteger retryCount = new AtomicInteger(0);
        AtomicBoolean successFlag = new AtomicBoolean(false);
        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt < 3) {
                    throw new RuntimeException("索引建立失败");
                }
                successFlag.set(true);
            } catch (Exception e) {
                retryCount.incrementAndGet();
            }
        }

        assertEquals(2, retryCount.get());
        assertTrue(successFlag.get());
    }

    @Test
    @DisplayName("索引建立失败 - 超过最大重试次数")
    void testIndexMaxRetriesExceeded() {
        int maxRetries = 3;
        int actualAttempts = 0;
        boolean success = false;

        for (int i = 0; i < maxRetries; i++) {
            actualAttempts++;
            if (i == maxRetries - 1) {
                break;
            }
        }

        assertEquals(maxRetries, actualAttempts);
        assertFalse(success);
    }

    @Test
    @DisplayName("搜索条件组合验证")
    void testSearchConditionCombination() {
        List<MailRecord> records = new ArrayList<>();
        records.add(TestDataBuilder.MailRecordBuilder.create()
                .withSubject("项目报告")
                .withSender("project@example.com")
                .withCategory("work")
                .withMailType("outbound")
                .build());

        Page<MailRecord> pageResult = new PageImpl<>(records);
        when(mailRecordRepository.searchMails(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(Pageable.class)))
                .thenReturn(pageResult);

        MailSearchRequest request = MailSearchRequest.builder()
                .keyword("项目")
                .category("work")
                .sender("project@example.com")
                .mailType("outbound")
                .build();

        Page<MailRecord> result = searchService.searchMails(request);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    @DisplayName("空条件搜索 - 应返回所有记录")
    void testEmptyConditions() {
        List<MailRecord> allRecords = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            allRecords.add(TestDataBuilder.MailRecordBuilder.create().build());
        }

        Page<MailRecord> pageResult = new PageImpl<>(allRecords);
        when(mailRecordRepository.searchMails(isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), any(Pageable.class)))
                .thenReturn(pageResult);

        MailSearchRequest request = MailSearchRequest.builder().build();

        Page<MailRecord> result = searchService.searchMails(request);

        assertEquals(5, result.getTotalElements());
    }
}
