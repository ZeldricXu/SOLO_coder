package minio

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/url"
	"time"

	miniov7 "github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"

	"onboarding-server/internal/config"
)

type Client struct {
	client *miniov7.Client
	bucket string
}

var DefaultClient *Client

func Connect(cfg *config.Config) (*Client, error) {
	client, err := miniov7.New(cfg.MinIO.Endpoint, &miniov7.Options{
		Creds:  credentials.NewStaticV4(cfg.MinIO.AccessKey, cfg.MinIO.SecretKey, ""),
		Secure: false,
	})
	if err != nil {
		return nil, fmt.Errorf("minio new client: %w", err)
	}

	c := &Client{
		client: client,
		bucket: cfg.MinIO.Bucket,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	exists, err := client.BucketExists(ctx, cfg.MinIO.Bucket)
	if err != nil {
		return nil, fmt.Errorf("check bucket: %w", err)
	}
	if !exists {
		if err := client.MakeBucket(ctx, cfg.MinIO.Bucket, miniov7.MakeBucketOptions{}); err != nil {
			return nil, fmt.Errorf("create bucket: %w", err)
		}
	}

	DefaultClient = c
	return c, nil
}

func (c *Client) Upload(ctx context.Context, objectKey string, data []byte, contentType string) error {
	reader := bytes.NewReader(data)
	_, err := c.client.PutObject(ctx, c.bucket, objectKey, reader, reader.Size(), miniov7.PutObjectOptions{
		ContentType: contentType,
	})
	if err != nil {
		return fmt.Errorf("put object: %w", err)
	}
	return nil
}

func (c *Client) Download(ctx context.Context, objectKey string) ([]byte, error) {
	obj, err := c.client.GetObject(ctx, c.bucket, objectKey, miniov7.GetObjectOptions{})
	if err != nil {
		return nil, fmt.Errorf("get object: %w", err)
	}
	defer obj.Close()

	data, err := io.ReadAll(obj)
	if err != nil {
		return nil, fmt.Errorf("read object: %w", err)
	}
	return data, nil
}

func (c *Client) Delete(ctx context.Context, objectKey string) error {
	err := c.client.RemoveObject(ctx, c.bucket, objectKey, miniov7.RemoveObjectOptions{})
	if err != nil {
		return fmt.Errorf("remove object: %w", err)
	}
	return nil
}

func (c *Client) PresignedGetURL(ctx context.Context, objectKey string, expiry time.Duration) (*url.URL, error) {
	reqParams := make(url.Values)
	u, err := c.client.PresignedGetObject(ctx, c.bucket, objectKey, expiry, reqParams)
	if err != nil {
		return nil, fmt.Errorf("presigned get: %w", err)
	}
	return u, nil
}

func (c *Client) PresignedPutURL(ctx context.Context, objectKey string, expiry time.Duration) (*url.URL, error) {
	u, err := c.client.PresignedPutObject(ctx, c.bucket, objectKey, expiry)
	if err != nil {
		return nil, fmt.Errorf("presigned put: %w", err)
	}
	return u, nil
}

func (c *Client) Raw() *miniov7.Client {
	return c.client
}
