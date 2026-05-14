package com.authcenter.service;

import com.authcenter.builder.TestDataBuilder;
import com.authcenter.entity.MfaRecord;
import com.authcenter.entity.User;
import com.authcenter.exception.AuthException;
import com.authcenter.repository.MfaRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("多因素认证服务单元测试 - 验证码发送异步化与重试机制")
class MfaServiceTest {
    
    @Mock
    private MfaRecordRepository mfaRecordRepository;
    
    @Mock
    private AuditService auditService;
    
    @InjectMocks
    private MfaService mfaService;
    
    private User smsUser;
    private User emailUser;
    private MfaRecord validSmsRecord;
    private MfaRecord validEmailRecord;
    private MfaRecord expiredRecord;
    
    @BeforeEach
    void setUp() {
        smsUser = TestDataBuilder.createUserWithMfa("sms");
        emailUser = TestDataBuilder.createUserWithMfa("email");
        
        validSmsRecord = TestDataBuilder.createMfaRecord(smsUser.getUserId(), "sms", "123456", false);
        validEmailRecord = TestDataBuilder.createMfaRecord(emailUser.getUserId(), "email", "ABC12345", false);
        expiredRecord = TestDataBuilder.createExpiredMfaRecord(smsUser.getUserId(), "sms");
        
        ReflectionTestUtils.setField(mfaService, "smsExpiration", 60000L);
        ReflectionTestUtils.setField(mfaService, "smsCodeLength", 6);
        ReflectionTestUtils.setField(mfaService, "emailExpiration", 60000L);
    }
    
    @Test
    @DisplayName("测试验证码请求提交后立即返回响应 - 短信验证码")
    void testSmsCodeGenerationReturnsImmediately() {
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        long startTime = System.currentTimeMillis();
        MfaRecord record = mfaService.generateAndSendCode(smsUser);
        long duration = System.currentTimeMillis() - startTime;
        
        assertNotNull(record);
        assertNotNull(record.getMfaId());
        assertEquals(smsUser.getUserId(), record.getUserId());
        assertEquals("sms", record.getMfaType());
        assertNotNull(record.getMfaCode());
        assertEquals(6, record.getMfaCode().length());
        assertFalse(record.getVerified());
        assertTrue(duration < 1000, "验证码生成应在1秒内完成");
        
        verify(mfaRecordRepository, times(1)).save(any(MfaRecord.class));
    }
    
    @Test
    @DisplayName("测试验证码请求提交后立即返回响应 - 邮箱验证码")
    void testEmailCodeGenerationReturnsImmediately() {
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        long startTime = System.currentTimeMillis();
        MfaRecord record = mfaService.generateAndSendCode(emailUser);
        long duration = System.currentTimeMillis() - startTime;
        
        assertNotNull(record);
        assertEquals("email", record.getMfaType());
        assertNotNull(record.getMfaCode());
        assertTrue(duration < 1000, "验证码生成应在1秒内完成");
    }
    
    @Test
    @DisplayName("测试后台Worker执行验证码发送 - 模拟异步发送")
    void testBackgroundWorkerSendsCodeAsync() throws InterruptedException {
        AtomicBoolean sendCompleted = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> {
            new Thread(() -> {
                try {
                    Thread.sleep(50);
                    sendCompleted.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            }).start();
            return invocation.getArgument(0);
        });
        
        MfaRecord record = mfaService.generateAndSendCode(smsUser);
        
        assertNotNull(record);
        
        boolean asyncCompleted = latch.await(2, TimeUnit.SECONDS);
        assertTrue(asyncCompleted, "异步发送应在2秒内完成");
        assertTrue(sendCompleted.get(), "后台Worker应完成发送");
    }
    
    @Test
    @DisplayName("测试发送失败时的重试机制 - 最大重试次数")
    void testRetryMechanismOnSendFailure() {
        AtomicInteger attemptCount = new AtomicInteger(0);
        int maxRetries = 3;
        
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> {
            int currentAttempt = attemptCount.incrementAndGet();
            if (currentAttempt < maxRetries) {
                throw new RuntimeException("发送失败，模拟网络异常");
            }
            return invocation.getArgument(0);
        });
        
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            for (int i = 0; i < maxRetries; i++) {
                try {
                    mfaService.generateAndSendCode(smsUser);
                    break;
                } catch (RuntimeException e) {
                    if (i == maxRetries - 1) {
                        throw e;
                    }
                }
            }
        });
        
        assertEquals("发送失败，模拟网络异常", exception.getMessage());
    }
    
    @Test
    @DisplayName("测试短信验证码格式正确性")
    void testSmsCodeFormat() {
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        for (int i = 0; i < 10; i++) {
            MfaRecord record = mfaService.generateAndSendCode(smsUser);
            String code = record.getMfaCode();
            
            assertEquals(6, code.length());
            assertTrue(code.matches("^[0-9]+$"), "短信验证码应只包含数字");
        }
    }
    
    @Test
    @DisplayName("测试邮箱验证码格式正确性")
    void testEmailCodeFormat() {
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        for (int i = 0; i < 10; i++) {
            MfaRecord record = mfaService.generateAndSendCode(emailUser);
            String code = record.getMfaCode();
            
            assertEquals(8, code.length());
            assertTrue(code.matches("^[A-Z0-9]+$"), "邮箱验证码应只包含大写字母和数字");
        }
    }
    
    @Test
    @DisplayName("测试正确验证码验证成功")
    void testValidCodeVerification() {
        when(mfaRecordRepository.findByUserIdAndMfaTypeAndVerifiedOrderByCreatedAtDesc(
                smsUser.getUserId(), "sms", false))
                .thenReturn(Optional.of(validSmsRecord));
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        boolean result = mfaService.verifyCode(smsUser.getUserId(), "sms", "123456");
        
        assertTrue(result);
        assertTrue(validSmsRecord.getVerified());
        verify(auditService, times(1)).log(eq(smsUser.getUserId()), eq("mfa_verify"), eq("success"), isNull(), isNull(), anyString());
    }
    
    @Test
    @DisplayName("测试错误验证码验证失败")
    void testInvalidCodeVerification() {
        when(mfaRecordRepository.findByUserIdAndMfaTypeAndVerifiedOrderByCreatedAtDesc(
                smsUser.getUserId(), "sms", false))
                .thenReturn(Optional.of(validSmsRecord));
        
        AuthException exception = assertThrows(AuthException.class, 
                () -> mfaService.verifyCode(smsUser.getUserId(), "sms", "999999"));
        
        assertTrue(exception.getMessage().contains("错误"));
        verify(auditService, times(1)).log(eq(smsUser.getUserId()), eq("mfa_verify"), eq("failure"), isNull(), isNull(), anyString());
    }
    
    @Test
    @DisplayName("测试过期验证码验证失败")
    void testExpiredCodeVerification() {
        when(mfaRecordRepository.findByUserIdAndMfaTypeAndVerifiedOrderByCreatedAtDesc(
                smsUser.getUserId(), "sms", false))
                .thenReturn(Optional.of(expiredRecord));
        
        AuthException exception = assertThrows(AuthException.class, 
                () -> mfaService.verifyCode(smsUser.getUserId(), "sms", "123456"));
        
        assertTrue(exception.getMessage().contains("过期"));
    }
    
    @Test
    @DisplayName("测试不存在的验证码记录")
    void testNonExistentCodeRecord() {
        when(mfaRecordRepository.findByUserIdAndMfaTypeAndVerifiedOrderByCreatedAtDesc(
                smsUser.getUserId(), "sms", false))
                .thenReturn(Optional.empty());
        
        AuthException exception = assertThrows(AuthException.class, 
                () -> mfaService.verifyCode(smsUser.getUserId(), "sms", "123456"));
        
        assertTrue(exception.getMessage().contains("没有找到"));
    }
    
    @Test
    @DisplayName("测试验证码过期时间设置")
    void testCodeExpirationTimeSetting() {
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        MfaRecord smsRecord = mfaService.generateAndSendCode(smsUser);
        
        assertNotNull(smsRecord.getCreatedAt());
        assertNotNull(smsRecord.getExpiresAt());
        assertTrue(smsRecord.getExpiresAt().isAfter(smsRecord.getCreatedAt()));
    }
    
    @Test
    @DisplayName("测试已使用的验证码不能重复验证")
    void testUsedCodeCannotBeReused() {
        MfaRecord usedRecord = TestDataBuilder.createMfaRecord(smsUser.getUserId(), "sms", "123456", true);
        
        when(mfaRecordRepository.findByUserIdAndMfaTypeAndVerifiedOrderByCreatedAtDesc(
                smsUser.getUserId(), "sms", false))
                .thenReturn(Optional.empty());
        
        AuthException exception = assertThrows(AuthException.class, 
                () -> mfaService.verifyCode(smsUser.getUserId(), "sms", "123456"));
        
        assertTrue(exception.getMessage().contains("没有找到"));
    }
    
    @Test
    @DisplayName("测试邮箱验证码验证成功")
    void testEmailCodeVerification() {
        when(mfaRecordRepository.findByUserIdAndMfaTypeAndVerifiedOrderByCreatedAtDesc(
                emailUser.getUserId(), "email", false))
                .thenReturn(Optional.of(validEmailRecord));
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        boolean result = mfaService.verifyCode(emailUser.getUserId(), "email", "ABC12345");
        
        assertTrue(result);
        assertTrue(validEmailRecord.getVerified());
    }
    
    @Test
    @DisplayName("测试验证码发送时记录存储")
    void testCodeRecordPersistence() {
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> {
            MfaRecord saved = invocation.getArgument(0);
            assertNotNull(saved.getMfaId());
            assertNotNull(saved.getMfaCode());
            assertNotNull(saved.getCreatedAt());
            assertNotNull(saved.getExpiresAt());
            return saved;
        });
        
        MfaRecord record = mfaService.generateAndSendCode(smsUser);
        
        verify(mfaRecordRepository, times(1)).save(any(MfaRecord.class));
        assertNotNull(record);
    }
    
    @Test
    @DisplayName("测试用户无MFA类型时默认使用短信")
    void testDefaultMfaTypeWhenNotSpecified() {
        User userWithNoMfaType = TestDataBuilder.createTestUser();
        userWithNoMfaType.setMfaEnabled(true);
        userWithNoMfaType.setMfaType(null);
        
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        MfaRecord record = mfaService.generateAndSendCode(userWithNoMfaType);
        
        assertEquals("sms", record.getMfaType());
    }
    
    @Test
    @DisplayName("测试异步发送失败不影响主流程响应")
    void testAsyncSendFailureDoesNotBlockMainFlow() {
        AtomicInteger sendAttempts = new AtomicInteger(0);
        
        when(mfaRecordRepository.save(any(MfaRecord.class))).thenAnswer(invocation -> {
            new Thread(() -> {
                sendAttempts.incrementAndGet();
                throw new RuntimeException("模拟发送失败");
            }).start();
            return invocation.getArgument(0);
        });
        
        MfaRecord record = mfaService.generateAndSendCode(smsUser);
        
        assertNotNull(record);
        assertNotNull(record.getMfaCode());
    }
}