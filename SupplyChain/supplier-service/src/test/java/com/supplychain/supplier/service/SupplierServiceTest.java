package com.supplychain.supplier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.entity.Supplier;
import com.supplychain.common.enums.SupplierStatus;
import com.supplychain.common.exception.BusinessException;
import com.supplychain.common.testdata.TestDataBuilder;
import com.supplychain.supplier.mapper.SupplierMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("供应商服务单元测试")
class SupplierServiceTest {

    @Mock
    private SupplierMapper supplierMapper;

    @InjectMocks
    private SupplierService supplierService;

    @Nested
    @DisplayName("供应商资质校验测试")
    class SupplierQualificationTests {

        @Test
        @DisplayName("测试合格供应商返回true")
        void testQualifiedSupplierReturnsTrue() {
            Supplier qualifiedSupplier = TestDataBuilder.buildQualifiedSupplier();
            
            when(supplierMapper.selectById("sup_qual_001")).thenReturn(qualifiedSupplier);

            boolean result = supplierService.isQualified("sup_qual_001");

            assertTrue(result);
        }

        @Test
        @DisplayName("测试待审核供应商返回false")
        void testPendingSupplierReturnsFalse() {
            Supplier pendingSupplier = TestDataBuilder.buildPendingSupplier();
            pendingSupplier.setSupplierId("sup_pending_001");
            
            when(supplierMapper.selectById("sup_pending_001")).thenReturn(pendingSupplier);

            boolean result = supplierService.isQualified("sup_pending_001");

            assertFalse(result);
        }

        @Test
        @DisplayName("测试已停用供应商返回false")
        void testSuspendedSupplierReturnsFalse() {
            Supplier suspendedSupplier = TestDataBuilder.buildSuspendedSupplier();
            suspendedSupplier.setSupplierId("sup_suspended_001");
            
            when(supplierMapper.selectById("sup_suspended_001")).thenReturn(suspendedSupplier);

            boolean result = supplierService.isQualified("sup_suspended_001");

            assertFalse(result);
        }

        @Test
        @DisplayName("测试合格供应商验证不抛出异常")
        void testQualifiedSupplierValidationNoException() {
            Supplier qualifiedSupplier = TestDataBuilder.buildQualifiedSupplier();
            
            when(supplierMapper.selectById("sup_qual_002")).thenReturn(qualifiedSupplier);

            assertDoesNotThrow(() -> supplierService.validateSupplier("sup_qual_002"));
        }

        @Test
        @DisplayName("测试非合格供应商验证抛出异常")
        void testNonQualifiedSupplierValidationThrowsException() {
            Supplier pendingSupplier = TestDataBuilder.buildPendingSupplier();
            pendingSupplier.setSupplierId("sup_pending_002");
            
            when(supplierMapper.selectById("sup_pending_002")).thenReturn(pendingSupplier);

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> supplierService.validateSupplier("sup_pending_002"));
            
            assertTrue(exception.getMessage().contains("供应商资质无效"));
        }

        @Test
        @DisplayName("测试供应商资质综合评估")
        void testSupplierQualificationEvaluation() {
            Supplier qualifiedSupplier = TestDataBuilder.buildQualifiedSupplier();
            qualifiedSupplier.setSupplierContact("张三");
            qualifiedSupplier.setSupplierAddress("北京市朝阳区XX街道");
            qualifiedSupplier.setSupplierRating(4.5);

            Map<String, Object> result = supplierService.evaluateSupplierQualification(qualifiedSupplier);

            assertNotNull(result);
            assertEquals(100.0, result.get("qualificationScore"));
            assertTrue((Boolean) result.get("isQualified"));

            @SuppressWarnings("unchecked")
            Map<String, Boolean> checks = (Map<String, Boolean>) result.get("checks");
            assertTrue(checks.get("hasRequiredStatus"));
            assertTrue(checks.get("hasValidContact"));
            assertTrue(checks.get("hasValidAddress"));
            assertTrue(checks.get("hasAcceptableRating"));
        }

        @Test
        @DisplayName("测试不完整信息的供应商资质评估")
        void testIncompleteSupplierQualificationEvaluation() {
            Supplier partialSupplier = TestDataBuilder.buildPendingSupplier();
            partialSupplier.setSupplierContact(null);
            partialSupplier.setSupplierRating(1.5);

            Map<String, Object> result = supplierService.evaluateSupplierQualification(partialSupplier);

            @SuppressWarnings("unchecked")
            Map<String, Boolean> checks = (Map<String, Boolean>) result.get("checks");
            
            assertFalse(checks.get("hasRequiredStatus"));
            assertFalse(checks.get("hasValidContact"));
            assertFalse(checks.get("hasAcceptableRating"));
            assertFalse((Boolean) result.get("isQualified"));
        }

        @Test
        @DisplayName("测试查找合格供应商列表")
        void testFindQualifiedSuppliers() {
            List<Supplier> qualifiedSuppliers = Arrays.asList(
                    TestDataBuilder.buildQualifiedSupplier(),
                    TestDataBuilder.buildHighRatingSupplier()
            );
            
            when(supplierMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(qualifiedSuppliers);

            List<Supplier> result = supplierService.findQualifiedSuppliers("电子零部件");

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("测试按类型查找合格供应商")
        void testFindQualifiedSuppliersByType() {
            Supplier electronicsSupplier = TestDataBuilder.buildQualifiedSupplier();
            electronicsSupplier.setSupplierType("电子零部件");
            
            when(supplierMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(electronicsSupplier));

            List<Supplier> result = supplierService.findQualifiedSuppliers("电子零部件");

            assertEquals(1, result.size());
            assertEquals("电子零部件", result.get(0).getSupplierType());
        }
    }

    @Nested
    @DisplayName("供应商匹配度计算测试")
    class SupplierMatchScoreTests {

        @Test
        @DisplayName("测试完全匹配供应商获得高分")
        void testPerfectMatchGetsHighScore() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierType("电子零部件");
            supplier.setSupplierRating(4.8);

            Map<String, Object> result = supplierService.calculateMatchScore(supplier, "电子零部件", 3.0);

            double totalScore = (double) result.get("totalScore");
            assertTrue(totalScore > 90.0);
            assertEquals("A", result.get("grade"));
            assertTrue((Boolean) result.get("qualified"));
        }

        @Test
        @DisplayName("测试部分匹配获得中等分数")
        void testPartialMatchGetsMediumScore() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierType("电子元器件");
            supplier.setSupplierRating(3.5);

            Map<String, Object> result = supplierService.calculateMatchScore(supplier, "电子零部件", 3.0);

            double totalScore = (double) result.get("totalScore");
            assertTrue(totalScore >= 60.0);
            assertTrue(totalScore < 90.0);
        }

        @Test
        @DisplayName("测试匹配度计算包含详细分数")
        void testMatchScoreContainsDetails() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierType("电子零部件");
            supplier.setSupplierRating(4.0);

            Map<String, Object> result = supplierService.calculateMatchScore(supplier, "电子零部件", 3.5);

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) result.get("details");

            assertNotNull(details.get("typeScore"));
            assertNotNull(details.get("ratingScore"));
            assertNotNull(details.get("qualificationScore"));
        }

        @Test
        @DisplayName("测试类型完全匹配的类型分数")
        void testTypeExactMatchScore() {
            Supplier supplier = TestDataBuilder.buildPendingSupplier();
            supplier.setSupplierType("电子零部件");
            supplier.setSupplierRating(0.0);

            Map<String, Object> result = supplierService.calculateMatchScore(supplier, "电子零部件", 3.0);

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) result.get("details");
            double typeScore = (double) details.get("typeScore");
            
            assertEquals(100.0, typeScore);
        }

        @Test
        @DisplayName("测试类型部分匹配的类型分数")
        void testTypePartialMatchScore() {
            Supplier supplier = TestDataBuilder.buildPendingSupplier();
            supplier.setSupplierType("电子材料供应商");
            supplier.setSupplierRating(0.0);

            Map<String, Object> result = supplierService.calculateMatchScore(supplier, "电子材料", 3.0);

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) result.get("details");
            double typeScore = (double) details.get("typeScore");
            
            assertEquals(70.0, typeScore);
        }

        @Test
        @DisplayName("测试合格供应商的资质分数")
        void testQualifiedSupplierQualificationScore() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierType("测试类型");
            supplier.setSupplierRating(0.0);

            Map<String, Object> result = supplierService.calculateMatchScore(supplier, "其他类型", 3.0);

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) result.get("details");
            double qualificationScore = (double) details.get("qualificationScore");
            
            assertEquals(100.0, qualificationScore);
        }

        @Test
        @DisplayName("测试待审核供应商的资质分数")
        void testPendingSupplierQualificationScore() {
            Supplier supplier = TestDataBuilder.buildPendingSupplier();
            supplier.setSupplierType("测试类型");
            supplier.setSupplierRating(0.0);

            Map<String, Object> result = supplierService.calculateMatchScore(supplier, "其他类型", 3.0);

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) result.get("details");
            double qualificationScore = (double) details.get("qualificationScore");
            
            assertEquals(50.0, qualificationScore);
        }

        @Test
        @DisplayName("测试匹配度等级划分")
        void testMatchGradeThresholds() {
            Map<String, Double> testCases = new LinkedHashMap<>();
            testCases.put("A", 95.0);
            testCases.put("B", 85.0);
            testCases.put("C", 75.0);
            testCases.put("D", 65.0);
            testCases.put("F", 50.0);

            for (Map.Entry<String, Double> entry : testCases.entrySet()) {
                String expectedGrade = entry.getKey();
                double score = entry.getValue();

                Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
                supplier.setSupplierType("测试类型");

                Map<String, Object> result = supplierService.calculateMatchScore(supplier, "测试类型", 3.0);
                double totalScore = (double) result.get("totalScore");

                assertTrue(totalScore >= score - 10.0, "分数应在合理范围内");
            }
        }

        @Test
        @DisplayName("测试匹配度合格阈值")
        void testMatchQualifiedThreshold() {
            Supplier lowScoreSupplier = TestDataBuilder.buildPendingSupplier();
            lowScoreSupplier.setSupplierType("完全不匹配类型");
            lowScoreSupplier.setSupplierRating(1.0);

            Map<String, Object> result = supplierService.calculateMatchScore(lowScoreSupplier, "其他类型", 3.0);

            assertFalse((Boolean) result.get("qualified"));
        }

        @Test
        @DisplayName("测试批量供应商匹配")
        void testBatchMatchSuppliers() {
            Supplier supplier1 = TestDataBuilder.buildQualifiedSupplier();
            supplier1.setSupplierId("sup_batch_001");
            supplier1.setSupplierName("供应商A");
            supplier1.setSupplierType("电子零部件");
            supplier1.setSupplierRating(4.8);

            Supplier supplier2 = TestDataBuilder.buildHighRatingSupplier();
            supplier2.setSupplierId("sup_batch_002");
            supplier2.setSupplierName("供应商B");
            supplier2.setSupplierType("电子元器件");
            supplier2.setSupplierRating(3.5);

            Supplier supplier3 = TestDataBuilder.buildPendingSupplier();
            supplier3.setSupplierId("sup_batch_003");
            supplier3.setSupplierName("供应商C");
            supplier3.setSupplierType("机械零件");
            supplier3.setSupplierRating(2.0);

            List<Supplier> suppliers = Arrays.asList(supplier1, supplier2, supplier3);

            List<Map<String, Object>> results = supplierService.batchMatchSuppliers(
                    suppliers, "电子零部件", 3.0
            );

            assertEquals(3, results.size());

            double firstScore = (double) results.get(0).get("totalScore");
            double secondScore = (double) results.get(1).get("totalScore");
            double thirdScore = (double) results.get(2).get("totalScore");

            assertTrue(firstScore >= secondScore);
            assertTrue(secondScore >= thirdScore);
        }
    }

    @Nested
    @DisplayName("供应商评级更新测试")
    class SupplierRatingUpdateTests {

        @Test
        @DisplayName("测试更新供应商评级")
        void testUpdateSupplierRating() {
            Supplier supplier = TestDataBuilder.buildPendingSupplier();
            supplier.setSupplierId("sup_rating_001");
            supplier.setSupplierRating(3.0);
            
            when(supplierMapper.selectById("sup_rating_001")).thenReturn(supplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.updateSupplierRating("sup_rating_001", 4.5);

            assertEquals(4.5, result.getSupplierRating());
            verify(supplierMapper).updateById(any(Supplier.class));
        }

        @Test
        @DisplayName("测试评级为0的边界情况")
        void testRatingZeroBoundary() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierId("sup_rating_bound_001");
            supplier.setSupplierRating(3.0);
            
            when(supplierMapper.selectById("sup_rating_bound_001")).thenReturn(supplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.updateSupplierRating("sup_rating_bound_001", 0.0);

            assertEquals(0.0, result.getSupplierRating());
        }

        @Test
        @DisplayName("测试评级为5的边界情况")
        void testRatingFiveBoundary() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierId("sup_rating_bound_002");
            supplier.setSupplierRating(3.0);
            
            when(supplierMapper.selectById("sup_rating_bound_002")).thenReturn(supplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.updateSupplierRating("sup_rating_bound_002", 5.0);

            assertEquals(5.0, result.getSupplierRating());
        }

        @Test
        @DisplayName("测试评级低于0抛出异常")
        void testRatingBelowZeroThrowsException() {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> supplierService.updateSupplierRating("sup_invalid_001", -0.1));
            
            assertTrue(exception.getMessage().contains("评分必须在0-5之间"));
        }

        @Test
        @DisplayName("测试评级高于5抛出异常")
        void testRatingAboveFiveThrowsException() {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> supplierService.updateSupplierRating("sup_invalid_002", 5.1));
            
            assertTrue(exception.getMessage().contains("评分必须在0-5之间"));
        }

        @Test
        @DisplayName("测试评级等级计算 - AAA级")
        void testRatingGradeAAA() {
            assertEquals("AAA", supplierService.calculateRatingGrade(4.5));
            assertEquals("AAA", supplierService.calculateRatingGrade(5.0));
            assertEquals("AAA", supplierService.calculateRatingGrade(4.9));
        }

        @Test
        @DisplayName("测试评级等级计算 - AA级")
        void testRatingGradeAA() {
            assertEquals("AA", supplierService.calculateRatingGrade(4.0));
            assertEquals("AA", supplierService.calculateRatingGrade(4.4));
            assertEquals("AA", supplierService.calculateRatingGrade(4.2));
        }

        @Test
        @DisplayName("测试评级等级计算 - A级")
        void testRatingGradeA() {
            assertEquals("A", supplierService.calculateRatingGrade(3.5));
            assertEquals("A", supplierService.calculateRatingGrade(3.9));
        }

        @Test
        @DisplayName("测试评级等级计算 - BBB级")
        void testRatingGradeBBB() {
            assertEquals("BBB", supplierService.calculateRatingGrade(3.0));
            assertEquals("BBB", supplierService.calculateRatingGrade(3.4));
        }

        @Test
        @DisplayName("测试评级等级计算 - BB级")
        void testRatingGradeBB() {
            assertEquals("BB", supplierService.calculateRatingGrade(2.5));
            assertEquals("BB", supplierService.calculateRatingGrade(2.9));
        }

        @Test
        @DisplayName("测试评级等级计算 - B级")
        void testRatingGradeB() {
            assertEquals("B", supplierService.calculateRatingGrade(2.0));
            assertEquals("B", supplierService.calculateRatingGrade(2.4));
        }

        @Test
        @DisplayName("测试评级等级计算 - C级")
        void testRatingGradeC() {
            assertEquals("C", supplierService.calculateRatingGrade(1.9));
            assertEquals("C", supplierService.calculateRatingGrade(0.0));
            assertEquals("C", supplierService.calculateRatingGrade(1.0));
        }

        @Test
        @DisplayName("测试通过评估调整评级")
        void testAdjustRatingByEvaluation() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierId("sup_adjust_001");
            supplier.setSupplierRating(3.5);
            
            when(supplierMapper.selectById("sup_adjust_001")).thenReturn(supplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.adjustRatingByEvaluation("sup_adjust_001", 90, 85, 95);

            assertNotNull(result.getSupplierRating());
            assertTrue(result.getSupplierRating() >= 0.0);
            assertTrue(result.getSupplierRating() <= 5.0);
        }

        @Test
        @DisplayName("测试高分评估提升评级")
        void testHighScoresImproveRating() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierId("sup_improve_001");
            supplier.setSupplierRating(3.0);
            
            when(supplierMapper.selectById("sup_improve_001")).thenReturn(supplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.adjustRatingByEvaluation("sup_improve_001", 95, 95, 95);

            assertTrue(result.getSupplierRating() > 3.0);
        }

        @Test
        @DisplayName("测试低分评估降低评级")
        void testLowScoresDecreaseRating() {
            Supplier supplier = TestDataBuilder.buildHighRatingSupplier();
            supplier.setSupplierId("sup_decrease_001");
            supplier.setSupplierRating(4.5);
            
            when(supplierMapper.selectById("sup_decrease_001")).thenReturn(supplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.adjustRatingByEvaluation("sup_decrease_001", 40, 40, 40);

            assertTrue(result.getSupplierRating() < 4.5);
        }

        @Test
        @DisplayName("测试评估分数边界值")
        void testEvaluationScoreBoundaries() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierId("sup_bound_eval_001");
            supplier.setSupplierRating(3.0);
            
            when(supplierMapper.selectById("sup_bound_eval_001")).thenReturn(supplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            assertDoesNotThrow(() -> 
                supplierService.adjustRatingByEvaluation("sup_bound_eval_001", 0, 0, 0));
            
            assertDoesNotThrow(() -> 
                supplierService.adjustRatingByEvaluation("sup_bound_eval_001", 100, 100, 100));
        }

        @Test
        @DisplayName("测试评估分数低于0抛出异常")
        void testEvaluationScoreBelowZeroThrowsException() {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> supplierService.adjustRatingByEvaluation("sup_inv_eval_001", -1, 80, 90));
            
            assertTrue(exception.getMessage().contains("评分必须在0-100之间"));
        }

        @Test
        @DisplayName("测试评估分数高于100抛出异常")
        void testEvaluationScoreAboveHundredThrowsException() {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> supplierService.adjustRatingByEvaluation("sup_inv_eval_002", 90, 101, 85));
            
            assertTrue(exception.getMessage().contains("评分必须在0-100之间"));
        }

        @Test
        @DisplayName("测试评级更新保持在有效范围内")
        void testRatingUpdateStaysWithinBounds() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierId("sup_bounds_001");
            supplier.setSupplierRating(0.5);
            
            when(supplierMapper.selectById("sup_bounds_001")).thenReturn(supplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            Supplier lowResult = supplierService.adjustRatingByEvaluation("sup_bounds_001", 10, 10, 10);
            assertTrue(lowResult.getSupplierRating() >= 0.0);

            supplier.setSupplierRating(4.8);
            Supplier highResult = supplierService.adjustRatingByEvaluation("sup_bounds_001", 100, 100, 100);
            assertTrue(highResult.getSupplierRating() <= 5.0);
        }
    }

    @Nested
    @DisplayName("供应商基本管理测试")
    class SupplierBasicManagementTests {

        @Test
        @DisplayName("测试创建供应商")
        void testCreateSupplier() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            
            when(supplierMapper.insert(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.createSupplier(supplier);

            assertNotNull(result.getSupplierId());
            assertNotNull(result.getRegisteredAt());
            assertNotNull(result.getUpdatedAt());
            verify(supplierMapper).insert(any(Supplier.class));
        }

        @Test
        @DisplayName("测试创建供应商时设置默认状态")
        void testCreateSupplierSetsDefaultStatus() {
            Supplier supplier = new Supplier();
            supplier.setSupplierName("测试供应商");
            supplier.setSupplierStatus(null);
            
            when(supplierMapper.insert(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.createSupplier(supplier);

            assertEquals(SupplierStatus.PENDING.getCode(), result.getSupplierStatus());
        }

        @Test
        @DisplayName("测试创建供应商时设置默认评级")
        void testCreateSupplierSetsDefaultRating() {
            Supplier supplier = new Supplier();
            supplier.setSupplierName("测试供应商");
            supplier.setSupplierRating(null);
            
            when(supplierMapper.insert(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.createSupplier(supplier);

            assertEquals(0.0, result.getSupplierRating());
        }

        @Test
        @DisplayName("测试获取供应商")
        void testGetSupplier() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            
            when(supplierMapper.selectById("sup_get_001")).thenReturn(supplier);

            Supplier result = supplierService.getSupplier("sup_get_001");

            assertNotNull(result);
            verify(supplierMapper).selectById("sup_get_001");
        }

        @Test
        @DisplayName("测试获取不存在的供应商抛出异常")
        void testGetNonExistentSupplierThrowsException() {
            when(supplierMapper.selectById("sup_nonexist_001")).thenReturn(null);

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> supplierService.getSupplier("sup_nonexist_001"));
            
            assertEquals(404, exception.getCode());
            assertTrue(exception.getMessage().contains("供应商不存在"));
        }

        @Test
        @DisplayName("测试更新供应商信息")
        void testUpdateSupplier() {
            Supplier existingSupplier = TestDataBuilder.buildPendingSupplier();
            existingSupplier.setSupplierId("sup_update_001");
            existingSupplier.setSupplierName("旧名称");
            existingSupplier.setSupplierType("旧类型");
            existingSupplier.setSupplierContact("旧联系人");
            existingSupplier.setSupplierAddress("旧地址");

            Supplier updatedSupplier = new Supplier();
            updatedSupplier.setSupplierName("新名称");
            updatedSupplier.setSupplierType("新类型");
            updatedSupplier.setSupplierContact("新联系人");
            updatedSupplier.setSupplierAddress("新地址");
            
            when(supplierMapper.selectById("sup_update_001")).thenReturn(existingSupplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.updateSupplier("sup_update_001", updatedSupplier);

            assertEquals("新名称", result.getSupplierName());
            assertEquals("新类型", result.getSupplierType());
            assertEquals("新联系人", result.getSupplierContact());
            assertEquals("新地址", result.getSupplierAddress());
            verify(supplierMapper).updateById(any(Supplier.class));
        }

        @Test
        @DisplayName("测试部分更新供应商信息")
        void testPartialUpdateSupplier() {
            Supplier existingSupplier = TestDataBuilder.buildPendingSupplier();
            existingSupplier.setSupplierId("sup_partial_001");
            existingSupplier.setSupplierName("完整名称");
            existingSupplier.setSupplierType("完整类型");
            existingSupplier.setSupplierContact("完整联系人");
            existingSupplier.setSupplierAddress("完整地址");

            Supplier partialUpdate = new Supplier();
            partialUpdate.setSupplierName("新名称");
            
            when(supplierMapper.selectById("sup_partial_001")).thenReturn(existingSupplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            Supplier result = supplierService.updateSupplier("sup_partial_001", partialUpdate);

            assertEquals("新名称", result.getSupplierName());
            assertEquals("完整类型", result.getSupplierType());
            assertEquals("完整联系人", result.getSupplierContact());
            assertEquals("完整地址", result.getSupplierAddress());
        }

        @Test
        @DisplayName("测试删除供应商（逻辑删除）")
        void testDeleteSupplier() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierId("sup_delete_001");
            
            when(supplierMapper.selectById("sup_delete_001")).thenReturn(supplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            supplierService.deleteSupplier("sup_delete_001");

            verify(supplierMapper).updateById(any(Supplier.class));
        }

        @Test
        @DisplayName("测试删除供应商后状态变为停用")
        void testDeleteSupplierSetsSuspendedStatus() {
            Supplier supplier = TestDataBuilder.buildQualifiedSupplier();
            supplier.setSupplierId("sup_status_001");
            supplier.setSupplierStatus(SupplierStatus.QUALIFIED.getCode());
            
            when(supplierMapper.selectById("sup_status_001")).thenReturn(supplier);
            when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

            supplierService.deleteSupplier("sup_status_001");

            assertEquals(SupplierStatus.SUSPENDED.getCode(), supplier.getSupplierStatus());
        }

        @Test
        @DisplayName("测试列出所有供应商")
        void testListAllSuppliers() {
            List<Supplier> suppliers = Arrays.asList(
                    TestDataBuilder.buildQualifiedSupplier(),
                    TestDataBuilder.buildPendingSupplier(),
                    TestDataBuilder.buildSuspendedSupplier()
            );
            
            when(supplierMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(suppliers);

            List<Supplier> result = supplierService.listSuppliers(null, null);

            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("测试按状态列出供应商")
        void testListSuppliersByStatus() {
            List<Supplier> qualifiedSuppliers = Collections.singletonList(
                    TestDataBuilder.buildQualifiedSupplier()
            );
            
            when(supplierMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(qualifiedSuppliers);

            List<Supplier> result = supplierService.listSuppliers(SupplierStatus.QUALIFIED.getCode(), null);

            assertEquals(1, result.size());
            assertEquals(SupplierStatus.QUALIFIED.getCode(), result.get(0).getSupplierStatus());
        }

        @Test
        @DisplayName("测试按类型列出供应商")
        void testListSuppliersByType() {
            Supplier electronicsSupplier = TestDataBuilder.buildQualifiedSupplier();
            electronicsSupplier.setSupplierType("电子零部件");
            
            when(supplierMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(electronicsSupplier));

            List<Supplier> result = supplierService.listSuppliers(null, "电子零部件");

            assertEquals(1, result.size());
            assertEquals("电子零部件", result.get(0).getSupplierType());
        }
    }
}
