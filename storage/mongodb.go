package storage

import (
	"context"
	"socialfeed/config"

	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type MongoDB struct {
	Client        *mongo.Client
	Database      *mongo.Database
	Collections   *Collections
}

type Collections struct {
	Posts         *mongo.Collection
	Feeds         *mongo.Collection
	Interactions  *mongo.Collection
	Comments      *mongo.Collection
	Relations     *mongo.Collection
	AuditRecords  *mongo.Collection
	Notifications *mongo.Collection
}

func NewMongoDB(cfg *config.MongoDBConfig) (*MongoDB, error) {
	clientOptions := options.Client().ApplyURI(cfg.URI)
	client, err := mongo.Connect(context.Background(), clientOptions)
	if err != nil {
		return nil, err
	}

	if err := client.Ping(context.Background(), nil); err != nil {
		return nil, err
	}

	database := client.Database(cfg.Database)

	return &MongoDB{
		Client:   client,
		Database: database,
		Collections: &Collections{
			Posts:         database.Collection(cfg.Collection.Posts),
			Feeds:         database.Collection(cfg.Collection.Feeds),
			Interactions:  database.Collection(cfg.Collection.Interactions),
			Comments:      database.Collection(cfg.Collection.Comments),
			Relations:     database.Collection(cfg.Collection.Relations),
			AuditRecords:  database.Collection(cfg.Collection.AuditRecords),
			Notifications: database.Collection(cfg.Collection.Notifications),
		},
	}, nil
}

func (m *MongoDB) Close(ctx context.Context) error {
	return m.Client.Disconnect(ctx)
}
