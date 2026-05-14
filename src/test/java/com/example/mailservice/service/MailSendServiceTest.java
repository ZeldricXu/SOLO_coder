package com.example.mailservice.service;

import com.example.mailservice.builder.TestDataBuilder;
import com.example.mailservice.config.AppConfig;
import com.example.mailservice.dto.MailSendRequest;
import com.example.mailservice.model.MailRecord;
import com.example.mailservice.model.SendStatus;
import com.example.mailservice.repository.MailRecordRepository;
import com.example.mailservice.repository.SendStatusRepository;
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
import org.springframework.mail.javamail.JavaMailSender;

import javax.mail.internet.MimeMessage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailSendServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private MailRecordRepository mailRecordRepository;

    @Mock
    private SendStatusRepository sendStatusRepository;

    @Mock
    private ArchiveService archiveService;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private StatusService statusService;

    @Mock
    private HistoryService historyService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private AppConfig appConfig;

    @InjectMocks
    private MailSendService mailSendService;

    @Captor
    private ArgumentCaptor<MailRecord> mailRecordCaptor;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounter();
        AppConfig.MailConfig mailConfig = new AppConfig.MailConfig();
        mailConfig.setMaxAttachmentSize(10485760L);
        mailConfig.setRetryCount(3);
        mailConfig.setRetryInterval(5000L);
        when(appConfig.getMail()).thenReturn(mailConfig);
    }

    @Test
    @DisplayName("发送成功测试 - 收件人校验通过")
    void testSendMail_ValidRecipient() throws Exception {
        MailSendRequest request = TestDataBuilder.MailSendRequestBuilder.create()
                .withRecipient("valid@example.com")
                .withSubject("测试邮件")
                .withContent("这是测试内容")
                .build();

        when(mailRecordRepository.save(any(MailRecord.class))).thenAnswer(invocation -> {
            MailRecord record = invocation.getArgument(0);
            return record;
        });

        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        MailSendService.SendResult result = mailSendService.sendMail(request);

        assertTrue(result.isSuccess());
        assertNotNull(result.getMailId());
        assertEquals("sent", result.getStatus());

        verify(mailRecordRepository, times(2)).save(any(MailRecord.class));
        verify(statusService, times(1)).createStatus(anyString(), eq("success"), anyString(), isNull());
        verify(archiveService, times(1)).archiveMail(anyString(), isNull());
        verify(historyService, times(1)).recordHistory(anyString(), eq("SENT"), anyString(), eq("system"));
        verify(analysisService, times(1)).incrementSentCount();
    }

    @Test
    @DisplayName("收件人无效测试 - 应返回失败")
    void testSendMail_InvalidRecipient() {
        MailSendRequest request = TestDataBuilder.MailSendRequestBuilder.create()
                .withRecipient("invalid-email")
                .build();

        MailSendService.SendResult result = mailSendService.sendMail(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("无效的收件人地址"));

        verify(mailRecordRepository, never()).save(any(MailRecord.class));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("多个收件人 - 部分无效测试")
    void testSendMail_PartialInvalidRecipients() {
        List<String> recipients = new ArrayList<>();
        recipients.add("valid1@example.com");
        recipients.add("invalid-email");
        recipients.add("valid2@example.com");

        MailSendRequest request = TestDataBuilder.MailSendRequestBuilder.create()
                .withRecipients(recipients)
                .build();

        MailSendService.SendResult result = mailSendService.sendMail(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("invalid-email"));
    }

    @Test
    @DisplayName("附件大小超出限制测试")
    void testSendMail_AttachmentTooLarge() {
        byte[] largeContent = new byte[15 * 1024 * 1024];
        MailSendRequest.AttachmentInfo largeAttachment = MailSendRequest.AttachmentInfo.builder()
                .fileName("large.pdf")
                .contentType("application/pdf")
                .content(largeContent)
                .build();

        MailSendRequest request = TestDataBuilder.MailSendRequestBuilder.create()
                .withRecipient("test@example.com")
                .build();
        request.setAttachments(Collections.singletonList(largeAttachment));

        MailSendService.SendResult result = mailSendService.sendMail(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("附件总大小超出限制"));
    }

    @Test
    @DisplayName("正常附件大小测试")
    void testSendMail_ValidAttachmentSize() {
        byte[] smallContent = new byte[1024 * 1024];
        MailSendRequest.AttachmentInfo smallAttachment = MailSendRequest.AttachmentInfo.builder()
                .fileName("small.pdf")
                .contentType("application/pdf")
                .content(smallContent)
                .build();

        MailSendRequest request = TestDataBuilder.MailSendRequestBuilder.create()
                .withRecipient("test@example.com")
                .build();
        request.setAttachments(Collections.singletonList(smallAttachment));

        when(mailRecordRepository.save(any(MailRecord.class))).thenAnswer(invocation -> {
            MailRecord record = invocation.getArgument(0);
            return record;
        });
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        MailSendService.SendResult result = mailSendService.sendMail(request);

        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("发送失败 - SMTP异常测试")
    void testSendMail_SMTPFailure() throws Exception {
        MailSendRequest request = TestDataBuilder.MailSendRequestBuilder.create()
                .withRecipient("test@example.com")
                .withSubject("失败测试邮件")
                .build();

        when(mailRecordRepository.save(any(MailRecord.class))).thenAnswer(invocation -> {
            MailRecord record = invocation.getArgument(0);
            return record;
        });
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));
        doThrow(new RuntimeException("SMTP连接失败")).when(mailSender).send(any(MimeMessage.class));

        MailSendService.SendResult result = mailSendService.sendMail(request);

        assertFalse(result.isSuccess());
        assertEquals("failed", result.getStatus());
        assertTrue(result.getMessage().contains("SMTP连接失败"));

        verify(statusService, times(1)).createStatus(anyString(), eq("failed"), isNull(), anyString());
        verify(historyService, times(1)).recordHistory(anyString(), eq("SEND_FAILED"), anyString(), eq("system"));
        verify(analysisService, times(1)).incrementFailedCount();
        verify(archiveService, never()).archiveMail(anyString(), isNull());
    }

    @Test
    @DisplayName("邮件状态更新 - 发送成功")
    void testSendMail_StatusUpdateOnSuccess() throws Exception {
        MailSendRequest request = TestDataBuilder.MailSendRequestBuilder.create()
                .withRecipient("success@example.com")
                .withSubject("成功邮件")
                .build();

        when(mailRecordRepository.save(any(MailRecord.class))).thenAnswer(invocation -> {
            MailRecord record = invocation.getArgument(0);
            return record;
        });
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        mailSendService.sendMail(request);

        verify(mailRecordRepository, times(2)).save(mailRecordCaptor.capture());

        List<MailRecord> savedRecords = mailRecordCaptor.getAllValues();
        assertEquals("sent", savedRecords.get(1).getMailStatus());
        assertNotNull(savedRecords.get(1).getSentAt());
    }

    @Test
    @DisplayName("分类归档触发测试")
    void testSendMail_ArchiveTriggered() throws Exception {
        MailSendRequest request = TestDataBuilder.MailSendRequestBuilder.create()
                .withRecipient("archive@example.com")
                .withCategory("work")
                .build();

        when(mailRecordRepository.save(any(MailRecord.class))).thenAnswer(invocation -> {
            MailRecord record = invocation.getArgument(0);
            return record;
        });
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        mailSendService.sendMail(request);

        verify(archiveService, times(1)).archiveMail(anyString(), eq("work"));
    }

    @Test
    @DisplayName("异步发送测试 - 高并发场景")
    void testAsyncSend_ConcurrentRequests() throws Exception {
        when(mailRecordRepository.save(any(MailRecord.class))).thenAnswer(invocation -> {
            MailRecord record = invocation.getArgument(0);
            return record;
        });
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        int concurrentCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(concurrentCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentCount; i++) {
            final int index = i;
            Future<?> future = executor.submit(() -> {
                try {
                    MailSendRequest req = TestDataBuilder.MailSendRequestBuilder.create()
                            .withRecipient("user" + index + "@example.com")
                            .withSubject("并发测试邮件 #" + index)
                            .build();
                    MailSendService.SendResult result = mailSendService.sendMail(req);
                    if (result.isSuccess()) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }

        latch.await(30, TimeUnit.SECONDS);

        assertEquals(concurrentCount, successCount.get());

        for (Future<?> future : futures) {
            assertTrue(future.isDone());
            assertFalse(future.isCancelled());
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("发送结果验证")
    void testSendResult_VerifyResultStructure() throws Exception {
        MailSendRequest request = TestDataBuilder.MailSendRequestBuilder.create()
                .withRecipient("result@example.com")
                .build();

        when(mailRecordRepository.save(any(MailRecord.class))).thenAnswer(invocation -> {
            MailRecord record = invocation.getArgument(0);
            return record;
        });
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        MailSendService.SendResult result = mailSendService.sendMail(request);

        assertTrue(result.isSuccess());
        assertNotNull(result.getMailId());
        assertTrue(result.getMailId().startsWith("mail_"));
        assertEquals("sent", result.getStatus());
        assertNotNull(result.getMessage());
    }

    @Test
    @DisplayName("重试机制 - 状态追踪测试")
    void testRetryMechanism_StatusTracking() {
        String mailId = "mail_retry_001";
        SendStatus status = TestDataBuilder.SendStatusBuilder.create()
                .withMailId(mailId)
                .withStatus("failed")
                .withErrorMessage("SMTP timeout")
                .withAttempts(1)
                .build();

        when(sendStatusRepository.findByMailId(mailId)).thenReturn(Optional.of(status));
        when(sendStatusRepository.save(any(SendStatus.class))).thenReturn(status);

        StatusService testStatusService = new StatusService(sendStatusRepository, appConfig);
        testStatusService.incrementAttempts(mailId);

        verify(sendStatusRepository, times(1)).findByMailId(mailId);
    }

    @Test
    @DisplayName("重试列表查询 - 检查失败状态")
    void testGetFailedStatuses() {
        List<SendStatus> failedStatuses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            failedStatuses.add(TestDataBuilder.SendStatusBuilder.create()
                    .withStatus("failed")
                    .withAttempts(i)
                    .build());
        }

        when(sendStatusRepository.findBySendStatusAndSendAttemptsLessThan("failed", 3))
                .thenReturn(failedStatuses);

        StatusService testStatusService = new StatusService(sendStatusRepository, appConfig);
        List<SendStatus> result = testStatusService.getFailedStatusesForRetry();

        assertEquals(3, result.size());
        verify(sendStatusRepository, times(1))
                .findBySendStatusAndSendAttemptsLessThan("failed", 3);
    }
}
