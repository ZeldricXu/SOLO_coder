package com.cardgame.room.manager;

import com.cardgame.common.TestDataBuilder;
import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.RoomStatus;
import com.cardgame.room.entity.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Reconnect Room State Tests")
class ReconnectRoomStateTest {

    @Mock
    private GameConfig gameConfig;

    @InjectMocks
    private RoomManager roomManager;

    @BeforeEach
    void setUp() {
        when(gameConfig.getMaxPlayersPerRoom()).thenReturn(4);
        when(gameConfig.getReconnectTimeoutSeconds()).thenReturn(60);
    }

    @Test
    @DisplayName("Reconnect - should restore correct room state snapshot")
    void reconnect_ShouldRestoreCorrectRoomSnapshot() {
        Room room = roomManager.createRoom("Test Room", "owner1", 4, false, null);
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");
        Player player3 = TestDataBuilder.createRoguePlayer("player3");

        roomManager.joinRoom(room.getRoomId(), player1, null);
        roomManager.joinRoom(room.getRoomId(), player2, null);
        roomManager.joinRoom(room.getRoomId(), player3, null);

        roomManager.updateRoomStatus(room.getRoomId(), RoomStatus.EXPLORING);
        room.setCurrentFloor(3);

        roomManager.setPlayerDisconnected("player2");

        Room savedRoomState = Room.builder()
                .roomId(room.getRoomId())
                .roomName(room.getRoomName())
                .ownerId(room.getOwnerId())
                .inviteCode(room.getInviteCode())
                .status(room.getStatus())
                .currentFloor(room.getCurrentFloor())
                .players(room.getPlayers())
                .playerMap(room.getPlayerMap())
                .disconnectTimes(room.getDisconnectTimes())
                .build();

        boolean reconnected = roomManager.setPlayerReconnected("player2", room.getRoomId());

        assertThat(reconnected).isTrue();
        assertThat(room.getPlayer("player2").isOnline()).isTrue();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.EXPLORING);
        assertThat(room.getCurrentFloor()).isEqualTo(3);
        assertThat(room.getPlayers()).hasSize(3);
        assertThat(room.getPlayer("player1").isOnline()).isTrue();
        assertThat(room.getPlayer("player3").isOnline()).isTrue();
        assertThat(room.getDisconnectTimes()).doesNotContainKey("player2");
    }

    @Test
    @DisplayName("Reconnect timeout - should not allow reconnect after timeout")
    void reconnect_Timeout_ShouldNotAllow() throws InterruptedException {
        when(gameConfig.getReconnectTimeoutSeconds()).thenReturn(1);

        Room room = roomManager.createRoom("Test Room", "owner1", 4, false, null);
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        roomManager.joinRoom(room.getRoomId(), player1, null);

        roomManager.setPlayerDisconnected("player1");

        Thread.sleep(1500);

        boolean reconnected = roomManager.setPlayerReconnected("player1", room.getRoomId());

        assertThat(reconnected).isFalse();
        assertThat(room.getPlayer("player1").isOnline()).isFalse();
    }

    @Test
    @DisplayName("Reconnect - non-existent player should return false")
    void reconnect_NonExistentPlayer_ShouldReturnFalse() {
        Room room = roomManager.createRoom("Test Room", "owner1", 4, false, null);

        boolean reconnected = roomManager.setPlayerReconnected("nonexistent", room.getRoomId());

        assertThat(reconnected).isFalse();
    }

    @Test
    @DisplayName("Reconnect - non-existent room should return false")
    void reconnect_NonExistentRoom_ShouldReturnFalse() {
        boolean reconnected = roomManager.setPlayerReconnected("player1", "nonexistent");

        assertThat(reconnected).isFalse();
    }

    @Test
    @DisplayName("Multiple disconnects and reconnects - should maintain correct state")
    void multipleDisconnectAndReconnect_ShouldMaintainState() {
        Room room = roomManager.createRoom("Test Room", "owner1", 4, false, null);
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");
        Player player3 = TestDataBuilder.createRoguePlayer("player3");
        Player player4 = TestDataBuilder.createPriestPlayer("player4");

        roomManager.joinRoom(room.getRoomId(), player1, null);
        roomManager.joinRoom(room.getRoomId(), player2, null);
        roomManager.joinRoom(room.getRoomId(), player3, null);
        roomManager.joinRoom(room.getRoomId(), player4, null);

        roomManager.setPlayerDisconnected("player1");
        roomManager.setPlayerDisconnected("player3");

        assertThat(room.getOnlinePlayerCount()).isEqualTo(2);
        assertThat(room.getDisconnectTimes()).hasSize(2);
        assertThat(room.getPlayer("player1").isOnline()).isFalse();
        assertThat(room.getPlayer("player3").isOnline()).isFalse();

        roomManager.setPlayerReconnected("player1", room.getRoomId());

        assertThat(room.getOnlinePlayerCount()).isEqualTo(3);
        assertThat(room.getDisconnectTimes()).hasSize(1);
        assertThat(room.getPlayer("player1").isOnline()).isTrue();
        assertThat(room.getPlayer("player3").isOnline()).isFalse();

        roomManager.setPlayerReconnected("player3", room.getRoomId());

        assertThat(room.getOnlinePlayerCount()).isEqualTo(4);
        assertThat(room.getDisconnectTimes()).isEmpty();
        assertThat(room.getPlayer("player3").isOnline()).isTrue();
        assertThat(room.isAllOnline()).isTrue();
    }

    @Test
    @DisplayName("Disconnect then new player joins - should handle correctly")
    void disconnectThenNewPlayerJoins_ShouldHandleCorrectly() {
        when(gameConfig.getMaxPlayersPerRoom()).thenReturn(4);

        Room room = roomManager.createRoom("Test Room", "owner1", 4, false, null);
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");
        Player player3 = TestDataBuilder.createRoguePlayer("player3");

        roomManager.joinRoom(room.getRoomId(), player1, null);
        roomManager.joinRoom(room.getRoomId(), player2, null);

        roomManager.setPlayerDisconnected("player2");

        assertThat(room.getOnlinePlayerCount()).isEqualTo(1);
        assertThat(room.isFull()).isFalse();

        boolean joined = roomManager.joinRoom(room.getRoomId(), player3, null);

        assertThat(joined).isTrue();
        assertThat(room.getPlayers()).hasSize(3);
        assertThat(room.getOnlinePlayerCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Concurrent disconnect and reconnect - should maintain consistency")
    void concurrentDisconnectAndReconnect_ShouldMaintainConsistency() throws InterruptedException {
        int playerCount = 10;
        Room room = roomManager.createRoom("Test Room", "owner1", 10, false, null);

        for (int i = 0; i < playerCount; i++) {
            Player player = TestDataBuilder.createWarriorPlayer("player" + i);
            roomManager.joinRoom(room.getRoomId(), player, null);
        }

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(playerCount * 2);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger disconnectCount = new AtomicInteger(0);
        AtomicInteger reconnectCount = new AtomicInteger(0);

        for (int i = 0; i < playerCount; i++) {
            final int playerIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    roomManager.setPlayerDisconnected("player" + playerIndex);
                    disconnectCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    startLatch.await();
                    Thread.sleep(10);
                    roomManager.setPlayerReconnected("player" + playerIndex, room.getRoomId());
                    reconnectCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(errorCount.get()).isEqualTo(0);
        assertThat(disconnectCount.get()).isEqualTo(playerCount);
        assertThat(reconnectCount.get()).isEqualTo(playerCount);
        assertThat(room.isAllOnline()).isTrue();
        assertThat(room.getDisconnectTimes()).isEmpty();

        executor.shutdown();
    }

    @Test
    @DisplayName("Room state snapshot - should preserve all fields during disconnect")
    void roomStateSnapshot_ShouldPreserveAllFields() {
        Room room = roomManager.createRoom("Adventure Room", "owner1", 4, true, "password123");
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        player1.setCurrentHp(50);
        player1.setMaxHp(80);
        player1.setEnergy(2);
        player1.setBlock(5);

        roomManager.joinRoom(room.getRoomId(), player1, null);
        roomManager.updateRoomStatus(room.getRoomId(), RoomStatus.BATTLE);
        room.setCurrentFloor(5);
        room.setMapSeed(12345L);

        roomManager.setPlayerDisconnected("player1");

        Player disconnectedPlayer = room.getPlayer("player1");
        assertThat(disconnectedPlayer.getCurrentHp()).isEqualTo(50);
        assertThat(disconnectedPlayer.getMaxHp()).isEqualTo(80);
        assertThat(disconnectedPlayer.getEnergy()).isEqualTo(2);
        assertThat(disconnectedPlayer.getBlock()).isEqualTo(5);
        assertThat(room.getStatus()).isEqualTo(RoomStatus.BATTLE);
        assertThat(room.getCurrentFloor()).isEqualTo(5);
        assertThat(room.getMapSeed()).isEqualTo(12345L);
        assertThat(room.getPassword()).isEqualTo("password123");
        assertThat(room.isPrivateRoom()).isTrue();

        roomManager.setPlayerReconnected("player1", room.getRoomId());

        Player reconnectedPlayer = room.getPlayer("player1");
        assertThat(reconnectedPlayer.getCurrentHp()).isEqualTo(50);
        assertThat(reconnectedPlayer.getMaxHp()).isEqualTo(80);
        assertThat(reconnectedPlayer.getEnergy()).isEqualTo(2);
        assertThat(reconnectedPlayer.getBlock()).isEqualTo(5);
        assertThat(reconnectedPlayer.isOnline()).isTrue();
    }

    @Test
    @DisplayName("Cleanup expired disconnects - should remove expired players")
    void cleanupExpiredDisconnects_ShouldRemoveExpired() throws InterruptedException {
        when(gameConfig.getReconnectTimeoutSeconds()).thenReturn(1);

        Room room = roomManager.createRoom("Test Room", "owner1", 4, false, null);
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");

        roomManager.joinRoom(room.getRoomId(), player1, null);
        roomManager.joinRoom(room.getRoomId(), player2, null);

        roomManager.setPlayerDisconnected("player1");

        Thread.sleep(1500);

        roomManager.cleanupEmptyRooms();

        assertThat(room.getPlayers()).hasSize(1);
        assertThat(room.getPlayer("player1")).isNull();
        assertThat(room.getPlayer("player2")).isNotNull();
    }

    @Test
    @DisplayName("Concurrent room operations during reconnect - should be thread safe")
    void concurrentOperationsDuringReconnect_ShouldBeThreadSafe() throws InterruptedException {
        int operationCount = 50;
        Room room = roomManager.createRoom("Test Room", "owner1", 10, false, null);

        for (int i = 0; i < 5; i++) {
            Player player = TestDataBuilder.createWarriorPlayer("player" + i);
            roomManager.joinRoom(room.getRoomId(), player, null);
        }

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(operationCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < operationCount; i++) {
            final int opIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    switch (opIndex % 4) {
                        case 0:
                            roomManager.setPlayerDisconnected("player" + (opIndex % 5));
                            break;
                        case 1:
                            roomManager.setPlayerReconnected("player" + (opIndex % 5), room.getRoomId());
                            break;
                        case 2:
                            roomManager.setPlayerReady("player" + (opIndex % 5), opIndex % 2 == 0);
                            break;
                        case 3:
                            roomManager.getOnlinePlayerCount();
                            break;
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(errorCount.get()).isEqualTo(0);
        assertThat(room.getPlayers()).hasSize(5);

        executor.shutdown();
    }
}
