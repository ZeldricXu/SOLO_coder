package com.cardgame.room.manager;

import com.cardgame.common.config.GameConfig;
import com.cardgame.common.enums.PlayerClass;
import com.cardgame.common.enums.RoomStatus;
import com.cardgame.room.entity.MatchRequest;
import com.cardgame.room.entity.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Matchmaking Manager Tests")
class MatchmakingManagerTest {

    @Mock
    private RoomManager roomManager;

    @Mock
    private GameConfig gameConfig;

    @InjectMocks
    private MatchmakingManager matchmakingManager;

    @BeforeEach
    void setUp() {
        when(gameConfig.getMaxMatchQueueSize()).thenReturn(100);
        when(gameConfig.getMatchTimeoutSeconds()).thenReturn(60);
        when(gameConfig.getBasePlayerHp()).thenReturn(80);
        when(gameConfig.getBasePlayerSpeed()).thenReturn(10);
        when(gameConfig.getDefaultMaxEnergy()).thenReturn(3);
        when(gameConfig.getMaxHandSize()).thenReturn(10);
    }

    private Queue<MatchRequest> getMatchQueue() throws Exception {
        Field field = MatchmakingManager.class.getDeclaredField("matchQueue");
        field.setAccessible(true);
        return (Queue<MatchRequest>) field.get(matchmakingManager);
    }

    @Nested
    @DisplayName("Match Queue Tests")
    class MatchQueueTests {

        @Test
        @DisplayName("Add to match queue - should add player to queue")
        void addToMatchQueue_ShouldAddPlayer() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).hasSize(1);
            assertThat(queue.peek().getPlayerId()).isEqualTo("player1");
        }

        @Test
        @DisplayName("Add multiple players to queue - should maintain order")
        void addToMatchQueue_Multiple_ShouldMaintainOrder() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player2", "Player2", PlayerClass.MAGE, 2);
            matchmakingManager.addToMatchQueue("player3", "Player3", PlayerClass.ROGUE, 3);

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).hasSize(3);
            assertThat(queue.poll().getPlayerId()).isEqualTo("player1");
            assertThat(queue.poll().getPlayerId()).isEqualTo("player2");
            assertThat(queue.poll().getPlayerId()).isEqualTo("player3");
        }

        @Test
        @DisplayName("Add to full queue - should reject")
        void addToMatchQueue_Full_ShouldReject() throws Exception {
            when(gameConfig.getMaxMatchQueueSize()).thenReturn(2);

            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player2", "Player2", PlayerClass.MAGE, 2);
            matchmakingManager.addToMatchQueue("player3", "Player3", PlayerClass.ROGUE, 3);

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).hasSize(2);
        }

        @Test
        @DisplayName("Get queue position - should return correct position")
        void getQueuePosition_ShouldReturnCorrect() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player2", "Player2", PlayerClass.MAGE, 2);
            matchmakingManager.addToMatchQueue("player3", "Player3", PlayerClass.ROGUE, 3);

            assertThat(matchmakingManager.getQueuePosition("player1")).isEqualTo(1);
            assertThat(matchmakingManager.getQueuePosition("player2")).isEqualTo(2);
            assertThat(matchmakingManager.getQueuePosition("player3")).isEqualTo(3);
        }

        @Test
        @DisplayName("Get queue position - player not in queue returns -1")
        void getQueuePosition_NotFound_ShouldReturnMinusOne() {
            assertThat(matchmakingManager.getQueuePosition("nonexistent")).isEqualTo(-1);
        }

        @Test
        @DisplayName("Get queue size - should return correct size")
        void getQueueSize_ShouldReturnCorrect() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player2", "Player2", PlayerClass.MAGE, 2);

            assertThat(matchmakingManager.getQueueSize()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Cancel Match Tests")
    class CancelMatchTests {

        @Test
        @DisplayName("Remove from match queue - should remove player")
        void removeFromMatchQueue_ShouldRemovePlayer() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player2", "Player2", PlayerClass.MAGE, 2);

            matchmakingManager.removeFromMatchQueue("player1");

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).hasSize(1);
            assertThat(queue.peek().getPlayerId()).isEqualTo("player2");
            assertThat(matchmakingManager.getQueuePosition("player1")).isEqualTo(-1);
        }

        @Test
        @DisplayName("Remove from match queue - remove non-existent player")
        void removeFromMatchQueue_NonExistent_ShouldNotCrash() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);

            matchmakingManager.removeFromMatchQueue("nonexistent");

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).hasSize(1);
        }

        @Test
        @DisplayName("Cancel match - should clean up all references")
        void removeFromMatchQueue_ShouldCleanUpAllReferences() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player2", "Player2", PlayerClass.MAGE, 2);
            matchmakingManager.addToMatchQueue("player3", "Player3", PlayerClass.ROGUE, 3);

            matchmakingManager.removeFromMatchQueue("player2");

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).hasSize(2);
            assertThat(queue).extracting("playerId").containsExactly("player1", "player3");
            assertThat(matchmakingManager.getQueuePosition("player1")).isEqualTo(1);
            assertThat(matchmakingManager.getQueuePosition("player3")).isEqualTo(2);
            assertThat(matchmakingManager.getQueuePosition("player2")).isEqualTo(-1);
        }

        @Test
        @DisplayName("Cancel match immediately after joining - should remove successfully")
        void removeFromMatchQueue_ImmediateCancel_ShouldRemove() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);

            matchmakingManager.removeFromMatchQueue("player1");

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).isEmpty();
            assertThat(matchmakingManager.getQueueSize()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Match Processing Tests")
    class MatchProcessingTests {

        @Test
        @DisplayName("Process matchmaking - enough players should create room")
        void processMatchmaking_EnoughPlayers_ShouldCreateRoom() throws Exception {
            Room mockRoom = Room.builder()
                    .roomId("room1")
                    .status(RoomStatus.WAITING)
                    .build();
            when(roomManager.createRoom(anyString(), anyString(), eq(4), eq(false), isNull()))
                    .thenReturn(mockRoom);

            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player2", "Player2", PlayerClass.MAGE, 2);

            Method processMethod = MatchmakingManager.class.getDeclaredMethod("processMatchmaking");
            processMethod.setAccessible(true);
            processMethod.invoke(matchmakingManager);

            verify(roomManager, times(1)).createRoom(anyString(), eq("player1"), eq(4), eq(false), isNull());
            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).isEmpty();
        }

        @Test
        @DisplayName("Process matchmaking - not enough players should not create room")
        void processMatchmaking_NotEnoughPlayers_ShouldNotCreateRoom() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);

            Method processMethod = MatchmakingManager.class.getDeclaredMethod("processMatchmaking");
            processMethod.setAccessible(true);
            processMethod.invoke(matchmakingManager);

            verify(roomManager, never()).createRoom(anyString(), anyString(), anyInt(), anyBoolean(), anyString());
            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).hasSize(1);
        }

        @Test
        @DisplayName("Process matchmaking - matched players should be removed from queue")
        void processMatchmaking_MatchedPlayers_ShouldBeRemoved() throws Exception {
            Room mockRoom = Room.builder()
                    .roomId("room1")
                    .status(RoomStatus.WAITING)
                    .build();
            when(roomManager.createRoom(anyString(), anyString(), eq(4), eq(false), isNull()))
                    .thenReturn(mockRoom);

            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player2", "Player2", PlayerClass.MAGE, 2);
            matchmakingManager.addToMatchQueue("player3", "Player3", PlayerClass.ROGUE, 3);

            Method processMethod = MatchmakingManager.class.getDeclaredMethod("processMatchmaking");
            processMethod.setAccessible(true);
            processMethod.invoke(matchmakingManager);

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).isEmpty();
        }
    }

    @Nested
    @DisplayName("Timeout Tests")
    class TimeoutTests {

        @Test
        @DisplayName("Cleanup timeout requests - should remove expired requests")
        void cleanupTimeoutRequests_Expired_ShouldRemove() throws Exception {
            when(gameConfig.getMatchTimeoutSeconds()).thenReturn(1);

            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);

            Queue<MatchRequest> queue = getMatchQueue();
            MatchRequest request = queue.peek();
            request.setRequestTime(System.currentTimeMillis() - 2000);

            Method cleanupMethod = MatchmakingManager.class.getDeclaredMethod("cleanupTimeoutRequests");
            cleanupMethod.setAccessible(true);
            cleanupMethod.invoke(matchmakingManager);

            assertThat(queue).isEmpty();
        }

        @Test
        @DisplayName("Cleanup timeout requests - should keep non-expired requests")
        void cleanupTimeoutRequests_NonExpired_ShouldKeep() throws Exception {
            when(gameConfig.getMatchTimeoutSeconds()).thenReturn(60);

            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);

            Method cleanupMethod = MatchmakingManager.class.getDeclaredMethod("cleanupTimeoutRequests");
            cleanupMethod.setAccessible(true);
            cleanupMethod.invoke(matchmakingManager);

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Add same player twice - should have two entries")
        void addToMatchQueue_SamePlayerTwice_ShouldHaveTwoEntries() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).hasSize(2);
        }

        @Test
        @DisplayName("Remove same player twice - should remove all occurrences")
        void removeFromMatchQueue_SamePlayerTwice_ShouldRemoveAll() throws Exception {
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);

            matchmakingManager.removeFromMatchQueue("player1");

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).isEmpty();
        }

        @Test
        @DisplayName("Cancel match during processing - should handle gracefully")
        void removeFromMatchQueue_DuringProcessing_ShouldHandleGracefully() throws Exception {
            Room mockRoom = Room.builder()
                    .roomId("room1")
                    .status(RoomStatus.WAITING)
                    .build();
            when(roomManager.createRoom(anyString(), anyString(), eq(4), eq(false), isNull()))
                    .thenReturn(mockRoom);

            matchmakingManager.addToMatchQueue("player1", "Player1", PlayerClass.WARRIOR, 1);
            matchmakingManager.addToMatchQueue("player2", "Player2", PlayerClass.MAGE, 2);
            matchmakingManager.addToMatchQueue("player3", "Player3", PlayerClass.ROGUE, 3);
            matchmakingManager.addToMatchQueue("player4", "Player4", PlayerClass.PRIEST, 4);

            matchmakingManager.removeFromMatchQueue("player2");

            Method processMethod = MatchmakingManager.class.getDeclaredMethod("processMatchmaking");
            processMethod.setAccessible(true);
            processMethod.invoke(matchmakingManager);

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).isEmpty();
        }
    }
}
