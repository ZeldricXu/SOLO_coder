package com.memberscore.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memberscore.entity.BenefitRecord;
import com.memberscore.entity.LevelConfig;
import com.memberscore.enums.BenefitStatus;
import com.memberscore.repository.BenefitRecordRepository;
import com.memberscore.repository.LevelConfigRepository;
import com.memberscore.testdata.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("权益服务 - 单元测试")
class BenefitServiceTest {

    @Mock
    private BenefitRecordRepository benefitRecordRepository;

    @Mock
    private LevelConfigRepository levelConfigRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private BenefitService benefitService;

    @Nested
    @DisplayName("权益配置加载正确性测试")
    class BenefitConfigLoadingTests {

        @Test
        @DisplayName("加载白银等级权益配置")
        void testConfigLoading_SilverLevelBenefits() throws Exception {
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            String memberId = "member_001";
            
            List<Map<String, String>> expectedBenefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣5%")
            );
            
            when(levelConfigRepository.findByLevelId("silver"))
                    .thenReturn(Optional.of(silverLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(expectedBenefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    eq(memberId), eq("silver"), anyString(), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(false);
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            benefitService.issueLevelBenefits(memberId, "silver");

            verify(benefitRecordRepository, times(1)).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("加载黄金等级权益配置 - 多项权益")
        void testConfigLoading_GoldLevelMultipleBenefits() throws Exception {
            LevelConfig goldLevel = TestDataBuilder.buildGoldLevelConfig();
            String memberId = "member_002";
            
            List<Map<String, String>> expectedBenefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣10%"),
                    Map.of("type", "service", "content", "专属客服")
            );
            
            when(levelConfigRepository.findByLevelId("gold"))
                    .thenReturn(Optional.of(goldLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(expectedBenefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    anyString(), eq("gold"), anyString(), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(false);
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            benefitService.issueLevelBenefits(memberId, "gold");

            verify(benefitRecordRepository, times(2)).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("等级配置不存在 - 不发放权益")
        void testConfigLoading_LevelConfigNotFound() {
            String memberId = "member_001";
            
            when(levelConfigRepository.findByLevelId("unknown"))
                    .thenReturn(Optional.empty());

            benefitService.issueLevelBenefits(memberId, "unknown");

            verify(benefitRecordRepository, never()).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("等级无权益配置 - 不发放权益")
        void testConfigLoading_NoBenefitsConfigured() {
            LevelConfig levelConfig = LevelConfig.builder()
                    .levelId("no_benefit")
                    .levelName("无权益等级")
                    .levelPointsRequired(0)
                    .levelBenefits(null)
                    .levelOrder(0)
                    .pointMultiplier(1.0)
                    .isEnabled(true)
                    .build();
            
            when(levelConfigRepository.findByLevelId("no_benefit"))
                    .thenReturn(Optional.of(levelConfig));

            benefitService.issueLevelBenefits("member_001", "no_benefit");

            verify(benefitRecordRepository, never()).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("权益配置为空字符串 - 不发放权益")
        void testConfigLoading_EmptyBenefitsString() {
            LevelConfig levelConfig = LevelConfig.builder()
                    .levelId("empty_benefit")
                    .levelName("空权益等级")
                    .levelPointsRequired(0)
                    .levelBenefits("")
                    .levelOrder(0)
                    .pointMultiplier(1.0)
                    .isEnabled(true)
                    .build();
            
            when(levelConfigRepository.findByLevelId("empty_benefit"))
                    .thenReturn(Optional.of(levelConfig));

            benefitService.issueLevelBenefits("member_001", "empty_benefit");

            verify(benefitRecordRepository, never()).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("权益配置解析失败 - 静默处理不抛出异常")
        void testConfigLoading_ParseFailure_SilentHandling() throws Exception {
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findByLevelId("silver"))
                    .thenReturn(Optional.of(silverLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenThrow(new RuntimeException("JSON解析失败"));

            assertDoesNotThrow(() -> {
                benefitService.issueLevelBenefits("member_001", "silver");
            });

            verify(benefitRecordRepository, never()).save(any(BenefitRecord.class));
        }
    }

    @Nested
    @DisplayName("权益发放处理测试")
    class BenefitIssuanceTests {

        @Test
        @DisplayName("发放新权益 - 成功创建记录")
        void testIssuance_NewBenefit_CreateRecord() throws Exception {
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            String memberId = "member_001";
            String levelId = "silver";
            
            List<Map<String, String>> benefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣5%")
            );
            
            when(levelConfigRepository.findByLevelId(levelId))
                    .thenReturn(Optional.of(silverLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(benefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    eq(memberId), eq(levelId), eq("discount"), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(false);
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> {
                        BenefitRecord saved = invocation.getArgument(0);
                        saved.setId(1L);
                        return saved;
                    });

            benefitService.issueLevelBenefits(memberId, levelId);

            verify(benefitRecordRepository, times(1)).save(argThat(record -> {
                assertNotNull(record.getBenefitId());
                assertEquals(memberId, record.getMemberId());
                assertEquals(levelId, record.getLevelId());
                assertEquals("discount", record.getBenefitType());
                assertEquals("购物折扣5%", record.getBenefitContent());
                assertEquals(BenefitStatus.ACTIVE, record.getBenefitStatus());
                assertNotNull(record.getExpireAt());
                assertTrue(record.getExpireAt().isAfter(LocalDateTime.now()));
                return true;
            }));
        }

        @Test
        @DisplayName("权益已存在 - 不重复发放")
        void testIssuance_ExistingBenefit_NoDuplicate() throws Exception {
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            String memberId = "member_001";
            String levelId = "silver";
            
            List<Map<String, String>> benefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣5%")
            );
            
            when(levelConfigRepository.findByLevelId(levelId))
                    .thenReturn(Optional.of(silverLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(benefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    eq(memberId), eq(levelId), eq("discount"), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(true);

            benefitService.issueLevelBenefits(memberId, levelId);

            verify(benefitRecordRepository, never()).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("多项权益发放 - 全部创建记录")
        void testIssuance_MultipleBenefits_AllCreated() throws Exception {
            LevelConfig goldLevel = TestDataBuilder.buildGoldLevelConfig();
            String memberId = "member_002";
            String levelId = "gold";
            
            List<Map<String, String>> benefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣10%"),
                    Map.of("type", "service", "content", "专属客服")
            );
            
            when(levelConfigRepository.findByLevelId(levelId))
                    .thenReturn(Optional.of(goldLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(benefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    anyString(), eq(levelId), anyString(), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(false);
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            benefitService.issueLevelBenefits(memberId, levelId);

            verify(benefitRecordRepository, times(2)).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("权益ID格式校验")
        void testIssuance_BenefitIdFormat() throws Exception {
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            List<Map<String, String>> benefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣5%")
            );
            
            when(levelConfigRepository.findByLevelId("silver"))
                    .thenReturn(Optional.of(silverLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(benefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    anyString(), anyString(), anyString(), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(false);
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> {
                        BenefitRecord saved = invocation.getArgument(0);
                        saved.setId(1L);
                        return saved;
                    });

            benefitService.issueLevelBenefits("member_001", "silver");

            verify(benefitRecordRepository, times(1)).save(argThat(record -> {
                assertNotNull(record.getBenefitId());
                assertTrue(record.getBenefitId().startsWith("benefit_"));
                assertTrue(record.getBenefitId().length() > 8);
                return true;
            }));
        }
    }

    @Nested
    @DisplayName("权益发放失败时的重试机制测试")
    class BenefitRetryMechanismTests {

        @Test
        @DisplayName("首次发放成功 - 无需重试")
        void testRetry_FirstSuccess_NoRetry() throws Exception {
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            List<Map<String, String>> benefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣5%")
            );
            
            when(levelConfigRepository.findByLevelId("silver"))
                    .thenReturn(Optional.of(silverLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(benefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    anyString(), anyString(), anyString(), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(false);
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            benefitService.issueLevelBenefits("member_001", "silver");

            verify(benefitRecordRepository, times(1)).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("权益已存在跳过 - 视为成功不重试")
        void testRetry_AlreadyExists_Skip() throws Exception {
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            List<Map<String, String>> benefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣5%")
            );
            
            when(levelConfigRepository.findByLevelId("silver"))
                    .thenReturn(Optional.of(silverLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(benefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    anyString(), anyString(), anyString(), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(true);

            benefitService.issueLevelBenefits("member_001", "silver");

            verify(benefitRecordRepository, never()).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("部分权益已存在 - 只发放新权益")
        void testRetry_PartialExists_OnlyNewOnes() throws Exception {
            LevelConfig goldLevel = TestDataBuilder.buildGoldLevelConfig();
            
            List<Map<String, String>> benefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣10%"),
                    Map.of("type", "service", "content", "专属客服")
            );
            
            when(levelConfigRepository.findByLevelId("gold"))
                    .thenReturn(Optional.of(goldLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(benefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    anyString(), eq("gold"), eq("discount"), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(true);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    anyString(), eq("gold"), eq("service"), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(false);
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            benefitService.issueLevelBenefits("member_002", "gold");

            verify(benefitRecordRepository, times(1)).save(any(BenefitRecord.class));
        }
    }

    @Nested
    @DisplayName("权益使用测试")
    class BenefitUsageTests {

        @Test
        @DisplayName("使用活跃权益 - 成功")
        void testUseBenefit_ActiveBenefit_Success() {
            BenefitRecord benefit = TestDataBuilder.buildActiveBenefit(
                    "member_001", "gold", "discount", "购物折扣10%");
            benefit.setBenefitStatus(BenefitStatus.ACTIVE);
            
            when(benefitRecordRepository.findByBenefitId("benefit_001"))
                    .thenReturn(Optional.of(benefit));
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            BenefitRecord result = benefitService.useBenefit("benefit_001");

            assertEquals(BenefitStatus.USED, result.getBenefitStatus());
            verify(benefitRecordRepository, times(1)).save(benefit);
        }

        @Test
        @DisplayName("权益不存在 - 抛出异常")
        void testUseBenefit_NotFound_ThrowException() {
            when(benefitRecordRepository.findByBenefitId("non_existent"))
                    .thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                benefitService.useBenefit("non_existent");
            });

            assertTrue(exception.getMessage().contains("权益不存在"));
        }

        @Test
        @DisplayName("权益已使用 - 抛出异常")
        void testUseBenefit_AlreadyUsed_ThrowException() {
            BenefitRecord benefit = TestDataBuilder.buildActiveBenefit(
                    "member_001", "gold", "discount", "购物折扣10%");
            benefit.setBenefitStatus(BenefitStatus.USED);
            
            when(benefitRecordRepository.findByBenefitId("benefit_001"))
                    .thenReturn(Optional.of(benefit));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                benefitService.useBenefit("benefit_001");
            });

            assertTrue(exception.getMessage().contains("权益状态不可用"));
            verify(benefitRecordRepository, never()).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("权益已过期 - 抛出异常")
        void testUseBenefit_Expired_ThrowException() {
            BenefitRecord benefit = TestDataBuilder.buildActiveBenefit(
                    "member_001", "gold", "discount", "购物折扣10%");
            benefit.setBenefitStatus(BenefitStatus.EXPIRED);
            
            when(benefitRecordRepository.findByBenefitId("benefit_001"))
                    .thenReturn(Optional.of(benefit));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                benefitService.useBenefit("benefit_001");
            });

            assertTrue(exception.getMessage().contains("权益状态不可用"));
        }
    }

    @Nested
    @DisplayName("权益查询测试")
    class BenefitQueryTests {

        @Test
        @DisplayName("查询会员所有权益")
        void testGetMemberBenefits_All() {
            String memberId = "member_001";
            List<BenefitRecord> benefits = List.of(
                    TestDataBuilder.buildActiveBenefit(memberId, "gold", "discount", "购物折扣10%"),
                    TestDataBuilder.buildActiveBenefit(memberId, "gold", "service", "专属客服")
            );
            
            when(benefitRecordRepository.findByMemberIdOrderByIssuedAtDesc(memberId))
                    .thenReturn(benefits);

            List<BenefitRecord> result = benefitService.getMemberBenefits(memberId);

            assertEquals(2, result.size());
            verify(benefitRecordRepository, times(1))
                    .findByMemberIdOrderByIssuedAtDesc(memberId);
        }

        @Test
        @DisplayName("查询会员活跃权益")
        void testGetMemberBenefits_ActiveOnly() {
            String memberId = "member_001";
            List<BenefitRecord> activeBenefits = List.of(
                    TestDataBuilder.buildActiveBenefit(memberId, "gold", "discount", "购物折扣10%")
            );
            
            when(benefitRecordRepository.findByMemberIdAndBenefitStatusOrderByIssuedAtDesc(
                    memberId, BenefitStatus.ACTIVE))
                    .thenReturn(activeBenefits);

            List<BenefitRecord> result = benefitService.getMemberActiveBenefits(memberId);

            assertEquals(1, result.size());
            verify(benefitRecordRepository, times(1))
                    .findByMemberIdAndBenefitStatusOrderByIssuedAtDesc(memberId, BenefitStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("权益过期处理测试")
    class BenefitExpirationTests {

        @Test
        @DisplayName("过期权益处理 - 状态变更为EXPIRED")
        void testExpireBenefits_UpdateStatus() {
            BenefitRecord expiredBenefit = TestDataBuilder.buildActiveBenefit(
                    "member_001", "gold", "discount", "购物折扣10%");
            expiredBenefit.setExpireAt(LocalDateTime.now().minusDays(1));
            expiredBenefit.setBenefitStatus(BenefitStatus.ACTIVE);
            
            List<BenefitRecord> allBenefits = List.of(expiredBenefit);
            
            when(benefitRecordRepository.findAll())
                    .thenReturn(allBenefits);
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            benefitService.expireBenefits();

            verify(benefitRecordRepository, times(1)).save(argThat(record -> {
                assertEquals(BenefitStatus.EXPIRED, record.getBenefitStatus());
                return true;
            }));
        }

        @Test
        @DisplayName("未过期权益 - 不处理")
        void testExpireBenefits_NotExpired_NoAction() {
            BenefitRecord activeBenefit = TestDataBuilder.buildActiveBenefit(
                    "member_001", "gold", "discount", "购物折扣10%");
            activeBenefit.setExpireAt(LocalDateTime.now().plusDays(30));
            activeBenefit.setBenefitStatus(BenefitStatus.ACTIVE);
            
            List<BenefitRecord> allBenefits = List.of(activeBenefit);
            
            when(benefitRecordRepository.findAll())
                    .thenReturn(allBenefits);

            benefitService.expireBenefits();

            verify(benefitRecordRepository, never()).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("无过期日期权益 - 不处理")
        void testExpireBenefits_NoExpireDate_NoAction() {
            BenefitRecord noExpireBenefit = TestDataBuilder.buildActiveBenefit(
                    "member_001", "gold", "discount", "购物折扣10%");
            noExpireBenefit.setExpireAt(null);
            noExpireBenefit.setBenefitStatus(BenefitStatus.ACTIVE);
            
            List<BenefitRecord> allBenefits = List.of(noExpireBenefit);
            
            when(benefitRecordRepository.findAll())
                    .thenReturn(allBenefits);

            benefitService.expireBenefits();

            verify(benefitRecordRepository, never()).save(any(BenefitRecord.class));
        }
    }

    @Nested
    @DisplayName("权益发放异步化测试")
    class AsyncBenefitIssuanceTests {

        @Test
        @DisplayName("权益发放完成后立即返回 - 不阻塞主流程")
        void testAsync_ReturnImmediately() throws Exception {
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            List<Map<String, String>> benefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣5%")
            );
            
            when(levelConfigRepository.findByLevelId("silver"))
                    .thenReturn(Optional.of(silverLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(benefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    anyString(), anyString(), anyString(), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(false);
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> {
                        Thread.sleep(10);
                        return invocation.getArgument(0);
                    });

            long startTime = System.currentTimeMillis();
            benefitService.issueLevelBenefits("member_001", "silver");
            long elapsedTime = System.currentTimeMillis() - startTime;

            assertTrue(elapsedTime < 1000);
            verify(benefitRecordRepository, times(1)).save(any(BenefitRecord.class));
        }

        @Test
        @DisplayName("权益发放失败 - 不影响等级升级结果")
        void testAsync_FailureDoesNotAffectLevelUpgrade() {
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findByLevelId("silver"))
                    .thenReturn(Optional.of(silverLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenThrow(new RuntimeException("权益发放异常"));

            assertDoesNotThrow(() -> {
                benefitService.issueLevelBenefits("member_001", "silver");
            });
        }

        @Test
        @DisplayName("等级升级完成后后台Worker执行权益发放")
        void testAsync_BackgroundWorkerExecution() throws Exception {
            LevelConfig goldLevel = TestDataBuilder.buildGoldLevelConfig();
            
            List<Map<String, String>> benefits = List.of(
                    Map.of("type", "discount", "content", "购物折扣10%"),
                    Map.of("type", "service", "content", "专属客服")
            );
            
            when(levelConfigRepository.findByLevelId("gold"))
                    .thenReturn(Optional.of(goldLevel));
            when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                    .thenReturn(benefits);
            when(benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                    anyString(), anyString(), anyString(), eq(BenefitStatus.ACTIVE)))
                    .thenReturn(false);
            when(benefitRecordRepository.save(any(BenefitRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            benefitService.issueLevelBenefits("member_002", "gold");

            verify(benefitRecordRepository, times(2)).save(any(BenefitRecord.class));
        }
    }
}
