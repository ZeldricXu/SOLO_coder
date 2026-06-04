package com.battle.platform.battlefield.layer;

import com.battle.platform.battlefield.AOIGrid;
import com.battle.platform.battlefield.PlayerPosition;
import com.battle.platform.battlefield.event.*;
import com.battle.platform.config.BattlefieldProperties;
import com.battle.platform.protocol.GameMessage;
import com.battle.platform.replay.ReplayRecorder;
import io.netty.channel.Channel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SyncLayer单元测试")
class SyncLayerTest {

    private static final String BATTLE_ID = "BF-sync-001";

    private BattlefieldEventBus eventBus;
    private BattlefieldProperties properties;
    private ConnectionLayer connectionLayer;
    private SyncLayer syncLayer;
    private ReplayRecorder replayRecorder;

    @BeforeEach
    void setUp() {
        eventBus = new BattlefieldEventBus();
        properties = new BattlefieldProperties();
        properties.setAoiGridSize(100);
        connectionLayer = new ConnectionLayer(BATTLE_ID, eventBus, properties);
        AOIGrid aoiGrid = new AOIGrid(properties);
        replayRecorder = mock(ReplayRecorder.class);
        syncLayer = new SyncLayer(BATTLE_ID, eventBus, properties, connectionLayer, aoiGrid, replayRecorder);

        eventBus.register(connectionLayer);
        eventBus.register(syncLayer);
    }

    @Nested
    @DisplayName("移动同步")
    class MoveSyncTest {

        @Test
        @DisplayName("玩家移动后位置更新")
        void playerMoveUpdatesPosition() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            syncLayer.onPlayerMove(1L, 10.0, 0.0, 20.0, 90.0f);

            PlayerPosition pos = syncLayer.getPlayerPosition(1L);
            assertThat(pos).isNotNull();
            assertThat(pos.getX()).isEqualTo(10.0);
            assertThat(pos.getZ()).isEqualTo(20.0);
            assertThat(pos.getRotation()).isEqualTo(90.0f);
        }

        @Test
        @DisplayName("视野内玩家收到移动广播")
        void nearbyPlayersReceiveMoveBroadcast() {
            Channel ch1 = mock(Channel.class);
            Channel ch2 = mock(Channel.class);
            when(ch1.isActive()).thenReturn(true);
            when(ch2.isActive()).thenReturn(true);

            connectionLayer.onPlayerConnect(1L, ch1);
            connectionLayer.onPlayerConnect(2L, ch2);

            syncLayer.onPlayerMove(1L, 10.0, 0.0, 10.0, 0.0f);
            syncLayer.onPlayerMove(2L, 15.0, 0.0, 15.0, 0.0f);

            syncLayer.onPlayerMove(1L, 12.0, 0.0, 12.0, 45.0f);

            verify(ch2, atLeastOnce()).writeAndFlush(any(GameMessage.class));
        }

        @Test
        @DisplayName("移动事件录制到回放")
        void moveEventRecordedForReplay() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            syncLayer.onPlayerMove(1L, 10.0, 0.0, 20.0, 0.0f);

            verify(replayRecorder).recordMoveEvent(eq(BATTLE_ID), eq(1L), any(PlayerPosition.class));
        }

        @Test
        @DisplayName("未连接玩家移动无效")
        void unknownPlayerMoveIgnored() {
            syncLayer.onPlayerMove(999L, 10.0, 0.0, 20.0, 0.0f);

            assertThat(syncLayer.getPlayerPosition(999L)).isNull();
        }
    }

    @Nested
    @DisplayName("玩家连接事件")
    class ConnectEventTest {

        @Test
        @DisplayName("连接事件触发初始位置设置")
        void connectEventSetsInitialPosition() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            PlayerPosition pos = syncLayer.getPlayerPosition(1L);
            assertThat(pos).isNotNull();
            assertThat(pos.getX()).isEqualTo(0.0);
            assertThat(pos.getZ()).isEqualTo(0.0);
        }
    }
}
