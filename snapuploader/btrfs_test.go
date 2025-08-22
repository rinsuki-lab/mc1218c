package main

import (
	"path/filepath"
	"testing"
)

func TestDecideUpload_FullWhenNoFullParent(t *testing.T) {
    // Only the current snapshot exists, no completed full yet
    current := SnapshotInfo{
        Path:    "/watch/snap-0002",
        Name:    "snap-0002",
        HasDone: false,
    }
    snapshots := []SnapshotInfo{current}
    snapPath := current.Path

    key, parentPath, err := DecideUpload(snapPath, snapshots, "")
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }

    if parentPath != nil {
        t.Fatalf("expected parentPath to be nil for full backup, got %v", *parentPath)
    }

    expected := "backup/snap-0002/full.zst"
    if key != expected {
        t.Fatalf("unexpected key: want %q, got %q", expected, key)
    }
}

func TestDecideUpload_IncrementalWithParent(t *testing.T) {
    parent := SnapshotInfo{
        Path:       "/watch/snap-0001",
        Name:       "snap-0001",
        HasDone:    true,
        BackupType: "full",
        Size:       1000,
    }
    current := SnapshotInfo{
        Path:    "/watch/snap-0002",
        Name:    "snap-0002",
        HasDone: false,
    }
    snapshots := []SnapshotInfo{parent, current}

    snapPath := current.Path
    key, parentPath, err := DecideUpload(snapPath, snapshots, "")
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }

    if parentPath == nil {
        t.Fatalf("expected parentPath for incremental backup, got nil")
    }
    if *parentPath != parent.Path {
        t.Fatalf("unexpected parentPath: want %q, got %q", parent.Path, *parentPath)
    }

    expected := "backup/snap-0001/incremental.snap-0002.zst"
    if key != expected {
        t.Fatalf("unexpected key: want %q, got %q", expected, key)
    }
}

func TestDecideUpload_FullDueToLargeLastIncremental(t *testing.T) {
    // Parent full with size 1000, last incremental 300 (> 1/4 of 1000)
    parent := SnapshotInfo{
        Path:       "/watch/snap-0001",
        Name:       "snap-0001",
        HasDone:    true,
        BackupType: "full",
        Size:       1000,
    }
    lastIncr := SnapshotInfo{
        Path:       "/watch/snap-0002",
        Name:       "snap-0002",
        HasDone:    true,
        BackupType: "incremental",
        Size:       300,
    }
    current := SnapshotInfo{
        Path:    "/watch/snap-0003",
        Name:    "snap-0003",
        HasDone: false,
    }
    snapshots := []SnapshotInfo{parent, lastIncr, current}

    // New snapshot should be full according to ShouldCreateFullBackup
    snapPath := current.Path
    key, parentPath, err := DecideUpload(snapPath, snapshots, "")
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }

    if parentPath != nil {
        t.Fatalf("expected parentPath to be nil for full backup, got %v", *parentPath)
    }

    expected := "backup/snap-0003/full.zst"
    if key != expected {
        t.Fatalf("unexpected key: want %q, got %q", expected, key)
    }
}

func TestDecideUpload_PrefixHandling(t *testing.T) {
    parent := SnapshotInfo{
        Path:       "/watch/snap-0001",
        Name:       "snap-0001",
        HasDone:    true,
        BackupType: "full",
        Size:       1000,
    }
    current := SnapshotInfo{
        Path:    "/watch/snap-0002",
        Name:    "snap-0002",
        HasDone: false,
    }
    snapshots := []SnapshotInfo{parent, current}

    snapPath := current.Path

    // Prefix without trailing slash
    key, _, err := DecideUpload(snapPath, snapshots, "prefix")
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }
    expected := "prefix/backup/snap-0001/incremental.snap-0002.zst"
    if key != expected {
        t.Fatalf("unexpected key (no slash): want %q, got %q", expected, key)
    }

    // Prefix with trailing slash
    key2, _, err := DecideUpload(snapPath, snapshots, "prefix/")
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }
    expected2 := "prefix/backup/snap-0001/incremental.snap-0002.zst"
    if key2 != expected2 {
        t.Fatalf("unexpected key (with slash): want %q, got %q", expected2, key2)
    }

    // Ensure parent name is derived from parent path correctly in the key
    if filepath.Base(parent.Path) != parent.Name {
        t.Fatalf("test setup invalid: base(%q) != %q", parent.Path, parent.Name)
    }
}

func TestDecideUpload_ErrorWhenNoSnapshots(t *testing.T) {
    // No snapshots should result in error
    var snapshots []SnapshotInfo
    snapPath := "/watch/snap-0001"
    _, _, err := DecideUpload(snapPath, snapshots, "")
    if err == nil {
        t.Fatalf("expected error when no snapshots, got nil")
    }
}

func TestDecideUpload_ErrorWhenCurrentNotInList(t *testing.T) {
    // Snapshots exist but don't include the current one
    snapshots := []SnapshotInfo{{
        Path:    "/watch/snap-0001",
        Name:    "snap-0001",
        HasDone: true,
        BackupType: "full",
        Size: 100,
    }}
    snapPath := "/watch/snap-9999" // not included
    _, _, err := DecideUpload(snapPath, snapshots, "")
    if err == nil {
        t.Fatalf("expected error when current snapshot not in list, got nil")
    }
}

func TestDecideUpload_SnapshotsIncludeCurrent_Incremental(t *testing.T) {
    // Parent full exists, snapshots slice also includes the current snapshot (HasDone=false)
    parent := SnapshotInfo{
        Path:       "/watch/snap-0001",
        Name:       "snap-0001",
        HasDone:    true,
        BackupType: "full",
        Size:       1000,
    }
    current := SnapshotInfo{
        Path:    "/watch/snap-0002",
        Name:    "snap-0002",
        HasDone: false,
    }
    snapshots := []SnapshotInfo{parent, current}

    key, parentPath, err := DecideUpload(current.Path, snapshots, "")
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }
    if parentPath == nil || *parentPath != parent.Path {
        t.Fatalf("expected parentPath to be %q, got %v", parent.Path, parentPath)
    }
    expected := "backup/snap-0001/incremental.snap-0002.zst"
    if key != expected {
        t.Fatalf("unexpected key: want %q, got %q", expected, key)
    }
}

func TestDecideUpload_SnapshotsIncludeCurrent_FullByPolicy(t *testing.T) {
    // Parent full exists, last incremental large triggers full; snapshots include current
    parent := SnapshotInfo{
        Path:       "/watch/snap-0001",
        Name:       "snap-0001",
        HasDone:    true,
        BackupType: "full",
        Size:       1000,
    }
    lastIncr := SnapshotInfo{
        Path:       "/watch/snap-0002",
        Name:       "snap-0002",
        HasDone:    true,
        BackupType: "incremental",
        Size:       300,
    }
    current := SnapshotInfo{
        Path:    "/watch/snap-0003",
        Name:    "snap-0003",
        HasDone: false,
    }
    snapshots := []SnapshotInfo{parent, lastIncr, current}

    key, parentPath, err := DecideUpload(current.Path, snapshots, "")
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }
    if parentPath != nil {
        t.Fatalf("expected full backup (nil parent), got %v", *parentPath)
    }
    expected := "backup/snap-0003/full.zst"
    if key != expected {
        t.Fatalf("unexpected key: want %q, got %q", expected, key)
    }
}

func TestDecideUpload_ThirdBackupIncremental(t *testing.T) {
    // Parent full exists, one small incremental exists; third should be incremental
    parent := SnapshotInfo{
        Path:       "/watch/snap-0001",
        Name:       "snap-0001",
        HasDone:    true,
        BackupType: "full",
        Size:       1000,
    }
    incr1 := SnapshotInfo{
        Path:       "/watch/snap-0002",
        Name:       "snap-0002",
        HasDone:    true,
        BackupType: "incremental",
        Size:       100, // small incremental
    }
    current := SnapshotInfo{
        Path:    "/watch/snap-0003",
        Name:    "snap-0003",
        HasDone: false,
    }
    snapshots := []SnapshotInfo{parent, incr1, current}

    key, parentPath, err := DecideUpload(current.Path, snapshots, "")
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }
    if parentPath == nil || *parentPath != parent.Path {
        t.Fatalf("expected parentPath to be parent full %q, got %v", parent.Path, parentPath)
    }
    expected := "backup/snap-0001/incremental.snap-0003.zst"
    if key != expected {
        t.Fatalf("unexpected key: want %q, got %q", expected, key)
    }
}

func TestDecideUpload_FullDueToCumulativeIncrementals(t *testing.T) {
    // Parent full is 1000. Add multiple small incrementals (<= 1/4 of full)
    // so each individual one is incremental, but cumulative exceeds full,
    // forcing the next backup to be full.
    parent := SnapshotInfo{
        Path:       "/watch/snap-0001",
        Name:       "snap-0001",
        HasDone:    true,
        BackupType: "full",
        Size:       1000,
    }

    // Prepare 5 small incrementals of 210 each (<= 250), cumulative 1050 > 1000
    incrs := []SnapshotInfo{
        {Path: "/watch/snap-0002", Name: "snap-0002", HasDone: true, BackupType: "incremental", Size: 210},
        {Path: "/watch/snap-0003", Name: "snap-0003", HasDone: true, BackupType: "incremental", Size: 210},
        {Path: "/watch/snap-0004", Name: "snap-0004", HasDone: true, BackupType: "incremental", Size: 210},
        {Path: "/watch/snap-0005", Name: "snap-0005", HasDone: true, BackupType: "incremental", Size: 210},
        {Path: "/watch/snap-0006", Name: "snap-0006", HasDone: true, BackupType: "incremental", Size: 210},
    }

    // Verify each incremental would be incremental at its time (before it was done)
    // by constructing the snapshots list with current (HasDone=false) and previous ones done.
    prev := []SnapshotInfo{parent}
    for i := 0; i < len(incrs); i++ {
        // previous done + current as not done
        current := SnapshotInfo{Path: incrs[i].Path, Name: incrs[i].Name, HasDone: false}
        snapshots := append(append([]SnapshotInfo{}, prev...), current)

        key, parentPath, err := DecideUpload(current.Path, snapshots, "")
        if err != nil {
            t.Fatalf("unexpected error at incr %d: %v", i+1, err)
        }
        if parentPath == nil || *parentPath != parent.Path {
            t.Fatalf("expected incremental with parent %q at incr %d, got %v", parent.Path, i+1, parentPath)
        }
        expectedKey := "backup/" + parent.Name + "/incremental." + current.Name + ".zst"
        if key != expectedKey {
            t.Fatalf("unexpected key at incr %d: want %q, got %q", i+1, expectedKey, key)
        }

        // mark current as done and move to next
        prev = append(prev, incrs[i])
    }

    // Now after 5 incrementals are done, the next snapshot should be full due to cumulative size
    next := SnapshotInfo{Path: "/watch/snap-0007", Name: "snap-0007", HasDone: false}
    snapshots := append(prev, next)
    key, parentPath, err := DecideUpload(next.Path, snapshots, "")
    if err != nil {
        t.Fatalf("unexpected error on next snapshot: %v", err)
    }
    if parentPath != nil {
        t.Fatalf("expected full due to cumulative incrementals, got parent %v", *parentPath)
    }
    expectedFull := "backup/" + next.Name + "/full.zst"
    if key != expectedFull {
        t.Fatalf("unexpected key for full due to cumulative: want %q, got %q", expectedFull, key)
    }
}
