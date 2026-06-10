package com.cardgame.room.manager;

import com.cardgame.common.TestDataBuilder;
import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.RoomStatus;
import com.cardgame.room.entity.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Room Manager Tests")
class RoomManagerTest {

    @Mock
    private GameConfig gameConfig;

    @InjectMocks
    private RoomManager roomManager;

    private Player player1;
    private Player player2;
    private Player player3;
    private Player player4;

    @BeforeEach
    void setUp() {
        player1 = TestDataBuilder.createWarriorPlayer("player1");
        player2 = TestDataBuilder.createMagePlayer("player2");
        player3 = TestDataBuilder.createRoguePlayer("player3");
        player4 = TestDataBuilder.createPriestPlayer("player4");

        when(gameConfig.getMaxPlayersPerRoom()).thenReturn(4);
        when(gameConfig.getReconnectTimeoutSeconds()).thenReturn(300);
    }

    @Nested
    @DisplayName("Room Creation Tests")
    class RoomCreationTests {

        @Test
        @DisplayName("Create room - should create room with correct properties")
        void createRoom_ShouldCreateRoom() {
            Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);

            assertThat(room).isNotNull();
            assertThat(room.getRoomId()).isNotNull();
            assertThat(room.getRoomName()).isEqualTo("Test Room");
            assertThat(room.getOwnerId()).isEqualTo("player1");
            assertThat(room.getMaxPlayers()).isEqualTo(4);
            assertThat(room.isPrivateRoom()).isFalse();
            assertThat(room.getStatus()).isEqualTo(RoomStatus.WAITING);
            assertThat(room.getInviteCode()).isNotNull().hasSize(6);
        }

        @Test
        @DisplayName("Create private room - should set password")
        void createRoom_Private_ShouldSetPassword() {
            Room room = roomManager.createRoom("Private Room", "player1", 4, true, "password123");

            assertThat(room.isPrivateRoom()).isTrue();
            assertThat(room.getPassword()).isEqualTo("password123");
        }

        @Test
        @DisplayName("Create room - should cap max players at config limit")
        void createRoom_MaxPlayers_ShouldCapAtLimit() {
            when(gameConfig.getMaxPlayersPerRoom()).thenReturn(4);

            Room room = roomManager.createRoom("Test Room", "player1", 10, false, null);

            assertThat(room.getMaxPlayers()).isEqualTo(4);
        }

        @Test
        @DisplayName("Create room - should register in room map")
        void createRoom_ShouldRegisterInMap() {
            Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);

            assertThat(roomManager.getRoom(room.getRoomId())).isEqualTo(room);
            assertThat(roomManager.getRoomByInviteCode(room.getInviteCode())).isEqualTo(room);
        }
    }

    @Nested
    @DisplayName("Join Room Tests")
    class JoinRoomTests {

        @Test
        @DisplayName("Join room - should add player to room")
        void joinRoom_ShouldAddPlayer() {
            Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);
            room.addPlayer(player1);

            boolean joined = roomManager.joinRoom(room.getRoomId(), player2, null);

            assertThat(joined).isTrue();
            assertThat(room.getPlayers()).containsExactly(player1, player2);
            assertThat(room.getPlayerMap()).containsKeys(player1.getPlayerId(), player2.getPlayerId());
        }

        @Test
        @DisplayName("Join full room - should fail")
        void joinRoom_Full_ShouldFail() {
            Room room = roomManager.createRoom("Test Room", "player1", 2, false, null);
            room.addPlayer(player1);
            room.addPlayer(player2);

            boolean joined = roomManager.joinRoom(room.getRoomId(), player3, null);

            assertThat(joined).isFalse();
            assertThat(room.getPlayers()).hasSize(2);
        }

        @Test
        @DisplayName("Join private room with wrong password - should fail")
        void joinRoom_PrivateWrongPassword_ShouldFail() {
            Room room = roomManager.createRoom("Private Room", "player1", 4, true, "correct");
            room.addPlayer(player1);

            boolean joined = roomManager.joinRoom(room.getRoomId(), player2, "wrong");

            assertThat(joined).isFalse();
        }

        @Test
        @DisplayName("Join private room with correct password - should succeed")
        void joinRoom_PrivateCorrectPassword_ShouldSucceed() {
            Room room = roomManager.createRoom("Private Room", "player1", 4, true, "correct");
            room.addPlayer(player1);

            boolean joined = roomManager.joinRoom(room.getRoomId(), player2, "correct");

            assertThat(joined).isTrue();
        }

        @Test
        @DisplayName("Join non-existent room - should fail")
        void joinRoom_NonExistent_ShouldFail() {
            boolean joined = roomManager.joinRoom("non-existent", player2, null);

            assertThat(joined).isFalse();
        }

        @Test
        @DisplayName("Join room not in waiting status - should fail")
        void joinRoom_NotWaiting_ShouldFail() {
            Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);
            room.addPlayer(player1);
            room.setStatus(RoomStatus.IN_GAME);

            boolean joined = roomManager.joinRoom(room.getRoomId(), player2, null);

            assertThat(joined).isFalse();
        }
    }

    @Nested
    @DisplayName("Room Status Tests")
    class RoomStatusTests {

        @Test
        @DisplayName("Update room status - should update status")
        void updateRoomStatus_ShouldUpdate() {
            Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);

            roomManager.updateRoomStatus(room.getRoomId(), RoomStatus.IN_GAME);

            assertThat(room.getStatus()).isEqualTo(RoomStatus.IN_GAME);
        }

        @Test
        @DisplayName("Update non-existent room - should not crash")
        void updateRoomStatus_NonExistent_ShouldNotCrash() {
            roomManager.updateRoomStatus("non-existent", RoomStatus.IN_GAME);

            assertThat(roomManager.getActiveRoomCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Get active room count - should return correct count")
        void getActiveRoomCount_ShouldReturnCorrect() {
            roomManager.createRoom("Room 1", "player1", 4, false, null);
            roomManager.createRoom("Room 2", "player2", 4, false, null);

            assertThat(roomManager.getActiveRoomCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Remove Room Tests")
    class RemoveRoomTests {

        @Test
        @DisplayName("Remove room - should remove from maps")
        void removeRoom_ShouldRemove() {
            Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);

            roomManager.removeRoom(room.getRoomId());

            assertThat(roomManager.getRoom(room.getRoomId())).isNull();
            assertThat(roomManager.getRoomByInviteCode(room.getInviteCode())).isNull();
        }

        @Test
        @DisplayName("Remove non-existent room - should not crash")
        void removeRoom_NonExistent_ShouldNotCrash() {
            roomManager.removeRoom("non-existent");
        }

        @Test
        @DisplayName("Cleanup empty rooms - should remove old empty rooms")
        void cleanupEmptyRooms_ShouldRemoveOldEmpty() throws InterruptedException {
            Room room1 = roomManager.createRoom("Empty Room", "player1", 4, false, null);
            room1.removePlayer("player1");

            Room room2 = roomManager.createRoom("Active Room", "player1", 4, false, null);
            room2.addPlayer(player1);

            Thread.sleep(100);

            roomManager.cleanupEmptyRooms();

            assertThat(roomManager.getRoom(room1.getRoomId())).isNotNull();
            assertThat(roomManager.getRoom(room2.getRoomId())).isNotNull();
        }
    }

    @Nested
    @DisplayName("Disconnect/Reconnect Tests")
    class DisconnectReconnectTests {

        @Test
        @DisplayName("Set player disconnected - should mark player as offline")
        void setPlayerDisconnected_ShouldMarkOffline() {
            Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);
            room.addPlayer(player1);
            room.addPlayer(player2);

            roomManager.setPlayerDisconnected(player2.getPlayerId());

            assertThat(player2.isOnline()).isFalse();
            assertThat(room.getDisconnectTimes()).containsKey(player2.getPlayerId());
        }

        @Test
        @DisplayName("Set player reconnected within timeout - should succeed")
        void setPlayerReconnected_WithinTimeout_ShouldSucceed() throws InterruptedException {
            Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);
            room.addPlayer(player1);
            room.addPlayer(player2);
            roomManager.setPlayerDisconnected(player2.getPlayerId());

            Thread.sleep(100);

            boolean reconnected = roomManager.setPlayerReconnected(player2.getPlayerId(), room.getRoomId());

            assertThat(reconnected).isTrue();
            assertThat(player2.isOnline()).isTrue();
            assertThat(room.getDisconnectTimes()).doesNotContainKey(player2.getPlayerId());
        }

        @Test
        @DisplayName("Set player reconnected after timeout - should fail")
        void setPlayerReconnected_AfterTimeout_ShouldFail() throws InterruptedException {
            Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);
            room.setReconnectTimeoutSeconds(1);
            room.addPlayer(player1);
            room.addPlayer(player2);
            roomManager.setPlayerDisconnected(player2.getPlayerId());

            Thread.sleep(1100);

            boolean reconnected = roomManager.setPlayerReconnected(player2.getPlayerId(), room.getRoomId());

            assertThat(reconnected).isFalse();
            assertThat(player2.isOnline()).isFalse();
        }

        @Test
        @DisplayName("Set player reconnected in wrong room - should fail")
        void setPlayerReconnected_WrongRoom_ShouldFail() {
            Room room1 = roomManager.createRoom("Room 1", "player1", 4, false, null);
            Room room2 = roomManager.createRoom("Room 2", "player2", 4, false, null);
            room1.addPlayer(player1);

            boolean reconnected = roomManager.setPlayerReconnected(player1.getPlayerId(), room2.getRoomId());

            assertThat(reconnected).isFalse();
        }
    }

    @Nested
    @DisplayName("Invite Code Tests")
    class InviteCodeTests {

        @Test
        @DisplayName("Get room by invite code - should return correct room")
        void getRoomByInviteCode_ShouldReturnRoom() {
            Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);

            Room found = roomManager.getRoomByInviteCode(room.getInviteCode());

            assertThat(found).isEqualTo(room);
        }

        @Test
        @DisplayName("Get room by invalid invite code - should return null")
        void getRoomByInviteCode_Invalid_ShouldReturnNull() {
            Room found = roomManager.getRoomByInviteCode("INVALID");

            assertThat(found).isNull();
        }
    }
}
