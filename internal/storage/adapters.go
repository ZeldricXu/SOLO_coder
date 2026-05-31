package storage

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type LocalStorageAdapter struct {
	basePath string
}

func NewLocalStorageAdapter(basePath string) (*LocalStorageAdapter, error) {
	if err := os.MkdirAll(basePath, 0755); err != nil {
		return nil, err
	}
	return &LocalStorageAdapter{basePath: basePath}, nil
}

func (a *LocalStorageAdapter) Upload(ctx context.Context, bucket, key string, data io.Reader, contentType string, metadata map[string]string) (*UploadResult, error) {
	fullPath := filepath.Join(a.basePath, bucket, key)
	dir := filepath.Dir(fullPath)

	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, err
	}

	file, err := os.Create(fullPath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	hasher := sha256.New()
	tee := io.TeeReader(data, hasher)

	size, err := io.Copy(file, tee)
	if err != nil {
		return nil, err
	}

	checksum := hex.EncodeToString(hasher.Sum(nil))

	return &UploadResult{
		Key:         key,
		SizeBytes:   size,
		Checksum:    checksum,
		ContentType: contentType,
	}, nil
}

func (a *LocalStorageAdapter) Download(ctx context.Context, bucket, key string) (io.ReadCloser, *ObjectInfo, error) {
	fullPath := filepath.Join(a.basePath, bucket, key)

	file, err := os.Open(fullPath)
	if err != nil {
		return nil, nil, err
	}

	stat, err := file.Stat()
	if err != nil {
		file.Close()
		return nil, nil, err
	}

	info := &ObjectInfo{
		Key:          key,
		SizeBytes:    stat.Size(),
		ContentType:  detectContentType(key),
		LastModified: stat.ModTime(),
		Metadata:     make(map[string]string),
	}

	return file, info, nil
}

func (a *LocalStorageAdapter) Delete(ctx context.Context, bucket, key string) error {
	fullPath := filepath.Join(a.basePath, bucket, key)
	return os.Remove(fullPath)
}

func (a *LocalStorageAdapter) List(ctx context.Context, bucket, prefix string, maxKeys int) ([]ObjectInfo, error) {
	fullPath := filepath.Join(a.basePath, bucket, prefix)

	var results []ObjectInfo

	err := filepath.Walk(fullPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}

		relPath, _ := filepath.Rel(filepath.Join(a.basePath, bucket), path)
		results = append(results, ObjectInfo{
			Key:          relPath,
			SizeBytes:    info.Size(),
			LastModified: info.ModTime(),
			ContentType:  detectContentType(relPath),
		})

		if maxKeys > 0 && len(results) >= maxKeys {
			return filepath.SkipDir
		}
		return nil
	})

	return results, err
}

func (a *LocalStorageAdapter) GetPresignedURL(ctx context.Context, bucket, key string, expires time.Duration) (string, error) {
	return fmt.Sprintf("file://%s/%s/%s", a.basePath, bucket, key), nil
}

func (a *LocalStorageAdapter) Copy(ctx context.Context, srcBucket, srcKey, dstBucket, dstKey string) error {
	srcPath := filepath.Join(a.basePath, srcBucket, srcKey)
	dstPath := filepath.Join(a.basePath, dstBucket, dstKey)

	srcFile, err := os.Open(srcPath)
	if err != nil {
		return err
	}
	defer srcFile.Close()

	dstDir := filepath.Dir(dstPath)
	if err := os.MkdirAll(dstDir, 0755); err != nil {
		return err
	}

	dstFile, err := os.Create(dstPath)
	if err != nil {
		return err
	}
	defer dstFile.Close()

	_, err = io.Copy(dstFile, srcFile)
	return err
}

func (a *LocalStorageAdapter) Exists(ctx context.Context, bucket, key string) (bool, error) {
	fullPath := filepath.Join(a.basePath, bucket, key)
	_, err := os.Stat(fullPath)
	if err == nil {
		return true, nil
	}
	if os.IsNotExist(err) {
		return false, nil
	}
	return false, err
}

func (a *LocalStorageAdapter) GetType() StorageType {
	return StorageTypeLocal
}

func detectContentType(filename string) string {
	ext := strings.ToLower(filepath.Ext(filename))
	switch ext {
	case ".json":
		return "application/json"
	case ".txt":
		return "text/plain"
	case ".csv":
		return "text/csv"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".pdf":
		return "application/pdf"
	case ".bin":
		return "application/octet-stream"
	default:
		return "application/octet-stream"
	}
}

type S3StorageAdapter struct {
	config *StorageConfig
}

func NewS3StorageAdapter(config *StorageConfig) (*S3StorageAdapter, error) {
	return &S3StorageAdapter{config: config}, nil
}

func (a *S3StorageAdapter) Upload(ctx context.Context, bucket, key string, data io.Reader, contentType string, metadata map[string]string) (*UploadResult, error) {
	return &UploadResult{
		Key:         key,
		SizeBytes:   0,
		Checksum:    "",
		ContentType: contentType,
	}, fmt.Errorf("S3 adapter not implemented in this version")
}

func (a *S3StorageAdapter) Download(ctx context.Context, bucket, key string) (io.ReadCloser, *ObjectInfo, error) {
	return nil, nil, fmt.Errorf("S3 adapter not implemented in this version")
}

func (a *S3StorageAdapter) Delete(ctx context.Context, bucket, key string) error {
	return fmt.Errorf("S3 adapter not implemented in this version")
}

func (a *S3StorageAdapter) List(ctx context.Context, bucket, prefix string, maxKeys int) ([]ObjectInfo, error) {
	return nil, fmt.Errorf("S3 adapter not implemented in this version")
}

func (a *S3StorageAdapter) GetPresignedURL(ctx context.Context, bucket, key string, expires time.Duration) (string, error) {
	return "", fmt.Errorf("S3 adapter not implemented in this version")
}

func (a *S3StorageAdapter) Copy(ctx context.Context, srcBucket, srcKey, dstBucket, dstKey string) error {
	return fmt.Errorf("S3 adapter not implemented in this version")
}

func (a *S3StorageAdapter) Exists(ctx context.Context, bucket, key string) (bool, error) {
	return false, fmt.Errorf("S3 adapter not implemented in this version")
}

func (a *S3StorageAdapter) GetType() StorageType {
	return StorageTypeS3
}

func NewStorageAdapter(config *StorageConfig) (StorageAdapter, error) {
	switch config.Type {
	case StorageTypeLocal:
		return NewLocalStorageAdapter(config.LocalBasePath)
	case StorageTypeS3:
		return NewS3StorageAdapter(config)
	case StorageTypeOSS, StorageTypeMinIO:
		return NewS3StorageAdapter(config)
	default:
		return NewLocalStorageAdapter(config.LocalBasePath)
	}
}
