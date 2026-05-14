package com.houserental.service;

import com.houserental.HouseRentalApplication;
import com.houserental.builder.TestDataBuilder;
import com.houserental.dto.*;
import com.houserental.entity.Contract;
import com.houserental.entity.House;
import com.houserental.entity.Landlord;
import com.houserental.entity.LeaseApplication;
import com.houserental.entity.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = HouseRentalApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional
class RentServiceTest {

    @Autowired
    private RentService rentService;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private ContractService contractService;

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

        ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());
        testApplication = applicationService.createApplication(createDTO);

        ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(testApplication.getApplicationId());
        applicationService.approveApplication(approveDTO);

        testContract = contractService.getActiveContractsByHouse(testHouse.getHouseId()).get(0);
    }

    @Test
    @DisplayName("支付租金成功")
    void testProcessPayment_Success() {
        PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());

        Payment result = rentService.processPayment(paymentDTO);

        assertNotNull(result);
        assertNotNull(result.getPaymentId());
        assertTrue(result.getPaymentId().startsWith("payment_"));
        assertEquals(testContract.getContractId(), result.getContractId());
        assertEquals(testContract.getTenantId(), result.getTenantId());
        assertEquals("paid", result.getPaymentStatus());
        assertNotNull(result.getPaidAt());
    }

    @Test
    @DisplayName("支付金额不足失败")
    void testProcessPayment_InsufficientAmount() {
        PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId(), 2000.0);

        assertThrows(Exception.class, () -> {
            rentService.processPayment(paymentDTO);
        });
    }

    @Test
    @DisplayName("获取支付记录成功")
    void testGetPaymentById_Success() {
        PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
        Payment created = rentService.processPayment(paymentDTO);

        Payment result = rentService.getPaymentById(created.getPaymentId());

        assertNotNull(result);
        assertEquals(created.getPaymentId(), result.getPaymentId());
    }

    @Test
    @DisplayName("统计支付数量")
    void testCountPayments() {
        long before = rentService.countTotalPayments();

        PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
        rentService.processPayment(paymentDTO);

        long after = rentService.countTotalPayments();
        long paid = rentService.countPaidPayments();

        assertEquals(before + 1, after);
        assertTrue(paid > 0);
    }

    @Test
    @DisplayName("获取合同的支付记录")
    void testGetPaymentsByContract() {
        PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
        rentService.processPayment(paymentDTO);

        var payments = rentService.getPaymentsByContract(testContract.getContractId());

        assertFalse(payments.isEmpty());
        assertTrue(payments.stream().anyMatch(p -> p.getPaymentStatus().equals("paid")));
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
    @DisplayName("统计总收入")
    void testGetTotalPaidAmount() {
        double before = rentService.getTotalPaidAmount();

        PaymentDTO paymentDTO = TestDataBuilder.buildPaymentDTO(testContract.getContractId());
        rentService.processPayment(paymentDTO);

        double after = rentService.getTotalPaidAmount();

        assertTrue(after > before);
        assertEquals(testContract.getContractRent(), after - before, 0.01);
    }
}
