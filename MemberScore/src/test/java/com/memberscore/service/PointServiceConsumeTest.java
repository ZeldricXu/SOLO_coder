package com.memberscore.service;

import com.memberscore.dto.ConsumePointRequest;
import com.memberscore.dto.PointOperationResponse;
import com.memberscore.entity.Member;
import com.memberscore.entity.PointRecord;
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
@DisplayName("积分消费服务 - 单元测试")
class PointServiceConsumeTest {

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

    private ConsumePointRequest buildConsumeRequest(String memberId, int amount, String type) {
        ConsumePointRequest request = new ConsumePointRequest();
        request.setMemberId(memberId);
        request.setConsumeAmount(amount);
        request.setConsumeType(type);
        return request;
    }

    @Nested
    @DisplayName("积分余额扣除正确性测试")
    class BalanceDeductionTests {

        @Test
        @DisplayName("消费积分 - 余额正确扣除")
        void testConsumePoints_BalanceDeductedCorrectly() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "gold", 5000, 5000);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 1000, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.consumePoints(request);

            assertEquals(4000, response.getBalance());
            assertEquals(1000, response.getConsumedPoints());
            assertEquals(4000, member.getAvailablePoints());
            assertEquals(1000, member.getUsedPoints());
            assertEquals(5000, member.getTotalPoints());
        }

        @Test
        @DisplayName("累计消费 - usedPoints正确累加")
        void testConsumePoints_UsedPointsAccumulated() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "gold", 5000, 4500);
            member.setUsedPoints(500);
            ConsumePointRequest request = buildConsumeRequest("member_test", 500, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.consumePoints(request);

            assertEquals(4000, response.getBalance());
            assertEquals(1000, member.getUsedPoints());
        }

        @Test
        @DisplayName("消费后余额为0 - 正确边界情况")
        void testConsumePoints_ExactBalance() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "bronze", 1000, 1000);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 1000, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.consumePoints(request);

            assertEquals(0, response.getBalance());
            assertEquals(1000, response.getConsumedPoints());
            assertEquals(0, member.getAvailablePoints());
            assertEquals(1000, member.getUsedPoints());
        }

        @Test
        @DisplayName("小额消费 - 精确扣除")
        void testConsumePoints_SmallAmount() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "bronze", 100, 100);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 1, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.consumePoints(request);

            assertEquals(99, response.getBalance());
            assertEquals(1, response.getConsumedPoints());
        }

        @Test
        @DisplayName("大额消费 - 完整流程")
        void testConsumePoints_LargeAmount() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "platinum", 20000, 20000);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 15000, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.consumePoints(request);

            assertEquals(5000, response.getBalance());
            assertEquals(15000, response.getConsumedPoints());
        }
    }

    @Nested
    @DisplayName("消费类型匹配准确性测试")
    class ConsumeTypeMatchingTests {

        @Test
        @DisplayName("兑换类型消费")
        void testConsumePoints_ExchangeType() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "gold", 5000, 5000);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 1000, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.consumePoints(request);

            verify(pointRecordRepository, times(1)).save(argThat(record -> {
                assertEquals("exchange", record.getConsumeType());
                assertEquals(com.memberscore.enums.PointType.CONSUME, record.getPointType());
                return true;
            }));
        }

        @Test
        @DisplayName("抵扣类型消费")
        void testConsumePoints_DeductType() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "gold", 5000, 5000);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 500, "deduct");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            pointService.consumePoints(request);

            verify(pointRecordRepository, times(1)).save(argThat(record -> {
                assertEquals("deduct", record.getConsumeType());
                return true;
            }));
        }

        @Test
        @DisplayName("过期类型消费（过期清理）")
        void testConsumePoints_ExpireType() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "gold", 5000, 5000);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 200, "expire");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            pointService.consumePoints(request);

            verify(pointRecordRepository, times(1)).save(argThat(record -> {
                assertEquals("expire", record.getConsumeType());
                return true;
            }));
        }
    }

    @Nested
    @DisplayName("余额不足 - 拒绝处理测试")
    class InsufficientBalanceTests {

        @Test
        @DisplayName("余额不足 - 抛出异常")
        void testConsumePoints_InsufficientBalance() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "bronze", 500, 500);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 1000, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                pointService.consumePoints(request);
            });

            assertTrue(exception.getMessage().contains("积分余额不足"));
            assertTrue(exception.getMessage().contains("可用=500"));
            assertTrue(exception.getMessage().contains("消费=1000"));
            verify(memberRepository, never()).save(any(Member.class));
            verify(pointRecordRepository, never()).save(any(PointRecord.class));
            verify(pointStatService, never()).recordConsumeStat(anyInt());
        }

        @Test
        @DisplayName("余额为0 - 完全拒绝")
        void testConsumePoints_ZeroBalance() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "bronze", 1000, 0);
            member.setUsedPoints(1000);
            ConsumePointRequest request = buildConsumeRequest("member_test", 1, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                pointService.consumePoints(request);
            });

            assertTrue(exception.getMessage().contains("积分余额不足"));
            assertEquals(0, member.getAvailablePoints());
        }

        @Test
        @DisplayName("消费金额超过余额1分 - 边界拒绝")
        void testConsumePoints_BalanceOneLess() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "bronze", 1000, 1000);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 1001, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                pointService.consumePoints(request);
            });

            assertTrue(exception.getMessage().contains("积分余额不足"));
        }

        @Test
        @DisplayName("消费金额恰好等于余额 - 可以消费")
        void testConsumePoints_BalanceEqualsAmount() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "bronze", 1000, 1000);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 1000, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            PointOperationResponse response = pointService.consumePoints(request);

            assertEquals(0, response.getBalance());
            verify(memberRepository, times(1)).save(any(Member.class));
        }
    }

    @Nested
    @DisplayName("积分过期消费限制测试")
    class ExpiredPointsRestrictionTests {

        @Test
        @DisplayName("会员不存在 - 消费失败")
        void testConsumePoints_MemberNotFound() {
            ConsumePointRequest request = buildConsumeRequest("non_existent", 100, "exchange");

            when(memberRepository.findByMemberId("non_existent")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                pointService.consumePoints(request);
            });

            assertTrue(exception.getMessage().contains("会员不存在"));
        }

        @Test
        @DisplayName("消费记录创建验证")
        void testConsumePoints_RecordCreation() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "gold", 5000, 5000);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 1000, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> {
                PointRecord saved = invocation.getArgument(0);
                saved.setId(1L);
                return saved;
            });

            PointOperationResponse response = pointService.consumePoints(request);

            verify(pointRecordRepository, times(1)).save(argThat(record -> {
                assertNotNull(record.getPointId());
                assertEquals("member_test", record.getMemberId());
                assertEquals(com.memberscore.enums.PointType.CONSUME, record.getPointType());
                assertEquals(Integer.valueOf(1000), record.getPointAmount());
                assertEquals("exchange", record.getConsumeType());
                assertEquals(Integer.valueOf(4000), record.getPointBalance());
                assertNull(record.getExpireAt());
                return true;
            }));
        }

        @Test
        @DisplayName("统计数据更新验证")
        void testConsumePoints_StatsUpdated() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "gold", 5000, 5000);
            member.setUsedPoints(0);
            ConsumePointRequest request = buildConsumeRequest("member_test", 1000, "exchange");

            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));
            when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

            pointService.consumePoints(request);

            verify(pointStatService, times(1)).recordConsumeStat(1000);
        }
    }

    @Nested
    @DisplayName("消费积分查询测试")
    class PointsQueryTests {

        @Test
        @DisplayName("查询可用积分 - 成功")
        void testGetAvailablePoints_Success() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "gold", 5000, 4000);
            
            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));

            int available = pointService.getAvailablePoints("member_test");

            assertEquals(4000, available);
        }

        @Test
        @DisplayName("查询累计积分 - 成功")
        void testGetTotalPoints_Success() {
            Member member = TestDataBuilder.buildMember("member_test", "user_test", "gold", 5000, 4000);
            
            when(memberRepository.findByMemberId("member_test")).thenReturn(Optional.of(member));

            int total = pointService.getTotalPoints("member_test");

            assertEquals(5000, total);
        }

        @Test
        @DisplayName("查询积分 - 会员不存在抛出异常")
        void testGetPoints_MemberNotFound() {
            when(memberRepository.findByMemberId("non_existent")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                pointService.getAvailablePoints("non_existent");
            });

            assertTrue(exception.getMessage().contains("会员不存在"));
        }
    }
}
