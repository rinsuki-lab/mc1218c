package main

import (
	"context"
	"fmt"
	"io"
	"log"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/feature/s3/manager"
	"github.com/aws/aws-sdk-go-v2/service/s3"
)

type S3Uploader struct {
	client   *s3.Client
	uploader *manager.Uploader
	bucket   string
}

func NewS3Uploader(cfg *Config) (*S3Uploader, error) {
	awsCfg, err := config.LoadDefaultConfig(context.TODO(),
		config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(
			cfg.S3AccessKey,
			cfg.S3SecretKey,
			"",
		)),
		config.WithRegion(cfg.S3Region),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}

	client := s3.NewFromConfig(awsCfg, func(o *s3.Options) {
		o.BaseEndpoint = aws.String(fmt.Sprintf("https://%s", cfg.S3Hostname))
	})

	uploader := manager.NewUploader(client, func(u *manager.Uploader) {
		// Configure uploader settings
		u.PartSize = 16 * 1024 * 1024 // 16MB parts
		u.Concurrency = 3             // 3 concurrent uploads
	})

	return &S3Uploader{
		client:   client,
		uploader: uploader,
		bucket:   cfg.S3Bucket,
	}, nil
}

func (u *S3Uploader) Upload(ctx context.Context, key string, reader io.Reader, contentLength int64) error {
	log.Printf("Starting upload to s3://%s/%s", u.bucket, key)

	input := &s3.PutObjectInput{
		Bucket: aws.String(u.bucket),
		Key:    aws.String(key),
		Body:   reader,
	}

	if contentLength > 0 {
		input.ContentLength = aws.Int64(contentLength)
	}

	// Use the upload manager for better handling of streams
	_, err := u.uploader.Upload(ctx, input)
	if err != nil {
		return fmt.Errorf("failed to upload to S3: %w", err)
	}

	log.Printf("Successfully uploaded to s3://%s/%s", u.bucket, key)
	return nil
}

func (u *S3Uploader) UploadStream(ctx context.Context, key string, reader io.Reader) error {
	// For streaming upload without known content length
	return u.Upload(ctx, key, reader, -1)
}