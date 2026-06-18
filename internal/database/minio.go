package database

import (
	"context"
	"fmt"
	"io"
	"net/url"
	"path/filepath"
	"strings"
	"time"

	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/google/uuid"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

var globalMinIO *MinIOClient

type MinIOClient struct {
	client     *minio.Client
	bucketName string
	region     string
}

type UploadResult struct {
	ObjectName  string `json:"object_name"`
	ETag        string `json:"etag"`
	Size        int64  `json:"size"`
	ContentType string `json:"content_type"`
}

func InitMinIO(cfg config.MinIOConfig) (*MinIOClient, error) {
	client, err := minio.New(cfg.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.AccessKeyID, cfg.SecretAccessKey, ""),
		Secure: cfg.UseSSL,
		Region: cfg.Region,
	})
	if err != nil {
		return nil, fmt.Errorf("create minio client: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	exists, err := client.BucketExists(ctx, cfg.BucketName)
	if err != nil {
		return nil, fmt.Errorf("check bucket exists: %w", err)
	}
	if !exists {
		region := cfg.Region
		if region == "" {
			region = "us-east-1"
		}
		if err := client.MakeBucket(ctx, cfg.BucketName, minio.MakeBucketOptions{Region: region}); err != nil {
			return nil, fmt.Errorf("make bucket %s: %w", cfg.BucketName, err)
		}
	}

	mc := &MinIOClient{
		client:     client,
		bucketName: cfg.BucketName,
		region:     cfg.Region,
	}

	globalMinIO = mc
	return mc, nil
}

func GetMinIO() *MinIOClient {
	return globalMinIO
}

func (m *MinIOClient) generateObjectName(tenantID, category, originalName string) string {
	ext := strings.ToLower(filepath.Ext(originalName))
	base := strings.TrimSuffix(originalName, ext)
	_ = base
	id := uuid.New().String()
	if ext != "" {
		return fmt.Sprintf("%s/%s/%s%s", tenantID, category, id, ext)
	}
	return fmt.Sprintf("%s/%s/%s", tenantID, category, id)
}

func (m *MinIOClient) Upload(ctx context.Context, tenantID, category, fileName string, reader io.Reader, size int64, contentType string) (*UploadResult, error) {
	objectName := m.generateObjectName(tenantID, category, fileName)

	uploadInfo, err := m.client.PutObject(ctx, m.bucketName, objectName, reader, size, minio.PutObjectOptions{
		ContentType: contentType,
		UserMetadata: map[string]string{
			"tenant-id":   tenantID,
			"category":    category,
			"filename":    fileName,
		},
	})
	if err != nil {
		return nil, fmt.Errorf("put object: %w", err)
	}

	return &UploadResult{
		ObjectName:  objectName,
		ETag:        uploadInfo.ETag,
		Size:        uploadInfo.Size,
		ContentType: contentType,
	}, nil
}

func (m *MinIOClient) UploadFile(ctx context.Context, tenantID, category, fileName, filePath string) (*UploadResult, error) {
	objectName := m.generateObjectName(tenantID, category, fileName)

	uploadInfo, err := m.client.FPutObject(ctx, m.bucketName, objectName, filePath, minio.PutObjectOptions{
		UserMetadata: map[string]string{
			"tenant-id":   tenantID,
			"category":    category,
			"filename":    fileName,
		},
	})
	if err != nil {
		return nil, fmt.Errorf("fput object: %w", err)
	}

	return &UploadResult{
		ObjectName: objectName,
		ETag:       uploadInfo.ETag,
		Size:       uploadInfo.Size,
	}, nil
}

func (m *MinIOClient) Download(ctx context.Context, objectName string) (io.ReadCloser, error) {
	object, err := m.client.GetObject(ctx, m.bucketName, objectName, minio.GetObjectOptions{})
	if err != nil {
		return nil, fmt.Errorf("get object: %w", err)
	}
	return object, nil
}

func (m *MinIOClient) DownloadFile(ctx context.Context, objectName, filePath string) error {
	if err := m.client.FGetObject(ctx, m.bucketName, objectName, filePath, minio.GetObjectOptions{}); err != nil {
		return fmt.Errorf("fget object: %w", err)
	}
	return nil
}

func (m *MinIOClient) Delete(ctx context.Context, objectName string) error {
	if err := m.client.RemoveObject(ctx, m.bucketName, objectName, minio.RemoveObjectOptions{}); err != nil {
		return fmt.Errorf("remove object: %w", err)
	}
	return nil
}

func (m *MinIOClient) DeleteMany(ctx context.Context, objectNames []string) error {
	objectsCh := make(chan minio.ObjectInfo, len(objectNames))
	go func() {
		defer close(objectsCh)
		for _, name := range objectNames {
			objectsCh <- minio.ObjectInfo{Key: name}
		}
	}()

	for err := range m.client.RemoveObjects(ctx, m.bucketName, objectsCh, minio.RemoveObjectsOptions{}) {
		if err.Err != nil {
			return fmt.Errorf("remove object %s: %w", err.ObjectName, err.Err)
		}
	}
	return nil
}

func (m *MinIOClient) Stat(ctx context.Context, objectName string) (minio.ObjectInfo, error) {
	return m.client.StatObject(ctx, m.bucketName, objectName, minio.StatObjectOptions{})
}

func (m *MinIOClient) PresignedGetURL(ctx context.Context, objectName string, expires time.Duration) (string, error) {
	reqParams := make(url.Values)
	presignedURL, err := m.client.PresignedGetObject(ctx, m.bucketName, objectName, expires, reqParams)
	if err != nil {
		return "", fmt.Errorf("presigned get object: %w", err)
	}
	return presignedURL.String(), nil
}

func (m *MinIOClient) PresignedPutURL(ctx context.Context, objectName string, expires time.Duration) (string, error) {
	presignedURL, err := m.client.PresignedPutObject(ctx, m.bucketName, objectName, expires)
	if err != nil {
		return "", fmt.Errorf("presigned put object: %w", err)
	}
	return presignedURL.String(), nil
}

func (m *MinIOClient) ListObjects(ctx context.Context, prefix string, recursive bool) <-chan minio.ObjectInfo {
	return m.client.ListObjects(ctx, m.bucketName, minio.ListObjectsOptions{
		Prefix:    prefix,
		Recursive: recursive,
	})
}

func (m *MinIOClient) BucketName() string {
	return m.bucketName
}

func (m *MinIOClient) Region() string {
	return m.region
}

func (m *MinIOClient) Copy(ctx context.Context, srcObjectName, dstObjectName string) error {
	src := minio.CopySrcOptions{
		Bucket: m.bucketName,
		Object: srcObjectName,
	}
	dst := minio.CopyDestOptions{
		Bucket: m.bucketName,
		Object: dstObjectName,
	}
	_, err := m.client.CopyObject(ctx, dst, src)
	return err
}
