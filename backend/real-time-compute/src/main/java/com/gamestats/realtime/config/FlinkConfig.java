package com.gamestats.realtime.config;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Data
public class FlinkConfig {
    private String kafkaBootstrapServers = "localhost:9092";
    private String kafkaTopic = "game-events";
    private String kafkaGroupId = "gamestats-flink-consumer";
    
    private String influxDbUrl = "http://localhost:8086";
    private String influxDbToken = "my-token";
    private String influxDbOrg = "gamestats";
    private String influxDbBucket = "events";
    
    private int checkpointInterval = 60000;
    private int parallelism = 2;
    private boolean enableCheckpointing = true;
    
    private long heartbeatTimeoutMs = 120000;
    private long heartbeatCheckIntervalMs = 30000;
    
    private Map<String, GameTypeConfig> gameTypeConfigs = new HashMap<>();
    private Map<String, String> gameIdToTypeMapping = new HashMap<>();
    private boolean enableGameTypeBasedTimeout = true;
    
    public static class GameTypeConfig {
        private String gameType;
        private String displayName;
        private long heartbeatTimeoutMs;
        private long heartbeatCheckIntervalMs;
        private String description;
        
        public GameTypeConfig() {}
        
        public GameTypeConfig(String gameType, long heartbeatTimeoutMs) {
            this.gameType = gameType;
            this.heartbeatTimeoutMs = heartbeatTimeoutMs;
            this.heartbeatCheckIntervalMs = Math.min(heartbeatTimeoutMs / 4, 30000);
        }
        
        public String getGameType() { return gameType; }
        public void setGameType(String gameType) { this.gameType = gameType; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public long getHeartbeatTimeoutMs() { return heartbeatTimeoutMs; }
        public void setHeartbeatTimeoutMs(long heartbeatTimeoutMs) { this.heartbeatTimeoutMs = heartbeatTimeoutMs; }
        public long getHeartbeatCheckIntervalMs() { return heartbeatCheckIntervalMs; }
        public void setHeartbeatCheckIntervalMs(long heartbeatCheckIntervalMs) { this.heartbeatCheckIntervalMs = heartbeatCheckIntervalMs; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
    
    public static FlinkConfig load() {
        FlinkConfig config = new FlinkConfig();
        
        String kafkaServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (kafkaServers != null && !kafkaServers.isEmpty()) {
            config.setKafkaBootstrapServers(kafkaServers);
        }
        
        String kafkaTopic = System.getenv("KAFKA_TOPIC");
        if (kafkaTopic != null && !kafkaTopic.isEmpty()) {
            config.setKafkaTopic(kafkaTopic);
        }
        
        String influxUrl = System.getenv("INFLUXDB_URL");
        if (influxUrl != null && !influxUrl.isEmpty()) {
            config.setInfluxDbUrl(influxUrl);
        }
        
        String influxToken = System.getenv("INFLUXDB_TOKEN");
        if (influxToken != null && !influxToken.isEmpty()) {
            config.setInfluxDbToken(influxToken);
        }
        
        String parallelismStr = System.getenv("FLINK_PARALLELISM");
        if (parallelismStr != null && !parallelismStr.isEmpty()) {
            config.setParallelism(Integer.parseInt(parallelismStr));
        }
        
        String timeoutStr = System.getenv("HEARTBEAT_TIMEOUT_MS");
        if (timeoutStr != null && !timeoutStr.isEmpty()) {
            config.setHeartbeatTimeoutMs(Long.parseLong(timeoutStr));
        }
        
        String checkIntervalStr = System.getenv("HEARTBEAT_CHECK_INTERVAL_MS");
        if (checkIntervalStr != null && !checkIntervalStr.isEmpty()) {
            config.setHeartbeatCheckIntervalMs(Long.parseLong(checkIntervalStr));
        }
        
        String enableGameTypeTimeout = System.getenv("ENABLE_GAME_TYPE_TIMEOUT");
        if (enableGameTypeTimeout != null && !enableGameTypeTimeout.isEmpty()) {
            config.setEnableGameTypeBasedTimeout(Boolean.parseBoolean(enableGameTypeTimeout));
        }
        
        config.initDefaultGameTypeConfigs();
        
        config.loadGameTypeMappingsFromEnv();
        
        config.loadGameTypeConfigsFromEnv();
        
        return config;
    }
    
    private void initDefaultGameTypeConfigs() {
        GameTypeConfig casualConfig = new GameTypeConfig("casual", 900000);
        casualConfig.setDisplayName("休闲游戏");
        casualConfig.setDescription("三消、卡牌、模拟经营等不需要实时对战的游戏，设置较长超时时间（15分钟）");
        gameTypeConfigs.put("casual", casualConfig);
        
        GameTypeConfig competitiveConfig = new GameTypeConfig("competitive", 180000);
        competitiveConfig.setDisplayName("竞技游戏");
        competitiveConfig.setDescription("MOBA、FPS、RTS等需要实时对战的游戏，设置较短超时时间（3分钟）");
        gameTypeConfigs.put("competitive", competitiveConfig);
        
        GameTypeConfig mmorpgConfig = new GameTypeConfig("mmorpg", 300000);
        mmorpgConfig.setDisplayName("大型多人在线");
        mmorpgConfig.setDescription("MMORPG等需要持续在线的游戏，设置中等超时时间（5分钟）");
        gameTypeConfigs.put("mmorpg", mmorpgConfig);
        
        GameTypeConfig puzzleConfig = new GameTypeConfig("puzzle", 600000);
        puzzleConfig.setDisplayName("解谜游戏");
        puzzleConfig.setDescription("解谜、益智类游戏，用户可能长时间思考（10分钟）");
        gameTypeConfigs.put("puzzle", puzzleConfig);
        
        GameTypeConfig arcadeConfig = new GameTypeConfig("arcade", 120000);
        arcadeConfig.setDisplayName("街机游戏");
        arcadeConfig.setDescription("快节奏街机游戏，需要较快的超时检测（2分钟）");
        gameTypeConfigs.put("arcade", arcadeConfig);
        
        GameTypeConfig strategyConfig = new GameTypeConfig("strategy", 420000);
        strategyConfig.setDisplayName("策略游戏");
        strategyConfig.setDescription("回合制策略游戏，思考时间较长（7分钟）");
        gameTypeConfigs.put("strategy", strategyConfig);
        
        GameTypeConfig defaultConfig = new GameTypeConfig("default", 120000);
        defaultConfig.setDisplayName("默认配置");
        defaultConfig.setDescription("未指定游戏类型时使用的默认配置（2分钟）");
        gameTypeConfigs.put("default", defaultConfig);
    }
    
    private void loadGameTypeMappingsFromEnv() {
        String mappingsStr = System.getenv("GAME_ID_TYPE_MAPPINGS");
        if (mappingsStr == null || mappingsStr.isEmpty()) {
            return;
        }
        
        String[] mappings = mappingsStr.split(";");
        for (String mapping : mappings) {
            String[] parts = mapping.split(":");
            if (parts.length == 2) {
                String gameId = parts[0].trim();
                String gameType = parts[1].trim();
                gameIdToTypeMapping.put(gameId, gameType);
            }
        }
    }
    
    private void loadGameTypeConfigsFromEnv() {
        String configsStr = System.getenv("GAME_TYPE_CONFIGS");
        if (configsStr == null || configsStr.isEmpty()) {
            return;
        }
        
        String[] configs = configsStr.split(";");
        for (String configStr : configs) {
            String[] parts = configStr.split(":");
            if (parts.length >= 2) {
                String gameType = parts[0].trim();
                try {
                    long timeoutMs = Long.parseLong(parts[1].trim());
                    GameTypeConfig config = new GameTypeConfig(gameType, timeoutMs);
                    if (parts.length >= 3) {
                        config.setDisplayName(parts[2].trim());
                    }
                    gameTypeConfigs.put(gameType, config);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid timeout value for game type: " + gameType);
                }
            }
        }
    }
    
    public GameTypeConfig getGameTypeConfig(String gameType) {
        if (gameType == null || gameType.isEmpty()) {
            return gameTypeConfigs.get("default");
        }
        
        GameTypeConfig config = gameTypeConfigs.get(gameType.toLowerCase());
        if (config == null) {
            return gameTypeConfigs.get("default");
        }
        return config;
    }
    
    public GameTypeConfig getConfigForGameId(String gameId) {
        if (!enableGameTypeBasedTimeout) {
            return getGameTypeConfig("default");
        }
        
        String gameType = gameIdToTypeMapping.get(gameId);
        if (gameType != null) {
            return getGameTypeConfig(gameType);
        }
        
        gameType = inferGameTypeFromGameId(gameId);
        if (gameType != null) {
            return getGameTypeConfig(gameType);
        }
        
        return getGameTypeConfig("default");
    }
    
    public long getTimeoutForGameId(String gameId) {
        return getConfigForGameId(gameId).getHeartbeatTimeoutMs();
    }
    
    public long getCheckIntervalForGameId(String gameId) {
        return getConfigForGameId(gameId).getHeartbeatCheckIntervalMs();
    }
    
    private String inferGameTypeFromGameId(String gameId) {
        if (gameId == null) {
            return null;
        }
        
        String lowerId = gameId.toLowerCase();
        
        if (lowerId.contains("casual") || lowerId.contains("match3") || 
            lowerId.contains("card") || lowerId.contains("sim") ||
            lowerId.contains("puzzle") || lowerId.contains("word")) {
            return "casual";
        }
        
        if (lowerId.contains("moba") || lowerId.contains("fps") || 
            lowerId.contains("rts") || lowerId.contains("battle") ||
            lowerId.contains("arena") || lowerId.contains("competitive") ||
            lowerId.contains("war") || lowerId.contains("fight")) {
            return "competitive";
        }
        
        if (lowerId.contains("mmo") || lowerId.contains("rpg") || 
            lowerId.contains("online") || lowerId.contains("world")) {
            return "mmorpg";
        }
        
        if (lowerId.contains("strategy") || lowerId.contains("turn") || 
            lowerId.contains("4x") || lowerId.contains("civ")) {
            return "strategy";
        }
        
        if (lowerId.contains("arcade") || lowerId.contains("runner") || 
            lowerId.contains("shooter") || lowerId.contains("action")) {
            return "arcade";
        }
        
        return null;
    }
    
    public void registerGameIdToType(String gameId, String gameType) {
        gameIdToTypeMapping.put(gameId, gameType);
    }
    
    public void addGameTypeConfig(GameTypeConfig config) {
        gameTypeConfigs.put(config.getGameType(), config);
    }
    
    public Map<String, GameTypeConfig> getAllGameTypeConfigs() {
        return new HashMap<>(gameTypeConfigs);
    }
    
    public Map<String, String> getAllGameIdMappings() {
        return new HashMap<>(gameIdToTypeMapping);
    }
}
