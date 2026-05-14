package com.invoice.mgmt.issue.service;

import com.invoice.mgmt.archive.service.InvoiceArchiveService;
import com.invoice.mgmt.common.dto.InvoiceIssueRequest;
import com.invoice.mgmt.common.dto.InvoiceIssueResponse;
import com.invoice.mgmt.common.entity.Invoice;
import com.invoice.mgmt.common.enums.InvoiceStatusEnum;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.mapper.InvoiceMapper;
import com.invoice.mgmt.common.testdata.MockConstants;
import com.invoice.mgmt.common.testdata.TestDataBuilder;
import com.invoice.mgmt.history.service.InvoiceHistoryService;
import com.invoice.mgmt.number.service.InvoiceNumberService;
import com.invoice.mgmt.statistics.service.InvoiceStatisticsService;
import com.invoice.mgmt.status.service.InvoiceStatusService;
import com.invoice.mgmt.type.service.InvoiceTypeService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("发票开具服务单元测试")
class InvoiceIssueServiceTest {

    @Mock
    private InvoiceMapper invoiceMapper;

    @Mock
    private InvoiceTypeService invoiceTypeService;

    @Mock
    private InvoiceNumberService invoiceNumberService;

    @Mock
    private InvoiceStatusService invoiceStatusService;

    @Mock
    private InvoiceArchiveService invoiceArchiveService;

    @Mock
    private InvoiceStatisticsService invoiceStatisticsService;

    @Mock
    private InvoiceHistoryService invoiceHistoryService;

    @InjectMocks
    private InvoiceIssueService invoiceIssueService;

    private BigDecimal amount10000;
    private BigDecimal amount1000;
    private BigDecimal amountSmall;
    private BigDecimal taxRate13;
    private BigDecimal taxRate9;
    private BigDecimal taxRate6;

    @BeforeEach
    void setUp() throws Exception {
        amount10000 = new BigDecimal("10000.00");
        amount1000 = new BigDecimal("1000.00");
        amountSmall = new BigDecimal("100.00");
        taxRate13 = new BigDecimal("0.13");
        taxRate9 = new BigDecimal("0.09");
        taxRate6 = new BigDecimal("0.06");

        setPrivateField(invoiceIssueService, "defaultSellerName", "销售公司");
        setPrivateField(invoiceIssueService, "defaultSellerTaxNo", "911100000000000000");
    }

    private void setPrivateField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    private BigDecimal calculateTax(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateTotal(BigDecimal amount, BigDecimal tax) {
        return amount.add(tax).setScale(2, RoundingMode.HALF_UP);
    }

    @Nested
    @DisplayName("发票生成正确性测试")
    class InvoiceGenerationTests {

        @Test
        @Order(1)
        @DisplayName("测试标准场景发票生成正确性")
        void testStandardInvoiceGeneration() {
            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount10000, "采购公司");

            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000001");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueResponse response = invoiceIssueService.issue(request);

            assertNotNull(response);
            assertNotNull(response.getInvoiceId());
            assertTrue(response.getInvoiceId().startsWith("invoice_"));
            assertEquals("00000001", response.getInvoiceNo());
            assertEquals("1100", response.getInvoiceCode());
            assertEquals(InvoiceStatusEnum.ISSUED.getCode(), response.getInvoiceStatus());
            assertNotNull(response.getIssueTime());

            verify(invoiceMapper, times(1)).insert(any(Invoice.class));
        }

        @Test
        @Order(2)
        @DisplayName("测试发票基本信息正确性")
        void testInvoiceBasicInfoCorrectness() {
            InvoiceIssueRequest request = InvoiceIssueRequest.builder()
                    .invoiceType("vat_special")
                    .buyerName("测试采购公司")
                    .buyerTaxNo("911100001234567890")
                    .invoiceAmount(amount10000)
                    .sellerName("测试销售公司")
                    .sellerTaxNo("911100009876543210")
                    .operator("admin")
                    .build();

            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000005");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);

            invoiceIssueService.issue(request);

            verify(invoiceMapper).insert(invoiceCaptor.capture());
            Invoice captured = invoiceCaptor.getValue();

            assertEquals("测试采购公司", captured.getBuyerName());
            assertEquals("911100001234567890", captured.getBuyerTaxNo());
            assertEquals("测试销售公司", captured.getSellerName());
            assertEquals("911100009876543210", captured.getSellerTaxNo());
            assertEquals("vat_special", captured.getInvoiceType());
            assertEquals("00000005", captured.getInvoiceNo());
            assertEquals("1100", captured.getInvoiceCode());
        }

        @Test
        @Order(3)
        @DisplayName("测试未指定销售方时使用默认值")
        void testUseDefaultSellerWhenNotSpecified() {
            InvoiceIssueRequest request = InvoiceIssueRequest.builder()
                    .invoiceType("vat_common")
                    .buyerName("采购公司")
                    .invoiceAmount(amount1000)
                    .build();

            when(invoiceTypeService.isValidType("vat_common")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_common")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_common")).thenReturn("1101");
            when(invoiceNumberService.allocate("vat_common")).thenReturn("00000002");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);

            invoiceIssueService.issue(request);

            verify(invoiceMapper).insert(invoiceCaptor.capture());
            Invoice captured = invoiceCaptor.getValue();

            assertEquals("销售公司", captured.getSellerName());
            assertEquals("911100000000000000", captured.getSellerTaxNo());
        }

        @Test
        @Order(4)
        @DisplayName("测试发票时间戳正确性")
        void testInvoiceTimestampsCorrectness() {
            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount1000, "测试公司");

            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000010");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);
            Instant before = Instant.now();

            invoiceIssueService.issue(request);

            verify(invoiceMapper).insert(invoiceCaptor.capture());
            Invoice captured = invoiceCaptor.getValue();
            Instant after = Instant.now();

            assertNotNull(captured.getIssueTime());
            assertNotNull(captured.getCreatedAt());
            assertNotNull(captured.getUpdatedAt());
            assertFalse(captured.getIssueTime().isBefore(before));
            assertFalse(captured.getCreatedAt().isBefore(before));
            assertFalse(captured.getIssueTime().isAfter(after));
            assertFalse(captured.getCreatedAt().isAfter(after));
        }
    }

    @Nested
    @DisplayName("号码分配准确性测试")
    class NumberAllocationTests {

        @Test
        @Order(5)
        @DisplayName("测试号码分配正确性")
        void testNumberAllocationCorrectness() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special"))
                    .thenReturn("00000001")
                    .thenReturn("00000002")
                    .thenReturn("00000003");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest request1 = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount1000, "公司A");
            InvoiceIssueRequest request2 = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount1000, "公司B");
            InvoiceIssueRequest request3 = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount1000, "公司C");

            InvoiceIssueResponse resp1 = invoiceIssueService.issue(request1);
            InvoiceIssueResponse resp2 = invoiceIssueService.issue(request2);
            InvoiceIssueResponse resp3 = invoiceIssueService.issue(request3);

            assertEquals("00000001", resp1.getInvoiceNo());
            assertEquals("00000002", resp2.getInvoiceNo());
            assertEquals("00000003", resp3.getInvoiceNo());

            verify(invoiceNumberService, times(3)).getInvoiceCode("vat_special");
            verify(invoiceNumberService, times(3)).allocate("vat_special");
        }

        @Test
        @Order(6)
        @DisplayName("测试不同类型发票号码独立分配")
        void testDifferentTypesIndependentAllocation() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.isValidType("vat_common")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceTypeService.getTaxRate("vat_common")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.getInvoiceCode("vat_common")).thenReturn("1101");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000050");
            when(invoiceNumberService.allocate("vat_common")).thenReturn("00000100");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest specialReq = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount1000, "专用票公司");
            InvoiceIssueRequest commonReq = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_common", amount1000, "普通票公司");

            InvoiceIssueResponse specialResp = invoiceIssueService.issue(specialReq);
            InvoiceIssueResponse commonResp = invoiceIssueService.issue(commonReq);

            assertEquals("1100", specialResp.getInvoiceCode());
            assertEquals("00000050", specialResp.getInvoiceNo());
            assertEquals("1101", commonResp.getInvoiceCode());
            assertEquals("00000100", commonResp.getInvoiceNo());
        }

        @Test
        @Order(7)
        @DisplayName("测试号码不足时抛出异常")
        void testNumberInsufficientThrowsException() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special"))
                    .thenThrow(InvoiceException.numberInsufficient());

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount1000, "测试公司");

            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.issue(request);
            });

            assertTrue(exception.getMessage().contains("发票号码不足"));
            verify(invoiceMapper, never()).insert(any(Invoice.class));
        }
    }

    @Nested
    @DisplayName("税额计算正确性测试")
    class TaxCalculationTests {

        @Test
        @Order(8)
        @DisplayName("测试13%税率税额计算正确性")
        void test13PercentTaxCalculation() {
            BigDecimal expectedTax = calculateTax(amount10000, taxRate13);
            BigDecimal expectedTotal = calculateTotal(amount10000, expectedTax);

            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000001");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount10000, "测试公司");

            ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);

            invoiceIssueService.issue(request);

            verify(invoiceMapper).insert(invoiceCaptor.capture());
            Invoice captured = invoiceCaptor.getValue();

            assertEquals(0, amount10000.compareTo(captured.getInvoiceAmount()));
            assertEquals(0, expectedTax.compareTo(captured.getTaxAmount()));
            assertEquals(0, expectedTotal.compareTo(captured.getTotalAmount()));
            assertEquals(new BigDecimal("1300.00"), captured.getTaxAmount());
            assertEquals(new BigDecimal("11300.00"), captured.getTotalAmount());
        }

        @Test
        @Order(9)
        @DisplayName("测试9%税率税额计算正确性")
        void test9PercentTaxCalculation() {
            BigDecimal amount = new BigDecimal("10000.00");
            BigDecimal expectedTax = calculateTax(amount, taxRate9);
            BigDecimal expectedTotal = calculateTotal(amount, expectedTax);

            when(invoiceTypeService.isValidType("vat_9pct")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_9pct")).thenReturn(taxRate9);
            when(invoiceNumberService.getInvoiceCode("vat_9pct")).thenReturn("1109");
            when(invoiceNumberService.allocate("vat_9pct")).thenReturn("00000001");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_9pct", amount, "建筑公司");

            ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);

            invoiceIssueService.issue(request);

            verify(invoiceMapper).insert(invoiceCaptor.capture());
            Invoice captured = invoiceCaptor.getValue();

            assertEquals(0, amount.compareTo(captured.getInvoiceAmount()));
            assertEquals(0, expectedTax.compareTo(captured.getTaxAmount()));
            assertEquals(0, expectedTotal.compareTo(captured.getTotalAmount()));
            assertEquals(new BigDecimal("900.00"), captured.getTaxAmount());
            assertEquals(new BigDecimal("10900.00"), captured.getTotalAmount());
        }

        @Test
        @Order(10)
        @DisplayName("测试6%税率税额计算正确性")
        void test6PercentTaxCalculation() {
            BigDecimal amount = new BigDecimal("10000.00");
            BigDecimal expectedTax = calculateTax(amount, taxRate6);
            BigDecimal expectedTotal = calculateTotal(amount, expectedTax);

            when(invoiceTypeService.isValidType("vat_6pct")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_6pct")).thenReturn(taxRate6);
            when(invoiceNumberService.getInvoiceCode("vat_6pct")).thenReturn("1106");
            when(invoiceNumberService.allocate("vat_6pct")).thenReturn("00000001");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_6pct", amount, "服务公司");

            ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);

            invoiceIssueService.issue(request);

            verify(invoiceMapper).insert(invoiceCaptor.capture());
            Invoice captured = invoiceCaptor.getValue();

            assertEquals(0, amount.compareTo(captured.getInvoiceAmount()));
            assertEquals(0, expectedTax.compareTo(captured.getTaxAmount()));
            assertEquals(0, expectedTotal.compareTo(captured.getTotalAmount()));
            assertEquals(new BigDecimal("600.00"), captured.getTaxAmount());
            assertEquals(new BigDecimal("10600.00"), captured.getTotalAmount());
        }

        @Test
        @Order(11)
        @DisplayName("测试小数金额税额计算四舍五入正确性")
        void testDecimalAmountRounding() {
            BigDecimal amount = new BigDecimal("12345.67");
            BigDecimal taxRate = new BigDecimal("0.13");
            BigDecimal expectedTax = new BigDecimal("1604.94");
            BigDecimal expectedTotal = new BigDecimal("13950.61");

            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000001");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount, "测试公司");

            ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);

            invoiceIssueService.issue(request);

            verify(invoiceMapper).insert(invoiceCaptor.capture());
            Invoice captured = invoiceCaptor.getValue();

            assertEquals(expectedTax, captured.getTaxAmount());
            assertEquals(expectedTotal, captured.getTotalAmount());
        }
    }

    @Nested
    @DisplayName("发票状态流转测试")
    class StatusFlowTests {

        @Test
        @Order(12)
        @DisplayName("测试开票后状态流转为已开具")
        void testStatusChangedToIssuedAfterIssue() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000001");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount1000, "测试公司");

            ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);

            InvoiceIssueResponse response = invoiceIssueService.issue(request);

            verify(invoiceMapper).insert(invoiceCaptor.capture());
            Invoice captured = invoiceCaptor.getValue();

            assertEquals(InvoiceStatusEnum.ISSUED.getCode(), captured.getInvoiceStatus());
            assertEquals(InvoiceStatusEnum.ISSUED.getCode(), response.getInvoiceStatus());

            verify(invoiceStatusService, times(1)).issue(anyString(), eq("admin"));
        }

        @Test
        @Order(13)
        @DisplayName("测试开票后触发归档操作")
        void testArchiveCalledAfterIssue() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000001");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount1000, "测试公司");

            invoiceIssueService.issue(request);

            verify(invoiceArchiveService, times(1)).archiveElectronic(anyString(), eq("admin"));
        }

        @Test
        @Order(14)
        @DisplayName("测试开票后更新统计信息")
        void testStatisticsUpdatedAfterIssue() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000001");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount10000, "测试公司");

            invoiceIssueService.issue(request);

            ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<BigDecimal> taxCaptor = ArgumentCaptor.forClass(BigDecimal.class);

            verify(invoiceStatisticsService, times(1))
                    .recordIssue(amountCaptor.capture(), taxCaptor.capture());

            assertEquals(0, amount10000.compareTo(amountCaptor.getValue()));
            assertEquals(new BigDecimal("1300.00"), taxCaptor.getValue());
        }

        @Test
        @Order(15)
        @DisplayName("测试开票后记录历史")
        void testHistoryRecordedAfterIssue() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000001");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount1000, "测试公司");

            invoiceIssueService.issue(request);

            verify(invoiceHistoryService, times(1)).recordIssue(anyString(), eq("admin"));
        }
    }

    @Nested
    @DisplayName("输入验证测试")
    class InputValidationTests {

        @Test
        @Order(16)
        @DisplayName("测试无效发票类型抛出异常")
        void testInvalidTypeThrowsException() {
            when(invoiceTypeService.isValidType("invalid_type")).thenReturn(false);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "invalid_type", amount1000, "测试公司");

            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.issue(request);
            });

            assertEquals("发票类型无效", exception.getMessage());
            verify(invoiceNumberService, never()).allocate(anyString());
        }

        @Test
        @Order(17)
        @DisplayName("测试购买方名称为空抛出异常")
        void testMissingBuyerNameThrowsException() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);

            InvoiceIssueRequest request = InvoiceIssueRequest.builder()
                    .invoiceType("vat_special")
                    .buyerName("")
                    .invoiceAmount(amount1000)
                    .build();

            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.issue(request);
            });

            assertEquals("购买方信息缺失", exception.getMessage());
        }

        @Test
        @Order(18)
        @DisplayName("测试购买方名称为空白抛出异常")
        void testBlankBuyerNameThrowsException() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);

            InvoiceIssueRequest request = InvoiceIssueRequest.builder()
                    .invoiceType("vat_special")
                    .buyerName("   ")
                    .invoiceAmount(amount1000)
                    .build();

            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.issue(request);
            });

            assertEquals("购买方信息缺失", exception.getMessage());
        }

        @Test
        @Order(19)
        @DisplayName("测试金额为0抛出异常")
        void testZeroAmountThrowsException() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", BigDecimal.ZERO, "测试公司");

            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.issue(request);
            });

            assertEquals("发票金额异常", exception.getMessage());
        }

        @Test
        @Order(20)
        @DisplayName("测试金额为负数抛出异常")
        void testNegativeAmountThrowsException() {
            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", new BigDecimal("-100.00"), "测试公司");

            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.issue(request);
            });

            assertEquals("发票金额异常", exception.getMessage());
        }

        @Test
        @Order(21)
        @DisplayName("测试金额为null抛出异常")
        void testNullAmountThrowsException() {
            InvoiceIssueRequest request = InvoiceIssueRequest.builder()
                    .invoiceType("vat_special")
                    .buyerName("测试公司")
                    .invoiceAmount(null)
                    .build();

            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.issue(request);
            });

            assertEquals("发票金额异常", exception.getMessage());
        }

        @Test
        @Order(22)
        @DisplayName("测试空请求抛出异常")
        void testNullRequestThrowsException() {
            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.issue(null);
            });

            assertEquals("请求不能为空", exception.getMessage());
        }

        @Test
        @Order(23)
        @DisplayName("测试空发票类型抛出异常")
        void testNullTypeThrowsException() {
            InvoiceIssueRequest request = InvoiceIssueRequest.builder()
                    .invoiceType(null)
                    .buyerName("测试公司")
                    .invoiceAmount(amount1000)
                    .build();

            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.issue(request);
            });

            assertEquals("发票类型无效", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("发票查询测试")
    class InvoiceQueryTests {

        @Test
        @Order(24)
        @DisplayName("测试按ID查询发票")
        void testGetInvoiceById() {
            Invoice expectedInvoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_test_001")
                    .invoiceNo("00000099")
                    .invoiceCode("1100")
                    .invoiceType("vat_special")
                    .build();

            when(invoiceMapper.findById("invoice_test_001")).thenReturn(expectedInvoice);

            Invoice result = invoiceIssueService.getById("invoice_test_001");

            assertNotNull(result);
            assertEquals("invoice_test_001", result.getInvoiceId());
            assertEquals("00000099", result.getInvoiceNo());
            assertEquals("1100", result.getInvoiceCode());
        }

        @Test
        @Order(25)
        @DisplayName("测试查询不存在的发票抛出异常")
        void testGetNonExistentInvoiceThrowsException() {
            when(invoiceMapper.findById("non_existent")).thenReturn(null);

            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.getById("non_existent");
            });

            assertEquals("发票不存在", exception.getMessage());
        }

        @Test
        @Order(26)
        @DisplayName("测试按号码和代码查询发票")
        void testGetInvoiceByNoAndCode() {
            Invoice expectedInvoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_test_002")
                    .invoiceNo("00000088")
                    .invoiceCode("1101")
                    .build();

            when(invoiceMapper.findByNoAndCode("00000088", "1101")).thenReturn(expectedInvoice);

            Invoice result = invoiceIssueService.getByNoAndCode("00000088", "1101");

            assertNotNull(result);
            assertEquals("invoice_test_002", result.getInvoiceId());
            assertEquals("00000088", result.getInvoiceNo());
        }

        @Test
        @Order(27)
        @DisplayName("测试按号码和代码查询不存在的发票抛出异常")
        void testGetByNoAndCodeNonExistentThrowsException() {
            when(invoiceMapper.findByNoAndCode("99999999", "9999")).thenReturn(null);

            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceIssueService.getByNoAndCode("99999999", "9999");
            });

            assertEquals("发票不存在", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("完整流程测试")
    class FullFlowTests {

        @Test
        @Order(28)
        @DisplayName("测试完整开票流程调用顺序")
        void testCompleteIssueFlow() {
            InOrder inOrder = inOrder(
                    invoiceTypeService,
                    invoiceNumberService,
                    invoiceMapper,
                    invoiceStatusService,
                    invoiceArchiveService,
                    invoiceStatisticsService,
                    invoiceHistoryService);

            when(invoiceTypeService.isValidType("vat_special")).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.allocate("vat_special")).thenReturn("00000100");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest request = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", amount10000, "完整流程测试公司");

            invoiceIssueService.issue(request);

            inOrder.verify(invoiceTypeService).isValidType("vat_special");
            inOrder.verify(invoiceTypeService).getTaxRate("vat_special");
            inOrder.verify(invoiceNumberService).getInvoiceCode("vat_special");
            inOrder.verify(invoiceNumberService).allocate("vat_special");
            inOrder.verify(invoiceMapper).insert(any(Invoice.class));
            inOrder.verify(invoiceStatusService).issue(anyString(), eq("admin"));
            inOrder.verify(invoiceArchiveService).archiveElectronic(anyString(), eq("admin"));
            inOrder.verify(invoiceStatisticsService).recordIssue(any(BigDecimal.class), any(BigDecimal.class));
            inOrder.verify(invoiceHistoryService).recordIssue(anyString(), eq("admin"));
        }

        @Test
        @Order(29)
        @DisplayName("测试多次开票流程独立性")
        void testMultipleInvoicesIndependence() {
            when(invoiceTypeService.isValidType(anyString())).thenReturn(true);
            when(invoiceTypeService.getTaxRate("vat_special")).thenReturn(taxRate13);
            when(invoiceTypeService.getTaxRate("vat_common")).thenReturn(taxRate13);
            when(invoiceNumberService.getInvoiceCode("vat_special")).thenReturn("1100");
            when(invoiceNumberService.getInvoiceCode("vat_common")).thenReturn("1101");
            when(invoiceNumberService.allocate("vat_special"))
                    .thenReturn("00000001")
                    .thenReturn("00000002");
            when(invoiceNumberService.allocate("vat_common")).thenReturn("00000100");
            when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);

            InvoiceIssueRequest req1 = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", new BigDecimal("5000.00"), "公司A");
            InvoiceIssueRequest req2 = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_special", new BigDecimal("8000.00"), "公司B");
            InvoiceIssueRequest req3 = TestDataBuilder.RequestBuilder.buildIssueRequest(
                    "vat_common", new BigDecimal("12000.00"), "公司C");

            InvoiceIssueResponse resp1 = invoiceIssueService.issue(req1);
            InvoiceIssueResponse resp2 = invoiceIssueService.issue(req2);
            InvoiceIssueResponse resp3 = invoiceIssueService.issue(req3);

            assertNotNull(resp1.getInvoiceId());
            assertNotNull(resp2.getInvoiceId());
            assertNotNull(resp3.getInvoiceId());
            assertNotEquals(resp1.getInvoiceId(), resp2.getInvoiceId());
            assertNotEquals(resp2.getInvoiceId(), resp3.getInvoiceId());

            assertEquals("1100", resp1.getInvoiceCode());
            assertEquals("1100", resp2.getInvoiceCode());
            assertEquals("1101", resp3.getInvoiceCode());

            verify(invoiceMapper, times(3)).insert(any(Invoice.class));
            verify(invoiceStatusService, times(3)).issue(anyString(), anyString());
            verify(invoiceArchiveService, times(3)).archiveElectronic(anyString(), anyString());
            verify(invoiceStatisticsService, times(3)).recordIssue(any(BigDecimal.class), any(BigDecimal.class));
        }
    }
}
