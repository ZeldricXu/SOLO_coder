package com.gamestats.realtime;

import com.gamestats.realtime.config.FlinkConfig;
import com.gamestats.realtime.config.FlinkConfig.GameTypeConfig;
import com.gamestats.realtime.model.GameEvent;
import com.gamestats.realtime.model.OnlineStats;
import com.gamestats.realtime.serialization.GameEventDeserializationSchema;
import com.gamestats.realtime.sink.InfluxDBSink;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.state.*;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.datastream.WindowedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class OnlineCountJob {
    private static final Logger LOG = LoggerFactory.getLogger(OnlineCountJob.class);
    
    private static final OutputTag<GameEvent> VIRTUAL_LOGOUT_TAG = 
            new OutputTag<GameEvent>("virtual-logout"){};

    public static void main(String[] args) throws Exception {
        FlinkConfig config = FlinkConfig.load();

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(config.getParallelism());

        if (config.isEnableCheckpointing()) {
            env.enableCheckpointing(config.getCheckpointInterval());
        }

        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("bootstrap.servers", config.getKafkaBootstrapServers());
        kafkaProps.setProperty("group.id", config.getKafkaGroupId());
        kafkaProps.setProperty("auto.offset.reset", "latest");

        FlinkKafkaConsumer<GameEvent> kafkaConsumer = new FlinkKafkaConsumer<>(
                config.getKafkaTopic(),
                new GameEventDeserializationSchema(),
                kafkaProps
        );

        DataStream<GameEvent> eventStream = env.addSource(kafkaConsumer)
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<GameEvent>forMonotonousTimestamps()
                                .withTimestampAssigner((event, timestamp) -> 
                                        parseEventTime(event.getEventTime()))
                );

        DataStream<GameEvent> heartbeatProcessedStream = eventStream
                .keyBy(GameEvent::getPlayerId)
                .process(new DynamicHeartbeatTimeoutProcessor(config))
                .name("dynamic-heartbeat-timeout-processor");

        DataStream<GameEvent> virtualLogoutStream = heartbeatProcessedStream
                .getSideOutput(VIRTUAL_LOGOUT_TAG);

        DataStream<GameEvent> allEventStream = heartbeatProcessedStream.union(virtualLogoutStream);

        LOG.info("Heartbeat timeout configuration loaded. Game type based timeout: {}", 
                config.isEnableGameTypeBasedTimeout());
        LOG.info("Default timeout: {} ms", config.getHeartbeatTimeoutMs());

        Map<String, GameTypeConfig> typeConfigs = config.getAllGameTypeConfigs();
        typeConfigs.forEach((type, cfg) -> {
            LOG.info("Game type '{}' ({}): timeout={} ms", 
                    type, cfg.getDisplayName(), cfg.getHeartbeatTimeoutMs());
        });

        KeyedStream<Tuple2<String, GameEvent>, String> keyedStream = allEventStream
                .filter(event -> 
                        "login".equals(event.getEventType()) || 
                        "logout".equals(event.getEventType()) ||
                        "heartbeat".equals(event.getEventType()))
                .flatMap(new EventTypeExtractor())
                .keyBy(tuple -> tuple.f0);

        WindowedStream<Tuple2<String, GameEvent>, String, TimeWindow> windowedStream = keyedStream
                .window(TumblingProcessingTimeWindows.of(Time.minutes(1)));

        DataStream<OnlineStats> statsStream = windowedStream
                .aggregate(new OnlineCountAggregator(), new OnlineStatsWindowFunction());

        statsStream.addSink(new InfluxDBSink(config));

        statsStream.print();

        virtualLogoutStream.print("VIRTUAL_LOGOUT");

        LOG.info("Starting OnlineCountJob with dynamic game type timeout support");
        env.execute("GameStats Online Count Job with Dynamic Game Type Heartbeat");
    }

    private static long parseEventTime(String eventTime) {
        try {
            return Instant.parse(eventTime).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    public static class DynamicHeartbeatTimeoutProcessor extends KeyedProcessFunction<String, GameEvent, GameEvent> {
        private final FlinkConfig config;
        private transient ValueState<PlayerOnlineStateV2> playerState;
        private transient ValueState<Long> timerState;
        private transient ValueState<String> gameTypeState;

        public DynamicHeartbeatTimeoutProcessor(FlinkConfig config) {
            this.config = config;
        }

        @Override
        public void open(Configuration parameters) {
            ValueStateDescriptor<PlayerOnlineStateV2> playerDescriptor =
                    new ValueStateDescriptor<>("player-online-state-v2", 
                            Types.POJO(PlayerOnlineStateV2.class));
            playerState = getRuntimeContext().getState(playerDescriptor);

            ValueStateDescriptor<Long> timerDescriptor =
                    new ValueStateDescriptor<>("timer-state", Types.LONG);
            timerState = getRuntimeContext().getState(timerDescriptor);

            ValueStateDescriptor<String> gameTypeDescriptor =
                    new ValueStateDescriptor<>("game-type-state", Types.STRING);
            gameTypeState = getRuntimeContext().getState(gameTypeDescriptor);
        }

        @Override
        public void processElement(GameEvent event, Context ctx, Collector<GameEvent> out) throws Exception {
            String playerId = ctx.getCurrentKey();
            long currentTime = ctx.timerService().currentProcessingTime();
            String gameId = event.getGameId();
            
            PlayerOnlineStateV2 state = playerState.value();
            if (state == null) {
                state = new PlayerOnlineStateV2();
                state.setPlayerId(playerId);
            }

            String eventType = event.getEventType();
            String currentGameType = gameTypeState.value();
            
            if (gameId != null && !gameId.isEmpty()) {
                GameTypeConfig typeConfig = config.getConfigForGameId(gameId);
                String gameType = typeConfig.getGameType();
                
                if (!gameType.equals(currentGameType)) {
                    LOG.debug("Player {} game type changed from {} to {} (gameId: {})", 
                            playerId, currentGameType, gameType, gameId);
                    gameTypeState.update(gameType);
                    cancelTimer(ctx);
                }
            }

            if ("login".equals(eventType)) {
                state.setOnline(true);
                state.setGameId(gameId);
                state.setServerId(event.getServerId());
                state.setLastEventTime(currentTime);
                state.setLoginTime(currentTime);
                
                GameTypeConfig typeConfig = config.getConfigForGameId(gameId);
                state.setCurrentGameType(typeConfig.getGameType());
                state.setCurrentTimeoutMs(typeConfig.getHeartbeatTimeoutMs());
                
                LOG.debug("Player {} logged in at {}, gameType={}, timeout={}ms", 
                        playerId, currentTime, typeConfig.getGameType(), typeConfig.getHeartbeatTimeoutMs());
                
                out.collect(event);
                
                scheduleTimer(ctx, currentTime, gameId);
                
            } else if ("heartbeat".equals(eventType)) {
                if (state.isOnline()) {
                    state.setLastEventTime(currentTime);
                    
                    String effectiveGameId = state.getGameId() != null ? state.getGameId() : gameId;
                    GameTypeConfig typeConfig = config.getConfigForGameId(effectiveGameId);
                    
                    LOG.debug("Player {} heartbeat received at {}, gameType={}", 
                            playerId, currentTime, typeConfig.getGameType());
                    
                    scheduleTimer(ctx, currentTime, effectiveGameId);
                }
                
            } else if ("logout".equals(eventType)) {
                if (state.isOnline()) {
                    state.setOnline(false);
                    state.setLastEventTime(currentTime);
                    state.setLogoutTime(currentTime);
                    
                    LOG.debug("Player {} logged out at {}", playerId, currentTime);
                    
                    out.collect(event);
                    
                    cancelTimer(ctx);
                }
            }

            playerState.update(state);
        }

        @Override
        public void onTimer(long timestamp, OnTimerContext ctx, Collector<GameEvent> out) throws Exception {
            PlayerOnlineStateV2 state = playerState.value();
            String gameType = gameTypeState.value();
            
            if (state != null && state.isOnline()) {
                long currentTime = ctx.timerService().currentProcessingTime();
                long timeSinceLastEvent = currentTime - state.getLastEventTime();
                
                long currentTimeout = state.getCurrentTimeoutMs() > 0 
                        ? state.getCurrentTimeoutMs() 
                        : config.getHeartbeatTimeoutMs();
                
                GameTypeConfig effectiveConfig = null;
                if (state.getGameId() != null && !state.getGameId().isEmpty()) {
                    effectiveConfig = config.getConfigForGameId(state.getGameId());
                    currentTimeout = effectiveConfig.getHeartbeatTimeoutMs();
                } else if (gameType != null) {
                    effectiveConfig = config.getGameTypeConfig(gameType);
                    currentTimeout = effectiveConfig.getHeartbeatTimeoutMs();
                }
                
                if (timeSinceLastEvent >= currentTimeout) {
                    String timeoutReason = effectiveConfig != null 
                            ? String.format("gameType=%s, timeout=%dms", 
                                    effectiveConfig.getGameType(), currentTimeout)
                            : String.format("default timeout=%dms", currentTimeout);
                    
                    LOG.warn("Player {} timeout detected. Last event: {} ms ago, {}", 
                            state.getPlayerId(), timeSinceLastEvent, timeoutReason);
                    
                    GameEvent virtualLogout = GameEvent.builder()
                            .eventId("vlogout_" + state.getPlayerId() + "_" + currentTime)
                            .playerId(state.getPlayerId())
                            .gameId(state.getGameId())
                            .serverId(state.getServerId())
                            .eventType("logout")
                            .eventTime(Instant.ofEpochMilli(currentTime).toString())
                            .eventData(new HashMap<>() {{
                                put("is_virtual", true);
                                put("reason", "timeout");
                                put("timeout_duration_ms", timeSinceLastEvent);
                                put("game_type", state.getCurrentGameType());
                                put("configured_timeout_ms", currentTimeout);
                            }})
                            .build();
                    
                    state.setOnline(false);
                    state.setLastEventTime(currentTime);
                    playerState.update(state);
                    
                    ctx.output(VIRTUAL_LOGOUT_TAG, virtualLogout);
                    
                    LOG.info("Virtual logout triggered for player {} due to timeout (gameType={})", 
                            state.getPlayerId(), state.getCurrentGameType());
                } else {
                    long nextTimeout = currentTimeout - timeSinceLastEvent;
                    LOG.debug("Player {} not yet timeout. timeSinceLastEvent={}ms < timeout={}ms, next check in {}ms",
                            state.getPlayerId(), timeSinceLastEvent, currentTimeout, nextTimeout);
                    
                    scheduleTimer(ctx, state.getLastEventTime(), state.getGameId());
                }
            }
        }

        private void scheduleTimer(Context ctx, long lastEventTime, String gameId) throws Exception {
            GameTypeConfig typeConfig = config.getConfigForGameId(gameId);
            long timeoutMs = typeConfig.getHeartbeatTimeoutMs();
            long nextTimerTime = lastEventTime + timeoutMs;
            
            Long existingTimer = timerState.value();
            if (existingTimer == null || nextTimerTime != existingTimer) {
                if (existingTimer != null) {
                    ctx.timerService().deleteProcessingTimeTimer(existingTimer);
                }
                ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
                timerState.update(nextTimerTime);
                LOG.debug("Scheduled timeout timer at {} for player {} (gameType={}, timeout={}ms)", 
                        nextTimerTime, ctx.getCurrentKey(), typeConfig.getGameType(), timeoutMs);
            }
        }

        private void cancelTimer(Context ctx) throws Exception {
            Long existingTimer = timerState.value();
            if (existingTimer != null) {
                ctx.timerService().deleteProcessingTimeTimer(existingTimer);
                timerState.clear();
                LOG.debug("Cancelled timeout timer for player {}", ctx.getCurrentKey());
            }
        }
    }

    public static class PlayerOnlineStateV2 implements java.io.Serializable {
        private String playerId;
        private String gameId;
        private String serverId;
        private String currentGameType;
        private long currentTimeoutMs;
        private boolean isOnline = false;
        private long lastEventTime = 0;
        private long loginTime = 0;
        private long logoutTime = 0;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public String getServerId() { return serverId; }
        public void setServerId(String serverId) { this.serverId = serverId; }
        public String getCurrentGameType() { return currentGameType; }
        public void setCurrentGameType(String currentGameType) { this.currentGameType = currentGameType; }
        public long getCurrentTimeoutMs() { return currentTimeoutMs; }
        public void setCurrentTimeoutMs(long currentTimeoutMs) { this.currentTimeoutMs = currentTimeoutMs; }
        public boolean isOnline() { return isOnline; }
        public void setOnline(boolean online) { isOnline = online; }
        public long getLastEventTime() { return lastEventTime; }
        public void setLastEventTime(long lastEventTime) { this.lastEventTime = lastEventTime; }
        public long getLoginTime() { return loginTime; }
        public void setLoginTime(long loginTime) { this.loginTime = loginTime; }
        public long getLogoutTime() { return logoutTime; }
        public void setLogoutTime(long logoutTime) { this.logoutTime = logoutTime; }
    }

    public static class PlayerOnlineState implements java.io.Serializable {
        private String playerId;
        private String gameId;
        private String serverId;
        private boolean isOnline = false;
        private long lastEventTime = 0;
        private long loginTime = 0;
        private long logoutTime = 0;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public String getServerId() { return serverId; }
        public void setServerId(String serverId) { this.serverId = serverId; }
        public boolean isOnline() { return isOnline; }
        public void setOnline(boolean online) { isOnline = online; }
        public long getLastEventTime() { return lastEventTime; }
        public void setLastEventTime(long lastEventTime) { this.lastEventTime = lastEventTime; }
        public long getLoginTime() { return loginTime; }
        public void setLoginTime(long loginTime) { this.loginTime = loginTime; }
        public long getLogoutTime() { return logoutTime; }
        public void setLogoutTime(long logoutTime) { this.logoutTime = logoutTime; }
    }

    public static class EventTypeExtractor extends RichFlatMapFunction<GameEvent, Tuple2<String, GameEvent>> {
        @Override
        public void flatMap(GameEvent event, Collector<Tuple2<String, GameEvent>> out) {
            String key = event.getGameId() + "_" + event.getServerId();
            out.collect(Tuple2.of(key, event));
            out.collect(Tuple2.of(event.getGameId() + "_all", event));
        }
    }

    public static class OnlineCountAggregator implements AggregateFunction<Tuple2<String, GameEvent>, OnlineCountAccumulator, OnlineCountAccumulator> {

        @Override
        public OnlineCountAccumulator createAccumulator() {
            return new OnlineCountAccumulator();
        }

        @Override
        public OnlineCountAccumulator add(Tuple2<String, GameEvent> value, OnlineCountAccumulator accumulator) {
            GameEvent event = value.f1;
            String playerId = event.getPlayerId();
            String serverId = event.getServerId();
            String gameId = event.getGameId();
            long eventTime = parseEventTime(event.getEventTime());

            if ("login".equals(event.getEventType())) {
                if (!accumulator.getActivePlayers().containsKey(playerId)) {
                    accumulator.getActivePlayers().put(playerId, 
                            new PlayerSessionInfo(serverId, eventTime));
                    accumulator.incrementGlobal();
                    accumulator.incrementServer(serverId);
                    accumulator.setGameId(gameId);
                    LOG.debug("Login processed: player={}, server={}", playerId, serverId);
                }
            } else if ("logout".equals(event.getEventType())) {
                if (accumulator.getActivePlayers().containsKey(playerId)) {
                    PlayerSessionInfo session = accumulator.getActivePlayers().remove(playerId);
                    String oldServerId = session.getServerId();
                    accumulator.decrementGlobal();
                    accumulator.decrementServer(oldServerId);
                    
                    boolean isVirtual = event.getEventData() != null && 
                            Boolean.TRUE.equals(event.getEventData().get("is_virtual"));
                    
                    LOG.debug("{} processed: player={}, server={}", 
                            isVirtual ? "Virtual logout" : "Logout", playerId, oldServerId);
                }
            } else if ("heartbeat".equals(event.getEventType())) {
                if (accumulator.getActivePlayers().containsKey(playerId)) {
                    PlayerSessionInfo session = accumulator.getActivePlayers().get(playerId);
                    session.setLastHeartbeatTime(eventTime);
                }
            }

            return accumulator;
        }

        @Override
        public OnlineCountAccumulator getResult(OnlineCountAccumulator accumulator) {
            return accumulator;
        }

        @Override
        public OnlineCountAccumulator merge(OnlineCountAccumulator a, OnlineCountAccumulator b) {
            OnlineCountAccumulator merged = new OnlineCountAccumulator();
            merged.setGameId(a.getGameId() != null ? a.getGameId() : b.getGameId());
            
            for (Map.Entry<String, PlayerSessionInfo> entry : a.getActivePlayers().entrySet()) {
                if (!merged.getActivePlayers().containsKey(entry.getKey())) {
                    merged.getActivePlayers().put(entry.getKey(), entry.getValue());
                    merged.incrementGlobal();
                    merged.incrementServer(entry.getValue().getServerId());
                }
            }
            
            for (Map.Entry<String, PlayerSessionInfo> entry : b.getActivePlayers().entrySet()) {
                if (!merged.getActivePlayers().containsKey(entry.getKey())) {
                    merged.getActivePlayers().put(entry.getKey(), entry.getValue());
                    merged.incrementGlobal();
                    merged.incrementServer(entry.getValue().getServerId());
                }
            }
            
            return merged;
        }
    }

    public static class PlayerSessionInfo implements java.io.Serializable {
        private String serverId;
        private long loginTime;
        private long lastHeartbeatTime;

        public PlayerSessionInfo() {}
        
        public PlayerSessionInfo(String serverId, long loginTime) {
            this.serverId = serverId;
            this.loginTime = loginTime;
            this.lastHeartbeatTime = loginTime;
        }

        public String getServerId() { return serverId; }
        public void setServerId(String serverId) { this.serverId = serverId; }
        public long getLoginTime() { return loginTime; }
        public void setLoginTime(long loginTime) { this.loginTime = loginTime; }
        public long getLastHeartbeatTime() { return lastHeartbeatTime; }
        public void setLastHeartbeatTime(long lastHeartbeatTime) { this.lastHeartbeatTime = lastHeartbeatTime; }
    }

    public static class OnlineCountAccumulator implements java.io.Serializable {
        private String gameId;
        private int globalCount = 0;
        private Map<String, Integer> serverCounts = new HashMap<>();
        private Map<String, PlayerSessionInfo> activePlayers = new HashMap<>();

        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public int getGlobalCount() { return globalCount; }
        public Map<String, Integer> getServerCounts() { return serverCounts; }
        public Map<String, PlayerSessionInfo> getActivePlayers() { return activePlayers; }

        public void incrementGlobal() { globalCount++; }
        public void decrementGlobal() { if (globalCount > 0) globalCount--; }
        public void incrementServer(String serverId) {
            serverCounts.put(serverId, serverCounts.getOrDefault(serverId, 0) + 1);
        }
        public void decrementServer(String serverId) {
            int current = serverCounts.getOrDefault(serverId, 0);
            if (current > 0) {
                serverCounts.put(serverId, current - 1);
            }
        }
    }

    public static class OnlineStatsWindowFunction extends ProcessWindowFunction<OnlineCountAccumulator, OnlineStats, String, TimeWindow> {
        private transient ValueState<Integer> peakTodayState;
        private transient MapState<String, Boolean> playerState;

        @Override
        public void open(Configuration parameters) {
            ValueStateDescriptor<Integer> peakDescriptor = new ValueStateDescriptor<>(
                    "peak-today", Types.INT, 0
            );
            peakTodayState = getRuntimeContext().getState(peakDescriptor);

            MapStateDescriptor<String, Boolean> playerDescriptor = new MapStateDescriptor<>(
                    "active-players", Types.STRING, Types.BOOLEAN
            );
            playerState = getRuntimeContext().getMapState(playerDescriptor);
        }

        @Override
        public void process(String key, Context context, Iterable<OnlineCountAccumulator> elements, Collector<OnlineStats> out) throws Exception {
            OnlineCountAccumulator accumulator = elements.iterator().next();
            long windowEnd = context.window().getEnd();
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

            String[] keyParts = key.split("_", 2);
            String gameId = keyParts[0];

            int currentPeak = peakTodayState.value();
            if (accumulator.getGlobalCount() > currentPeak) {
                peakTodayState.update(accumulator.getGlobalCount());
                currentPeak = accumulator.getGlobalCount();
            }

            Map<String, Integer> serverDist = new HashMap<>(accumulator.getServerCounts());
            accumulator.getActivePlayers().forEach((playerId, session) -> {
                String sid = session.getServerId();
                if (!serverDist.containsKey(sid)) {
                    serverDist.put(sid, serverDist.getOrDefault(sid, 0));
                }
            });

            OnlineStats stats = OnlineStats.builder()
                    .statId("online_" + now.format(formatter))
                    .gameId(gameId)
                    .onlineCount(accumulator.getGlobalCount())
                    .serverDistribution(serverDist)
                    .sampleTime(windowEnd)
                    .peakToday(currentPeak)
                    .build();

            LOG.info("Generated online stats: gameId={}, onlineCount={}, peakToday={}, activePlayers={}",
                    gameId, stats.getOnlineCount(), stats.getPeakToday(), accumulator.getActivePlayers().size());

            out.collect(stats);
        }
    }
}
