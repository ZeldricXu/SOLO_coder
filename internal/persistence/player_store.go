package persistence

import (
	"context"
	"errors"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
	"pixelrealm/pkg/models"
)

var (
	ErrPlayerNotFound    = errors.New("player not found")
	ErrPlayerExists      = errors.New("player already exists")
	ErrVersionConflict   = errors.New("version conflict")
)

type PlayerStore struct {
	db         *MongoDB
	collection *mongo.Collection
}

func NewPlayerStore(db *MongoDB) *PlayerStore {
	return &PlayerStore{
		db:         db,
		collection: db.GetCollection("players"),
	}
}

func (s *PlayerStore) CreateIndexes() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	
	_, err := s.collection.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "player_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys:    bson.D{{Key: "username", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys: bson.D{{Key: "position.map_id", Value: 1}},
		},
		{
			Keys: bson.D{{Key: "online_status", Value: 1}},
		},
	})
	
	return err
}

func (s *PlayerStore) Create(ctx context.Context, player *models.Player) error {
	player.LastSyncTime = time.Now()
	player.Version = 1
	
	_, err := s.collection.InsertOne(ctx, player)
	if mongo.IsDuplicateKeyError(err) {
		return ErrPlayerExists
	}
	return err
}

func (s *PlayerStore) FindByID(ctx context.Context, playerID models.PlayerID) (*models.Player, error) {
	var player models.Player
	
	err := s.collection.FindOne(ctx, bson.M{"player_id": playerID}).Decode(&player)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, ErrPlayerNotFound
		}
		return nil, err
	}
	
	return &player, nil
}

func (s *PlayerStore) FindByUsername(ctx context.Context, username string) (*models.Player, error) {
	var player models.Player
	
	err := s.collection.FindOne(ctx, bson.M{"username": username}).Decode(&player)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, ErrPlayerNotFound
		}
		return nil, err
	}
	
	return &player, nil
}

func (s *PlayerStore) Update(ctx context.Context, player *models.Player) error {
	oldVersion := player.Version
	player.Version++
	player.LastSyncTime = time.Now()
	
	result, err := s.collection.UpdateOne(
		ctx,
		bson.M{
			"player_id": player.PlayerID,
			"version":   oldVersion,
		},
		bson.M{
			"$set": player,
		},
	)
	
	if err != nil {
		player.Version = oldVersion
		return err
	}
	
	if result.MatchedCount == 0 {
		player.Version = oldVersion
		return ErrVersionConflict
	}
	
	return nil
}

func (s *PlayerStore) UpdateAsync(player *models.Player) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	
	go func() {
		s.Update(ctx, player)
	}()
}

func (s *PlayerStore) UpdateField(ctx context.Context, playerID models.PlayerID, field string, value interface{}) error {
	_, err := s.collection.UpdateOne(
		ctx,
		bson.M{"player_id": playerID},
		bson.M{
			"$set": bson.M{
				field:           value,
				"last_sync_time": time.Now(),
			},
			"$inc": bson.M{"version": 1},
		},
	)
	return err
}

func (s *PlayerStore) SetOnline(ctx context.Context, playerID models.PlayerID, online bool) error {
	return s.UpdateField(ctx, playerID, "online_status", online)
}

func (s *PlayerStore) UpdatePosition(ctx context.Context, playerID models.PlayerID, pos models.Position) error {
	_, err := s.collection.UpdateOne(
		ctx,
		bson.M{"player_id": playerID},
		bson.M{
			"$set": bson.M{
				"position":       pos,
				"last_sync_time": time.Now(),
			},
			"$inc": bson.M{"version": 1},
		},
	)
	return err
}

func (s *PlayerStore) UpdateAttributes(ctx context.Context, playerID models.PlayerID, attrs models.Attributes) error {
	_, err := s.collection.UpdateOne(
		ctx,
		bson.M{"player_id": playerID},
		bson.M{
			"$set": bson.M{
				"attributes":     attrs,
				"last_sync_time": time.Now(),
			},
			"$inc": bson.M{"version": 1},
		},
	)
	return err
}

func (s *PlayerStore) FindByMapID(ctx context.Context, mapID string) ([]*models.Player, error) {
	cursor, err := s.collection.Find(ctx, bson.M{
		"position.map_id": mapID,
		"online_status":   true,
	})
	
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)
	
	var players []*models.Player
	for cursor.Next(ctx) {
		var player models.Player
		if err := cursor.Decode(&player); err != nil {
			continue
		}
		players = append(players, &player)
	}
	
	return players, nil
}

func (s *PlayerStore) Delete(ctx context.Context, playerID models.PlayerID) error {
	_, err := s.collection.DeleteOne(ctx, bson.M{"player_id": playerID})
	return err
}

func (s *PlayerStore) Count(ctx context.Context) (int64, error) {
	return s.collection.CountDocuments(ctx, bson.M{})
}

func (s *PlayerStore) CountOnline(ctx context.Context) (int64, error) {
	return s.collection.CountDocuments(ctx, bson.M{"online_status": true})
}
