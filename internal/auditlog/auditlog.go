package auditlog

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/apishield/apishield/internal/core/ports"
)

type hashNode struct {
	hash  []byte
	left  *hashNode
	right *hashNode
}

type auditLogService struct {
	logs []ports.AuditLogEntry
	mu   sync.RWMutex
}

func NewAuditLogService() ports.AuditLogPort {
	return &auditLogService{
		logs: make([]ports.AuditLogEntry, 0),
	}
}

func (s *auditLogService) AppendLog(ctx context.Context, actor, action, resource string, metadata map[string]string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	entry := ports.AuditLogEntry{
		Timestamp: time.Now().UnixNano(),
		Actor:     actor,
		Action:    action,
		Resource:  resource,
		Metadata:  metadata,
	}

	if len(s.logs) > 0 {
		entry.PreviousHash = s.logs[len(s.logs)-1].Hash
	} else {
		entry.PreviousHash = make([]byte, 32)
	}

	entry.Hash = s.calculateHash(entry)
	s.logs = append(s.logs, entry)

	return nil
}

func (s *auditLogService) VerifyIntegrity(ctx context.Context) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if len(s.logs) == 0 {
		return true, nil
	}

	for i := 0; i < len(s.logs); i++ {
		entry := s.logs[i]
		expectedHash := s.calculateHash(entry)

		if !hashEqual(entry.Hash, expectedHash) {
			return false, nil
		}

		if i > 0 {
			if !hashEqual(entry.PreviousHash, s.logs[i-1].Hash) {
				return false, nil
			}
		} else {
			zeroHash := make([]byte, 32)
			if !hashEqual(entry.PreviousHash, zeroHash) {
				return false, nil
			}
		}
	}

	return true, nil
}

func (s *auditLogService) DetectTampering(ctx context.Context) ([]int, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	tamperedIndices := make([]int, 0)

	if len(s.logs) == 0 {
		return tamperedIndices, nil
	}

	for i := 0; i < len(s.logs); i++ {
		entry := s.logs[i]
		expectedHash := s.calculateHash(entry)

		if !hashEqual(entry.Hash, expectedHash) {
			tamperedIndices = append(tamperedIndices, i)
			continue
		}

		if i > 0 {
			if !hashEqual(entry.PreviousHash, s.logs[i-1].Hash) {
				tamperedIndices = append(tamperedIndices, i)
			}
		}
	}

	return tamperedIndices, nil
}

func (s *auditLogService) GetLogs(ctx context.Context, offset, limit int) ([]ports.AuditLogEntry, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if offset < 0 {
		return nil, errors.New("offset must be non-negative")
	}
	if limit < 0 {
		return nil, errors.New("limit must be non-negative")
	}
	if offset >= len(s.logs) {
		return []ports.AuditLogEntry{}, nil
	}

	end := offset + limit
	if end > len(s.logs) {
		end = len(s.logs)
	}

	result := make([]ports.AuditLogEntry, end-offset)
	copy(result, s.logs[offset:end])

	return result, nil
}

func (s *auditLogService) calculateHash(entry ports.AuditLogEntry) []byte {
	metadataKeys := make([]string, 0, len(entry.Metadata))
	for k := range entry.Metadata {
		metadataKeys = append(metadataKeys, k)
	}
	sort.Strings(metadataKeys)

	metadataStr := ""
	for _, k := range metadataKeys {
		metadataStr += fmt.Sprintf("%s:%s;", k, entry.Metadata[k])
	}

	data := fmt.Sprintf("%d|%s|%s|%s|%s|%s",
		entry.Timestamp,
		entry.Actor,
		entry.Action,
		entry.Resource,
		metadataStr,
		hex.EncodeToString(entry.PreviousHash),
	)

	hash := sha256.Sum256([]byte(data))
	return hash[:]
}

func hashEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func (s *auditLogService) CalculateMerkleRoot(ctx context.Context, start, end int) ([]byte, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if start < 0 || end > len(s.logs) || start >= end {
		return nil, errors.New("invalid range")
	}

	hashes := make([][]byte, 0, end-start)
	for i := start; i < end; i++ {
		hashes = append(hashes, s.logs[i].Hash)
	}

	return buildMerkleRoot(hashes), nil
}

func buildMerkleRoot(hashes [][]byte) []byte {
	if len(hashes) == 0 {
		empty := sha256.Sum256([]byte{})
		return empty[:]
	}

	if len(hashes) == 1 {
		return hashes[0]
	}

	nodes := make([]*hashNode, len(hashes))
	for i, h := range hashes {
		nodes[i] = &hashNode{hash: h}
	}

	for len(nodes) > 1 {
		nextLevel := make([]*hashNode, 0)
		for i := 0; i < len(nodes); i += 2 {
			if i+1 < len(nodes) {
				combined := append(nodes[i].hash, nodes[i+1].hash...)
				hash := sha256.Sum256(combined)
				nextLevel = append(nextLevel, &hashNode{
					hash:  hash[:],
					left:  nodes[i],
					right: nodes[i+1],
				})
			} else {
				nextLevel = append(nextLevel, nodes[i])
			}
		}
		nodes = nextLevel
	}

	return nodes[0].hash
}

func (s *auditLogService) ExportLogs(ctx context.Context) ([]byte, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return json.Marshal(s.logs)
}
