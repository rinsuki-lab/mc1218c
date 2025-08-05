package main

import (
	"bufio"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

const SOCKET_PATH = "/run/snapshotter/snapshot.sock"

func main() {
	// Get source path from environment
	src := os.Getenv("SNAPSHOT_SRC")
	if src == "" {
		log.Fatal("SNAPSHOT_SRC environment variable is required")
	}

	// Get optional prefix from environment
	prefix := os.Getenv("SNAPSHOT_PREFIX")

	// Get regex pattern from environment
	pattern := os.Getenv("SNAPSHOT_SUFFIX_REGEX")
	if pattern == "" {
		log.Fatal("SNAPSHOT_SUFFIX_REGEX environment variable is required")
	}

	// Compile regex
	regex, err := regexp.Compile(pattern)
	if err != nil {
		log.Fatalf("Invalid regex pattern: %v", err)
	}

	// Create Unix domain socket
	listener, err := net.Listen("unix", SOCKET_PATH)
	if err != nil {
		log.Fatal("Failed to create socket:", err)
	}
	defer listener.Close()

	log.Printf("Listening on %s, source: %s, prefix: %s, regex: %s", SOCKET_PATH, src, prefix, pattern)

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("Failed to accept connection: %v", err)
			continue
		}

		go handleConnection(conn, src, prefix, regex)
	}
}

func handleConnection(conn net.Conn, src, prefix string, regex *regexp.Regexp) {
	defer conn.Close()

	scanner := bufio.NewScanner(conn)
	if scanner.Scan() {
		suffix := strings.TrimSpace(scanner.Text())
		if suffix != "" {
			// Validate suffix against regex
			if !regex.MatchString(suffix) {
				errMsg := fmt.Sprintf("ERROR: Invalid suffix format. Suffix '%s' does not match pattern\n", suffix)
				log.Print(errMsg)
				conn.Write([]byte(errMsg))
				return
			}

			// Apply prefix if provided
			dst := suffix
			if prefix != "" {
				dst = time.Now().Format(prefix) + dst
			}

			log.Printf("Creating snapshot: %s -> %s", src, dst)
			
			cmd := exec.Command("btrfs", "subvolume", "snapshot", src, dst)
			output, err := cmd.CombinedOutput()
			
			if err != nil {
				errMsg := fmt.Sprintf("ERROR: %v\nOutput: %s\n", err, output)
				log.Print(errMsg)
				conn.Write([]byte(errMsg))
			} else {
				successMsg := fmt.Sprintf("SUCCESS: %s\n", string(output))
				log.Print(successMsg)
				conn.Write([]byte(successMsg))
			}
		}
	}

	if err := scanner.Err(); err != nil {
		log.Printf("Scanner error: %v", err)
	}
}