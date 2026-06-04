package com.battle.platform.reward;

import com.battle.platform.entity.RewardRecord;
import com.battle.platform.repository.RewardRecordRepository;
import com.battle.platform.repository.SeasonRankingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RewardServiceIdempotencyTest {

    @Mock
    private RewardRecordRepository rewardRecordRepository;

    @Mock
    private SeasonRankingRepository seasonRankingRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RewardService rewardService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        rewardService = new RewardService(rewardRecordRepository, seasonRankingRepository, stringRedisTemplate);
    }

    @Test
    void testDeliverReward_WithSameRequestId_ReturnsCachedResult() {
        String requestId = "req-001";

        when(valueOperations.get("reward:idempotent:req-001")).thenReturn("true|Success");

        RewardService.RewardDeliveryResult result1 = rewardService.deliverReward(1L, requestId);
        RewardService.RewardDeliveryResult result2 = rewardService.deliverReward(1L, requestId);

        assertTrue(result1.isSuccess());
        assertTrue(result2.isSuccess());
    }

    @Test
    void testDeliverReward_AlreadyDelivered_ReturnsSuccess() {
        RewardRecord deliveredRecord = RewardRecord.builder()
                .id(1L)
                .playerId(100L)
                .seasonId(1L)
                .rewardType(RewardRecord.RewardType.PERSONAL)
                .rank(1)
                .rewardContentJson("{\"diamond\":100}")
                .status(RewardRecord.RewardStatus.DELIVERED)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        when(rewardRecordRepository.findById(1L)).thenReturn(Optional.of(deliveredRecord));

        RewardService.RewardDeliveryResult result = rewardService.deliverReward(1L);

        assertTrue(result.isSuccess());
        assertEquals("Already delivered", result.getMessage());
    }

    @Test
    void testDeliverReward_RewardNotFound_ReturnsFailure() {
        when(rewardRecordRepository.findById(999L)).thenReturn(Optional.empty());

        RewardService.RewardDeliveryResult result = rewardService.deliverReward(999L);

        assertFalse(result.isSuccess());
        assertEquals("Reward not found", result.getMessage());
    }

    @Test
    void testDeliverReward_CachesResultWithRequestId() {
        String requestId = "req-002";

        RewardRecord pendingRecord = RewardRecord.builder()
                .id(1L)
                .playerId(100L)
                .seasonId(1L)
                .rewardType(RewardRecord.RewardType.PERSONAL)
                .rank(1)
                .rewardContentJson("{\"diamond\":100}")
                .status(RewardRecord.RewardStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        when(rewardRecordRepository.findById(1L)).thenReturn(Optional.of(pendingRecord));
        when(rewardRecordRepository.save(any(RewardRecord.class))).thenReturn(pendingRecord);

        RewardService.RewardDeliveryResult result = rewardService.deliverReward(1L, requestId);

        assertTrue(result.isSuccess());
        verify(valueOperations).set(
                eq("reward:idempotent:req-002"),
                eq("true|Success"),
                eq(24L),
                eq(TimeUnit.HOURS)
        );
    }

    @Test
    void testRewardDeliveryResultSerialization() {
        RewardService.RewardDeliveryResult result = new RewardService.RewardDeliveryResult(true, "Test message");
        String serialized = result.toString();
        RewardService.RewardDeliveryResult deserialized = RewardService.RewardDeliveryResult.fromString(serialized);

        assertTrue(deserialized.isSuccess());
        assertEquals("Test message", deserialized.getMessage());
    }

    @Test
    void testRewardDeliveryResultSerialization_Failure() {
        RewardService.RewardDeliveryResult result = new RewardService.RewardDeliveryResult(false, "Error occurred");
        String serialized = result.toString();
        RewardService.RewardDeliveryResult deserialized = RewardService.RewardDeliveryResult.fromString(serialized);

        assertFalse(deserialized.isSuccess());
        assertEquals("Error occurred", deserialized.getMessage());
    }
}
