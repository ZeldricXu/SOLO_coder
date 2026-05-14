package com.memberscore.service;

import com.memberscore.dto.EarnPointRequest;
import com.memberscore.dto.PointOperationResponse;
import com.memberscore.entity.Member;
import com.memberscore.entity.PointRecord;
import com.memberscore.entity.PointRule;
import com.memberscore.repository.MemberRepository;
import com.memberscore.repository.PointRecordRepository;
import com.memberscore.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("积分获取服务 - 单元测试")
class PointServiceEarnTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PointRecordRepository pointRecordRepository;

    @Mock
    private PointRuleService pointRuleService;

    @Mock
    private LevelService levelService;

    @Mock
    private PointStatService pointStatService;

    @InjectMocks
    private PointService pointService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(pointService, "expireDays", 365);
    }

    private EarnPointRequest buildEarnRequest(String memberId, String source, int amount) {
        EarnPointRequest request = new EarnPointRequest();
        request.setMemberId(memberId);
        request.setPointSource(source);
        request.setPointAmount(amount);
        return request;
    }

    @Nested
    @DisplayName("积分获取计算正确性测试")
    class EarnPointsCalculationTests {

        @Test
        @DisplayName("青铜会员 - 基础积分获取成功")
        void testEarnPoints_BronzeMemberBasic() {
            Member member = TestDataBuilder.buildBronzeMember();
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            EarnPointRequest request = buildEarnRequest("member_001", "purchase", 100);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("bronze")).thenReturn(1.0);
            when(pointRuleService.calculatePoints(purchaseRule, 100, 1.0)).thenReturn(100);
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.earnPoints(request);

            assertNotNull(response);
            assertNotNull(response.getPointId());
            assertEquals(100, response.getBalance());
            assertEquals(100, response.getEarnedPoints());
            verify(memberRepository, times(1)).save(member);
            verify(pointRecordRepository, times(1)).save(any(PointRecord.class));
            verify(pointStatService, times(1)).recordEarnStat(100);
            verify(levelService, times(1)).checkAndUpgradeLevel(member);
        }

        @Test
        @DisplayName("白银会员 - 1.2倍积分加成")
        void testEarnPoints_SilverMemberMultiplier() {
            Member member = TestDataBuilder.buildSilverMember();
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            EarnPointRequest request = buildEarnRequest("member_002", "purchase", 1000);

            when(memberRepository.findByMemberId("member_002")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("silver")).thenReturn(1.2);
            when(pointRuleService.calculatePoints(purchaseRule, 1000, 1.2)).thenReturn(1200);
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.earnPoints(request);

            assertEquals(2700, response.getBalance());
            assertEquals(1200, response.getEarnedPoints());
        }

        @Test
        @DisplayName("黄金会员 - 1.5倍积分加成")
        void testEarnPoints_GoldMemberMultiplier() {
            Member member = TestDataBuilder.buildGoldMember();
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            EarnPointRequest request = buildEarnRequest("member_003", "purchase", 100);

            when(memberRepository.findByMemberId("member_003")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("gold")).thenReturn(1.5);
            when(pointRuleService.calculatePoints(purchaseRule, 100, 1.5)).thenReturn(150);
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.earnPoints(request);

            assertEquals(5150, response.getBalance());
            assertEquals(150, response.getEarnedPoints());
        }

        @Test
        @DisplayName("铂金会员 - 2.0倍积分加成")
        void testEarnPoints_PlatinumMemberMultiplier() {
            Member member = TestDataBuilder.buildPlatinumMember();
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            EarnPointRequest request = buildEarnRequest("member_004", "purchase", 100);

            when(memberRepository.findByMemberId("member_004")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("platinum")).thenReturn(2.0);
            when(pointRuleService.calculatePoints(purchaseRule, 100, 2.0)).thenReturn(200);
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.earnPoints(request);

            assertEquals(15200, response.getBalance());
            assertEquals(200, response.getEarnedPoints());
        }

        @Test
        @DisplayName("促销活动积分 - 双倍积分规则")
        void testEarnPoints_PromotionDoublePoints() {
            Member member = TestDataBuilder.buildBronzeMember();
            PointRule promotionRule = TestDataBuilder.buildPromotionRule();
            EarnPointRequest request = buildEarnRequest("member_001", "promotion", 100);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("promotion")).thenReturn(Optional.of(promotionRule));
            when(levelService.getLevelMultiplier("bronze")).thenReturn(1.0);
            when(pointRuleService.calculatePoints(promotionRule, 100, 1.0)).thenReturn(1000);
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.earnPoints(request);

            assertEquals(1000, response.getEarnedPoints());
        }

        @Test
        @DisplayName("签到积分 - 固定积分获取")
        void testEarnPoints_SignInFixedPoints() {
            Member member = TestDataBuilder.buildBronzeMember();
            PointRule signRule = TestDataBuilder.buildSignRule();
            EarnPointRequest request = buildEarnRequest("member_001", "sign", 1);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("sign")).thenReturn(Optional.of(signRule));
            when(levelService.getLevelMultiplier("bronze")).thenReturn(1.0);
            when(pointRuleService.calculatePoints(signRule, 1, 1.0)).thenReturn(10);
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.earnPoints(request);

            assertEquals(10, response.getEarnedPoints());
            assertEquals(10, response.getBalance());
        }
    }

    @Nested
    @DisplayName("积分余额更新正确性测试")
    class BalanceUpdateTests {

        @Test
        @DisplayName("余额更新 - 累计积分验证")
        void testBalanceUpdate_CumulativePoints() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "bronze", 500, 500);
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            EarnPointRequest request = buildEarnRequest("member_test", "purchase", 100);

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("bronze")).thenReturn(1.0);
            when(pointRuleService.calculatePoints(purchaseRule, 100, 1.0)).thenReturn(100);
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.earnPoints(request);

            assertEquals(600, response.getBalance());
            assertEquals(100, response.getEarnedPoints());
            assertEquals(600, member.getAvailablePoints());
            assertEquals(600, member.getTotalPoints());
        }

        @Test
        @DisplayName("大额消费 - 余额大幅增长")
        void testBalanceUpdate_LargePurchase() {
            Member member = TestDataBuilder.buildMember("member_large", "user_large", "gold", 1000, 1000);
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            EarnPointRequest request = buildEarnRequest("member_large", "purchase", 10000);

            when(memberRepository.findByMemberId("member_large")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("gold")).thenReturn(1.5);
            when(pointRuleService.calculatePoints(purchaseRule, 10000, 1.5)).thenReturn(15000);
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.earnPoints(request);

            assertEquals(16000, response.getBalance());
            assertEquals(15000, response.getEarnedPoints());
        }

        @Test
        @DisplayName("usedPoints不应受积分获取影响")
        void testBalanceUpdate_UsedPointsUnchanged() {
            Member member = TestDataBuilder.buildMember("member_used", "user_used", "bronze", 1000, 800);
            member.setUsedPoints(200);
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            EarnPointRequest request = buildEarnRequest("member_used", "purchase", 200);

            when(memberRepository.findByMemberId("member_used")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("bronze")).thenReturn(1.0);
            when(pointRuleService.calculatePoints(purchaseRule, 200, 1.0)).thenReturn(200);
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            pointService.earnPoints(request);

            assertEquals(200, member.getUsedPoints());
            assertEquals(1000, member.getAvailablePoints());
            assertEquals(1200, member.getTotalPoints());
        }
    }

    @Nested
    @DisplayName("校验失败 - 拒绝累计机制测试")
    class ValidationFailureTests {

        @Test
        @DisplayName("会员不存在 - 抛出异常")
        void testEarnPoints_MemberNotFound() {
            EarnPointRequest request = buildEarnRequest("non_existent", "purchase", 100);

            when(memberRepository.findByMemberId("non_existent")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                pointService.earnPoints(request);
            });

            assertTrue(exception.getMessage().contains("会员不存在"));
            verify(memberRepository, never()).save(any(Member.class));
            verify(pointRecordRepository, never()).save(any(PointRecord.class));
            verify(pointStatService, never()).recordEarnStat(anyInt());
        }

        @Test
        @DisplayName("无效积分来源 - 规则匹配失败")
        void testEarnPoints_InvalidRuleSource() {
            Member member = TestDataBuilder.buildBronzeMember();
            EarnPointRequest request = buildEarnRequest("member_001", "invalid_source", 100);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("invalid_source")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                pointService.earnPoints(request);
            });

            assertTrue(exception.getMessage().contains("无效的积分来源"));
            verify(memberRepository, never()).save(any(Member.class));
            verify(pointRecordRepository, never()).save(any(PointRecord.class));
        }

        @Test
        @DisplayName("积分计算结果为0 - 拒绝累计")
        void testEarnPoints_ZeroPointsCalculated() {
            Member member = TestDataBuilder.buildBronzeMember();
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            EarnPointRequest request = buildEarnRequest("member_001", "purchase", 100);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("bronze")).thenReturn(1.0);
            when(pointRuleService.calculatePoints(purchaseRule, 100, 1.0)).thenReturn(0);

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                pointService.earnPoints(request);
            });

            assertTrue(exception.getMessage().contains("积分数为0"));
            verify(memberRepository, never()).save(any(Member.class));
            verify(pointRecordRepository, never()).save(any(PointRecord.class));
        }

        @Test
        @DisplayName("已禁用规则 - 拒绝累计")
        void testEarnPoints_DisabledRule() {
            Member member = TestDataBuilder.buildBronzeMember();
            EarnPointRequest request = buildEarnRequest("member_001", "old_rule", 100);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("old_rule")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                pointService.earnPoints(request);
            });

            assertTrue(exception.getMessage().contains("无效的积分来源"));
            assertEquals(0, member.getAvailablePoints());
        }

        @Test
        @DisplayName("已过期活动规则 - 拒绝累计")
        void testEarnPoints_ExpiredPromotionRule() {
            Member member = TestDataBuilder.buildBronzeMember();
            EarnPointRequest request = buildEarnRequest("member_001", "expired_promo", 100);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("expired_promo")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                pointService.earnPoints(request);
            });

            assertTrue(exception.getMessage().contains("无效的积分来源"));
        }
    }

    @Nested
    @DisplayName("积分来源类型差异校验测试")
    class SourceTypeValidationTests {

        @Test
        @DisplayName("购物积分 - 金额关联校验")
        void testSourceType_PurchaseAmountRelated() {
            Member member = TestDataBuilder.buildBronzeMember();
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            
            EarnPointRequest request1 = buildEarnRequest("member_001", "purchase", 50);
            EarnPointRequest request2 = buildEarnRequest("member_001", "purchase", 200);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("bronze")).thenReturn(1.0);
            when(pointRuleService.calculatePoints(purchaseRule, 50, 1.0)).thenReturn(50);
            when(pointRuleService.calculatePoints(purchaseRule, 200, 1.0)).thenReturn(200);
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response1 = pointService.earnPoints(request1);
            assertEquals(50, response1.getEarnedPoints());

            PointOperationResponse response2 = pointService.earnPoints(request2);
            assertEquals(200, response2.getEarnedPoints());
        }

        @Test
        @DisplayName("签到积分 - 固定积分不随金额变化")
        void testSourceType_SignInFixedAmount() {
            Member member = TestDataBuilder.buildBronzeMember();
            PointRule signRule = TestDataBuilder.buildSignRule();
            
            EarnPointRequest request1 = buildEarnRequest("member_001", "sign", 1);
            EarnPointRequest request2 = buildEarnRequest("member_001", "sign", 100);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("sign")).thenReturn(Optional.of(signRule));
            when(levelService.getLevelMultiplier("bronze")).thenReturn(1.0);
            when(pointRuleService.calculatePoints(signRule, 1, 1.0)).thenReturn(10);
            when(pointRuleService.calculatePoints(signRule, 100, 1.0)).thenReturn(10);
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response1 = pointService.earnPoints(request1);
            PointOperationResponse response2 = pointService.earnPoints(request2);

            assertEquals(response1.getEarnedPoints(), response2.getEarnedPoints());
            assertEquals(10, response1.getEarnedPoints());
        }
    }

    @Nested
    @DisplayName("积分记录创建测试")
    class PointRecordCreationTests {

        @Test
        @DisplayName("积分获取记录创建成功")
        void testEarnPoints_RecordCreated() {
            Member member = TestDataBuilder.buildBronzeMember();
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            EarnPointRequest request = buildEarnRequest("member_001", "purchase", 100);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("bronze")).thenReturn(1.0);
            when(pointRuleService.calculatePoints(purchaseRule, 100, 1.0)).thenReturn(100);
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> {
                PointRecord saved = invocation.getArgument(0);
                saved.setId(1L);
                return saved;
            });

            PointOperationResponse response = pointService.earnPoints(request);

            verify(pointRecordRepository, times(1)).save(argThat(record -> {
                assertNotNull(record.getPointId());
                assertEquals("member_001", record.getMemberId());
                assertEquals(com.memberscore.enums.PointType.EARN, record.getPointType());
                assertEquals(Integer.valueOf(100), record.getPointAmount());
                assertEquals("purchase", record.getPointSource());
                assertEquals(Integer.valueOf(100), record.getPointBalance());
                assertNotNull(record.getExpireAt());
                assertFalse(record.getIsExpired());
                return true;
            }));
        }

        @Test
        @DisplayName("积分ID格式校验")
        void testEarnPoints_PointIdFormat() {
            Member member = TestDataBuilder.buildBronzeMember();
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            EarnPointRequest request = buildEarnRequest("member_001", "purchase", 100);

            when(memberRepository.findByMemberId("member_001")).thenReturn(Optional.of(member));
            when(pointRuleService.getActiveRule("purchase")).thenReturn(Optional.of(purchaseRule));
            when(levelService.getLevelMultiplier("bronze")).thenReturn(1.0);
            when(pointRuleService.calculatePoints(purchaseRule, 100, 1.0)).thenReturn(100);
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.earnPoints(request);

            assertNotNull(response.getPointId());
            assertTrue(response.getPointId().startsWith("point_"));
            assertTrue(response.getPointId().length() > 6);
        }
    }
}
