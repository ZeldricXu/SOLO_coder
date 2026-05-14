package com.contractmgmt.service;

import com.contractmgmt.builder.TestDataBuilder;
import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ReminderConfig;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.ReminderConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("提醒模块单元测试")
class ReminderServiceTest {

    @Mock
    private ReminderConfigRepository reminderConfigRepository;

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private ContractConfig contractConfig;

    @Mock
    private ContractConfig.Reminder reminderConfig;

    @InjectMocks
    private ReminderService reminderService;

    @BeforeEach
    void setUp() {
        when(contractConfig.getReminder()).thenReturn(reminderConfig);
        when(reminderConfig.getAdvanceDays()).thenReturn(15);
    }

    @Nested
    @DisplayName("到期提醒创建测试")
    class ExpireReminderCreationTests {

        @Test
        @DisplayName("应成功创建到期提醒配置")
        void createExpireReminder_WithValidData_ShouldSucceed() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            LocalDate contractEnd = LocalDate.now().plusDays(30);

            when(reminderConfigRepository.findByContractIdAndReminderType(contractId, "expire"))
                    .thenReturn(Optional.empty());
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ReminderConfig result = reminderService.createExpireReminder(contractId, contractEnd);

            assertNotNull(result);
            assertEquals(contractId, result.getContractId());
            assertEquals("expire", result.getReminderType());
            assertEquals("email", result.getReminderChannel());
            assertEquals("pending", result.getReminderStatus());
            assertEquals(0, result.getRetryCount());

            LocalDate expectedReminderDate = contractEnd.minusDays(15);
            assertEquals(expectedReminderDate, result.getReminderTime());
        }

        @Test
        @DisplayName("提醒时间应等于合同到期日减去提前天数")
        void createExpireReminder_ShouldCalculateCorrectReminderTime() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            LocalDate contractEnd = LocalDate.of(2026, 6, 30);
            when(reminderConfig.getAdvanceDays()).thenReturn(10);

            when(reminderConfigRepository.findByContractIdAndReminderType(contractId, "expire"))
                    .thenReturn(Optional.empty());
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ReminderConfig result = reminderService.createExpireReminder(contractId, contractEnd);

            LocalDate expectedReminderDate = LocalDate.of(2026, 6, 20);
            assertEquals(expectedReminderDate, result.getReminderTime());
        }

        @Test
        @DisplayName("已存在的提醒应直接返回")
        void createExpireReminder_WhenAlreadyExists_ShouldReturnExisting() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            LocalDate contractEnd = LocalDate.now().plusDays(30);
            ReminderConfig existing = TestDataBuilder.buildPendingReminder(contractId);

            when(reminderConfigRepository.findByContractIdAndReminderType(contractId, "expire"))
                    .thenReturn(Optional.of(existing));

            ReminderConfig result = reminderService.createExpireReminder(contractId, contractEnd);

            assertSame(existing, result);
            verify(reminderConfigRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("提前天数配置测试")
    class AdvanceDaysConfigurationTests {

        @Test
        @DisplayName("默认提前天数应为15天")
        void createExpireReminder_WithDefaultAdvanceDays_ShouldBe15() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            LocalDate contractEnd = LocalDate.of(2026, 6, 30);

            when(reminderConfig.getAdvanceDays()).thenReturn(15);
            when(reminderConfigRepository.findByContractIdAndReminderType(contractId, "expire"))
                    .thenReturn(Optional.empty());
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ReminderConfig result = reminderService.createExpireReminder(contractId, contractEnd);

            assertEquals(LocalDate.of(2026, 6, 15), result.getReminderTime());
        }

        @Test
        @DisplayName("自定义提前天数应生效")
        void createExpireReminder_WithCustomAdvanceDays_ShouldApply() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            LocalDate contractEnd = LocalDate.of(2026, 6, 30);

            when(reminderConfig.getAdvanceDays()).thenReturn(30);
            when(reminderConfigRepository.findByContractIdAndReminderType(contractId, "expire"))
                    .thenReturn(Optional.empty());
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ReminderConfig result = reminderService.createExpireReminder(contractId, contractEnd);

            assertEquals(LocalDate.of(2026, 5, 31), result.getReminderTime());
        }
    }

    @Nested
    @DisplayName("提醒发送测试")
    class ReminderSendingTests {

        @Test
        @DisplayName("待发送提醒应被成功发送")
        void checkAndSendReminders_WithPendingReminders_ShouldSend() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            ReminderConfig pendingReminder = TestDataBuilder.buildPendingReminder(contractId);
            Contract contract = TestDataBuilder.buildExpiringContract(15);

            when(reminderConfigRepository.findByReminderTimeAndStatusIn(
                    eq(LocalDate.now()), anyList()))
                    .thenReturn(Arrays.asList(pendingReminder));
            when(contractRepository.findByContractId(contractId))
                    .thenReturn(Optional.of(contract));
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            reminderService.checkAndSendReminders();

            verify(reminderConfigRepository, times(1)).save(argThat(r ->
                    "sent".equals(r.getReminderStatus()) && r.getSentTime() != null));
        }

        @Test
        @DisplayName("失败的提醒应被重试")
        void checkAndSendReminders_WithFailedReminders_ShouldRetry() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            ReminderConfig failedReminder = TestDataBuilder.buildFailedReminder(contractId);
            Contract contract = TestDataBuilder.buildExpiringContract(15);

            when(reminderConfigRepository.findByReminderTimeAndStatusIn(
                    eq(LocalDate.now()), anyList()))
                    .thenReturn(Arrays.asList(failedReminder));
            when(contractRepository.findByContractId(contractId))
                    .thenReturn(Optional.of(contract));
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            reminderService.checkAndSendReminders();

            verify(reminderConfigRepository, times(1)).save(argThat(r ->
                    "sent".equals(r.getReminderStatus())));
        }

        @Test
        @DisplayName("已发送的提醒不应重复发送")
        void checkAndSendReminders_WithSentReminders_ShouldNotResend() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            ReminderConfig sentReminder = TestDataBuilder.buildSentReminder(contractId);

            when(reminderConfigRepository.findByReminderTimeAndStatusIn(
                    eq(LocalDate.now()), anyList()))
                    .thenReturn(Collections.emptyList());

            reminderService.checkAndSendReminders();

            verify(reminderConfigRepository, never()).save(sentReminder);
        }

        @Test
        @DisplayName("合同不存在时提醒应标记为失败")
        void checkAndSendReminders_WithNonExistingContract_ShouldMarkFailed() {
            String contractId = "non_existing_001";
            ReminderConfig pendingReminder = TestDataBuilder.buildPendingReminder(contractId);

            when(reminderConfigRepository.findByReminderTimeAndStatusIn(
                    eq(LocalDate.now()), anyList()))
                    .thenReturn(Arrays.asList(pendingReminder));
            when(contractRepository.findByContractId(contractId))
                    .thenReturn(Optional.empty());
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            reminderService.checkAndSendReminders();

            verify(reminderConfigRepository, times(1)).save(argThat(r ->
                    "failed".equals(r.getReminderStatus())));
        }

        @Test
        @DisplayName("没有待发送提醒时不应执行操作")
        void checkAndSendReminders_WithNoPendingReminders_ShouldDoNothing() {
            when(reminderConfigRepository.findByReminderTimeAndStatusIn(
                    eq(LocalDate.now()), anyList()))
                    .thenReturn(Collections.emptyList());

            reminderService.checkAndSendReminders();

            verify(contractRepository, never()).findByContractId(anyString());
            verify(reminderConfigRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("多渠道提醒测试")
    class MultiChannelReminderTests {

        @Test
        @DisplayName("邮件渠道提醒应正确保存")
        void createExpireReminder_WithEmailChannel_ShouldSaveCorrectly() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            LocalDate contractEnd = LocalDate.now().plusDays(30);

            when(reminderConfigRepository.findByContractIdAndReminderType(contractId, "expire"))
                    .thenReturn(Optional.empty());
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ReminderConfig result = reminderService.createExpireReminder(contractId, contractEnd);

            assertEquals("email", result.getReminderChannel());
        }

        @Test
        @DisplayName("短信渠道提醒应能正确处理")
        void createExpireReminder_WithSmsChannel_ShouldBeSupported() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            ReminderConfig smsReminder = TestDataBuilder.buildMultiChannelReminder(contractId, "sms");
            Contract contract = TestDataBuilder.buildExpiringContract(15);

            when(reminderConfigRepository.findByReminderTimeAndStatusIn(
                    eq(LocalDate.now()), anyList()))
                    .thenReturn(Arrays.asList(smsReminder));
            when(contractRepository.findByContractId(contractId))
                    .thenReturn(Optional.of(contract));
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            reminderService.checkAndSendReminders();

            verify(reminderConfigRepository, times(1)).save(argThat(r ->
                    "sms".equals(r.getReminderChannel()) && "sent".equals(r.getReminderStatus())));
        }

        @Test
        @DisplayName("多种渠道提醒应都能成功发送")
        void checkAndSendReminders_WithDifferentChannels_ShouldAllSend() {
            String contractId1 = "contract_001";
            String contractId2 = "contract_002";
            ReminderConfig emailReminder = TestDataBuilder.buildMultiChannelReminder(contractId1, "email");
            ReminderConfig smsReminder = TestDataBuilder.buildMultiChannelReminder(contractId2, "sms");
            Contract contract1 = TestDataBuilder.buildExpiringContract(15);
            Contract contract2 = TestDataBuilder.buildExpiringContract(10);

            when(reminderConfigRepository.findByReminderTimeAndStatusIn(
                    eq(LocalDate.now()), anyList()))
                    .thenReturn(Arrays.asList(emailReminder, smsReminder));
            when(contractRepository.findByContractId(contractId1))
                    .thenReturn(Optional.of(contract1));
            when(contractRepository.findByContractId(contractId2))
                    .thenReturn(Optional.of(contract2));
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            reminderService.checkAndSendReminders();

            verify(reminderConfigRepository, times(2)).save(any());
        }
    }

    @Nested
    @DisplayName("多重提醒测试")
    class MultipleReminderTests {

        @Test
        @DisplayName("多个待发送提醒应依次发送")
        void checkAndSendReminders_WithMultipleReminders_ShouldSendAll() {
            ReminderConfig reminder1 = TestDataBuilder.buildPendingReminder("contract_001");
            ReminderConfig reminder2 = TestDataBuilder.buildPendingReminder("contract_002");
            ReminderConfig reminder3 = TestDataBuilder.buildPendingReminder("contract_003");
            Contract contract1 = TestDataBuilder.buildExpiringContract(15);
            Contract contract2 = TestDataBuilder.buildExpiringContract(10);
            Contract contract3 = TestDataBuilder.buildExpiringContract(5);

            when(reminderConfigRepository.findByReminderTimeAndStatusIn(
                    eq(LocalDate.now()), anyList()))
                    .thenReturn(Arrays.asList(reminder1, reminder2, reminder3));
            when(contractRepository.findByContractId("contract_001"))
                    .thenReturn(Optional.of(contract1));
            when(contractRepository.findByContractId("contract_002"))
                    .thenReturn(Optional.of(contract2));
            when(contractRepository.findByContractId("contract_003"))
                    .thenReturn(Optional.of(contract3));
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            reminderService.checkAndSendReminders();

            verify(reminderConfigRepository, times(3)).save(argThat(r ->
                    "sent".equals(r.getReminderStatus())));
        }

        @Test
        @DisplayName("重试计数应正确递增")
        void checkAndSendReminders_WithRetry_ShouldIncrementRetryCount() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            ReminderConfig failedReminder = TestDataBuilder.buildFailedReminder(contractId);
            Contract contract = TestDataBuilder.buildExpiringContract(15);

            when(reminderConfigRepository.findByReminderTimeAndStatusIn(
                    eq(LocalDate.now()), anyList()))
                    .thenReturn(Arrays.asList(failedReminder));
            when(contractRepository.findByContractId(contractId))
                    .thenReturn(Optional.of(contract));
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> {
                        ReminderConfig saved = invocation.getArgument(0);
                        return saved;
                    });

            reminderService.checkAndSendReminders();

            verify(reminderConfigRepository, times(1)).save(argThat(r ->
                    "sent".equals(r.getReminderStatus())));
        }
    }

    @Nested
    @DisplayName("提醒查询测试")
    class ReminderQueryTests {

        @Test
        @DisplayName("应能获取合同的所有提醒配置")
        void getRemindersByContract_ShouldReturnAll() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            ReminderConfig reminder1 = TestDataBuilder.buildPendingReminder(contractId);
            ReminderConfig reminder2 = TestDataBuilder.buildSentReminder(contractId);

            when(reminderConfigRepository.findByContractId(contractId))
                    .thenReturn(Arrays.asList(reminder1, reminder2));

            List<ReminderConfig> result = reminderService.getRemindersByContract(contractId);

            assertEquals(2, result.size());
            assertEquals(contractId, result.get(0).getContractId());
        }

        @Test
        @DisplayName("应能获取所有待发送的提醒")
        void getPendingReminders_ShouldReturnOnlyPending() {
            ReminderConfig pending = TestDataBuilder.buildPendingReminder("contract_001");
            ReminderConfig sent = TestDataBuilder.buildSentReminder("contract_002");

            when(reminderConfigRepository.findByReminderStatus("pending"))
                    .thenReturn(Arrays.asList(pending));

            List<ReminderConfig> result = reminderService.getPendingReminders();

            assertEquals(1, result.size());
            assertEquals("pending", result.get(0).getReminderStatus());
        }
    }
}
