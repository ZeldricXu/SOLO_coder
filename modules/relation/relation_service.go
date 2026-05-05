package relation

import (
	"context"
	"socialfeed/models"
	"socialfeed/storage"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
)

type RelationService struct {
	mongoDB *storage.MongoDB
	redisDB *storage.RedisDB
}

func NewRelationService(mongoDB *storage.MongoDB, redisDB *storage.RedisDB) *RelationService {
	return &RelationService{
		mongoDB: mongoDB,
		redisDB: redisDB,
	}
}

func (s *RelationService) FollowUser(ctx context.Context, userID, targetUserID string) error {
	existingRelation, err := s.GetRelation(ctx, userID, targetUserID)
	if err != nil && err != mongo.ErrNoDocuments {
		return err
	}

	if existingRelation != nil {
		if existingRelation.Status == models.RelationStatusActive {
			return nil
		}
		filter := bson.M{"relation_id": existingRelation.RelationID}
		update := bson.M{"$set": bson.M{"status": models.RelationStatusActive}}
		_, err = s.mongoDB.Collections.Relations.UpdateOne(ctx, filter, update)
		return err
	}

	relation := &models.Relation{
		RelationID:   "relation_" + uuid.New().String()[:8],
		UserID:       userID,
		TargetUserID: targetUserID,
		RelationType: models.RelationTypeFollow,
		CreatedAt:    time.Now().UTC(),
		Status:       models.RelationStatusActive,
	}

	_, err = s.mongoDB.Collections.Relations.InsertOne(ctx, relation)
	return err
}

func (s *RelationService) UnfollowUser(ctx context.Context, userID, targetUserID string) error {
	filter := bson.M{
		"user_id":        userID,
		"target_user_id": targetUserID,
		"relation_type":  models.RelationTypeFollow,
	}
	update := bson.M{"$set": bson.M{"status": models.RelationStatusBlocked}}
	_, err := s.mongoDB.Collections.Relations.UpdateOne(ctx, filter, update)
	return err
}

func (s *RelationService) GetRelation(ctx context.Context, userID, targetUserID string) (*models.Relation, error) {
	var relation models.Relation
	filter := bson.M{
		"user_id":        userID,
		"target_user_id": targetUserID,
		"relation_type":  models.RelationTypeFollow,
	}
	err := s.mongoDB.Collections.Relations.FindOne(ctx, filter).Decode(&relation)
	if err != nil {
		return nil, err
	}
	return &relation, nil
}

func (s *RelationService) GetFollowers(ctx context.Context, userID string) ([]string, error) {
	filter := bson.M{
		"target_user_id": userID,
		"relation_type":  models.RelationTypeFollow,
		"status":         models.RelationStatusActive,
	}

	cursor, err := s.mongoDB.Collections.Relations.Find(ctx, filter)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var followers []string
	for cursor.Next(ctx) {
		var relation models.Relation
		if err := cursor.Decode(&relation); err != nil {
			continue
		}
		followers = append(followers, relation.UserID)
	}

	return followers, nil
}

func (s *RelationService) GetFollowing(ctx context.Context, userID string) ([]string, error) {
	filter := bson.M{
		"user_id":       userID,
		"relation_type": models.RelationTypeFollow,
		"status":        models.RelationStatusActive,
	}

	cursor, err := s.mongoDB.Collections.Relations.Find(ctx, filter)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var following []string
	for cursor.Next(ctx) {
		var relation models.Relation
		if err := cursor.Decode(&relation); err != nil {
			continue
		}
		following = append(following, relation.TargetUserID)
	}

	return following, nil
}

func (s *RelationService) IsFollowing(ctx context.Context, userID, targetUserID string) (bool, error) {
	filter := bson.M{
		"user_id":        userID,
		"target_user_id": targetUserID,
		"relation_type":  models.RelationTypeFollow,
		"status":         models.RelationStatusActive,
	}

	count, err := s.mongoDB.Collections.Relations.CountDocuments(ctx, filter)
	if err != nil {
		return false, err
	}
	return count > 0, nil
}
