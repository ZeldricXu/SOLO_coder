package com.stockmgmt.service;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class CheckService {

    private static final Logger logger = LoggerFactory.getLogger(CheckService.class);

    @Autowired
    private StockCheckRepository checkRepository;

    @Autowired
    private StockCheckDiffRepository diffRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private HistoryService historyService;

    @Transactional(rollbackFor = Exception.class)
    public StockCheck createCheck(CheckCreateRequest request) {
        logger.info("创建盘点任务，仓库: {}, 类型: {}", request.getWarehouseId(), request.getCheckType());

        CheckType checkType = CheckType.fromCode(request.getCheckType());
        if (checkType == null) {
            throw BusinessException.of("盘点类型无效: " + request.getCheckType());
        }

        String checkNo = generateCheckNo();

        StockCheck check = new StockCheck();
        check.setCheckNo(checkNo);
        check.setWarehouseId(request.getWarehouseId());
        check.setCheckType(checkType);
        check.setCheckStatus(CheckStatus.PENDING);
        check.setCheckName(request.getCheckName() != null ? request.getCheckName() : checkNo);
        check.setOperator(request.getOperator());
        check.setRemark(request.getRemark());
        check.setTotalItems(0);
        check.setCheckedItems(0);
        check.setDifferenceCount(0);

        StockCheck saved = checkRepository.save(check);
        logger.info("盘点任务创建成功，checkId: {}", saved.getCheckId());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockCheck startCheck(String checkId, String operator) {
        logger.info("开始盘点任务，checkId: {}", checkId);

        StockCheck check = checkRepository.findById(checkId)
                .orElseThrow(() -> BusinessException.of("盘点任务不存在: " + checkId));

        if (check.getCheckStatus() != CheckStatus.PENDING) {
            throw BusinessException.of("盘点任务状态不正确，当前状态: " + check.getCheckStatus());
        }

        List<Stock> stocks = stockRepository.findByWarehouseId(check.getWarehouseId());

        check.setCheckStatus(CheckStatus.PROCESSING);
        check.setStartedAt(LocalDateTime.now());
        check.setTotalItems(stocks.size());
        check.setOperator(operator != null ? operator : check.getOperator());

        StockCheck saved = checkRepository.save(check);
        logger.info("盘点任务开始成功，checkId: {}, 总盘点数: {}", saved.getCheckId(), saved.getTotalItems());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockCheckDiff recordCheckDiff(CheckDiffRequest request) {
        logger.info("记录盘点差异，checkId: {}, stockId: {}", request.getCheckId(), request.getStockId());

        StockCheck check = checkRepository.findById(request.getCheckId())
                .orElseThrow(() -> BusinessException.of("盘点任务不存在: " + request.getCheckId()));

        if (check.getCheckStatus() != CheckStatus.PROCESSING) {
            throw BusinessException.of("盘点任务未在执行中");
        }

        Stock stock = stockRepository.findById(request.getStockId())
                .orElseThrow(() -> BusinessException.of("库存记录不存在: " + request.getStockId()));

        int difference = request.getActualQuantity() - stock.getCurrentQuantity();

        StockCheckDiff diff = new StockCheckDiff();
        diff.setCheckId(check.getCheckId());
        diff.setStockId(stock.getStockId());
        diff.setProductId(stock.getProductId());
        diff.setProductName(stock.getProductName());
        diff.setSystemQuantity(stock.getCurrentQuantity());
        diff.setActualQuantity(request.getActualQuantity());
        diff.setDifference(difference);
        diff.setDiffReason(request.getDiffReason());
        diff.setHandleStatus(DiffHandleStatus.PENDING);
        diff.setOperator(request.getOperator());

        StockCheckDiff savedDiff = diffRepository.save(diff);

        check.setCheckedItems(check.getCheckedItems() + 1);
        if (difference != 0) {
            check.setDifferenceCount(check.getDifferenceCount() + 1);
        }
        checkRepository.save(check);

        logger.info("盘点差异记录成功，diffId: {}, 差异数量: {}", savedDiff.getDiffId(), difference);
        return savedDiff;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockCheck completeCheck(String checkId, String operator) {
        logger.info("完成盘点任务，checkId: {}", checkId);

        StockCheck check = checkRepository.findById(checkId)
                .orElseThrow(() -> BusinessException.of("盘点任务不存在: " + checkId));

        if (check.getCheckStatus() != CheckStatus.PROCESSING) {
            throw BusinessException.of("盘点任务未在执行中");
        }

        check.setCheckStatus(CheckStatus.COMPLETED);
        check.setCompletedAt(LocalDateTime.now());
        check.setOperator(operator != null ? operator : check.getOperator());

        StockCheck saved = checkRepository.save(check);
        logger.info("盘点任务完成，checkId: {}", saved.getCheckId());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockCheck cancelCheck(String checkId, String operator, String remark) {
        logger.info("取消盘点任务，checkId: {}", checkId);

        StockCheck check = checkRepository.findById(checkId)
                .orElseThrow(() -> BusinessException.of("盘点任务不存在: " + checkId));

        if (check.getCheckStatus() == CheckStatus.COMPLETED || check.getCheckStatus() == CheckStatus.CANCELLED) {
            throw BusinessException.of("盘点任务已完成或已取消");
        }

        check.setCheckStatus(CheckStatus.CANCELLED);
        check.setOperator(operator != null ? operator : check.getOperator());
        check.setRemark(remark != null ? remark : check.getRemark());

        StockCheck saved = checkRepository.save(check);
        logger.info("盘点任务取消成功，checkId: {}", saved.getCheckId());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockCheckDiff approveDiff(String diffId, String operator) {
        logger.info("审批盘点差异，diffId: {}", diffId);

        StockCheckDiff diff = diffRepository.findById(diffId)
                .orElseThrow(() -> BusinessException.of("差异记录不存在: " + diffId));

        if (diff.getHandleStatus() != DiffHandleStatus.PENDING) {
            throw BusinessException.of("差异状态不正确，当前状态: " + diff.getHandleStatus());
        }

        diff.setHandleStatus(DiffHandleStatus.APPROVED);
        diff.setApproveBy(operator != null ? operator : "system");
        diff.setApproveAt(LocalDateTime.now());

        return diffRepository.save(diff);
    }

    @Transactional(rollbackFor = Exception.class)
    public StockCheckDiff rejectDiff(String diffId, String operator, String remark) {
        logger.info("拒绝盘点差异，diffId: {}", diffId);

        StockCheckDiff diff = diffRepository.findById(diffId)
                .orElseThrow(() -> BusinessException.of("差异记录不存在: " + diffId));

        if (diff.getHandleStatus() != DiffHandleStatus.PENDING) {
            throw BusinessException.of("差异状态不正确，当前状态: " + diff.getHandleStatus());
        }

        diff.setHandleStatus(DiffHandleStatus.REJECTED);
        diff.setApproveBy(operator != null ? operator : "system");
        diff.setApproveAt(LocalDateTime.now());
        diff.setRemark(remark);

        return diffRepository.save(diff);
    }

    @Transactional(rollbackFor = Exception.class)
    public StockCheckDiff processDiff(String diffId, String operator) {
        logger.info("处理盘点差异（调整库存），diffId: {}", diffId);

        StockCheckDiff diff = diffRepository.findById(diffId)
                .orElseThrow(() -> BusinessException.of("差异记录不存在: " + diffId));

        if (diff.getHandleStatus() != DiffHandleStatus.APPROVED) {
            throw BusinessException.of("差异未审批，无法处理");
        }

        Stock stock = stockRepository.findByIdWithLock(diff.getStockId())
                .orElseThrow(() -> BusinessException.of("库存记录不存在: " + diff.getStockId()));

        int beforeQuantity = stock.getCurrentQuantity();
        stock.setCurrentQuantity(diff.getActualQuantity());
        stock.setAvailableQuantity(stock.getAvailableQuantity() + diff.getDifference());
        stockRepository.save(stock);

        historyService.recordHistory(stock, com.stockmgmt.enums.OperationType.ADJUST, 
                diff.getDifference(), beforeQuantity, stock.getCurrentQuantity(),
                operator, "ADJUST_" + diff.getDiffId(), 
                "盘点调整: " + (diff.getDiffReason() != null ? diff.getDiffReason() : ""));

        diff.setHandleStatus(DiffHandleStatus.PROCESSED);
        diff.setHandledBy(operator != null ? operator : "system");
        diff.setHandledAt(LocalDateTime.now());

        return diffRepository.save(diff);
    }

    public StockCheck getCheckById(String checkId) {
        return checkRepository.findById(checkId)
                .orElseThrow(() -> BusinessException.of("盘点任务不存在: " + checkId));
    }

    public StockCheck getCheckByNo(String checkNo) {
        return checkRepository.findByCheckNo(checkNo)
                .orElseThrow(() -> BusinessException.of("盘点任务不存在: " + checkNo));
    }

    public List<StockCheck> getChecksByWarehouse(String warehouseId) {
        return checkRepository.findByWarehouseId(warehouseId);
    }

    public List<StockCheck> getChecksByStatus(CheckStatus status) {
        return checkRepository.findByCheckStatus(status);
    }

    public Page<StockCheck> getCheckPage(String warehouseId, String status, int pageNum, int pageSize) {
        Pageable pageable = PageRequest.of(pageNum - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        org.springframework.data.jpa.domain.Specification<StockCheck> spec = null;

        if (warehouseId != null && !warehouseId.isEmpty()) {
            spec = (root, query, cb) -> cb.equal(root.get("warehouseId"), warehouseId);
        }
        if (status != null && !status.isEmpty()) {
            CheckStatus cs = CheckStatus.fromCode(status);
            if (cs != null) {
                org.springframework.data.jpa.domain.Specification<StockCheck> statusSpec =
                        (root, query, cb) -> cb.equal(root.get("checkStatus"), cs);
                spec = spec != null ? spec.and(statusSpec) : statusSpec;
            }
        }

        if (spec != null) {
            return checkRepository.findAll(spec, pageable);
        }
        return checkRepository.findAll(pageable);
    }

    public List<StockCheckDiff> getDiffsByCheckId(String checkId) {
        return diffRepository.findByCheckId(checkId);
    }

    public List<StockCheckDiff> getPendingDiffs(String checkId) {
        return diffRepository.findByCheckIdAndHandleStatus(checkId, DiffHandleStatus.PENDING);
    }

    private String generateCheckNo() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String timestamp = LocalDateTime.now().format(formatter);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "PD" + timestamp + uuid;
    }
}
