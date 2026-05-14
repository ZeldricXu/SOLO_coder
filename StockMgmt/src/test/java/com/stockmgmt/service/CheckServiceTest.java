package com.stockmgmt.service;

import com.stockmgmt.builder.TestDataBuilder;
import com.stockmgmt.dto.CheckCreateRequest;
import com.stockmgmt.dto.CheckDiffRequest;
import com.stockmgmt.entity.Stock;
import com.stockmgmt.entity.StockCheck;
import com.stockmgmt.entity.StockCheckDiff;
import com.stockmgmt.enums.CheckStatus;
import com.stockmgmt.enums.CheckType;
import com.stockmgmt.enums.DiffHandleStatus;
import com.stockmgmt.exception.BusinessException;
import com.stockmgmt.repository.StockCheckDiffRepository;
import com.stockmgmt.repository.StockCheckRepository;
import com.stockmgmt.repository.StockRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("盘点差异处理测试")
class CheckServiceTest {

    @Mock
    private StockCheckRepository checkRepository;

    @Mock
    private StockCheckDiffRepository diffRepository;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private CheckService checkService;

    @BeforeEach
    void setUp() {
        TestDataBuilder.reset();
    }

    @Test
    @DisplayName("创建盘点任务 - 成功创建盘点任务")
    void testCreateCheck_Success() {
        CheckCreateRequest request = TestDataBuilder.buildCheckCreateRequest();

        when(checkRepository.save(any(StockCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheck result = checkService.createCheck(request);

        assertNotNull(result);
        assertNotNull(result.getCheckNo());
        assertEquals(request.getWarehouseId(), result.getWarehouseId());
        assertEquals(CheckType.FULL, result.getCheckType());
        assertEquals(CheckStatus.PENDING, result.getCheckStatus());
        assertEquals(0, result.getTotalItems());
        assertEquals(0, result.getCheckedItems());
        assertEquals(0, result.getDifferenceCount());

        ArgumentCaptor<StockCheck> captor = ArgumentCaptor.forClass(StockCheck.class);
        verify(checkRepository).save(captor.capture());
        assertTrue(captor.getValue().getCheckNo().startsWith("PD"));
    }

    @Test
    @DisplayName("创建盘点任务 - 无效盘点类型抛出异常")
    void testCreateCheck_InvalidType() {
        CheckCreateRequest request = TestDataBuilder.buildCheckCreateRequest();
        request.setCheckType("invalid_type");

        assertThrows(BusinessException.class, () -> checkService.createCheck(request));
        verify(checkRepository, never()).save(any(StockCheck.class));
    }

    @Test
    @DisplayName("开始盘点任务 - 从待处理变为执行中")
    void testStartCheck_Success() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.PENDING, CheckType.FULL);
        List<Stock> stocks = Arrays.asList(TestDataBuilder.buildStock());

        when(checkRepository.findById(check.getCheckId())).thenReturn(Optional.of(check));
        when(stockRepository.findByWarehouseId(check.getWarehouseId())).thenReturn(stocks);
        when(checkRepository.save(any(StockCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheck result = checkService.startCheck(check.getCheckId(), "test_user");

        assertEquals(CheckStatus.PROCESSING, result.getCheckStatus());
        assertNotNull(result.getStartedAt());
        assertEquals(1, result.getTotalItems());

        verify(checkRepository).save(check);
    }

    @Test
    @DisplayName("开始盘点任务 - 任务已在执行中抛出异常")
    void testStartCheck_AlreadyProcessing() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.PROCESSING, CheckType.FULL);

        when(checkRepository.findById(check.getCheckId())).thenReturn(Optional.of(check));

        assertThrows(BusinessException.class, () -> checkService.startCheck(check.getCheckId(), "test_user"));
        verify(checkRepository, never()).save(any(StockCheck.class));
    }

    @Test
    @DisplayName("记录盘点差异 - 盘亏差异计算正确")
    void testRecordCheckDiff_NegativeDiff() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.PROCESSING, CheckType.FULL);
        check.setCheckId("CHECK_001");
        check.setCheckedItems(0);
        check.setDifferenceCount(0);

        Stock stock = TestDataBuilder.buildStock(100, 100, 0);
        stock.setStockId("STOCK_001");

        CheckDiffRequest request = TestDataBuilder.buildCheckDiffRequest("CHECK_001", "STOCK_001", 95, "破损丢失");

        when(checkRepository.findById("CHECK_001")).thenReturn(Optional.of(check));
        when(stockRepository.findById("STOCK_001")).thenReturn(Optional.of(stock));
        when(diffRepository.save(any(StockCheckDiff.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(checkRepository.save(any(StockCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheckDiff result = checkService.recordCheckDiff(request);

        assertNotNull(result);
        assertEquals(100, result.getSystemQuantity());
        assertEquals(95, result.getActualQuantity());
        assertEquals(-5, result.getDifference());
        assertEquals("破损丢失", result.getDiffReason());
        assertEquals(DiffHandleStatus.PENDING, result.getHandleStatus());

        verify(checkRepository).save(check);
        assertEquals(1, check.getCheckedItems());
        assertEquals(1, check.getDifferenceCount());
    }

    @Test
    @DisplayName("记录盘点差异 - 盘盈差异计算正确")
    void testRecordCheckDiff_PositiveDiff() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.PROCESSING, CheckType.FULL);
        check.setCheckId("CHECK_001");
        check.setCheckedItems(0);
        check.setDifferenceCount(0);

        Stock stock = TestDataBuilder.buildStock(100, 100, 0);
        stock.setStockId("STOCK_001");

        CheckDiffRequest request = TestDataBuilder.buildCheckDiffRequest("CHECK_001", "STOCK_001", 105, "盘盈-清点错误");

        when(checkRepository.findById("CHECK_001")).thenReturn(Optional.of(check));
        when(stockRepository.findById("STOCK_001")).thenReturn(Optional.of(stock));
        when(diffRepository.save(any(StockCheckDiff.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(checkRepository.save(any(StockCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheckDiff result = checkService.recordCheckDiff(request);

        assertEquals(100, result.getSystemQuantity());
        assertEquals(105, result.getActualQuantity());
        assertEquals(5, result.getDifference());
        assertEquals(1, check.getCheckedItems());
        assertEquals(1, check.getDifferenceCount());
    }

    @Test
    @DisplayName("记录盘点差异 - 无差异时不计入差异数")
    void testRecordCheckDiff_NoDifference() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.PROCESSING, CheckType.FULL);
        check.setCheckId("CHECK_001");
        check.setCheckedItems(0);
        check.setDifferenceCount(0);

        Stock stock = TestDataBuilder.buildStock(100, 100, 0);
        stock.setStockId("STOCK_001");

        CheckDiffRequest request = TestDataBuilder.buildCheckDiffRequest("CHECK_001", "STOCK_001", 100, "账实一致");

        when(checkRepository.findById("CHECK_001")).thenReturn(Optional.of(check));
        when(stockRepository.findById("STOCK_001")).thenReturn(Optional.of(stock));
        when(diffRepository.save(any(StockCheckDiff.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(checkRepository.save(any(StockCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        checkService.recordCheckDiff(request);

        assertEquals(1, check.getCheckedItems());
        assertEquals(0, check.getDifferenceCount());
    }

    @Test
    @DisplayName("记录盘点差异 - 盘点任务未执行中抛出异常")
    void testRecordCheckDiff_NotProcessing() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.PENDING, CheckType.FULL);

        CheckDiffRequest request = TestDataBuilder.buildCheckDiffRequest();

        when(checkRepository.findById(anyString())).thenReturn(Optional.of(check));

        assertThrows(BusinessException.class, () -> checkService.recordCheckDiff(request));
        verify(diffRepository, never()).save(any(StockCheckDiff.class));
    }

    @Test
    @DisplayName("完成盘点任务 - 从执行中变为已完成")
    void testCompleteCheck_Success() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.PROCESSING, CheckType.FULL);

        when(checkRepository.findById(check.getCheckId())).thenReturn(Optional.of(check));
        when(checkRepository.save(any(StockCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheck result = checkService.completeCheck(check.getCheckId(), "test_user");

        assertEquals(CheckStatus.COMPLETED, result.getCheckStatus());
        assertNotNull(result.getCompletedAt());

        verify(checkRepository).save(check);
    }

    @Test
    @DisplayName("审批盘点差异 - 待处理变为已批准")
    void testApproveDiff_Success() {
        StockCheckDiff diff = TestDataBuilder.buildStockCheckDiff(-5, DiffHandleStatus.PENDING);

        when(diffRepository.findById(diff.getDiffId())).thenReturn(Optional.of(diff));
        when(diffRepository.save(any(StockCheckDiff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheckDiff result = checkService.approveDiff(diff.getDiffId(), "approver_user");

        assertEquals(DiffHandleStatus.APPROVED, result.getHandleStatus());
        assertEquals("approver_user", result.getApproveBy());
        assertNotNull(result.getApproveAt());

        verify(diffRepository).save(diff);
    }

    @Test
    @DisplayName("审批盘点差异 - 差异已处理抛出异常")
    void testApproveDiff_AlreadyApproved() {
        StockCheckDiff diff = TestDataBuilder.buildStockCheckDiff(-5, DiffHandleStatus.APPROVED);

        when(diffRepository.findById(diff.getDiffId())).thenReturn(Optional.of(diff));

        assertThrows(BusinessException.class, () -> checkService.approveDiff(diff.getDiffId(), "approver_user"));
        verify(diffRepository, never()).save(any(StockCheckDiff.class));
    }

    @Test
    @DisplayName("拒绝盘点差异 - 待处理变为已拒绝")
    void testRejectDiff_Success() {
        StockCheckDiff diff = TestDataBuilder.buildStockCheckDiff(-5, DiffHandleStatus.PENDING);

        when(diffRepository.findById(diff.getDiffId())).thenReturn(Optional.of(diff));
        when(diffRepository.save(any(StockCheckDiff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheckDiff result = checkService.rejectDiff(diff.getDiffId(), "approver_user", "盘点记录有误");

        assertEquals(DiffHandleStatus.REJECTED, result.getHandleStatus());
        assertEquals("approver_user", result.getApproveBy());
        assertEquals("盘点记录有误", result.getRemark());

        verify(diffRepository).save(diff);
    }

    @Test
    @DisplayName("处理盘点差异 - 盘亏调整库存正确减少")
    void testProcessDiff_NegativeDiff() {
        StockCheckDiff diff = TestDataBuilder.buildStockCheckDiff(-5, DiffHandleStatus.APPROVED);
        diff.setStockId("STOCK_001");

        Stock stock = TestDataBuilder.buildStock(100, 100, 0);
        stock.setStockId("STOCK_001");

        when(diffRepository.findById(diff.getDiffId())).thenReturn(Optional.of(diff));
        when(stockRepository.findByIdWithLock("STOCK_001")).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diffRepository.save(any(StockCheckDiff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheckDiff result = checkService.processDiff(diff.getDiffId(), "handler_user");

        assertEquals(DiffHandleStatus.PROCESSED, result.getHandleStatus());
        assertEquals("handler_user", result.getHandledBy());
        assertNotNull(result.getHandledAt());

        ArgumentCaptor<Stock> stockCaptor = ArgumentCaptor.forClass(Stock.class);
        verify(stockRepository).save(stockCaptor.capture());

        Stock updatedStock = stockCaptor.getValue();
        assertEquals(95, updatedStock.getCurrentQuantity());
        assertEquals(95, updatedStock.getAvailableQuantity());

        verify(historyService).recordHistory(
                any(Stock.class),
                any(com.stockmgmt.enums.OperationType.class),
                eq(-5),
                eq(100),
                eq(95),
                eq("handler_user"),
                anyString(),
                anyString()
        );
    }

    @Test
    @DisplayName("处理盘点差异 - 盘盈调整库存正确增加")
    void testProcessDiff_PositiveDiff() {
        StockCheckDiff diff = TestDataBuilder.buildStockCheckDiff(5, DiffHandleStatus.APPROVED);
        diff.setStockId("STOCK_001");

        Stock stock = TestDataBuilder.buildStock(100, 100, 0);
        stock.setStockId("STOCK_001");

        when(diffRepository.findById(diff.getDiffId())).thenReturn(Optional.of(diff));
        when(stockRepository.findByIdWithLock("STOCK_001")).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diffRepository.save(any(StockCheckDiff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        checkService.processDiff(diff.getDiffId(), "handler_user");

        ArgumentCaptor<Stock> stockCaptor = ArgumentCaptor.forClass(Stock.class);
        verify(stockRepository).save(stockCaptor.capture());

        Stock updatedStock = stockCaptor.getValue();
        assertEquals(105, updatedStock.getCurrentQuantity());
        assertEquals(105, updatedStock.getAvailableQuantity());
    }

    @Test
    @DisplayName("处理盘点差异 - 未审批抛出异常")
    void testProcessDiff_NotApproved() {
        StockCheckDiff diff = TestDataBuilder.buildStockCheckDiff(-5, DiffHandleStatus.PENDING);

        when(diffRepository.findById(diff.getDiffId())).thenReturn(Optional.of(diff));

        assertThrows(BusinessException.class, () -> checkService.processDiff(diff.getDiffId(), "handler_user"));
        verify(stockRepository, never()).save(any(Stock.class));
    }

    @Test
    @DisplayName("取消盘点任务 - 待处理任务可取消")
    void testCancelCheck_FromPending() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.PENDING, CheckType.FULL);

        when(checkRepository.findById(check.getCheckId())).thenReturn(Optional.of(check));
        when(checkRepository.save(any(StockCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheck result = checkService.cancelCheck(check.getCheckId(), "test_user", "盘点任务取消");

        assertEquals(CheckStatus.CANCELLED, result.getCheckStatus());
        assertEquals("盘点任务取消", result.getRemark());

        verify(checkRepository).save(check);
    }

    @Test
    @DisplayName("取消盘点任务 - 执行中任务可取消")
    void testCancelCheck_FromProcessing() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.PROCESSING, CheckType.FULL);

        when(checkRepository.findById(check.getCheckId())).thenReturn(Optional.of(check));
        when(checkRepository.save(any(StockCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheck result = checkService.cancelCheck(check.getCheckId(), "test_user", null);

        assertEquals(CheckStatus.CANCELLED, result.getCheckStatus());
    }

    @Test
    @DisplayName("取消盘点任务 - 已完成任务不可取消")
    void testCancelCheck_AlreadyCompleted() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.COMPLETED, CheckType.FULL);

        when(checkRepository.findById(check.getCheckId())).thenReturn(Optional.of(check));

        assertThrows(BusinessException.class, () -> checkService.cancelCheck(check.getCheckId(), "test_user", "测试取消"));
        verify(checkRepository, never()).save(any(StockCheck.class));
    }

    @Test
    @DisplayName("获取盘点任务 - 按ID查询")
    void testGetCheckById_Success() {
        StockCheck check = TestDataBuilder.buildStockCheck();

        when(checkRepository.findById(check.getCheckId())).thenReturn(Optional.of(check));

        StockCheck result = checkService.getCheckById(check.getCheckId());

        assertNotNull(result);
        assertEquals(check.getCheckId(), result.getCheckId());
    }

    @Test
    @DisplayName("获取盘点任务 - 不存在抛出异常")
    void testGetCheckById_NotFound() {
        when(checkRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> checkService.getCheckById("INVALID_ID"));
    }

    @Test
    @DisplayName("获取盘点差异 - 按盘点任务查询")
    void testGetDiffsByCheckId_Success() {
        StockCheckDiff diff1 = TestDataBuilder.buildStockCheckDiff(-5, DiffHandleStatus.PENDING);
        StockCheckDiff diff2 = TestDataBuilder.buildStockCheckDiff(3, DiffHandleStatus.APPROVED);

        when(diffRepository.findByCheckId("CHECK_001")).thenReturn(Arrays.asList(diff1, diff2));

        List<StockCheckDiff> diffs = checkService.getDiffsByCheckId("CHECK_001");

        assertEquals(2, diffs.size());
    }

    @Test
    @DisplayName("获取待处理差异 - 正确过滤状态")
    void testGetPendingDiffs_Success() {
        StockCheckDiff pendingDiff = TestDataBuilder.buildStockCheckDiff(-5, DiffHandleStatus.PENDING);
        StockCheckDiff approvedDiff = TestDataBuilder.buildStockCheckDiff(3, DiffHandleStatus.APPROVED);

        when(diffRepository.findByCheckIdAndHandleStatus("CHECK_001", DiffHandleStatus.PENDING))
                .thenReturn(Arrays.asList(pendingDiff));

        List<StockCheckDiff> diffs = checkService.getPendingDiffs("CHECK_001");

        assertEquals(1, diffs.size());
        assertEquals(DiffHandleStatus.PENDING, diffs.get(0).getHandleStatus());
    }

    @Test
    @DisplayName("盘点审核流程 - 完整流程测试")
    void testFullCheckWorkflow() {
        StockCheck check = TestDataBuilder.buildStockCheck(CheckStatus.PENDING, CheckType.FULL);
        check.setCheckId("CHECK_001");

        Stock stock = TestDataBuilder.buildStock(100, 100, 0);
        stock.setStockId("STOCK_001");

        when(checkRepository.findById("CHECK_001")).thenReturn(Optional.of(check));
        when(stockRepository.findByWarehouseId(anyString())).thenReturn(Arrays.asList(stock));
        when(checkRepository.save(any(StockCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        checkService.startCheck("CHECK_001", "operator");

        assertEquals(CheckStatus.PROCESSING, check.getCheckStatus());

        StockCheckDiff diff = TestDataBuilder.buildStockCheckDiff(-5, DiffHandleStatus.PENDING);
        diff.setStockId("STOCK_001");

        when(diffRepository.findById(diff.getDiffId())).thenReturn(Optional.of(diff));
        when(diffRepository.save(any(StockCheckDiff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockCheckDiff approvedDiff = checkService.approveDiff(diff.getDiffId(), "approver");
        assertEquals(DiffHandleStatus.APPROVED, approvedDiff.getHandleStatus());

        when(stockRepository.findByIdWithLock("STOCK_001")).thenReturn(Optional.of(stock));
        when(historyService.recordHistory(any(), any(), anyInt(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(null);

        StockCheckDiff processedDiff = checkService.processDiff(diff.getDiffId(), "handler");
        assertEquals(DiffHandleStatus.PROCESSED, processedDiff.getHandleStatus());

        checkService.completeCheck("CHECK_001", "operator");
        assertEquals(CheckStatus.COMPLETED, check.getCheckStatus());
    }
}
