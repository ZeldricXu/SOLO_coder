package com.restaurant.mgmt.service;

import com.restaurant.mgmt.config.DynamicStockDeductionConfig;
import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.Stock;
import com.restaurant.mgmt.model.StockMovement;
import com.restaurant.mgmt.model.StockWarning;
import com.restaurant.mgmt.repository.StockMovementRepository;
import com.restaurant.mgmt.repository.StockRepository;
import com.restaurant.mgmt.repository.StockWarningRepository;
import com.restaurant.mgmt.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class StockService {

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockMovementRepository movementRepository;

    @Autowired
    private StockWarningRepository warningRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private DynamicStockDeductionConfig deductionConfig;

    public Stock createStock(Stock stock) {
        if (stock.getIngredientId() == null || stock.getIngredientId().trim().isEmpty()) {
            stock.setIngredientId(IdGenerator.generateIngredientId());
        }
        if (stock.getIngredientName() == null || stock.getIngredientName().trim().isEmpty()) {
            throw new BusinessException("食材名称不能为空");
        }
        if (stockRepository.existsByIngredientId(stock.getIngredientId())) {
            throw new BusinessException("食材ID已存在");
        }
        
        stock.setStockId(IdGenerator.generateStockId());
        stock.setCreatedAt(LocalDateTime.now());
        stock.setUpdatedAt(LocalDateTime.now());
        
        Stock saved = stockRepository.save(stock);
        historyService.recordHistory("stock", saved.getStockId(), "创建库存", 
            "创建食材库存: " + saved.getIngredientName(), "system", "create", "success");
        
        checkAndCreateWarning(saved);
        return saved;
    }

    public Stock updateStock(String stockId, Stock stock) {
        Optional<Stock> existingOpt = stockRepository.findById(stockId);
        if (existingOpt.isEmpty()) {
            throw new BusinessException("库存记录不存在");
        }
        
        Stock existing = existingOpt.get();
        if (stock.getIngredientName() != null) {
            existing.setIngredientName(stock.getIngredientName());
        }
        if (stock.getStockUnit() != null) {
            existing.setStockUnit(stock.getStockUnit());
        }
        if (stock.getWarningThreshold() >= 0) {
            existing.setWarningThreshold(stock.getWarningThreshold());
        }
        if (stock.getCategory() != null) {
            existing.setCategory(stock.getCategory());
        }
        if (stock.getSupplier() != null) {
            existing.setSupplier(stock.getSupplier());
        }
        if (stock.getUnitPrice() >= 0) {
            existing.setUnitPrice(stock.getUnitPrice());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        
        Stock saved = stockRepository.save(existing);
        checkAndCreateWarning(saved);
        return saved;
    }

    public void deleteStock(String stockId) {
        if (!stockRepository.existsById(stockId)) {
            throw new BusinessException("库存记录不存在");
        }
        stockRepository.deleteById(stockId);
    }

    public Stock getStockById(String stockId) {
        return stockRepository.findById(stockId)
                .orElseThrow(() -> new BusinessException("库存记录不存在"));
    }

    public Stock getStockByIngredientId(String ingredientId) {
        return stockRepository.findByIngredientId(ingredientId)
                .orElseThrow(() -> new BusinessException("食材不存在"));
    }

    public List<Stock> getAllStocks() {
        return stockRepository.findAll();
    }

    public List<Stock> getStocksByCategory(String category) {
        return stockRepository.findByCategory(category);
    }

    public List<Stock> getLowStockItems() {
        return stockRepository.findLowStockItems();
    }

    @Transactional
    public Stock addStock(String ingredientId, double quantity, String operator, String remark, String referenceId) {
        Stock stock = getStockByIngredientId(ingredientId);
        double previousQuantity = stock.getStockQuantity();
        double newQuantity = previousQuantity + quantity;
        
        stock.setStockQuantity(newQuantity);
        stock.setUpdatedAt(LocalDateTime.now());
        
        Stock saved = stockRepository.save(stock);
        
        createMovement(ingredientId, "in", quantity, previousQuantity, newQuantity, 
            referenceId, "order", operator, remark);
        
        historyService.recordHistory("stock", saved.getStockId(), "入库", 
            "食材入库: " + saved.getIngredientName() + ", 数量: +" + quantity, 
            operator, "stock_in", "success");
        
        return saved;
    }

    @Transactional
    public Stock reduceStock(String ingredientId, double quantity, String operator, String remark, String referenceId) {
        Stock stock = getStockByIngredientId(ingredientId);
        double previousQuantity = stock.getStockQuantity();
        
        if (previousQuantity < quantity) {
            throw new BusinessException("库存不足，食材: " + stock.getIngredientName());
        }
        
        double newQuantity = previousQuantity - quantity;
        stock.setStockQuantity(newQuantity);
        stock.setUpdatedAt(LocalDateTime.now());
        
        Stock saved = stockRepository.save(stock);
        
        createMovement(ingredientId, "out", quantity, previousQuantity, newQuantity, 
            referenceId, "order", operator, remark);
        
        checkAndCreateWarning(saved);
        
        historyService.recordHistory("stock", saved.getStockId(), "出库", 
            "食材出库: " + saved.getIngredientName() + ", 数量: -" + quantity, 
            operator, "stock_out", "success");
        
        return saved;
    }

    @Transactional
    public Map<String, Object> preDeductCriticalIngredients(Map<String, Double> ingredientQuantities,
            String operator, String referenceId) {
        Map<String, Object> result = new HashMap<>();
        Map<String, String> strategies = new HashMap<>();
        List<String> preDeducted = new ArrayList<>();
        List<String> insufficient = new ArrayList<>();

        for (Map.Entry<String, Double> entry : ingredientQuantities.entrySet()) {
            String ingredientId = entry.getKey();
            Double quantity = entry.getValue();

            Optional<Stock> stockOpt = stockRepository.findByIngredientId(ingredientId);
            if (stockOpt.isEmpty()) {
                insufficient.add(ingredientId + "(不存在)");
                strategies.put(ingredientId, "missing");
                continue;
            }

            Stock stock = stockOpt.get();
            String strategy = deductionConfig.getDeductionStrategy(ingredientId, stock.getCategory());
            strategies.put(ingredientId, strategy);

            if (deductionConfig.shouldPreDeduct(ingredientId, stock.getCategory())) {
                if (stock.getStockQuantity() < quantity) {
                    insufficient.add(stock.getIngredientName() + 
                        "(剩余: " + stock.getStockQuantity() + ", 需要: " + quantity + ")");
                } else {
                    reduceStock(ingredientId, quantity, operator, "预扣减-关键食材", referenceId);
                    preDeducted.add(ingredientId);
                }
            }
        }

        result.put("strategies", strategies);
        result.put("preDeducted", preDeducted);
        result.put("insufficient", insufficient);
        result.put("success", insufficient.isEmpty());

        if (!insufficient.isEmpty()) {
            throw new BusinessException("关键食材库存不足: " + String.join(", ", insufficient));
        }

        return result;
    }

    @Transactional
    public Map<String, Object> deductOnConfirmation(Map<String, Double> ingredientQuantities,
            String operator, String referenceId) {
        Map<String, Object> result = new HashMap<>();
        List<String> confirmedDeducted = new ArrayList<>();
        List<String> alreadyDeducted = new ArrayList<>();
        List<String> insufficient = new ArrayList<>();

        for (Map.Entry<String, Double> entry : ingredientQuantities.entrySet()) {
            String ingredientId = entry.getKey();
            Double quantity = entry.getValue();

            Optional<Stock> stockOpt = stockRepository.findByIngredientId(ingredientId);
            if (stockOpt.isEmpty()) {
                insufficient.add(ingredientId + "(不存在)");
                continue;
            }

            Stock stock = stockOpt.get();
            
            if (deductionConfig.shouldConfirmDeduct(ingredientId, stock.getCategory())) {
                if (stock.getStockQuantity() < quantity) {
                    insufficient.add(stock.getIngredientName() + 
                        "(剩余: " + stock.getStockQuantity() + ", 需要: " + quantity + ")");
                } else {
                    reduceStock(ingredientId, quantity, operator, "确认扣减-普通食材", referenceId);
                    confirmedDeducted.add(ingredientId);
                }
            } else {
                alreadyDeducted.add(ingredientId);
            }
        }

        result.put("confirmedDeducted", confirmedDeducted);
        result.put("alreadyDeducted", alreadyDeducted);
        result.put("insufficient", insufficient);
        result.put("success", insufficient.isEmpty());

        if (!insufficient.isEmpty()) {
            throw new BusinessException("食材库存不足: " + String.join(", ", insufficient));
        }

        return result;
    }

    @Transactional
    public Map<String, Boolean> checkAndReduceStocks(Map<String, Double> ingredientQuantities, 
            String operator, String referenceId) {
        Map<String, Boolean> result = new HashMap<>();
        Map<String, Double> insufficient = new HashMap<>();
        
        for (Map.Entry<String, Double> entry : ingredientQuantities.entrySet()) {
            String ingredientId = entry.getKey();
            Double quantity = entry.getValue();
            
            Optional<Stock> stockOpt = stockRepository.findByIngredientId(ingredientId);
            if (stockOpt.isEmpty()) {
                result.put(ingredientId, false);
                insufficient.put(ingredientId, 0.0);
                continue;
            }
            
            Stock stock = stockOpt.get();
            if (stock.getStockQuantity() < quantity) {
                result.put(ingredientId, false);
                insufficient.put(ingredientId, stock.getStockQuantity());
            } else {
                result.put(ingredientId, true);
            }
        }
        
        if (!insufficient.isEmpty()) {
            StringBuilder sb = new StringBuilder("以下食材库存不足: ");
            for (Map.Entry<String, Double> entry : insufficient.entrySet()) {
                Optional<Stock> stockOpt = stockRepository.findByIngredientId(entry.getKey());
                String name = stockOpt.map(Stock::getIngredientName).orElse(entry.getKey());
                sb.append(name).append("(剩余: ").append(entry.getValue()).append("), ");
            }
            throw new BusinessException(sb.substring(0, sb.length() - 2));
        }
        
        for (Map.Entry<String, Double> entry : ingredientQuantities.entrySet()) {
            reduceStock(entry.getKey(), entry.getValue(), operator, "订单消耗", referenceId);
        }
        
        return result;
    }

    private void createMovement(String ingredientId, String movementType, double quantity, 
            double previousQuantity, double newQuantity, String referenceId, 
            String referenceType, String operator, String remark) {
        StockMovement movement = new StockMovement();
        movement.setIngredientId(ingredientId);
        movement.setMovementType(movementType);
        movement.setQuantity(quantity);
        movement.setPreviousQuantity(previousQuantity);
        movement.setNewQuantity(newQuantity);
        movement.setReferenceId(referenceId);
        movement.setReferenceType(referenceType);
        movement.setOperator(operator);
        movement.setRemark(remark);
        movementRepository.save(movement);
    }

    private void checkAndCreateWarning(Stock stock) {
        if (stock.getStockQuantity() <= stock.getWarningThreshold()) {
            StockWarning warning = new StockWarning();
            warning.setWarningId(IdGenerator.generateWarningId());
            warning.setIngredientId(stock.getIngredientId());
            warning.setIngredientName(stock.getIngredientName());
            warning.setWarningType("low_stock");
            
            String warningLevel = deductionConfig.getWarningLevel(
                stock.getStockQuantity(), stock.getWarningThreshold());
            warning.setWarningLevel(warningLevel);
            
            warning.setCurrentQuantity(stock.getStockQuantity());
            warning.setWarningThreshold(stock.getWarningThreshold());
            warning.setTriggeredAt(LocalDateTime.now());
            
            warningRepository.save(warning);
            
            notificationService.sendStockWarning(warning);
            
            historyService.recordHistory("stock_warning", warning.getWarningId(), "库存预警", 
                "食材预警: " + stock.getIngredientName() + ", 当前库存: " + stock.getStockQuantity(), 
                "system", "warning", "triggered");
        }
    }

    public boolean checkWarningThreshold(String ingredientId) {
        Stock stock = getStockByIngredientId(ingredientId);
        return stock.getStockQuantity() <= stock.getWarningThreshold();
    }

    public List<StockWarning> getAllWarnings() {
        return warningRepository.findAll();
    }

    public List<StockWarning> getUnhandledWarnings() {
        return warningRepository.findByHandledFalse();
    }

    @Transactional
    public StockWarning handleWarning(String warningId, String handleNote, String operator) {
        StockWarning warning = warningRepository.findById(warningId)
                .orElseThrow(() -> new BusinessException("预警记录不存在"));
        
        warning.setHandled(true);
        warning.setHandledAt(LocalDateTime.now());
        warning.setHandleNote(handleNote);
        
        historyService.recordHistory("stock_warning", warningId, "处理预警", 
            "处理预警: " + warning.getIngredientName() + ", 备注: " + handleNote, 
            operator, "handle", "success");
        
        return warningRepository.save(warning);
    }

    public List<StockMovement> getMovementsByIngredient(String ingredientId) {
        return movementRepository.findByIngredientId(ingredientId);
    }

    public List<StockMovement> getMovementsByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        return movementRepository.findByCreatedAtBetween(startTime, endTime);
    }

    public Map<String, Object> getDeductionInfo(String ingredientId) {
        Map<String, Object> info = new HashMap<>();
        Optional<Stock> stockOpt = stockRepository.findByIngredientId(ingredientId);
        
        if (stockOpt.isPresent()) {
            Stock stock = stockOpt.get();
            info.put("ingredientId", ingredientId);
            info.put("ingredientName", stock.getIngredientName());
            info.put("category", stock.getCategory());
            info.put("strategy", deductionConfig.getDeductionStrategy(ingredientId, stock.getCategory()));
            info.put("isCritical", deductionConfig.shouldPreDeduct(ingredientId, stock.getCategory()));
        }
        
        return info;
    }
}
