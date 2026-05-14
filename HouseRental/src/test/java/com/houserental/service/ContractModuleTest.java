package com.houserental.service;

import com.houserental.HouseRentalApplication;
import com.houserental.builder.TestDataBuilder;
import com.houserental.dto.*;
import com.houserental.entity.Contract;
import com.houserental.entity.House;
import com.houserental.entity.Landlord;
import com.houserental.entity.LeaseApplication;
import com.houserental.exception.HouseRentalException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = HouseRentalApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional
@DisplayName("合同模块单元测试")
class ContractModuleTest {

    @Autowired
    private ContractService contractService;

    @Autowired
    private ContractReminderService reminderService;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private HouseService houseService;

    @Autowired
    private LandlordService landlordService;

    private Landlord testLandlord;
    private House testHouse;
    private LeaseApplication testApplication;

    @BeforeEach
    void setUp() {
        LandlordDTO landlordDTO = TestDataBuilder.buildLandlordDTO();
        testLandlord = landlordService.createLandlord(landlordDTO);

        HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        testHouse = houseService.createHouse(houseDTO);

        ApplicationCreateDTO appDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());
        testApplication = applicationService.createApplication(appDTO);
    }

    @Nested
    @DisplayName("合同提醒机制测试")
    class ContractReminderTests {

        @Test
        @DisplayName("合同类型判断 - 长租合同(>=12个月)")
        void testDetermineContractType_LongTerm() {
            Contract longTermContract = TestDataBuilder.ContractTestScenarios.buildLongTermContract(
                    testHouse.getHouseId(),
                    "tenant_001",
                    testLandlord.getLandlordId()
            );

            String type = reminderService.determineContractType(longTermContract);

            assertEquals(ContractReminderService.ContractType.LONG_TERM, type);
        }

        @Test
        @DisplayName("合同类型判断 - 短租合同(<12个月)")
        void testDetermineContractType_ShortTerm() {
            Contract shortTermContract = TestDataBuilder.ContractTestScenarios.buildShortTermContract(
                    testHouse.getHouseId(),
                    "tenant_001",
                    testLandlord.getLandlordId()
            );

            String type = reminderService.determineContractType(shortTermContract);

            assertEquals(ContractReminderService.ContractType.SHORT_TERM, type);
        }

        @Test
        @DisplayName("提醒提前天数差异 - 长租合同早提醒")
        void testReminderDaysBefore_LongTerm_Earlier() {
            reminderService.setLongTermDaysBefore(60);
            reminderService.setShortTermDaysBefore(14);
            reminderService.setLongTermThresholdMonths(12);

            Contract longTermContract = TestDataBuilder.ContractTestScenarios.buildLongTermContract(
                    testHouse.getHouseId(),
                    "tenant_001",
                    testLandlord.getLandlordId()
            );

            Contract shortTermContract = TestDataBuilder.ContractTestScenarios.buildShortTermContract(
                    testHouse.getHouseId() + "_2",
                    "tenant_002",
                    testLandlord.getLandlordId()
            );

            int longTermDays = reminderService.getReminderDaysBefore(longTermContract);
            int shortTermDays = reminderService.getReminderDaysBefore(shortTermContract);

            assertTrue(longTermDays > shortTermDays);
            assertEquals(60, longTermDays);
            assertEquals(14, shortTermDays);
        }

        @Test
        @DisplayName("提醒频率差异 - 长租合同多次提醒")
        void testReminderFrequency_LongTerm_Multiple() {
            Contract longTermContract = TestDataBuilder.ContractTestScenarios.buildLongTermContract(
                    testHouse.getHouseId(),
                    "tenant_001",
                    testLandlord.getLandlordId()
            );

            Contract shortTermContract = TestDataBuilder.ContractTestScenarios.buildShortTermContract(
                    testHouse.getHouseId() + "_2",
                    "tenant_002",
                    testLandlord.getLandlordId()
            );

            int longTermFreq = reminderService.getReminderFrequency(longTermContract);
            int shortTermFreq = reminderService.getReminderFrequency(shortTermContract);

            assertTrue(longTermFreq > shortTermFreq);
            assertEquals(3, longTermFreq);
            assertEquals(1, shortTermFreq);
        }

        @Test
        @DisplayName("到期提醒触发验证 - 长租合同提前60天")
        void testShouldSendReminder_LongTerm_60DaysBefore() {
            reminderService.setLongTermDaysBefore(60);
            reminderService.setLongTermThresholdMonths(12);

            Contract expiringContract = TestDataBuilder.ContractTestScenarios.buildExpiringContract(
                    testHouse.getHouseId(),
                    "tenant_001",
                    testLandlord.getLandlordId(),
                    60
            );

            boolean shouldSend = reminderService.shouldSendReminder(expiringContract);

            assertTrue(shouldSend);
        }

        @Test
        @DisplayName("到期提醒触发验证 - 短租合同提前14天")
        void testShouldSendReminder_ShortTerm_14DaysBefore() {
            reminderService.setShortTermDaysBefore(14);
            reminderService.setLongTermThresholdMonths(12);

            Contract expiringContract = TestDataBuilder.ContractTestScenarios.buildExpiringContract(
                    testHouse.getHouseId(),
                    "tenant_001",
                    testLandlord.getLandlordId(),
                    14
            );

            boolean shouldSend = reminderService.shouldSendReminder(expiringContract);

            assertTrue(shouldSend);
        }

        @Test
        @DisplayName("到期提醒不触发 - 合同远未到期")
        void testShouldNotSendReminder_TooEarly() {
            Contract contract = TestDataBuilder.ContractTestScenarios.buildActiveContract(
                    testHouse.getHouseId(),
                    "tenant_001",
                    testLandlord.getLandlordId()
            );

            boolean shouldSend = reminderService.shouldSendReminder(contract);

            assertFalse(shouldSend);
        }

        @Test
        @DisplayName("到期提醒不触发 - 合同已过期")
        void testShouldNotSendReminder_AlreadyExpired() {
            Contract expiredContract = TestDataBuilder.ContractTestScenarios.buildExpiredContract(
                    testHouse.getHouseId(),
                    "tenant_001",
                    testLandlord.getLandlordId()
            );

            boolean shouldSend = reminderService.shouldSendReminder(expiredContract);

            assertFalse(shouldSend);
        }

        @Test
        @DisplayName("发送提醒验证")
        void testSendReminder_Success() {
            reminderService.clearSentReminders();

            Contract contract = TestDataBuilder.ContractTestScenarios.buildExpiringContract(
                    testHouse.getHouseId(),
                    "tenant_001",
                    testLandlord.getLandlordId(),
                    30
            );

            Map<String, Object> reminder = reminderService.sendReminder(contract);

            assertNotNull(reminder);
            assertEquals(contract.getContractId(), reminder.get("contractId"));
            assertEquals(contract.getTenantId(), reminder.get("tenantId"));
            assertEquals(contract.getLandlordId(), reminder.get("landlordId"));
            assertEquals("CONTRACT_EXPIRY", reminder.get("reminderType"));

            assertEquals(1, reminderService.getSentReminders().size());
        }

        @Test
        @DisplayName("不同合同类型的提醒统计")
        void testCountSentRemindersByContractType() {
            reminderService.clearSentReminders();

            Contract longTerm = TestDataBuilder.ContractTestScenarios.buildLongTermContract(
                    testHouse.getHouseId(),
                    "tenant_001",
                    testLandlord.getLandlordId()
            );
            Contract shortTerm = TestDataBuilder.ContractTestScenarios.buildShortTermContract(
                    testHouse.getHouseId() + "_2",
                    "tenant_002",
                    testLandlord.getLandlordId()
            );

            reminderService.sendReminder(longTerm);
            reminderService.sendReminder(shortTerm);

            int longTermCount = reminderService.countSentRemindersByContractType(
                    ContractReminderService.ContractType.LONG_TERM
            );
            int shortTermCount = reminderService.countSentRemindersByContractType(
                    ContractReminderService.ContractType.SHORT_TERM
            );

            assertEquals(1, longTermCount);
            assertEquals(1, shortTermCount);
        }
    }

    @Nested
    @DisplayName("合同状态流转测试")
    class ContractStatusFlowTests {

        private Contract activeContract;

        @BeforeEach
        void setUpContract() {
            ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(
                    testApplication.getApplicationId()
            );
            applicationService.approveApplication(approveDTO);

            List<Contract> contracts = contractService.getActiveContractsByHouse(testHouse.getHouseId());
            activeContract = contracts.get(0);
        }

        @Test
        @DisplayName("状态流转: active -> renewed (续签)")
        void testStatusFlow_ActiveToRenewed() {
            assertEquals("active", activeContract.getContractStatus());

            ContractRenewDTO renewDTO = TestDataBuilder.buildContractRenewDTO(
                    activeContract.getContractId()
            );
            Contract newContract = contractService.renewContract(renewDTO);

            assertEquals("active", newContract.getContractStatus());
            assertEquals(1, newContract.getRenewalCount());
            assertEquals(activeContract.getContractId(), newContract.getPreviousContractId());

            Contract oldContract = contractService.getContractById(activeContract.getContractId());
            assertEquals("renewed", oldContract.getContractStatus());
        }

        @Test
        @DisplayName("状态流转: active -> terminated (终止)")
        void testStatusFlow_ActiveToTerminated() {
            assertEquals("active", activeContract.getContractStatus());

            Contract terminated = contractService.terminateContract(
                    activeContract.getContractId(),
                    "租客提前退租"
            );

            assertEquals("terminated", terminated.getContractStatus());

            House updatedHouse = houseService.getHouseById(testHouse.getHouseId());
            assertEquals("available", updatedHouse.getHouseStatus());
        }

        @Test
        @DisplayName("状态流转: active -> expired (到期)")
        void testStatusFlow_ActiveToExpired() {
            assertEquals("active", activeContract.getContractStatus());

            Contract expired = contractService.expireContract(activeContract.getContractId());

            assertEquals("expired", expired.getContractStatus());

            House updatedHouse = houseService.getHouseById(testHouse.getHouseId());
            assertEquals("available", updatedHouse.getHouseStatus());
        }

        @Test
        @DisplayName("终止已终止的合同失败")
        void testTerminate_Fail_AlreadyTerminated() {
            contractService.terminateContract(activeContract.getContractId(), "提前退租");

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> contractService.terminateContract(activeContract.getContractId(), "再次终止")
            );

            assertTrue(exception.getMessage().contains("合同状态异常"));
        }

        @Test
        @DisplayName("续签已终止的合同失败")
        void testRenew_Fail_TerminatedContract() {
            contractService.terminateContract(activeContract.getContractId(), "提前退租");

            ContractRenewDTO renewDTO = TestDataBuilder.buildContractRenewDTO(
                    activeContract.getContractId()
            );

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> contractService.renewContract(renewDTO)
            );

            assertTrue(exception.getMessage().contains("合同状态异常"));
        }

        @Test
        @DisplayName("完整合同生命周期测试")
        void testCompleteContractLifecycle() {
            List<Contract> activeContracts = contractService.getActiveContracts();
            int initialActiveCount = activeContracts.size();

            ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(
                    testApplication.getApplicationId()
            );
            applicationService.approveApplication(approveDTO);

            List<Contract> newActiveContracts = contractService.getActiveContracts();
            assertEquals(initialActiveCount + 1, newActiveContracts.size());

            Contract contract = contractService.getActiveContractsByHouse(testHouse.getHouseId()).get(0);
            assertEquals("active", contract.getContractStatus());

            ContractRenewDTO renewDTO = TestDataBuilder.buildContractRenewDTO(contract.getContractId());
            Contract renewedContract = contractService.renewContract(renewDTO);
            assertEquals("active", renewedContract.getContractStatus());
            assertEquals(1, renewedContract.getRenewalCount());

            Contract terminated = contractService.terminateContract(renewedContract.getContractId(), "测试终止");
            assertEquals("terminated", terminated.getContractStatus());

            long totalCount = contractService.countTotalContracts();
            long activeCount = contractService.countActiveContracts();
            long terminatedCount = contractService.countTerminatedContracts();

            assertTrue(totalCount > 0);
            assertTrue(terminatedCount > 0);
        }
    }

    @Nested
    @DisplayName("合同续签流程测试")
    class ContractRenewalTests {

        private Contract activeContract;

        @BeforeEach
        void setUpContract() {
            ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(
                    testApplication.getApplicationId()
            );
            applicationService.approveApplication(approveDTO);

            List<Contract> contracts = contractService.getActiveContractsByHouse(testHouse.getHouseId());
            activeContract = contracts.get(0);
        }

        @Test
        @DisplayName("合同续签成功 - 租金不变")
        void testRenewContract_Success_SameRent() {
            double originalRent = activeContract.getContractRent();
            LocalDate originalEnd = activeContract.getContractEnd();

            ContractRenewDTO renewDTO = TestDataBuilder.buildContractRenewDTO(activeContract.getContractId());
            renewDTO.setNewRent(originalRent);

            Contract newContract = contractService.renewContract(renewDTO);

            assertEquals(originalRent, newContract.getContractRent());
            assertTrue(newContract.getContractEnd().isAfter(originalEnd));
            assertEquals(activeContract.getContractId(), newContract.getPreviousContractId());
            assertEquals(1, newContract.getRenewalCount());
        }

        @Test
        @DisplayName("合同续签成功 - 租金调整")
        void testRenewContract_Success_RentAdjusted() {
            double newRent = activeContract.getContractRent() * 1.1;

            ContractRenewDTO renewDTO = TestDataBuilder.buildContractRenewDTO(activeContract.getContractId());
            renewDTO.setNewRent(newRent);

            Contract newContract = contractService.renewContract(renewDTO);

            assertEquals(newRent, newContract.getContractRent());
            assertNotEquals(activeContract.getContractRent(), newContract.getContractRent());
        }

        @Test
        @DisplayName("合同续签失败 - 结束日期早于开始日期")
        void testRenewContract_Fail_InvalidDates() {
            ContractRenewDTO renewDTO = TestDataBuilder.buildContractRenewDTO(activeContract.getContractId());
            renewDTO.setNewContractStart(LocalDate.now().plusDays(30));
            renewDTO.setNewContractEnd(LocalDate.now().plusDays(10));

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> contractService.renewContract(renewDTO)
            );

            assertTrue(exception.getMessage().contains("结束日期不能早于开始日期"));
        }

        @Test
        @DisplayName("续签后原合同状态变更为renewed")
        void testRenewContract_OriginalContractMarkedAsRenewed() {
            String originalContractId = activeContract.getContractId();

            ContractRenewDTO renewDTO = TestDataBuilder.buildContractRenewDTO(activeContract.getContractId());
            contractService.renewContract(renewDTO);

            Contract originalContract = contractService.getContractById(originalContractId);
            assertEquals("renewed", originalContract.getContractStatus());
        }

        @Test
        @DisplayName("续签次数统计")
        void testRenewalCount_Increased() {
            ContractRenewDTO renewDTO1 = TestDataBuilder.buildContractRenewDTO(activeContract.getContractId());
            Contract firstRenewal = contractService.renewContract(renewDTO1);

            assertEquals(1, firstRenewal.getRenewalCount());

            long beforeRenewalCount = contractService.countRenewedContracts();

            ContractRenewDTO renewDTO2 = TestDataBuilder.buildContractRenewDTO(firstRenewal.getContractId());
            renewDTO2.setNewContractStart(firstRenewal.getContractEnd());
            renewDTO2.setNewContractEnd(firstRenewal.getContractEnd().plusYears(1));
            Contract secondRenewal = contractService.renewContract(renewDTO2);

            assertEquals(2, secondRenewal.getRenewalCount());

            long afterRenewalCount = contractService.countRenewedContracts();
            assertTrue(afterRenewalCount > beforeRenewalCount);
        }
    }

    @Nested
    @DisplayName("合同查询功能测试")
    class ContractQueryTests {

        private Contract activeContract;

        @BeforeEach
        void setUpContract() {
            ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(
                    testApplication.getApplicationId()
            );
            applicationService.approveApplication(approveDTO);

            List<Contract> contracts = contractService.getActiveContractsByHouse(testHouse.getHouseId());
            activeContract = contracts.get(0);
        }

        @Test
        @DisplayName("按ID查询合同")
        void testGetContractById() {
            Contract found = contractService.getContractById(activeContract.getContractId());

            assertNotNull(found);
            assertEquals(activeContract.getContractId(), found.getContractId());
            assertEquals(activeContract.getHouseId(), found.getHouseId());
        }

        @Test
        @DisplayName("按房源查询合同")
        void testGetContractsByHouse() {
            List<Contract> contracts = contractService.getContractsByHouse(testHouse.getHouseId());

            assertFalse(contracts.isEmpty());
            assertTrue(contracts.stream().allMatch(c -> c.getHouseId().equals(testHouse.getHouseId())));
        }

        @Test
        @DisplayName("按租客查询合同")
        void testGetContractsByTenant() {
            List<Contract> contracts = contractService.getContractsByTenant(activeContract.getTenantId());

            assertFalse(contracts.isEmpty());
            assertTrue(contracts.stream().allMatch(c -> c.getTenantId().equals(activeContract.getTenantId())));
        }

        @Test
        @DisplayName("按房东查询合同")
        void testGetContractsByLandlord() {
            List<Contract> contracts = contractService.getContractsByLandlord(testLandlord.getLandlordId());

            assertFalse(contracts.isEmpty());
            assertTrue(contracts.stream().allMatch(c -> c.getLandlordId().equals(testLandlord.getLandlordId())));
        }

        @Test
        @DisplayName("查询活跃合同")
        void testGetActiveContracts() {
            List<Contract> activeContracts = contractService.getActiveContracts();

            assertTrue(activeContracts.stream().allMatch(c -> c.getContractStatus().equals("active")));
        }

        @Test
        @DisplayName("合同统计查询")
        void testContractStatistics() {
            long total = contractService.countTotalContracts();
            long active = contractService.countActiveContracts();
            long expired = contractService.countExpiredContracts();
            long renewed = contractService.countRenewedContracts();

            assertTrue(total > 0);
            assertTrue(active > 0);
            assertEquals(0, expired);
            assertEquals(0, renewed);
        }
    }
}
