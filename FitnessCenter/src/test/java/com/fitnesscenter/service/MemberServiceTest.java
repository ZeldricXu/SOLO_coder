package com.fitnesscenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesscenter.builder.TestDataBuilder;
import com.fitnesscenter.dto.MemberRequest;
import com.fitnesscenter.model.History;
import com.fitnesscenter.model.Member;
import com.fitnesscenter.model.Statistic;
import com.fitnesscenter.repository.HistoryRepository;
import com.fitnesscenter.repository.MemberRepository;
import com.fitnesscenter.repository.StatisticRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("会员管理模块测试")
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private HistoryRepository historyRepository;

    @Mock
    private StatisticRepository statisticRepository;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private MemberService memberService;

    private Member vipMember;
    private Member regularMember;
    private Member activeMember;
    private Member frozenMember;

    @BeforeEach
    void setUp() {
        vipMember = TestDataBuilder.buildVipMember();
        regularMember = TestDataBuilder.buildRegularMember();
        activeMember = TestDataBuilder.buildActiveMember();
        frozenMember = TestDataBuilder.buildFrozenMember();
    }

    @Test
    @DisplayName("测试会员注册")
    void testRegisterMember() {
        MemberRequest request = TestDataBuilder.buildMemberRegistrationRequest();
        Member savedMember = TestDataBuilder.buildRegularMember();
        savedMember.setMemberPhone(request.getMemberPhone());

        when(memberRepository.existsByMemberPhone(request.getMemberPhone())).thenReturn(false);
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> {
                    Member member = invocation.getArgument(0);
                    member.setMemberId(savedMember.getMemberId());
                    return member;
                });
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        Member registeredMember = memberService.registerMember(request);

        assertNotNull(registeredMember, "注册的会员不应该为空");
        assertEquals(request.getMemberName(), registeredMember.getMemberName(), "会员名称应该正确");
        assertEquals("active", registeredMember.getMemberStatus(), "初始状态应该为active");
        assertEquals(0, registeredMember.getBookingCount(), "初始预约次数应该为0");
        assertEquals(0, registeredMember.getTrainingCount(), "初始训练次数应该为0");
        assertEquals(0, registeredMember.getTotalCalories(), "初始总热量应该为0");
        verify(memberRepository).save(any(Member.class));
        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("测试会员注册 - 手机号已存在")
    void testRegisterMemberWhenPhoneExists() {
        MemberRequest request = TestDataBuilder.buildMemberRegistrationRequest();

        when(memberRepository.existsByMemberPhone(request.getMemberPhone())).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> memberService.registerMember(request),
                "手机号已存在时应该抛出异常");
    }

    @Test
    @DisplayName("测试获取会员信息")
    void testGetMemberById() {
        when(memberRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(vipMember));

        Member member = memberService.getMemberById(vipMember.getMemberId());

        assertNotNull(member, "会员不应该为空");
        assertEquals(vipMember.getMemberId(), member.getMemberId(), "会员ID应该正确");
        assertEquals(vipMember.getMemberName(), member.getMemberName(), "会员名称应该正确");
    }

    @Test
    @DisplayName("测试获取不存在的会员")
    void testGetMemberByIdWhenNotExists() {
        when(memberRepository.findByMemberId("NON_EXISTENT_MEMBER")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> memberService.getMemberById("NON_EXISTENT_MEMBER"),
                "不存在的会员应该抛出异常");
    }

    @Test
    @DisplayName("测试获取所有会员")
    void testGetAllMembers() {
        when(memberRepository.findAll()).thenReturn(Arrays.asList(vipMember, regularMember));

        java.util.List<Member> members = memberService.getAllMembers();

        assertEquals(2, members.size(), "应该返回2个会员");
    }

    @Test
    @DisplayName("测试获取所有会员 - 空列表")
    void testGetAllMembersWhenEmpty() {
        when(memberRepository.findAll()).thenReturn(Collections.emptyList());

        java.util.List<Member> members = memberService.getAllMembers();

        assertTrue(members.isEmpty(), "应该返回空列表");
    }

    @Test
    @DisplayName("测试更新会员信息")
    void testUpdateMember() {
        MemberRequest request = new MemberRequest();
        request.setMemberName("更新后的名称");
        request.setMemberLevel("vip");

        when(memberRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.of(regularMember));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(historyRepository.save(any(History.class)))
                .thenReturn(new History());

        Member updatedMember = memberService.updateMember(regularMember.getMemberId(), request);

        assertEquals("更新后的名称", updatedMember.getMemberName(), "名称应该被更新");
        assertEquals("vip", updatedMember.getMemberLevel(), "等级应该被更新");
        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("测试更新会员 - 手机号已被其他会员使用")
    void testUpdateMemberWhenPhoneUsedByOthers() {
        Member otherMember = TestDataBuilder.buildVipMember();
        otherMember.setMemberPhone("13900000002");

        MemberRequest request = new MemberRequest();
        request.setMemberPhone(otherMember.getMemberPhone());

        when(memberRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.of(regularMember));
        when(memberRepository.existsByMemberPhone(otherMember.getMemberPhone())).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> memberService.updateMember(regularMember.getMemberId(), request),
                "手机号已被其他会员使用时应该抛出异常");
    }

    @Test
    @DisplayName("测试会员状态流转 - 正常->过期")
    void testMemberStatusFlowActiveToExpired() {
        when(memberRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.of(regularMember));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        memberService.updateMemberStatus(regularMember.getMemberId(), "expired");

        verify(memberRepository).save(any(Member.class));
        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("测试会员状态流转 - 正常->冻结")
    void testMemberStatusFlowActiveToFrozen() {
        when(memberRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.of(regularMember));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        memberService.updateMemberStatus(regularMember.getMemberId(), "frozen");

        verify(memberRepository).save(any(Member.class));
        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("测试会员等级变更")
    void testMemberLevelChange() {
        MemberRequest request = new MemberRequest();
        request.setMemberLevel("vip");

        when(memberRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.of(regularMember));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        Member updatedMember = memberService.updateMember(regularMember.getMemberId(), request);

        assertEquals("vip", updatedMember.getMemberLevel(), "等级应该被更新为vip");
        verify(memberRepository).save(any(Member.class));
    }

    @Test
    @DisplayName("测试增加预约计数")
    void testIncrementBookingCount() {
        int initialCount = vipMember.getBookingCount();
        when(memberRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(vipMember));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        memberService.incrementBookingCount(vipMember.getMemberId());

        verify(memberRepository).save(any(Member.class));
    }

    @Test
    @DisplayName("测试更新训练统计")
    void testUpdateTrainingStats() {
        int initialTrainingCount = regularMember.getTrainingCount();
        int initialCalories = regularMember.getTotalCalories();

        when(memberRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.of(regularMember));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        memberService.updateTrainingStats(regularMember.getMemberId(), 500);

        verify(memberRepository).save(any(Member.class));
    }

    @Test
    @DisplayName("测试验证会员状态 - 活跃会员")
    void testValidateMemberStatusActive() {
        when(memberRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Optional.of(vipMember));

        assertDoesNotThrow(() -> memberService.validateMemberStatus(vipMember.getMemberId()),
                "活跃会员验证不应该抛出异常");
    }

    @Test
    @DisplayName("测试验证会员状态 - 过期会员")
    void testValidateMemberStatusExpired() {
        Member expiredMember = TestDataBuilder.buildExpiredMember();
        when(memberRepository.findByMemberId(expiredMember.getMemberId())).thenReturn(Optional.of(expiredMember));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> memberService.validateMemberStatus(expiredMember.getMemberId()),
                "过期会员应该抛出异常");
        assertTrue(exception.getMessage().contains("过期"));
    }

    @Test
    @DisplayName("测试验证会员状态 - 冻结会员")
    void testValidateMemberStatusFrozen() {
        when(memberRepository.findByMemberId(frozenMember.getMemberId())).thenReturn(Optional.of(frozenMember));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> memberService.validateMemberStatus(frozenMember.getMemberId()),
                "冻结会员应该抛出异常");
        assertTrue(exception.getMessage().contains("冻结"));
    }

    @Test
    @DisplayName("测试验证会员状态 - 不存在的会员")
    void testValidateMemberStatusNotExists() {
        when(memberRepository.findByMemberId("NON_EXISTENT_MEMBER")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> memberService.validateMemberStatus("NON_EXISTENT_MEMBER"),
                "不存在的会员应该抛出异常");
    }

    @Test
    @DisplayName("测试会员训练数据统计")
    void testMemberTrainingStatistics() {
        Member memberWithStats = TestDataBuilder.buildMemberWithCustomStats(
                "MEMBER_WITH_STATS",
                10,
                8,
                4500
        );

        when(memberRepository.findByMemberId("MEMBER_WITH_STATS")).thenReturn(Optional.of(memberWithStats));

        Member foundMember = memberService.getMemberById("MEMBER_WITH_STATS");

        assertEquals(10, foundMember.getBookingCount(), "预约次数应该正确");
        assertEquals(8, foundMember.getTrainingCount(), "训练次数应该正确");
        assertEquals(4500, foundMember.getTotalCalories(), "总热量应该正确");
    }

    @Test
    @DisplayName("测试会员注册 - VIP会员注册")
    void testRegisterVipMember() {
        MemberRequest request = TestDataBuilder.buildVipMemberRegistrationRequest();
        Member savedVipMember = TestDataBuilder.buildVipMember();

        when(memberRepository.existsByMemberPhone(request.getMemberPhone())).thenReturn(false);
        when(memberRepository.save(any(Member.class))
                .thenAnswer(invocation -> {
                    Member member = invocation.getArgument(0);
                    member.setMemberId(savedVipMember.getMemberId());
                    return member;
                });
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        Member registeredMember = memberService.registerMember(request);

        assertNotNull(registeredMember, "注册的会员不应该为空");
        assertEquals("vip", registeredMember.getMemberLevel(), "等级应该为vip");
        assertEquals("annual", registeredMember.getMemberType(), "类型应该为annual");
    }

    @Test
    @DisplayName("测试会员状态更新历史记录")
    void testMemberStatusUpdateHistory() {
        when(memberRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.of(regularMember));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        memberService.updateMemberStatus(regularMember.getMemberId(), "expired");

        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("测试会员更新历史记录")
    void testMemberUpdateHistory() {
        MemberRequest request = new MemberRequest();
        request.setMemberName("新名称");

        when(memberRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Optional.of(regularMember));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        memberService.updateMember(regularMember.getMemberId(), request);

        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("测试会员注册统计数据更新")
    void testMemberRegistrationUpdatesStatistics() {
        MemberRequest request = TestDataBuilder.buildMemberRegistrationRequest();
        Member savedMember = TestDataBuilder.buildRegularMember();

        when(memberRepository.existsByMemberPhone(request.getMemberPhone())).thenReturn(false);
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> {
                    Member member = invocation.getArgument(0);
                    member.setMemberId(savedMember.getMemberId());
                    return member;
                });
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        memberService.registerMember(request);

        verify(statisticRepository).save(any(Statistic.class));
    }

    @Test
    @DisplayName("测试获取会员训练统计更新")
    void testUpdateTrainingStatsUpdatesHistory() {
        when(memberRepository.findByMemberId(activeMember.getMemberId())).thenReturn(Optional.of(activeMember));
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        memberService.updateTrainingStats(activeMember.getMemberId(), 300);

        verify(memberRepository).save(any(Member.class));
    }
}
