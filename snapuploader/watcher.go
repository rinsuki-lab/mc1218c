package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"time"

	"github.com/fsnotify/fsnotify"
)

type CountingReader struct {
	reader io.Reader
	count  int64
}

func (cr *CountingReader) Read(p []byte) (n int, err error) {
	n, err = cr.reader.Read(p)
	cr.count += int64(n)
	return n, err
}

type DirectoryWatcher struct {
	watchDir string
	watcher  *fsnotify.Watcher
	config   *Config
	uploader *S3Uploader
}

func NewDirectoryWatcher(cfg *Config, uploader *S3Uploader) (*DirectoryWatcher, error) {
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, fmt.Errorf("failed to create fsnotify watcher: %w", err)
	}

	return &DirectoryWatcher{
		watchDir: cfg.WatchDir,
		watcher:  watcher,
		config:   cfg,
		uploader: uploader,
	}, nil
}

func (dw *DirectoryWatcher) Start(ctx context.Context) error {
	// Add the watch directory
	if err := dw.watcher.Add(dw.watchDir); err != nil {
		return fmt.Errorf("failed to add watch directory: %w", err)
	}

	log.Printf("Started watching directory: %s", dw.watchDir)

	// Process existing snapshots on startup
	if err := dw.processExistingSnapshots(ctx); err != nil {
		// Propagate error so main can handle fatal exit
		return err
	}

	// Start watching for new events
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case event, ok := <-dw.watcher.Events:
			if !ok {
				return fmt.Errorf("watcher events channel closed")
			}
			dw.handleEvent(ctx, event)
		case err, ok := <-dw.watcher.Errors:
			if !ok {
				return fmt.Errorf("watcher errors channel closed")
			}
			log.Printf("Watcher error: %v", err)
		}
	}
}

func (dw *DirectoryWatcher) handleEvent(ctx context.Context, event fsnotify.Event) {
	// We're interested in new directories being created
	if event.Op&fsnotify.Create == fsnotify.Create {
		// Check if it's a directory
		fileInfo, err := os.Stat(event.Name)
		if err != nil {
			log.Printf("Failed to stat %s: %v", event.Name, err)
			return
		}

		// Only process directories
		if !fileInfo.IsDir() {
			return
		}

		// Wait a bit for the snapshot to be fully created
		time.Sleep(5 * time.Second)

		// Instead of only uploading the created snapshot, process all
		// snapshots that are not yet uploaded (.done missing)
		if err := dw.processExistingSnapshots(ctx); err != nil {
			if errors.Is(err, ErrS3Upload) {
				log.Printf("S3 error detected while processing snapshots; exiting: %v", err)
				os.Exit(1)
			}
			log.Printf("Error processing pending snapshots after new event %s: %v", event.Name, err)
		}
	}
}

func (dw *DirectoryWatcher) processExistingSnapshots(ctx context.Context) error {
	snapshots, err := FindSnapshots(dw.watchDir)
	if err != nil {
		return fmt.Errorf("failed to find snapshots: %w", err)
	}

	for _, snapshot := range snapshots {
		if !snapshot.HasDone {
			log.Printf("Found unprocessed snapshot: %s", snapshot.Name)
			if err := dw.processSnapshot(ctx, snapshot.Path); err != nil {
				// Exit on S3-specific errors
				if errors.Is(err, ErrS3Upload) {
					return err
				}
				log.Printf("Error processing snapshot %s: %v", snapshot.Name, err)
			}
		}
	}

	return nil
}

func (dw *DirectoryWatcher) processSnapshot(ctx context.Context, snapshotPath string) error {
	log.Printf("Processing snapshot: %s", snapshotPath)

	// Check if already processed
	doneFile := snapshotPath + ".done"
	if _, err := os.Stat(doneFile); err == nil {
		log.Printf("Snapshot already processed (has .done file): %s", snapshotPath)
		return nil
	}

	// Find all snapshots
	snapshots, err := FindSnapshots(dw.watchDir)
	if err != nil {
		return fmt.Errorf("failed to find snapshots: %w", err)
	}

	// Decide upload plan (S3 key, full/incremental, parent)
	key, parentPath, derr := DecideUpload(snapshotPath, snapshots, dw.config.SnapshotPrefix)
	if derr != nil {
		return derr
	}
	if parentPath == nil {
		log.Printf("Creating FULL backup (no parent)")
	} else {
		log.Printf("Creating INCREMENTAL backup with parent: %s", filepath.Base(*parentPath))
	}

	// Create btrfs send stream
	btrfsCmd, btrfsOutput, err := CreateBtrfsSendDiff(snapshotPath, parentPath)
	if err != nil {
		return fmt.Errorf("failed to create btrfs send: %w", err)
	}
	defer btrfsOutput.Close()

	// Compress with zstd
	zstdCmd, zstdOutput, err := CompressWithZstd(btrfsOutput)
	if err != nil {
		btrfsCmd.Process.Kill()
		return fmt.Errorf("failed to start zstd compression: %w", err)
	}
	defer zstdOutput.Close()

	// Wrap with counting reader to measure size
	countingReader := &CountingReader{reader: zstdOutput}

	// Upload to S3
	if err := dw.uploader.UploadStream(ctx, key, countingReader); err != nil {
		btrfsCmd.Process.Kill()
		zstdCmd.Process.Kill()
		return fmt.Errorf("failed to upload to S3: %w", err)
	}

	// Wait for commands to finish
	if err := btrfsCmd.Wait(); err != nil {
		return fmt.Errorf("btrfs send failed: %w", err)
	}
	
	if err := zstdCmd.Wait(); err != nil {
		return fmt.Errorf("zstd compression failed: %w", err)
	}

	// Get the size that was uploaded
	uploadedSize := countingReader.count

	// Create .done file with backup type and size
	bt := "full"
	if parentPath != nil {
		bt = "incremental"
	}
	if err := CreateDoneFile(snapshotPath, bt, uploadedSize); err != nil {
		return fmt.Errorf("failed to create .done file: %w", err)
	}

	snapshotName := filepath.Base(snapshotPath)
	log.Printf("Successfully processed snapshot: %s -> %s (type: %s, size: %d bytes)", 
		snapshotName, key, bt, uploadedSize)
	return nil
}

func (dw *DirectoryWatcher) Close() error {
	return dw.watcher.Close()
}
