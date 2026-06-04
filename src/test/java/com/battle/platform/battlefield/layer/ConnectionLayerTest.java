package com.battle.platform.battlefield.layer;

import com.battle.platform.battlefield.event.*;
import com.battle.platform.config.BattlefieldProperties;
import com.google.common.eventbus.Subscribe;
import io.netty.channel.Channel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConnectionLayer单元测试")
class ConnectionLayerTest {

    private static final String BATTLE_ID = "BF-test-001";

    private BattlefieldEventBus eventBus;
    private BattlefieldProperties properties;
    private ConnectionLayer connectionLayer;

    @BeforeEach
    void setUp() {
        eventBus = new BattlefieldEventBus();
        properties = new BattlefieldProperties();
        properties.setIdleTimeoutMs(30000);
        connectionLayer = new ConnectionLayer(BATTLE_ID, eventBus, properties);
        eventBus.register(connectionLayer);
    }

    @Nested
    @DisplayName("玩家连接管理")
    class PlayerConnectTest {

        @Test
        @DisplayName("玩家连接后状态为connected")
        void playerConnectedAfterJoin() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);

            connectionLayer.onPlayerConnect(1L, ch);

            assertThat(connectionLayer.isConnected(1L)).isTrue();
            assertThat(connectionLayer.isDisconnected(1L)).isFalse();
            assertThat(connectionLayer.getConnectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("玩家连接后发放重连token")
        void reconnectTokenGeneratedOnConnect() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);

            connectionLayer.onPlayerConnect(1L, ch);

            assertThat(connectionLayer.getReconnectToken(1L)).isNotNull();
        }

        @Test
        @DisplayName("玩家连接后触发PlayerConnectedEvent")
        void playerConnectedEventFired() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<PlayerConnectedEvent> captured = new AtomicReference<>();

            Object listener = new Object() {
                @Subscribe
                public void onEvent(PlayerConnectedEvent event) {
                    captured.set(event);
                    latch.countDown();
                }
            };
            eventBus.register(listener);

            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get().getPlayerId()).isEqualTo(1L);
            assertThat(captured.get().getBattleId()).isEqualTo(BATTLE_ID);

            eventBus.unregister(listener);
        }

        @Test
        @DisplayName("多个玩家连接计数正确")
        void multiplePlayersConnect() {
            Channel ch1 = mock(Channel.class);
            Channel ch2 = mock(Channel.class);
            when(ch1.isActive()).thenReturn(true);
            when(ch2.isActive()).thenReturn(true);

            connectionLayer.onPlayerConnect(1L, ch1);
            connectionLayer.onPlayerConnect(2L, ch2);

            assertThat(connectionLayer.getConnectedCount()).isEqualTo(2);
            assertThat(connectionLayer.getConnectedPlayers()).containsExactlyInAnyOrder(1L, 2L);
        }
    }

    @Nested
    @DisplayName("玩家断线")
    class PlayerDisconnectTest {

        @Test
        @DisplayName("玩家断线后状态为disconnected")
        void playerDisconnectedAfterLeave() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            connectionLayer.onPlayerDisconnect(1L, "client_close");

            assertThat(connectionLayer.isConnected(1L)).isFalse();
            assertThat(connectionLayer.isDisconnected(1L)).isTrue();
        }

        @Test
        @DisplayName("断线后重连token保留供重连校验")
        void reconnectTokenRetainedAfterDisconnect() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);
            String token = connectionLayer.getReconnectToken(1L);

            connectionLayer.onPlayerDisconnect(1L, "client_close");

            assertThat(connectionLayer.getReconnectToken(1L)).isEqualTo(token);
        }

        @Test
        @DisplayName("断线后触发PlayerDisconnectedEvent")
        void playerDisconnectedEventFired() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<PlayerDisconnectedEvent> captured = new AtomicReference<>();

            Object listener = new Object() {
                @Subscribe
                public void onEvent(PlayerDisconnectedEvent event) {
                    captured.set(event);
                    latch.countDown();
                }
            };
            eventBus.register(listener);

            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);
            connectionLayer.onPlayerDisconnect(1L, "timeout");

            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get().getPlayerId()).isEqualTo(1L);
            assertThat(captured.get().getReason()).isEqualTo("timeout");

            eventBus.unregister(listener);
        }

        @Test
        @DisplayName("断线后连接数减少")
        void connectedCountDecreasesAfterDisconnect() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            connectionLayer.onPlayerDisconnect(1L, "client_close");

            assertThat(connectionLayer.getConnectedCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("重连校验")
    class ReconnectTest {

        @Test
        @DisplayName("正确token可以重连成功")
        void reconnectWithValidTokenSucceeds() {
            Channel ch1 = mock(Channel.class);
            when(ch1.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch1);

            String token = connectionLayer.getReconnectToken(1L);
            connectionLayer.onPlayerDisconnect(1L, "timeout");

            Channel ch2 = mock(Channel.class);
            when(ch2.isActive()).thenReturn(true);
            boolean result = connectionLayer.onReconnect(1L, token, ch2);

            assertThat(result).isTrue();
            assertThat(connectionLayer.isConnected(1L)).isTrue();
            assertThat(connectionLayer.isDisconnected(1L)).isFalse();
        }

        @Test
        @DisplayName("错误token重连失败")
        void reconnectWithInvalidTokenFails() {
            Channel ch1 = mock(Channel.class);
            when(ch1.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch1);

            connectionLayer.onPlayerDisconnect(1L, "timeout");

            Channel ch2 = mock(Channel.class);
            boolean result = connectionLayer.onReconnect(1L, "invalid-token", ch2);

            assertThat(result).isFalse();
            assertThat(connectionLayer.isConnected(1L)).isFalse();
        }

        @Test
        @DisplayName("重连后生成新的token（旧token失效）")
        void newTokenGeneratedAfterReconnect() {
            Channel ch1 = mock(Channel.class);
            when(ch1.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch1);

            String oldToken = connectionLayer.getReconnectToken(1L);
            connectionLayer.onPlayerDisconnect(1L, "timeout");

            Channel ch2 = mock(Channel.class);
            when(ch2.isActive()).thenReturn(true);
            connectionLayer.onReconnect(1L, oldToken, ch2);

            String newToken = connectionLayer.getReconnectToken(1L);
            assertThat(newToken).isNotEqualTo(oldToken);
        }

        @Test
        @DisplayName("用旧token再次重连失败")
        void oldTokenCannotReuseAfterReconnect() {
            Channel ch1 = mock(Channel.class);
            when(ch1.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch1);

            String token1 = connectionLayer.getReconnectToken(1L);
            connectionLayer.onPlayerDisconnect(1L, "timeout");

            Channel ch2 = mock(Channel.class);
            when(ch2.isActive()).thenReturn(true);
            connectionLayer.onReconnect(1L, token1, ch2);

            connectionLayer.onPlayerDisconnect(1L, "timeout2");

            Channel ch3 = mock(Channel.class);
            boolean result = connectionLayer.onReconnect(1L, token1, ch3);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("未断线状态下不能重连")
        void cannotReconnectWhileConnected() {
            Channel ch1 = mock(Channel.class);
            when(ch1.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch1);

            String token = connectionLayer.getReconnectToken(1L);

            Channel ch2 = mock(Channel.class);
            boolean result = connectionLayer.onReconnect(1L, token, ch2);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("重连成功触发PlayerReconnectedEvent")
        void reconnectEventFired() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<PlayerReconnectedEvent> captured = new AtomicReference<>();

            Object listener = new Object() {
                @Subscribe
                public void onEvent(PlayerReconnectedEvent event) {
                    captured.set(event);
                    latch.countDown();
                }
            };
            eventBus.register(listener);

            Channel ch1 = mock(Channel.class);
            when(ch1.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch1);
            String token = connectionLayer.getReconnectToken(1L);
            connectionLayer.onPlayerDisconnect(1L, "timeout");

            Channel ch2 = mock(Channel.class);
            when(ch2.isActive()).thenReturn(true);
            connectionLayer.onReconnect(1L, token, ch2);

            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get().getPlayerId()).isEqualTo(1L);
            assertThat(captured.get().getChannel()).isEqualTo(ch2);

            eventBus.unregister(listener);
        }
    }

    @Nested
    @DisplayName("心跳超时检测")
    class HeartbeatTimeoutTest {

        @Test
        @DisplayName("正常心跳不会触发超时")
        void normalHeartbeatNoTimeout() {
            properties.setIdleTimeoutMs(60000);
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            connectionLayer.onHeartbeat(1L);
            connectionLayer.checkHeartbeatTimeouts();

            assertThat(connectionLayer.isConnected(1L)).isTrue();
        }

        @Test
        @DisplayName("心跳超时后断开连接")
        void heartbeatTimeoutDisconnectsPlayer() {
            properties.setIdleTimeoutMs(100);
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {}

            connectionLayer.checkHeartbeatTimeouts();

            assertThat(connectionLayer.isConnected(1L)).isFalse();
            assertThat(connectionLayer.isDisconnected(1L)).isTrue();
        }

        @Test
        @DisplayName("心跳超时触发HeartbeatTimeoutEvent")
        void heartbeatTimeoutEventFired() throws InterruptedException {
            properties.setIdleTimeoutMs(100);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<HeartbeatTimeoutEvent> captured = new AtomicReference<>();

            Object listener = new Object() {
                @Subscribe
                public void onEvent(HeartbeatTimeoutEvent event) {
                    captured.set(event);
                    latch.countDown();
                }
            };
            eventBus.register(listener);

            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {}

            connectionLayer.checkHeartbeatTimeouts();

            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get().getPlayerId()).isEqualTo(1L);
            assertThat(captured.get().getBattleId()).isEqualTo(BATTLE_ID);

            eventBus.unregister(listener);
        }

        @Test
        @DisplayName("心跳续约不会超时")
        void heartbeatRenewalPreventsTimeout() {
            properties.setIdleTimeoutMs(200);
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}

            connectionLayer.onHeartbeat(1L);

            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}

            connectionLayer.checkHeartbeatTimeouts();

            assertThat(connectionLayer.isConnected(1L)).isTrue();
        }
    }

    @Nested
    @DisplayName("消息发送")
    class MessageSendTest {

        @Test
        @DisplayName("sendToPlayer向已连接玩家发送消息")
        void sendToConnectedPlayer() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            com.battle.platform.protocol.GameMessage msg = com.battle.platform.protocol.GameMessage.builder()
                    .msgId(1).msgType(1).playerId(1L).timestamp(System.currentTimeMillis()).build();
            connectionLayer.sendToPlayer(1L, msg);

            verify(ch).writeAndFlush(msg);
        }

        @Test
        @DisplayName("sendToPlayer对未连接玩家不报错")
        void sendToNonConnectedPlayerNoError() {
            com.battle.platform.protocol.GameMessage msg = com.battle.platform.protocol.GameMessage.builder()
                    .msgId(1).msgType(1).playerId(99L).timestamp(System.currentTimeMillis()).build();
            connectionLayer.sendToPlayer(99L, msg);
        }
    }

    @Nested
    @DisplayName("removePlayer清理")
    class RemovePlayerTest {

        @Test
        @DisplayName("removePlayer彻底清除玩家所有状态")
        void removePlayerClearsAllState() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);

            connectionLayer.removePlayer(1L);

            assertThat(connectionLayer.isConnected(1L)).isFalse();
            assertThat(connectionLayer.isDisconnected(1L)).isFalse();
            assertThat(connectionLayer.getReconnectToken(1L)).isNull();
            assertThat(connectionLayer.getPlayerChannel(1L)).isNull();
        }

        @Test
        @DisplayName("removePlayer后重连token失效")
        void noReconnectAfterRemove() {
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            connectionLayer.onPlayerConnect(1L, ch);
            String token = connectionLayer.getReconnectToken(1L);

            connectionLayer.onPlayerDisconnect(1L, "kick");
            connectionLayer.removePlayer(1L);

            Channel ch2 = mock(Channel.class);
            when(ch2.isActive()).thenReturn(true);
            boolean result = connectionLayer.onReconnect(1L, token, ch2);

            assertThat(result).isFalse();
        }
    }
}
