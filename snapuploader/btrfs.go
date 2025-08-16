package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
)

type SnapshotInfo struct {
	Path       string
	Name       string
	HasDone    bool
	BackupType string // "full" or "incremental"
	Size       int64  // Size in bytes after zstd compression
}

type DoneFileContent struct {
	Type string `json:"type"` // "full" or "incremental"
	Size int64  `json:"size"` // Size in bytes after zstd compression
}

func ReadDoneFile(doneFile string) (backupType string, size int64, err error) {
	data, err := os.ReadFile(doneFile)
	if err != nil {
		return "", 0, err
	}

	var content DoneFileContent
	if err := json.Unmarshal(data, &content); err != nil {
		// Try to handle old format for backwards compatibility
		return "", 0, err
	}
	
	return content.Type, content.Size, nil
}

func FindSnapshots(watchDir string) ([]SnapshotInfo, error) {
	entries, err := os.ReadDir(watchDir)
	if err != nil {
		return nil, fmt.Errorf("failed to read directory: %w", err)
	}

	var snapshots []SnapshotInfo
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}

		snapshotPath := filepath.Join(watchDir, entry.Name())
		doneFile := snapshotPath + ".done"
		
		info := SnapshotInfo{
			Path: snapshotPath,
			Name: entry.Name(),
		}
		
		if _, err := os.Stat(doneFile); err == nil {
			info.HasDone = true
			// Read backup type and size from .done file
			if backupType, size, err := ReadDoneFile(doneFile); err == nil {
				info.BackupType = backupType
				info.Size = size
			}
		}

		snapshots = append(snapshots, info)
	}

	// Sort by name (assuming names are sortable chronologically)
	sort.Slice(snapshots, func(i, j int) bool {
		return snapshots[i].Name < snapshots[j].Name
	})

	return snapshots, nil
}

func FindLatestParent(snapshots []SnapshotInfo) *SnapshotInfo {
	// Find the most recent FULL backup with .done file
	for i := len(snapshots) - 1; i >= 0; i-- {
		if snapshots[i].HasDone && snapshots[i].BackupType == "full" {
			return &snapshots[i]
		}
	}
	return nil
}

func ShouldCreateFullBackup(parent *SnapshotInfo, snapshots []SnapshotInfo) bool {
	if parent == nil {
		// No parent means first backup, should be full
		return true
	}
	
	// Metrics since the last full backup
	incrementalCount := 0
	var lastIncrementalSize int64 = 0
	var cumulativeIncrementalSize int64 = 0
	for i := len(snapshots) - 1; i >= 0; i-- {
		snapshot := &snapshots[i]
		if snapshot.HasDone && snapshot.BackupType == "full" {
			// Found a full backup, stop counting
			break
		}
		if snapshot.HasDone && snapshot.BackupType == "incremental" {
			incrementalCount++
			cumulativeIncrementalSize += snapshot.Size
			if lastIncrementalSize == 0 {
				lastIncrementalSize = snapshot.Size
			}
		}
	}
	
	// Check if the latest snapshot is a full backup
	for i := len(snapshots) - 1; i >= 0; i-- {
		snapshot := &snapshots[i]
		if snapshot.HasDone && snapshot.BackupType == "full" {
			return false // If full backup is latest snapshot, always create incremental backup
		}
		if snapshot.HasDone && snapshot.BackupType == "incremental" {
			break
		}
	}
	
	// If there are 990 or more incremental backups, create a new full backup
	if incrementalCount >= 990 {
		log.Printf("Creating full backup due to reaching 990 incremental backups (count = %d)", incrementalCount)
		return true
	}
	
	// If last incremental size is more than 1/4 of the full backup size,
	// create a new full backup
	if lastIncrementalSize > 0 && parent.Size > 0 {
		if lastIncrementalSize > parent.Size/4 {
			log.Printf("Creating full backup due to large incremental size: last incremental = %d bytes (%.2f MB), parent full = %d bytes (%.2f MB), ratio = %.2f%%",
				lastIncrementalSize, float64(lastIncrementalSize)/1024/1024,
				parent.Size, float64(parent.Size)/1024/1024,
				float64(lastIncrementalSize)*100/float64(parent.Size))
			return true
		}
	}
	
	// If cumulative incremental size since the last full exceeds the size of the last full,
	// the next backup should be a full backup
	if cumulativeIncrementalSize > 0 && parent.Size > 0 && cumulativeIncrementalSize > parent.Size {
		log.Printf(
			"Creating full backup due to cumulative incremental size exceeding full: cumulative = %d bytes (%.2f MB), parent full = %d bytes (%.2f MB), ratio = %.2f%%",
			cumulativeIncrementalSize, float64(cumulativeIncrementalSize)/1024/1024,
			parent.Size, float64(parent.Size)/1024/1024,
			float64(cumulativeIncrementalSize)*100/float64(parent.Size),
		)
		return true
	}
	
	return false
}

func CreateBtrfsSendDiff(snapshotPath string, parentPath *string) (*exec.Cmd, io.ReadCloser, error) {
	args := []string{"send"}
	
	if parentPath != nil {
		args = append(args, "-p", *parentPath)
	}
	
	args = append(args, snapshotPath)
	
	cmd := exec.Command("btrfs", args...)
	
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, nil, fmt.Errorf("failed to create stdout pipe: %w", err)
	}
	
	cmd.Stderr = os.Stderr
	
	if err := cmd.Start(); err != nil {
		return nil, nil, fmt.Errorf("failed to start btrfs send: %w", err)
	}
	
	log.Printf("Started btrfs send for %s", snapshotPath)
	if parentPath != nil {
		log.Printf("  Using parent: %s", *parentPath)
	} else {
		log.Printf("  No parent (full backup)")
	}
	
	return cmd, stdout, nil
}

func CompressWithZstd(input io.Reader) (*exec.Cmd, io.ReadCloser, error) {
	cmd := exec.Command("zstd", "--ultra", "-22", "-T1")
	
	cmd.Stdin = input
	
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, nil, fmt.Errorf("failed to create stdout pipe: %w", err)
	}
	
	cmd.Stderr = os.Stderr
	
	if err := cmd.Start(); err != nil {
		return nil, nil, fmt.Errorf("failed to start zstd: %w", err)
	}
	
	log.Printf("Started zstd compression with level 22")
	
	return cmd, stdout, nil
}

func CreateDoneFile(snapshotPath string, backupType string, size int64) error {
	doneFile := snapshotPath + ".done"
	
	content := DoneFileContent{
		Type: backupType,
		Size: size,
	}
	
	data, err := json.MarshalIndent(content, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal done file content: %w", err)
	}
	
	if err := os.WriteFile(doneFile, data, 0644); err != nil {
		return fmt.Errorf("failed to write .done file: %w", err)
	}
	
	log.Printf("Created .done file in %s (type: %s, size: %d bytes)", snapshotPath, backupType, size)
	
	return nil
}

func GetSnapshotKey(snapshotName string, parentName string, prefix string) string {
	var key string
	
	if parentName == "" {
		// Full backup: backup/<name>/full.zst
		key = fmt.Sprintf("backup/%s/full.zst", snapshotName)
	} else {
		// Incremental backup: backup/<parent>/incremental.<name>.zst
		key = fmt.Sprintf("backup/%s/incremental.%s.zst", parentName, snapshotName)
	}
	
	if prefix != "" {
		key = strings.TrimSuffix(prefix, "/") + "/" + key
	}
	
	return key
}
