package storage

import (
	"context"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"

	"github.com/studio/gameroom/pkg/common"
)

type RoomSnapshot struct {
	ID        primitive.ObjectID `json:"id" bson:"_id,omitempty"`
	RoomID    common.RoomID     `json:"room_id" bson:"room_id"`
	Config    *common.RoomConfig `json:"config" bson:"config"`
	State     common.GameState   `json:"state" bson:"state"`
	Players   []*common.Player  `json:"players" bson:"players"`
	HostID    common.UserID     `json:"host_id" bson:"host_id"`
	CreatedAt time.Time        `json:"created_at" bson:"created_at"`
	UpdatedAt time.Time        `json:"updated_at" bson:"updated_at"`
	SnapshotAt time.Time      `json:"snapshot_at" bson:"snapshot_at"`
	Extra     bson.M           `json:"extra" bson:"extra"`
}

type GameRecord struct {
	ID          primitive.ObjectID `json:"id" bson:"_id,omitempty"`
	RoomID      common.RoomID   `json:"room_id" bson:"room_id"`
	GameType    common.GameType `json:"game_type" bson:"game_type"`
	StartTime   time.Time        `json:"start_time" bson:"start_time"`
	EndTime     time.Time        `json:"end_time" bson:"end_time"`
	DurationSec int64          `json:"duration_sec" bson:"duration_sec"`
	Players     []PlayerRecord   `json:"players" bson:"players"`
	Actions     []common.GameAction `json:"actions" bson:"actions"`
	Winners    []common.UserID   `json:"winners" bson:"winners"`
	IsFinished bool             `json:"is_finished" bson:"is_finished"`
}

type PlayerRecord struct {
	UserID   common.UserID `json:"user_id" bson:"user_id"`
	Nickname string       `json:"nickname" bson:"nickname"`
	Score    int64        `json:"score" bson:"score"`
	Rank     int          `json:"rank" bson:"rank"`
	IsWinner bool         `json:"is_winner" bson:"is_winner"`
	IsRobot  bool         `json:"is_robot" bson:"is_robot"`
}

type PlayerStats struct {
	UserID       common.UserID `json:"user_id" bson:"user_id"`
	GameType     common.GameType `json:"game_type" bson:"game_type"`
	TotalGames   int64      `json:"total_games" bson:"total_games"`
	Wins         int64      `json:"wins" bson:"wins"`
	Losses       int64      `json:"losses" bson:"losses"`
	TotalScore   int64      `json:"total_score" bson:"total_score"`
	MaxWinStreak int       `json:"max_win_streak" bson:"max_win_streak"`
	CurrentStreak  int        `json:"current_streak" bson:"current_streak"`
	AvgScore     float64    `json:"avg_score" bson:"avg_score"`
	LastPlayedAt time.Time   `json:"last_played_at" bson:"last_played_at"`
}

type DailyStats struct {
	UserID    common.UserID `json:"user_id" bson:"user_id"`
	GameType  common.GameType `json:"game_type" bson:"game_type"`
	Date      string       `json:"date" bson:"date"`
	Games     int          `json:"games" bson:"games"`
	Wins      int          `json:"wins" bson:"wins"`
	WinRate   float64      `json:"win_rate" bson:"win_rate"`
	Score     int64        `json:"score" bson:"score"`
}

type MongoStore struct {
	client     *mongo.Client
	database   *mongo.Database
	roomSnaps  *mongo.Collection
	gameRecs   *mongo.Collection
	stats      *mongo.Collection
	dailyStats *mongo.Collection
}

func NewMongoStore(uri, dbName string) (*MongoStore, error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	client, err := mongo.Connect(ctx, options.Client().ApplyURI(uri))
	if err != nil {
		return nil, err
	}

	if err := client.Ping(ctx, nil); err != nil {
		return nil, err
	}

	db := client.Database(dbName)
	store := &MongoStore{
		client:     client,
		database: db,
		roomSnaps:  db.Collection("room_snapshots"),
		gameRecs:   db.Collection("game_records"),
		stats:      db.Collection("player_stats"),
		dailyStats: db.Collection("daily_stats"),
	}
	store.ensureIndexes()
	return store, nil
}

func (s *MongoStore) ensureIndexes() {
	ctx := context.Background()

	s.roomSnaps.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys: bson.D{{Key: "room_id", Value: 1}},
	})
	s.gameRecs.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys: bson.D{{Key: "room_id", Value: 1}},
	})
	s.gameRecs.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys: bson.D{{Key: "game_type", Value: 1}, {Key: "start_time", Value: -1}},
	})
	s.stats.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys: bson.D{{Key: "user_id", Value: 1}, {Key: "game_type", Value: 1}},
		Options: options.Index().SetUnique(true),
	})
	s.dailyStats.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys: bson.D{{Key: "user_id", Value: 1}, {Key: "game_type", Value: 1}, {Key: "date", Value: 1}},
		Options: options.Index().SetUnique(true),
	})
}

func (s *MongoStore) SaveRoomSnapshot(snap *RoomSnapshot) error {
	snap.SnapshotAt = time.Now()
	ctx := context.Background()
	_, err := s.roomSnaps.InsertOne(ctx, snap)
	return err
}

func (s *MongoStore) SaveGameRecord(rec *GameRecord) error {
	ctx := context.Background()
	_, err := s.gameRecs.InsertOne(ctx, rec)
	return err
}

func (s *MongoStore) GetGameRecord(roomID common.RoomID) (*GameRecord, error) {
	ctx := context.Background()
	var rec GameRecord
	err := s.gameRecs.FindOne(ctx, bson.M{"room_id": roomID}).Decode(&rec)
	if err != nil {
		return nil, err
	}
	return &rec, nil
}

func (s *MongoStore) ListGameRecords(userID common.UserID, gameType common.GameType, limit int) ([]GameRecord, error) {
	ctx := context.Background()
	filter := bson.M{}
	if userID != "" {
		filter["players.user_id"] = userID
	}
	if gameType != "" {
		filter["game_type"] = gameType
	}
	opts := options.Find().SetSort(bson.D{{Key: "start_time", Value: -1}}).SetLimit(int64(limit))
	cursor, err := s.gameRecs.Find(ctx, filter, opts)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var records []GameRecord
	err = cursor.All(ctx, &records)
	return records, err
}

func (s *MongoStore) UpdatePlayerStats(userID common.UserID, gameType common.GameType, delta PlayerRecord) error {
	ctx := context.Background()

	filter := bson.M{"user_id": userID, "game_type": gameType}
	update := bson.M{
		"$inc": bson.M{
			"total_games": 1,
			"total_score": delta.Score,
		},
		"$set": bson.M{
			"last_played_at": time.Now(),
		},
		"$setOnInsert": bson.M{
			"wins":            0,
			"losses":          0,
			"max_win_streak":  0,
			"current_streak": 0,
		},
	}
	if delta.IsWinner {
		update["$inc"].(bson.M)["wins"] = 1
		update["$set"].(bson.M)["current_streak"] = 1
	} else {
		update["$inc"].(bson.M)["losses"] = 1
		update["$set"].(bson.M)["current_streak"] = 0
	}

	opts := options.Update().SetUpsert(true)
	_, err := s.stats.UpdateOne(ctx, filter, update, opts)
	return err
}

func (s *MongoStore) GetPlayerStats(userID common.UserID, gameType common.GameType) (*PlayerStats, error) {
	ctx := context.Background()
	var stats PlayerStats
	err := s.stats.FindOne(ctx, bson.M{"user_id": userID, "game_type": gameType}).Decode(&stats)
	if err != nil {
		return nil, err
	}
	if stats.TotalGames > 0 {
		stats.WinRate = float64(stats.Wins) / float64(stats.TotalGames)
	}
	return &stats, nil
}

func (s *MongoStore) RecordDailyStats(userID common.UserID, gameType common.GameType, isWinner bool, score int64) error {
	ctx := context.Background()
	date := time.Now().Format("2006-01-02")

	filter := bson.M{"user_id": userID, "game_type": gameType, "date": date}
	update := bson.M{
		"$inc": bson.M{"games": 1, "score": score},
	}
	if isWinner {
		update["$inc"].(bson.M)["wins"] = 1
	}

	opts := options.Update().SetUpsert(true)
	_, err := s.dailyStats.UpdateOne(ctx, filter, update, opts)
	return err
}

func (s *MongoStore) GetDailyTrend(userID common.UserID, gameType common.GameType, days int) ([]DailyStats, error) {
	ctx := context.Background()
	filter := bson.M{"user_id": userID, "game_type": gameType}
	opts := options.Find().SetSort(bson.D{{Key: "date", Value: -1}}).SetLimit(int64(days))
	cursor, err := s.dailyStats.Find(ctx, filter, opts)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var stats []DailyStats
	err = cursor.All(ctx, &stats)
	for i := range stats {
		if stats[i].Games > 0 {
			stats[i].WinRate = float64(stats[i].Wins) / float64(stats[i].Games)
		}
	}
	return stats, err
}

func (s *MongoStore) GetActionsForPlayback(roomID common.RoomID) ([]common.GameAction, error) {
	rec, err := s.GetGameRecord(roomID)
	if err != nil {
		return nil, err
	}
	return rec.Actions, nil
}

func (s *MongoStore) Close() error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return s.client.Disconnect(ctx)
}
