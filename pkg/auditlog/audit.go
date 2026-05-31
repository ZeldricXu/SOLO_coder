package auditlog

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type chainState struct {
	entries      []*interfaces.AuditEntry
	lastHash     string
	lastIndex    int
	privateKey   *ecdsa.PrivateKey
	publicKey    []byte
}

type DefaultAuditLogger struct {
	state  *chainState
	logger *zap.Logger
	mu     sync.RWMutex
}

func NewDefaultAuditLogger() *DefaultAuditLogger {
	privateKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	publicKeyBytes, _ := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)

	return &DefaultAuditLogger{
		state: &chainState{
			entries:    make([]*interfaces.AuditEntry, 0),
			lastHash:   "0000000000000000000000000000000000000000000000000000000000000000",
			lastIndex:  -1,
			privateKey: privateKey,
			publicKey:  publicKeyBytes,
		},
		logger: utils.GetLogger(),
	}
}

func (a *DefaultAuditLogger) Log(ctx context.Context, entry *interfaces.AuditEntry) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	entry.Index = a.state.lastIndex + 1
	entry.Timestamp = time.Now().Unix()
	entry.PrevHash = a.state.lastHash

	entry.Hash = a.calculateEntryHash(entry)

	signature, err := a.signEntry(entry)
	if err != nil {
		return fmt.Errorf("failed to sign entry: %w", err)
	}
	entry.Signature = signature

	a.state.entries = append(a.state.entries, entry)
	a.state.lastHash = entry.Hash
	a.state.lastIndex = entry.Index

	a.logger.Info("Audit entry logged",
		zap.Int("index", entry.Index),
		zap.String("action", entry.Action),
		zap.String("user_id", entry.UserID),
		zap.String("hash", entry.Hash[:16]+"..."),
	)

	return nil
}

func (a *DefaultAuditLogger) calculateEntryHash(entry *interfaces.AuditEntry) string {
	type hashContent struct {
		Index     int    `json:"index"`
		Timestamp int64  `json:"timestamp"`
		UserID    string `json:"user_id"`
		Action    string `json:"action"`
		Resource  string `json:"resource"`
		Payload   string `json:"payload"`
		PrevHash  string `json:"prev_hash"`
	}

	content := hashContent{
		Index:     entry.Index,
		Timestamp: entry.Timestamp,
		UserID:    entry.UserID,
		Action:    entry.Action,
		Resource:  entry.Resource,
		Payload:   entry.Payload,
		PrevHash:  entry.PrevHash,
	}

	bytes, _ := json.Marshal(content)
	hash := sha256.Sum256(bytes)
	return hex.EncodeToString(hash[:])
}

func (a *DefaultAuditLogger) signEntry(entry *interfaces.AuditEntry) (string, error) {
	hashBytes, _ := hex.DecodeString(entry.Hash)
	r, s, err := ecdsa.Sign(rand.Reader, a.state.privateKey, hashBytes)
	if err != nil {
		return "", err
	}

	signature := append(r.Bytes(), s.Bytes()...)
	return hex.EncodeToString(signature), nil
}

func (a *DefaultAuditLogger) VerifyEntrySignature(entry *interfaces.AuditEntry) (bool, error) {
	publicKey, err := x509.ParsePKIXPublicKey(a.state.publicKey)
	if err != nil {
		return false, err
	}

	ecdsaPubKey, ok := publicKey.(*ecdsa.PublicKey)
	if !ok {
		return false, fmt.Errorf("invalid public key type")
	}

	hashBytes, _ := hex.DecodeString(entry.Hash)
	sigBytes, _ := hex.DecodeString(entry.Signature)

	rBytes := sigBytes[:len(sigBytes)/2]
	sBytes := sigBytes[len(sigBytes)/2:]

	r := new(ecdsaPublicKey).SetBytes(rBytes)
	s := new(ecdsaPublicKey).SetBytes(sBytes)

	return ecdsa.Verify(ecdsaPubKey, hashBytes, r, s), nil
}

type ecdsaPublicKey struct {
	bytes []byte
}

func (k *ecdsaPublicKey) SetBytes(b []byte) *ecdsaPublicKey {
	k.bytes = b
	return k
}

func (a *DefaultAuditLogger) VerifyIntegrity(ctx context.Context, startIndex, endIndex int) (bool, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	if startIndex < 0 || endIndex >= len(a.state.entries) || startIndex > endIndex {
		return false, fmt.Errorf("invalid index range")
	}

	for i := startIndex; i <= endIndex; i++ {
		entry := a.state.entries[i]

		expectedHash := a.calculateEntryHash(entry)
		if expectedHash != entry.Hash {
			a.logger.Warn("Hash mismatch detected",
				zap.Int("index", i),
				zap.String("expected", expectedHash[:16]+"..."),
				zap.String("actual", entry.Hash[:16]+"..."),
			)
			return false, nil
		}

		if i > startIndex {
			prevEntry := a.state.entries[i-1]
			if entry.PrevHash != prevEntry.Hash {
				a.logger.Warn("Chain link broken",
					zap.Int("index", i),
					zap.String("expected_prev", prevEntry.Hash[:16]+"..."),
					zap.String("actual_prev", entry.PrevHash[:16]+"..."),
				)
				return false, nil
			}
		}

		valid, err := a.VerifyEntrySignature(entry)
		if err != nil {
			return false, err
		}
		if !valid {
			a.logger.Warn("Invalid signature", zap.Int("index", i))
			return false, nil
		}
	}

	return true, nil
}

func (a *DefaultAuditLogger) GetEntry(ctx context.Context, index int) (*interfaces.AuditEntry, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	if index < 0 || index >= len(a.state.entries) {
		return nil, utils.ErrNotFound
	}

	entry := *a.state.entries[index]
	return &entry, nil
}

func (a *DefaultAuditLogger) DetectTampering(ctx context.Context) ([]int, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	var tamperedIndices []int

	for i := 0; i < len(a.state.entries); i++ {
		entry := a.state.entries[i]
		expectedHash := a.calculateEntryHash(entry)

		if expectedHash != entry.Hash {
			tamperedIndices = append(tamperedIndices, i)
			continue
		}

		if i > 0 {
			prevEntry := a.state.entries[i-1]
			if entry.PrevHash != prevEntry.Hash {
				tamperedIndices = append(tamperedIndices, i)
			}
		}

		valid, _ := a.VerifyEntrySignature(entry)
		if !valid {
			tamperedIndices = append(tamperedIndices, i)
		}
	}

	if len(tamperedIndices) > 0 {
		a.logger.Warn("Tampering detected", zap.Int("count", len(tamperedIndices)))
	}

	return tamperedIndices, nil
}

func (a *DefaultAuditLogger) GetEntries(startIndex, endIndex int) ([]*interfaces.AuditEntry, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	if startIndex < 0 || endIndex >= len(a.state.entries) || startIndex > endIndex {
		return nil, fmt.Errorf("invalid index range")
	}

	entries := make([]*interfaces.AuditEntry, endIndex-startIndex+1)
	for i := startIndex; i <= endIndex; i++ {
		entry := *a.state.entries[i]
		entries[i-startIndex] = &entry
	}

	return entries, nil
}

func (a *DefaultAuditLogger) GetEntriesByUser(userID string, limit int) []*interfaces.AuditEntry {
	a.mu.RLock()
	defer a.mu.RUnlock()

	var result []*interfaces.AuditEntry
	count := 0

	for i := len(a.state.entries) - 1; i >= 0 && count < limit; i-- {
		if a.state.entries[i].UserID == userID {
			entry := *a.state.entries[i]
			result = append(result, &entry)
			count++
		}
	}

	return result
}

func (a *DefaultAuditLogger) GetEntriesByAction(action string, limit int) []*interfaces.AuditEntry {
	a.mu.RLock()
	defer a.mu.RUnlock()

	var result []*interfaces.AuditEntry
	count := 0

	for i := len(a.state.entries) - 1; i >= 0 && count < limit; i-- {
		if a.state.entries[i].Action == action {
			entry := *a.state.entries[i]
			result = append(result, &entry)
			count++
		}
	}

	return result
}

func (a *DefaultAuditLogger) GetEntriesByResource(resource string, limit int) []*interfaces.AuditEntry {
	a.mu.RLock()
	defer a.mu.RUnlock()

	var result []*interfaces.AuditEntry
	count := 0

	for i := len(a.state.entries) - 1; i >= 0 && count < limit; i-- {
		if a.state.entries[i].Resource == resource {
			entry := *a.state.entries[i]
			result = append(result, &entry)
			count++
		}
	}

	return result
}

func (a *DefaultAuditLogger) GetLastHash() string {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.state.lastHash
}

func (a *DefaultAuditLogger) GetEntryCount() int {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return len(a.state.entries)
}

func (a *DefaultAuditLogger) ExportChain() ([]byte, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	type exportData struct {
		Entries   []*interfaces.AuditEntry `json:"entries"`
		PublicKey string                   `json:"public_key"`
		LastHash  string                   `json:"last_hash"`
	}

	data := exportData{
		Entries:   a.state.entries,
		PublicKey: hex.EncodeToString(a.state.publicKey),
		LastHash:  a.state.lastHash,
	}

	return json.MarshalIndent(data, "", "  ")
}

func (a *DefaultAuditLogger) ImportChain(data []byte) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	type importData struct {
		Entries   []*interfaces.AuditEntry `json:"entries"`
		PublicKey string                   `json:"public_key"`
		LastHash  string                   `json:"last_hash"`
	}

	var imported importData
	if err := json.Unmarshal(data, &imported); err != nil {
		return err
	}

	if len(imported.Entries) == 0 {
		return fmt.Errorf("no entries to import")
	}

	expectedHash := imported.LastHash
	for i := len(imported.Entries) - 1; i >= 0; i-- {
		entry := imported.Entries[i]
		calculatedHash := a.calculateEntryHash(entry)
		if calculatedHash != expectedHash {
			return fmt.Errorf("hash mismatch at index %d", i)
		}
		expectedHash = entry.PrevHash
	}

	a.state.entries = imported.Entries
	a.state.lastHash = imported.LastHash
	a.state.lastIndex = len(imported.Entries) - 1

	a.logger.Info("Audit chain imported", zap.Int("entry_count", len(imported.Entries)))
	return nil
}
