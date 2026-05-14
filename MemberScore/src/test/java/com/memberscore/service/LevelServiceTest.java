package com.memberscore.service;

import com.memberscore.dto.LevelQueryResponse;
import com.memberscore.entity.LevelConfig;
import com.memberscore.entity.Member;
import com.memberscore.repository.LevelConfigRepository;
import com.memberscore.repository.MemberRepository;
import com.memberscore.testdata.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("等级服务 - 单元测试")
class LevelServiceTest {

    @Mock
    private LevelConfigRepository levelConfigRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private BenefitService benefitService;

    @InjectMocks
    private LevelService levelService;

    @Nested
    @DisplayName("积分达标判断正确性测试")
    class PointsThresholdTests {

        @Test
        @DisplayName("青铜会员 - 达到白银升级门槛")
        void testPointsThreshold_BronzeToSilver() {
            Member member = TestDataBuilder.MemberScenario.exactlyAtSilverThreshold();
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(1000))
                    .thenReturn(List.of(silverLevel));
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            doNothing().when(benefitService).issueLevelBenefits(anyString(), anyString());

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertTrue(upgraded);
            assertEquals("silver", member.getMemberLevel());
            verify(memberRepository, times(1)).save(member);
            verify(benefitService, times(1)).issueLevelBenefits(member.getMemberId(), "silver");
        }

        @Test
        @DisplayName("白银会员 - 达到黄金升级门槛")
        void testPointsThreshold_SilverToGold() {
            Member member = TestDataBuilder.MemberScenario.exactlyAtGoldThreshold();
            LevelConfig goldLevel = TestDataBuilder.buildGoldLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(3000))
                    .thenReturn(List.of(goldLevel));
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            doNothing().when(benefitService).issueLevelBenefits(anyString(), anyString());

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertTrue(upgraded);
            assertEquals("gold", member.getMemberLevel());
        }

        @Test
        @DisplayName("积分低于门槛 - 不升级")
        void testPointsThreshold_BelowThreshold_NoUpgrade() {
            Member member = TestDataBuilder.MemberScenario.aboutToUpgradeToSilver();
            LevelConfig bronzeLevel = TestDataBuilder.buildBronzeLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(990))
                    .thenReturn(List.of(bronzeLevel));

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertFalse(upgraded);
            assertEquals("bronze", member.getMemberLevel());
            verify(memberRepository, never()).save(any(Member.class));
            verify(benefitService, never()).issueLevelBenefits(anyString(), anyString());
        }

        @Test
        @DisplayName("积分刚超过门槛 - 触发升级")
        void testPointsThreshold_JustAboveThreshold_Upgrade() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "bronze", 1001, 1001);
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(1001))
                    .thenReturn(List.of(silverLevel));
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            doNothing().when(benefitService).issueLevelBenefits(anyString(), anyString());

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertTrue(upgraded);
            assertEquals("silver", member.getMemberLevel());
        }

        @Test
        @DisplayName("0积分 - 保持青铜等级")
        void testPointsThreshold_ZeroPoints_BronzeLevel() {
            Member member = TestDataBuilder.buildBronzeMember();
            LevelConfig bronzeLevel = TestDataBuilder.buildBronzeLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(0))
                    .thenReturn(List.of(bronzeLevel));

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertFalse(upgraded);
            assertEquals("bronze", member.getMemberLevel());
        }
    }

    @Nested
    @DisplayName("等级升级前后积分校验测试")
    class LevelUpgradeValidationTests {

        @Test
        @DisplayName("升级前等级校验 - 确认当前等级")
        void testUpgradeValidation_BeforeUpgrade() {
            Member member = TestDataBuilder.MemberScenario.aboutToUpgradeToSilver();
            assertEquals("bronze", member.getMemberLevel());
            assertEquals(990, member.getTotalPoints());
        }

        @Test
        @DisplayName("升级后等级校验 - 确认等级变更")
        void testUpgradeValidation_AfterUpgrade() {
            Member member = TestDataBuilder.MemberScenario.exactlyAtSilverThreshold();
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(1000))
                    .thenReturn(List.of(silverLevel));
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            doNothing().when(benefitService).issueLevelBenefits(anyString(), anyString());

            levelService.checkAndUpgradeLevel(member);

            assertEquals("silver", member.getMemberLevel());
            assertNotNull(member.getLevelUpdatedAt());
        }

        @Test
        @DisplayName("升级前后积分一致性校验")
        void testUpgradeValidation_PointsConsistency() {
            Member member = TestDataBuilder.MemberScenario.exactlyAtSilverThreshold();
            int originalPoints = member.getTotalPoints();
            int originalAvailable = member.getAvailablePoints();
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(1000))
                    .thenReturn(List.of(silverLevel));
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            doNothing().when(benefitService).issueLevelBenefits(anyString(), anyString());

            levelService.checkAndUpgradeLevel(member);

            assertEquals(originalPoints, member.getTotalPoints());
            assertEquals(originalAvailable, member.getAvailablePoints());
        }

        @Test
        @DisplayName("等级未变化时 - 不执行任何操作")
        void testUpgradeValidation_NoLevelChange_NoAction() {
            Member member = TestDataBuilder.buildSilverMember();
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(1500))
                    .thenReturn(List.of(silverLevel));

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertFalse(upgraded);
            verify(memberRepository, never()).save(any(Member.class));
            verify(benefitService, never()).issueLevelBenefits(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("升级误判时的权益发放拒绝测试")
    class MisjudgementProtectionTests {

        @Test
        @DisplayName("等级配置缺失 - 跳过升级和权益发放")
        void testMisjudgement_NoLevelConfig_SkipUpgrade() {
            Member member = TestDataBuilder.MemberScenario.exactlyAtSilverThreshold();
            
            when(levelConfigRepository.findHighestLevelForPoints(1000))
                    .thenReturn(Collections.emptyList());

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertFalse(upgraded);
            assertEquals("bronze", member.getMemberLevel());
            verify(memberRepository, never()).save(any(Member.class));
            verify(benefitService, never()).issueLevelBenefits(anyString(), anyString());
        }

        @Test
        @DisplayName("等级未变化 - 不重复发放权益")
        void testMisjudgement_SameLevel_NoDuplicateBenefits() {
            Member member = TestDataBuilder.buildGoldMember();
            LevelConfig goldLevel = TestDataBuilder.buildGoldLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(5000))
                    .thenReturn(List.of(goldLevel));

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertFalse(upgraded);
            verify(benefitService, never()).issueLevelBenefits(anyString(), anyString());
        }

        @Test
        @DisplayName("升级成功后才发放权益 - 顺序校验")
        void testMisjudgement_UpgradeBeforeBenefits() {
            Member member = TestDataBuilder.MemberScenario.exactlyAtSilverThreshold();
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(1000))
                    .thenReturn(List.of(silverLevel));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> {
                Member saved = invocation.getArgument(0);
                assertEquals("silver", saved.getMemberLevel());
                return saved;
            });
            doNothing().when(benefitService).issueLevelBenefits(anyString(), anyString());

            levelService.checkAndUpgradeLevel(member);

            verify(memberRepository, times(1)).save(any(Member.class));
            verify(benefitService, times(1)).issueLevelBenefits(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("校验失败时的等级状态恢复测试")
    class StateRecoveryTests {

        @Test
        @DisplayName("升级失败 - 保持原等级")
        void testStateRecovery_UpgradeFail_KeepOriginalLevel() {
            Member member = TestDataBuilder.MemberScenario.aboutToUpgradeToSilver();
            LevelConfig bronzeLevel = TestDataBuilder.buildBronzeLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(990))
                    .thenReturn(List.of(bronzeLevel));

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertFalse(upgraded);
            assertEquals("bronze", member.getMemberLevel());
        }

        @Test
        @DisplayName("权益发放异常 - 等级已更新无法回滚")
        void testStateRecovery_BenefitFail_LevelUpdated() {
            Member member = TestDataBuilder.MemberScenario.exactlyAtSilverThreshold();
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(1000))
                    .thenReturn(List.of(silverLevel));
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            doThrow(new RuntimeException("权益发放异常"))
                    .when(benefitService).issueLevelBenefits(anyString(), anyString());

            assertThrows(RuntimeException.class, () -> {
                levelService.checkAndUpgradeLevel(member);
            });

            assertEquals("silver", member.getMemberLevel());
            verify(memberRepository, times(1)).save(member);
        }

        @Test
        @DisplayName("等级配置为空 - 不修改任何状态")
        void testStateRecovery_EmptyConfig_NoStateChange() {
            Member member = TestDataBuilder.buildGoldMember();
            String originalLevel = member.getMemberLevel();
            int originalPoints = member.getTotalPoints();
            
            when(levelConfigRepository.findHighestLevelForPoints(5000))
                    .thenReturn(Collections.emptyList());

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertFalse(upgraded);
            assertEquals(originalLevel, member.getMemberLevel());
            assertEquals(originalPoints, member.getTotalPoints());
        }
    }

    @Nested
    @DisplayName("多等级间的升级路径正确性测试")
    class MultiLevelUpgradeTests {

        @Test
        @DisplayName("青铜 → 白银 → 黄金 - 连续升级路径")
        void testMultiLevelUpgrade_SequentialPath() {
            Member bronzeMember = TestDataBuilder.MemberScenario.exactlyAtSilverThreshold();
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(1000))
                    .thenReturn(List.of(silverLevel));
            when(memberRepository.save(any(Member.class))).thenReturn(bronzeMember);
            doNothing().when(benefitService).issueLevelBenefits(anyString(), anyString());

            boolean firstUpgrade = levelService.checkAndUpgradeLevel(bronzeMember);
            assertTrue(firstUpgrade);
            assertEquals("silver", bronzeMember.getMemberLevel());

            Member silverMember = bronzeMember;
            silverMember.setTotalPoints(3000);
            LevelConfig goldLevel = TestDataBuilder.buildGoldLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(3000))
                    .thenReturn(List.of(goldLevel));

            boolean secondUpgrade = levelService.checkAndUpgradeLevel(silverMember);
            assertTrue(secondUpgrade);
            assertEquals("gold", silverMember.getMemberLevel());
        }

        @Test
        @DisplayName("青铜直接跳到铂金 - 越级升级")
        void testMultiLevelUpgrade_DirectJump() {
            Member member = TestDataBuilder.MemberScenario.multiLevelJumpCandidate();
            LevelConfig platinumLevel = TestDataBuilder.buildPlatinumLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(15000))
                    .thenReturn(List.of(platinumLevel));
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            doNothing().when(benefitService).issueLevelBenefits(anyString(), anyString());

            boolean upgraded = levelService.checkAndUpgradeLevel(member);

            assertTrue(upgraded);
            assertEquals("platinum", member.getMemberLevel());
            verify(benefitService, times(1)).issueLevelBenefits(member.getMemberId(), "platinum");
        }

        @Test
        @DisplayName("越级升级 - 只发放最终等级权益")
        void testMultiLevelUpgrade_OnlyFinalLevelBenefits() {
            Member member = TestDataBuilder.MemberScenario.multiLevelJumpCandidate();
            LevelConfig platinumLevel = TestDataBuilder.buildPlatinumLevelConfig();
            
            when(levelConfigRepository.findHighestLevelForPoints(15000))
                    .thenReturn(List.of(platinumLevel));
            when(memberRepository.save(any(Member.class))).thenReturn(member);
            doNothing().when(benefitService).issueLevelBenefits(anyString(), anyString());

            levelService.checkAndUpgradeLevel(member);

            verify(benefitService, times(1)).issueLevelBenefits(anyString(), eq("platinum"));
            verify(benefitService, never()).issueLevelBenefits(anyString(), eq("silver"));
            verify(benefitService, never()).issueLevelBenefits(anyString(), eq("gold"));
        }

        @Test
        @DisplayName("越级升级 - 积分门槛验证")
        void testMultiLevelUpgrade_ThresholdVerification() {
            Member member = TestDataBuilder.MemberScenario.multiLevelJumpCandidate();
            
            assertEquals(15000, member.getTotalPoints());
            assertTrue(member.getTotalPoints() >= 10000);
        }
    }

    @Nested
    @DisplayName("等级倍率获取测试")
    class LevelMultiplierTests {

        @Test
        @DisplayName("青铜会员倍率 - 1.0倍")
        void testLevelMultiplier_Bronze() {
            LevelConfig bronze = TestDataBuilder.buildBronzeLevelConfig();
            
            when(levelConfigRepository.findByLevelId("bronze"))
                    .thenReturn(Optional.of(bronze));

            double multiplier = levelService.getLevelMultiplier("bronze");

            assertEquals(1.0, multiplier);
        }

        @Test
        @DisplayName("白银会员倍率 - 1.2倍")
        void testLevelMultiplier_Silver() {
            LevelConfig silver = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findByLevelId("silver"))
                    .thenReturn(Optional.of(silver));

            double multiplier = levelService.getLevelMultiplier("silver");

            assertEquals(1.2, multiplier);
        }

        @Test
        @DisplayName("黄金会员倍率 - 1.5倍")
        void testLevelMultiplier_Gold() {
            LevelConfig gold = TestDataBuilder.buildGoldLevelConfig();
            
            when(levelConfigRepository.findByLevelId("gold"))
                    .thenReturn(Optional.of(gold));

            double multiplier = levelService.getLevelMultiplier("gold");

            assertEquals(1.5, multiplier);
        }

        @Test
        @DisplayName("铂金会员倍率 - 2.0倍")
        void testLevelMultiplier_Platinum() {
            LevelConfig platinum = TestDataBuilder.buildPlatinumLevelConfig();
            
            when(levelConfigRepository.findByLevelId("platinum"))
                    .thenReturn(Optional.of(platinum));

            double multiplier = levelService.getLevelMultiplier("platinum");

            assertEquals(2.0, multiplier);
        }

        @Test
        @DisplayName("未知等级 - 默认1.0倍")
        void testLevelMultiplier_UnknownLevel() {
            when(levelConfigRepository.findByLevelId("unknown"))
                    .thenReturn(Optional.empty());

            double multiplier = levelService.getLevelMultiplier("unknown");

            assertEquals(1.0, multiplier);
        }
    }

    @Nested
    @DisplayName("等级查询测试")
    class LevelQueryTests {

        @Test
        @DisplayName("查询会员等级 - 青铜会员")
        void testQueryMemberLevel_Bronze() {
            Member member = TestDataBuilder.buildBronzeMember();
            LevelConfig bronzeLevel = TestDataBuilder.buildBronzeLevelConfig();
            LevelConfig silverLevel = TestDataBuilder.buildSilverLevelConfig();
            
            when(levelConfigRepository.findByLevelId("bronze"))
                    .thenReturn(Optional.of(bronzeLevel));
            when(levelConfigRepository.findNextLevels(0))
                    .thenReturn(List.of(silverLevel));

            LevelQueryResponse response = levelService.queryMemberLevel(member);

            assertEquals("bronze", response.getLevel());
            assertEquals("青铜会员", response.getLevelName());
            assertEquals(0, response.getTotalPoints());
            assertEquals(0, response.getAvailablePoints());
            assertEquals(1000, response.getPointsToNextLevel());
        }

        @Test
        @DisplayName("查询会员等级 - 黄金会员")
        void testQueryMemberLevel_Gold() {
            Member member = TestDataBuilder.buildGoldMember();
            LevelConfig goldLevel = TestDataBuilder.buildGoldLevelConfig();
            LevelConfig platinumLevel = TestDataBuilder.buildPlatinumLevelConfig();
            
            when(levelConfigRepository.findByLevelId("gold"))
                    .thenReturn(Optional.of(goldLevel));
            when(levelConfigRepository.findNextLevels(5000))
                    .thenReturn(List.of(platinumLevel));

            LevelQueryResponse response = levelService.queryMemberLevel(member);

            assertEquals("gold", response.getLevel());
            assertEquals("黄金会员", response.getLevelName());
            assertEquals(5000, response.getTotalPoints());
            assertEquals(5000, response.getAvailablePoints());
            assertEquals(5000, response.getPointsToNextLevel());
        }

        @Test
        @DisplayName("查询会员等级 - 最高等级")
        void testQueryMemberLevel_HighestLevel() {
            Member member = TestDataBuilder.buildPlatinumMember();
            LevelConfig platinumLevel = TestDataBuilder.buildPlatinumLevelConfig();
            
            when(levelConfigRepository.findByLevelId("platinum"))
                    .thenReturn(Optional.of(platinumLevel));
            when(levelConfigRepository.findNextLevels(15000))
                    .thenReturn(Collections.emptyList());

            LevelQueryResponse response = levelService.queryMemberLevel(member);

            assertEquals("platinum", response.getLevel());
            assertEquals(0, response.getPointsToNextLevel());
        }

        @Test
        @DisplayName("未知等级名称处理")
        void testQueryMemberLevel_UnknownLevelName() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "unknown", 100, 100);
            
            when(levelConfigRepository.findByLevelId("unknown"))
                    .thenReturn(Optional.empty());
            when(levelConfigRepository.findNextLevels(100))
                    .thenReturn(List.of(TestDataBuilder.buildSilverLevelConfig()));

            LevelQueryResponse response = levelService.queryMemberLevel(member);

            assertEquals("unknown", response.getLevel());
            assertEquals("未知等级", response.getLevelName());
        }
    }
}
