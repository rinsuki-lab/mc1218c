package main

import (
	"fmt"
	"os"
)

type Config struct {
	WatchDir       string
	S3Hostname     string
	S3Bucket       string
	S3AccessKey    string
	S3SecretKey    string
	S3Region       string
	SnapshotPrefix string
}

func LoadConfig() (*Config, error) {
	config := &Config{
		WatchDir:       os.Getenv("WATCH_DIR"),
		S3Hostname:     os.Getenv("S3_HOSTNAME"),
		S3Bucket:       os.Getenv("S3_BUCKET"),
		S3AccessKey:    os.Getenv("S3_ACCESS_KEY"),
		S3SecretKey:    os.Getenv("S3_SECRET_KEY"),
		S3Region:       os.Getenv("S3_REGION"),
		SnapshotPrefix: os.Getenv("SNAPSHOT_PREFIX"),
	}

	if config.WatchDir == "" {
		return nil, fmt.Errorf("WATCH_DIR environment variable is required")
	}

	if config.S3Hostname == "" {
		return nil, fmt.Errorf("S3_HOSTNAME environment variable is required")
	}

	if config.S3Bucket == "" {
		return nil, fmt.Errorf("S3_BUCKET environment variable is required")
	}

	if config.S3AccessKey == "" {
		return nil, fmt.Errorf("S3_ACCESS_KEY environment variable is required")
	}

	if config.S3SecretKey == "" {
		return nil, fmt.Errorf("S3_SECRET_KEY environment variable is required")
	}

	if config.S3Region == "" {
		config.S3Region = "auto"
	}

	return config, nil
}