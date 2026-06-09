package storage

import (
	"context"
	"fmt"
	"io"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"go.uber.org/zap"
)

var minioClient *minio.Client

type MinIOClient struct {
	client *minio.Client
	bucket string
}

func InitMinIO(cfg *config.MinIOConfig) error {
	logger.Info("connecting to minio",
		zap.String("endpoint", cfg.Endpoint),
		zap.String("bucket", cfg.Bucket),
		zap.Bool("secure", cfg.Secure),
	)

	client, err := minio.New(cfg.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, ""),
		Secure: cfg.Secure,
	})
	if err != nil {
		return fmt.Errorf("failed to create minio client: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	exists, err := client.BucketExists(ctx, cfg.Bucket)
	if err != nil {
		return fmt.Errorf("failed to check bucket: %w", err)
	}

	if !exists {
		logger.Info("creating minio bucket", zap.String("bucket", cfg.Bucket))
		if err := client.MakeBucket(ctx, cfg.Bucket, minio.MakeBucketOptions{}); err != nil {
			return fmt.Errorf("failed to create bucket: %w", err)
		}
	}

	minioClient = client
	logger.Info("minio connected successfully")
	return nil
}

func GetMinIO() *minio.Client {
	if minioClient == nil {
		logger.Fatal("minio not initialized")
	}
	return minioClient
}

func NewMinIOClient(cfg *config.MinIOConfig) (*MinIOClient, error) {
	if minioClient == nil {
		if err := InitMinIO(cfg); err != nil {
			return nil, err
		}
	}
	return &MinIOClient{
		client: minioClient,
		bucket: cfg.Bucket,
	}, nil
}

func (m *MinIOClient) Upload(ctx context.Context, objectName string, reader io.Reader, size int64, contentType string) error {
	_, err := m.client.PutObject(ctx, m.bucket, objectName, reader, size, minio.PutObjectOptions{
		ContentType: contentType,
	})
	return err
}

func (m *MinIOClient) Download(ctx context.Context, objectName string) (io.ReadCloser, error) {
	return m.client.GetObject(ctx, m.bucket, objectName, minio.GetObjectOptions{})
}

func (m *MinIOClient) Stat(ctx context.Context, objectName string) (minio.ObjectInfo, error) {
	return m.client.StatObject(ctx, m.bucket, objectName, minio.StatObjectOptions{})
}

func (m *MinIOClient) Delete(ctx context.Context, objectName string) error {
	return m.client.RemoveObject(ctx, m.bucket, objectName, minio.RemoveObjectOptions{})
}

func (m *MinIOClient) DeleteMany(ctx context.Context, objectNames []string) error {
	objectsCh := make(chan minio.ObjectInfo, len(objectNames))
	go func() {
		defer close(objectsCh)
		for _, name := range objectNames {
			objectsCh <- minio.ObjectInfo{Key: name}
		}
	}()

	for err := range m.client.RemoveObjects(ctx, m.bucket, objectsCh, minio.RemoveObjectsOptions{}) {
		if err.Err != nil {
			return err.Err
		}
	}
	return nil
}

func (m *MinIOClient) PresignedGetURL(ctx context.Context, objectName string, expires time.Duration) (string, error) {
	url, err := m.client.PresignedGetObject(ctx, m.bucket, objectName, expires, nil)
	if err != nil {
		return "", err
	}
	return url.String(), nil
}

func (m *MinIOClient) List(ctx context.Context, prefix string) <-chan minio.ObjectInfo {
	return m.client.ListObjects(ctx, m.bucket, minio.ListObjectsOptions{
		Prefix:    prefix,
		Recursive: true,
	})
}

func (m *MinIOClient) DeleteExpired(ctx context.Context, before time.Time) (int, error) {
	count := 0
	objects := m.client.ListObjects(ctx, m.bucket, minio.ListObjectsOptions{Recursive: true})

	var toDelete []string
	for obj := range objects {
		if obj.Err != nil {
			return count, obj.Err
		}
		if obj.LastModified.Before(before) {
			toDelete = append(toDelete, obj.Key)
		}
	}

	if len(toDelete) > 0 {
		if err := m.DeleteMany(ctx, toDelete); err != nil {
			return count, err
		}
		count = len(toDelete)
	}

	return count, nil
}

func (m *MinIOClient) Bucket() string {
	return m.bucket
}
