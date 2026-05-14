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
@DisplayName("租金模块单元测试")
class PaymentModuleTest {

    @Autowired
    private RentService rentService;

    @Autowired
    private PaymentWorkerService paymentWorkerService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private HouseService houseService;

    @Autowired
    private LandlordService landlordService;

    private Landlord testLandlord;
    private House testHouse;
    private LeaseApplication testApplication;
    private Contract testContract;

    @BeforeEach
    void setUp() {
        LandlordDTO landlordDTO = TestDataBuilder.buildLandlordDTO();
        testLandlord = landlordService.createLandlord(landlordDTO);

        HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        testHouse = houseService.createHouse(houseDTO);

        ApplicationCreateDTO appDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());
        testApplication = applicationService.createApplication(appDTO);

        ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(
                testApplication.getApplicationId()
        );
        applicationService.approveApplication(approveDTO);

        List<Contract> contracts = contractService.getActiveContractsByHouse(testHouse.getHouseId());
        testContract = contracts.get(0);

        paymentWorkerService.clearPaymentResults();
        paymentWorkerService.setMaxRetryCount(3);
        paymentWorkerService.setRetryDelayMinutes(5);
    }

    @Nested
    @DisplayName("租金支付异步化测试")
    class AsyncPaymentTests {

        @Test
        @DisplayName("异步支付 - 立即返回响应不阻塞")
        void testAsyncPayment_ReturnsImmediately() {
            long startTime = System.currentTimeMillis();

            PaymentWorkerService.CompletablePaymentResult result = paymentWorkerService.processPaymentAsync(
                    testContract.getContractId(),
                    3000.0,
                    "wechat",
                    LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))
            );

            long elapsedTime = System.currentTimeMillis() - startTime;

            assertNotNull(result);
            assertNotNull(result.getPaymentId());
            assertEquals(testContract.getContractId(), result.getContractId());
            assertEquals("processing", result.getStatus());
            assertTrue(elapsedTime < 5000);
        }

        @Test
        @DisplayName("异步支付 - 支付成功后更新状态")
        void testAsyncPayment_SuccessUpdatesStatus() throws InterruptedException {
            String paymentPeriod = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));

            PaymentWorkerService.CompletablePaymentResult result = paymentWorkerService.processPaymentAsync(
                    testContract.getContractId(),
                    3000.0,
                    "wechat",
                    paymentPeriod
            );

            Thread.sleep(500);

            List<Map<String, Object>> allResults = paymentWorkerService.getAllPaymentResults();
            assertFalse(allResults.isEmpty());

            Map<String, Object> paymentResult = allResults.stream()
                    .filter(r -> result.getPaymentId().equals(r.get("paymentId")))
                    .findFirst()
                    .orElse(null);

            assertNotNull(paymentResult);
            assertTrue(
                    "paid".equals(paymentResult.get("status")) ||
                    "failed".equals(paymentResult.get("status"))
            );
        }

        @Test
        @DisplayName("异步支付 - 合同终止时拒绝支付")
        void testAsyncPayment_Fail_WhenContractTerminated() {
            contractService.terminateContract(testContract.getContractId(), "测试终止");

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> paymentWorkerService.processPaymentAsync(
                            testContract.getContractId(),
                            3000.0,
                            "wechat",
                            LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))
                    )
            );

            assertTrue(exception.getMessage().contains("合同已终止"));
        }

        @Test
        @DisplayName("同步支付 - 成功流程")
        void testSyncPayment_Success() {
            PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());

            var result = rentService.processPayment(paymentDTO);

            assertNotNull(result);
            assertNotNull(result.getPaymentId());
            assertEquals("paid", result.getPaymentStatus());
            assertNotNull(result.getPaidAt());
            assertEquals(testContract.getContractId(), result.getContractId());
            assertEquals(3000.0, result.getPaymentAmount());
        }

        @Test
        @DisplayName("同步支付 - 金额不足失败")
        void testSyncPayment_Fail_InsufficientAmount() {
            PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId(), 2000.0);

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> rentService.processPayment(paymentDTO)
            );

            assertTrue(exception.getMessage().contains("支付金额不足"));
        }

        @Test
        @DisplayName("同步支付 - 合同已过期失败")
        void testSyncPayment_Fail_ContractExpired() {
            contractService.expireContract(testContract.getContractId());

            PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> rentService.processPayment(paymentDTO)
            );

            assertTrue(exception.getMessage().contains("合同已过期"));
        }

        @Test
        @DisplayName("重复支付检查 - 同一周期重复支付失败")
        void testDuplicatePayment_Fail_SamePeriod() {
            PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
            rentService.processPayment(paymentDTO);

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> rentService.processPayment(paymentDTO)
            );

            assertTrue(exception.getMessage().contains("已支付"));
        }
    }

    @Nested
    @DisplayName("支付失败重试机制测试")
    class PaymentRetryTests {

        @Test
        @DisplayName("重试机制 - 最大重试次数配置")
        void testRetryConfiguration_MaxRetries() {
            assertEquals(3, paymentWorkerService.getMaxRetryCount());
            assertEquals(5, paymentWorkerService.getRetryDelayMinutes());
        }

        @Test
        @DisplayName("重试机制 - 首次失败后调度重试")
        void testRetry_ScheduleAfterFirstFailure() {
            String paymentId = "test_payment_retry_001";

            assertEquals(0, paymentWorkerService.getRetryCount(paymentId));
            assertFalse(paymentWorkerService.hasReachedMaxRetries(paymentId));

            paymentWorkerService.scheduleRetry(paymentId);

            assertEquals(1, paymentWorkerService.getRetryCount(paymentId));
            assertFalse(paymentWorkerService.hasReachedMaxRetries(paymentId));
        }

        @Test
        @DisplayName("重试机制 - 达到最大重试次数")
        void testRetry_ReachMaxRetries() {
            String paymentId = "test_payment_max_retry_001";

            for (int i = 0; i < 3; i++) {
                paymentWorkerService.scheduleRetry(paymentId);
            }

            assertEquals(3, paymentWorkerService.getRetryCount(paymentId));
            assertTrue(paymentWorkerService.hasReachedMaxRetries(paymentId));
        }

        @Test
        @DisplayName("重试机制 - 超过最大重试后不再调度")
        void testRetry_StopAfterMaxRetries() {
            String paymentId = "test_payment_stop_retry_001";

            for (int i = 0; i < 5; i++) {
                paymentWorkerService.scheduleRetry(paymentId);
            }

            assertEquals(3, paymentWorkerService.getRetryCount(paymentId));
            assertTrue(paymentWorkerService.hasReachedMaxRetries(paymentId));
        }

        @Test
        @DisplayName("重试机制 - 可配置重试次数")
        void testRetry_ConfigurableRetryCount() {
            paymentWorkerService.setMaxRetryCount(5);
            assertEquals(5, paymentWorkerService.getMaxRetryCount());

            String paymentId = "test_payment_config_retry_001";
            for (int i = 0; i < 5; i++) {
                paymentWorkerService.scheduleRetry(paymentId);
            }

            assertEquals(5, paymentWorkerService.getRetryCount(paymentId));
            assertTrue(paymentWorkerService.hasReachedMaxRetries(paymentId));
        }

        @Test
        @DisplayName("支付网关执行 - 成功场景")
        void testPaymentGateway_Success() {
            boolean result = paymentWorkerService.executePaymentGatewayDeterministic(
                    "test_gateway_success_001",
                    3000.0,
                    "wechat",
                    true
            );

            assertTrue(result);
        }

        @Test
        @DisplayName("支付网关执行 - 失败场景")
        void testPaymentGateway_Failure() {
            boolean result = paymentWorkerService.executePaymentGatewayDeterministic(
                    "test_gateway_failure_001",
                    3000.0,
                    "wechat",
                    false
            );

            assertFalse(result);
        }

        @Test
        @DisplayName("清除重试计数")
        void testClearRetryCount() {
            String paymentId = "test_clear_retry_001";

            paymentWorkerService.scheduleRetry(paymentId);
            assertEquals(1, paymentWorkerService.getRetryCount(paymentId));

            paymentWorkerService.setRetryCount(paymentId, 0);
            assertEquals(0, paymentWorkerService.getRetryCount(paymentId));
        }
    }

    @Nested
    @DisplayName("租金计算与查询测试")
    class RentCalculationTests {

        @Test
        @DisplayName("租金计算 - 单月租金")
        void testCalculateRent_SingleMonth() {
            double rent = rentService.calculateRent(testContract);

            assertEquals(3000.0, rent);
        }

        @Test
        @DisplayName("租金计算 - 多月租金")
        void testCalculateRent_MultipleMonths() {
            double rent = rentService.calculateRentForPeriod(testContract, 3);

            assertEquals(9000.0, rent);
        }

        @Test
        @DisplayName("租金计算 - 整年租金")
        void testCalculateRent_FullYear() {
            double rent = rentService.calculateRentForPeriod(testContract, 12);

            assertEquals(36000.0, rent);
        }

        @Test
        @DisplayName("获取合同的支付记录")
        void testGetPaymentsByContract() {
            PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
            rentService.processPayment(paymentDTO);

            var payments = rentService.getPaymentsByContract(testContract.getContractId());

            assertFalse(payments.isEmpty());
            assertTrue(payments.stream().anyMatch(p -> "paid".equals(p.getPaymentStatus())));
        }

        @Test
        @DisplayName("获取租客的支付记录")
        void testGetPaymentsByTenant() {
            PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
            rentService.processPayment(paymentDTO);

            var payments = rentService.getPaymentsByTenant(testContract.getTenantId());

            assertFalse(payments.isEmpty());
        }

        @Test
        @DisplayName("获取待支付记录")
        void testGetPendingPayments() {
            var pendingPayments = rentService.getPendingPayments();

            assertNotNull(pendingPayments);
        }

        @Test
        @DisplayName("获取已支付记录")
        void testGetPaidPayments() {
            PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
            rentService.processPayment(paymentDTO);

            var paidPayments = rentService.getPaidPayments();

            assertFalse(paidPayments.isEmpty());
        }
    }

    @Nested
    @DisplayName("支付统计测试")
    class PaymentStatisticsTests {

        @Test
        @DisplayName("支付记录数量统计")
        void testPaymentCountStatistics() {
            long beforeTotal = rentService.countTotalPayments();
            long beforePaid = rentService.countPaidPayments();

            PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
            rentService.processPayment(paymentDTO);

            long afterTotal = rentService.countTotalPayments();
            long afterPaid = rentService.countPaidPayments();

            assertEquals(beforeTotal + 1, afterTotal);
            assertEquals(beforePaid + 1, afterPaid);
        }

        @Test
        @DisplayName("总支付金额统计")
        void testTotalPaidAmountStatistics() {
            double beforeTotal = rentService.getTotalPaidAmount();

            PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
            rentService.processPayment(paymentDTO);

            double afterTotal = rentService.getTotalPaidAmount();

            assertEquals(beforeTotal + 3000.0, afterTotal, 0.01);
        }

        @Test
        @DisplayName("多笔支付的总金额统计")
        void testMultiplePaymentsTotalAmount() {
            double beforeTotal = rentService.getTotalPaidAmount();

            LandlordDTO landlord2DTO = TestDataBuilder.buildLandlordDTO("房东2", "13800138002");
            Landlord landlord2 = landlordService.createLandlord(landlord2DTO);

            HouseDTO house2DTO = TestDataBuilder.buildHouseDTO(landlord2.getLandlordId());
            House house2 = houseService.createHouse(house2DTO);

            ApplicationCreateDTO app2DTO = TestDataBuilder.buildApplicationCreateDTO(
                    house2.getHouseId(),
                    "租客2",
                    "13900139002"
            );
            LeaseApplication app2 = applicationService.createApplication(app2DTO);

            ApplicationApproveDTO approve2DTO = TestDataBuilder.buildApplicationApproveDTO(app2.getApplicationId());
            applicationService.approveApplication(approve2DTO);

            List<Contract> contracts2 = contractService.getActiveContractsByHouse(house2.getHouseId());
            Contract contract2 = contracts2.get(0);

            PaymentDTO payment1DTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
            rentService.processPayment(payment1DTO);

            PaymentDTO payment2DTO = TestDataBuilder.buildPaymentDTO(contract2.getContractId(), 5000.0);
            rentService.processPayment(payment2DTO);

            double afterTotal = rentService.getTotalPaidAmount();

            assertEquals(beforeTotal + 3000.0 + 5000.0, afterTotal, 0.01);
        }

        @Test
        @DisplayName("支付状态分布统计")
        void testPaymentStatusDistribution() {
            long beforePending = rentService.countPendingPayments();
            long beforePaid = rentService.countPaidPayments();
            long beforeFailed = rentService.countFailedPayments();

            PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
            rentService.processPayment(paymentDTO);

            long afterPaid = rentService.countPaidPayments();

            assertTrue(afterPaid > beforePaid);
        }
    }
}
