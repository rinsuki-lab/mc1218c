package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	// Load configuration
	cfg, err := LoadConfig()
	if err != nil {
		log.Fatalf("Failed to load configuration: %v", err)
	}

	log.Printf("Starting snapuploader")
	log.Printf("Watch directory: %s", cfg.WatchDir)
	log.Printf("S3 hostname: %s", cfg.S3Hostname)
	log.Printf("S3 bucket: %s", cfg.S3Bucket)
	log.Printf("S3 region: %s", cfg.S3Region)

	// Check if watch directory exists
	if _, err := os.Stat(cfg.WatchDir); err != nil {
		log.Fatalf("Watch directory does not exist: %v", err)
	}

	// Create S3 uploader
	uploader, err := NewS3Uploader(cfg)
	if err != nil {
		log.Fatalf("Failed to create S3 uploader: %v", err)
	}

	// Create directory watcher
	watcher, err := NewDirectoryWatcher(cfg, uploader)
	if err != nil {
		log.Fatalf("Failed to create directory watcher: %v", err)
	}
	defer watcher.Close()

	// Create context for graceful shutdown
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Handle signals for graceful shutdown
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		sig := <-sigChan
		log.Printf("Received signal %v, shutting down...", sig)
		cancel()
	}()

	// Start watching
	if err := watcher.Start(ctx); err != nil {
		if err != context.Canceled {
			log.Fatalf("Watcher error: %v", err)
		}
	}

	log.Printf("Snapuploader stopped")
}