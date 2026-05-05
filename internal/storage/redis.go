package storage

import (
	"GameLeaderboard/internal/config"
	"context"
	"fmt"
	"time"

	"github.com/go-redis/redis/v8"
)

type RedisStore struct {
	client *redis.Client
	ctx    context.Context
}

func NewRedisStore(cfg *config.RedisConfig) (*RedisStore, error) {
	client := redis.NewClient(&redis.Options{
		Addr:     fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Password: cfg.Password,
		DB:       cfg.DB,
	})

	ctx := context.Background()
	_, err := client.Ping(ctx).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to connect to redis: %w", err)
	}

	return &RedisStore{
		client: client,
		ctx:    ctx,
	}, nil
}

func (s *RedisStore) GetClient() *redis.Client {
	return s.client
}

func (s *RedisStore) GetLeaderboardKey(gameID, seasonID string) string {
	return fmt.Sprintf("lb:%s:%s", gameID, seasonID)
}

func (s *RedisStore) UpdatePlayerScore(gameID, seasonID, playerID string, score int64) error {
	key := s.GetLeaderboardKey(gameID, seasonID)
	return s.client.ZAdd(s.ctx, key, &redis.Z{
		Score:  float64(score),
		Member: playerID,
	}).Err()
}

func (s *RedisStore) GetPlayerRank(gameID, seasonID, playerID string) (int64, error) {
	key := s.GetLeaderboardKey(gameID, seasonID)
	rank, err := s.client.ZRevRank(s.ctx, key, playerID).Result()
	if err != nil {
		if err == redis.Nil {
			return 0, nil
		}
		return 0, err
	}
	return rank + 1, nil
}

func (s *RedisStore) GetPlayerScore(gameID, seasonID, playerID string) (int64, error) {
	key := s.GetLeaderboardKey(gameID, seasonID)
	score, err := s.client.ZScore(s.ctx, key, playerID).Result()
	if err != nil {
		if err == redis.Nil {
			return 0, nil
		}
		return 0, err
	}
	return int64(score), nil
}

func (s *RedisStore) GetTopPlayers(gameID, seasonID string, start, stop int64) ([]redis.Z, error) {
	key := s.GetLeaderboardKey(gameID, seasonID)
	result, err := s.client.ZRevRangeWithScores(s.ctx, key, start, stop).Result()
	if err != nil {
		return nil, err
	}
	return result, nil
}

func (s *RedisStore) GetTotalPlayers(gameID, seasonID string) (int64, error) {
	key := s.GetLeaderboardKey(gameID, seasonID)
	return s.client.ZCard(s.ctx, key).Result()
}

func (s *RedisStore) RemovePlayer(gameID, seasonID, playerID string) error {
	key := s.GetLeaderboardKey(gameID, seasonID)
	return s.client.ZRem(s.ctx, key, playerID).Err()
}

func (s *RedisStore) ClearLeaderboard(gameID, seasonID string) error {
	key := s.GetLeaderboardKey(gameID, seasonID)
	return s.client.Del(s.ctx, key).Err()
}

func (s *RedisStore) PublishRankChange(gameID, seasonID string, message string) error {
	channel := fmt.Sprintf("rank_change:%s:%s", gameID, seasonID)
	return s.client.Publish(s.ctx, channel, message).Err()
}

func (s *RedisStore) PublishSeasonSwitch(gameID, oldSeasonID, newSeasonID string) error {
	channel := fmt.Sprintf("season_switch:%s", gameID)
	message := fmt.Sprintf(`{"old_season_id":"%s","new_season_id":"%s","timestamp":%d}`,
		oldSeasonID, newSeasonID, time.Now().Unix())
	return s.client.Publish(s.ctx, channel, message).Err()
}

func (s *RedisStore) SetPlayerScoreCache(gameID, seasonID, playerID string, score int64, expiration time.Duration) error {
	key := fmt.Sprintf("player_score:%s:%s:%s", gameID, seasonID, playerID)
	return s.client.Set(s.ctx, key, score, expiration).Err()
}

func (s *RedisStore) GetPlayerScoreCache(gameID, seasonID, playerID string) (int64, error) {
	key := fmt.Sprintf("player_score:%s:%s:%s", gameID, seasonID, playerID)
	scoreStr, err := s.client.Get(s.ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return 0, nil
		}
		return 0, err
	}
	var score int64
	fmt.Sscanf(scoreStr, "%d", &score)
	return score, nil
}

func (s *RedisStore) Close() error {
	return s.client.Close()
}
