package com.battle.platform.leaderboard;

import com.battle.platform.repository.PlayerRepository;
import com.battle.platform.repository.SeasonRankingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaderboardServiceLockTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SeasonRankingRepository seasonRankingRepository;

    @Mock
    private PlayerRepository playerRepository;

    private LeaderboardService leaderboardService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        leaderboardService = new LeaderboardService(stringRedisTemplate, seasonRankingRepository, playerRepository);
    }

    @Test
    void testTakeSnapshot_WithLockAcquired() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        assertDoesNotThrow(() -> leaderboardService.takeSnapshot());

        verify(valueOperations).setIfAbsent(
                eq("leaderboard:snapshot:lock"),
                anyString(),
                eq(600L),
                eq(TimeUnit.SECONDS)
        );
        verify(stringRedisTemplate).delete("leaderboard:snapshot:lock");
    }

    @Test
    void testTakeSnapshot_WithLockHeld_SkipsExecution() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        assertDoesNotThrow(() -> leaderboardService.takeSnapshot());

        verify(valueOperations).setIfAbsent(
                eq("leaderboard:snapshot:lock"),
                anyString(),
                eq(600L),
                eq(TimeUnit.SECONDS)
        );
        verify(stringRedisTemplate, never()).delete("leaderboard:snapshot:lock");
    }

    @Test
    void testArchiveSeasonToMySQL_WithLockAcquired() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        assertDoesNotThrow(() -> leaderboardService.archiveSeasonToMySQL(1L));

        verify(stringRedisTemplate).delete("leaderboard:archive:1:lock");
    }

    @Test
    void testArchiveSeasonToMySQL_WithLockHeld_SkipsExecution() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        assertDoesNotThrow(() -> leaderboardService.archiveSeasonToMySQL(1L));

        verify(stringRedisTemplate, never()).delete("leaderboard:archive:1:lock");
    }
}
