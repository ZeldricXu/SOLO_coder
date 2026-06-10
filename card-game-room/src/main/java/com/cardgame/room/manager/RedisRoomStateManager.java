package com.cardgame.room.manager;

import com.cardgame.common.config.GameConfig;
import com.cardgame.common.config.RedisConfig;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.room.entity.Room;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class RedisRoomStateManager {

    private static final String ROOM_PREFIX = "cardgame:room:";
    private static final String PLAYER_ROOM_PREFIX = "cardgame:player:room:";
    private static final String ONLINE_PLAYERS_KEY = "cardgame:online:players";

    private JedisPool jedisPool;

    @Autowired
    private RedisConfig redisConfig;

    @Autowired
    private GameConfig gameConfig;

    @PostConstruct
    public void init() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(redisConfig.getMaxTotal());
        poolConfig.setMaxIdle(redisConfig.getMaxIdle());
        poolConfig.setMinIdle(redisConfig.getMinIdle());

        if (redisConfig.getPassword() != null && !redisConfig.getPassword().isEmpty()) {
            jedisPool = new JedisPool(poolConfig, redisConfig.getHost(), redisConfig.getPort(),
                    redisConfig.getTimeout(), redisConfig.getPassword(), redisConfig.getDatabase());
        } else {
            jedisPool = new JedisPool(poolConfig, redisConfig.getHost(), redisConfig.getPort(),
                    redisConfig.getTimeout(), null, redisConfig.getDatabase());
        }
        log.info("Redis room state manager initialized");
    }

    @PreDestroy
    public void destroy() {
        if (jedisPool != null) {
            jedisPool.close();
        }
    }

    public void saveRoom(Room room) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = ROOM_PREFIX + room.getRoomId();
            String json = JsonUtils.toJson(room);
            jedis.setex(key, 3600, json);

            for (com.cardgame.common.entity.Player player : room.getPlayers()) {
                jedis.set(PLAYER_ROOM_PREFIX + player.getPlayerId(), room.getRoomId());
                if (player.isOnline()) {
                    jedis.sadd(ONLINE_PLAYERS_KEY, player.getPlayerId());
                }
            }
        } catch (Exception e) {
            log.error("Error saving room to Redis", e);
        }
    }

    public Room getRoom(String roomId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = ROOM_PREFIX + roomId;
            String json = jedis.get(key);
            if (json != null) {
                return JsonUtils.fromJson(json, Room.class);
            }
        } catch (Exception e) {
            log.error("Error getting room from Redis", e);
        }
        return null;
    }

    public void deleteRoom(String roomId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Room room = getRoom(roomId);
            if (room != null) {
                for (com.cardgame.common.entity.Player player : room.getPlayers()) {
                    jedis.del(PLAYER_ROOM_PREFIX + player.getPlayerId());
                    jedis.srem(ONLINE_PLAYERS_KEY, player.getPlayerId());
                }
            }
            jedis.del(ROOM_PREFIX + roomId);
        } catch (Exception e) {
            log.error("Error deleting room from Redis", e);
        }
    }

    public String getPlayerRoomId(String playerId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(PLAYER_ROOM_PREFIX + playerId);
        } catch (Exception e) {
            log.error("Error getting player room from Redis", e);
        }
        return null;
    }

    public void setPlayerOnline(String playerId, boolean online) {
        try (Jedis jedis = jedisPool.getResource()) {
            if (online) {
                jedis.sadd(ONLINE_PLAYERS_KEY, playerId);
            } else {
                jedis.srem(ONLINE_PLAYERS_KEY, playerId);
            }
        } catch (Exception e) {
            log.error("Error setting player online status", e);
        }
    }

    public boolean isPlayerOnline(String playerId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.sismember(ONLINE_PLAYERS_KEY, playerId);
        } catch (Exception e) {
            log.error("Error checking player online status", e);
        }
        return false;
    }

    public int getOnlinePlayerCount() {
        try (Jedis jedis = jedisPool.getResource()) {
            return (int) jedis.scard(ONLINE_PLAYERS_KEY);
        } catch (Exception e) {
            log.error("Error getting online player count", e);
        }
        return 0;
    }

    public List<String> getOnlinePlayers() {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> members = jedis.smembers(ONLINE_PLAYERS_KEY);
            return new ArrayList<>(members);
        } catch (Exception e) {
            log.error("Error getting online players", e);
        }
        return new ArrayList<>();
    }

    public void updateRoomField(String roomId, String field, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            Room room = getRoom(roomId);
            if (room != null) {
                saveRoom(room);
            }
        } catch (Exception e) {
            log.error("Error updating room field", e);
        }
    }
}
