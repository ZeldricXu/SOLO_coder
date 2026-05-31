package audit

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
	"github.com/solocoder/session148/pkg/utils"
)

type HashChainAuditTrail struct {
	storagePath string
	records     []domain.AuditRecord
	hasher      domain.Hasher
	mu          sync.RWMutex
	logger      domain.Logger
	clock       domain.Clock
}

type AuditConfig struct {
	StoragePath string
	Logger      domain.Logger
}

func NewHashChainAuditTrail(cfg AuditConfig) (*HashChainAuditTrail, error) {
	if cfg.StoragePath == "" {
		cfg.StoragePath = "./audit"
	}

	if err := os.MkdirAll(cfg.StoragePath, 0755); err != nil {
		return nil, err
	}

	trail := &HashChainAuditTrail{
		storagePath: cfg.StoragePath,
		records:     []domain.AuditRecord{},
		hasher:      utils.NewSHA256Hasher(),
		logger:      cfg.Logger,
		clock:       utils.NewRealClock(),
	}

	if err := trail.loadRecords(); err != nil {
		return nil, err
	}

	return trail, nil
}

func (a *HashChainAuditTrail) Record(ctx context.Context, record *domain.AuditRecord) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	record.ID = utils.NewAuditID()
	record.Timestamp = a.clock.Now()

	previousHash := ""
	if len(a.records) > 0 {
		previousHash = a.records[len(a.records)-1].Hash
	}
	record.PreviousHash = previousHash

	record.Hash = a.calculateHash(record)

	a.records = append(a.records, *record)

	if err := a.saveRecords(); err != nil {
		a.records = a.records[:len(a.records)-1]
		return err
	}

	a.logger.Info("audit record created", "id", record.ID, "operation", record.Operation, "user", record.UserID)
	return nil
}

func (a *HashChainAuditTrail) VerifyIntegrity(ctx context.Context) (bool, []string, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	var violations []string

	for i, record := range a.records {
		expectedHash := a.calculateHash(&record)
		if record.Hash != expectedHash {
			violations = append(violations, fmt.Sprintf("record %d (id: %s) hash mismatch", i, record.ID))
			continue
		}

		if i > 0 {
			expectedPrev := a.records[i-1].Hash
			if record.PreviousHash != expectedPrev {
				violations = append(violations, fmt.Sprintf("record %d (id: %s) previous hash mismatch", i, record.ID))
			}
		} else {
			if record.PreviousHash != "" {
				violations = append(violations, fmt.Sprintf("genesis record %d has non-empty previous hash", i))
			}
		}
	}

	return len(violations) == 0, violations, nil
}

func (a *HashChainAuditTrail) List(ctx context.Context, limit, offset int) ([]domain.AuditRecord, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}

	start := offset
	end := offset + limit
	if start >= len(a.records) {
		return []domain.AuditRecord{}, nil
	}
	if end > len(a.records) {
		end = len(a.records)
	}

	records := make([]domain.AuditRecord, end-start)
	copy(records, a.records[start:end])

	return records, nil
}

func (a *HashChainAuditTrail) calculateHash(record *domain.AuditRecord) string {
	data := fmt.Sprintf("%s|%s|%s|%s|%v|%s",
		record.ID,
		record.Operation,
		record.UserID,
		record.Resource,
		record.Data,
		record.PreviousHash,
	)
	return a.hasher.Hash([]byte(data))
}

func (a *HashChainAuditTrail) loadRecords() error {
	path := filepath.Join(a.storagePath, "audit.log")
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	lines := splitLines(data)
	for _, line := range lines {
		if len(line) == 0 {
			continue
		}
		var record domain.AuditRecord
		if err := json.Unmarshal(line, &record); err != nil {
			a.logger.Warn("invalid audit record", "error", err)
			continue
		}
		a.records = append(a.records, record)
	}

	return nil
}

func (a *HashChainAuditTrail) saveRecords() error {
	if len(a.records) == 0 {
		return nil
	}

	path := filepath.Join(a.storagePath, "audit.log")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return err
	}
	defer file.Close()

	record := a.records[len(a.records)-1]
	data, err := json.Marshal(record)
	if err != nil {
		return err
	}

	if _, err := file.Write(append(data, '\n')); err != nil {
		return err
	}

	return nil
}

func splitLines(data []byte) [][]byte {
	var lines [][]byte
	start := 0
	for i, b := range data {
		if b == '\n' {
			lines = append(lines, data[start:i])
			start = i + 1
		}
	}
	if start < len(data) {
		lines = append(lines, data[start:])
	}
	return lines
}

func (a *HashChainAuditTrail) GetRecordCount() int {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return len(a.records)
}

func (a *HashChainAuditTrail) QueryByUser(ctx context.Context, userID string, limit int) ([]domain.AuditRecord, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	var result []domain.AuditRecord
	for i := len(a.records) - 1; i >= 0 && len(result) < limit; i-- {
		if a.records[i].UserID == userID {
			result = append(result, a.records[i])
		}
	}
	return result, nil
}

func (a *HashChainAuditTrail) QueryByOperation(ctx context.Context, operation string, limit int) ([]domain.AuditRecord, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	var result []domain.AuditRecord
	for i := len(a.records) - 1; i >= 0 && len(result) < limit; i-- {
		if a.records[i].Operation == operation {
			result = append(result, a.records[i])
		}
	}
	return result, nil
}

func (a *HashChainAuditTrail) QueryByResource(ctx context.Context, resource string, limit int) ([]domain.AuditRecord, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	var result []domain.AuditRecord
	for i := len(a.records) - 1; i >= 0 && len(result) < limit; i-- {
		if a.records[i].Resource == resource {
			result = append(result, a.records[i])
		}
	}
	return result, nil
}

func (a *HashChainAuditTrail) QueryByTimeRange(ctx context.Context, start, end time.Time, limit int) ([]domain.AuditRecord, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	var result []domain.AuditRecord
	for i := len(a.records) - 1; i >= 0 && len(result) < limit; i-- {
		rec := a.records[i]
		if (rec.Timestamp.Equal(start) || rec.Timestamp.After(start)) &&
			(rec.Timestamp.Equal(end) || rec.Timestamp.Before(end)) {
			result = append(result, rec)
		}
	}
	return result, nil
}

type AuditMiddleware struct {
	trail *HashChainAuditTrail
}

func NewAuditMiddleware(trail *HashChainAuditTrail) *AuditMiddleware {
	return &AuditMiddleware{trail: trail}
}

func (m *AuditMiddleware) LogOperation(userID, operation, resource string, data map[string]interface{}) error {
	record := &domain.AuditRecord{
		Operation: operation,
		UserID:    userID,
		Resource:  resource,
		Data:      data,
	}
	return m.trail.Record(context.Background(), record)
}

func (a *HashChainAuditTrail) Export(ctx context.Context, writer interface{ Write([]byte) (int, error) }) error {
	a.mu.RLock()
	defer a.mu.RUnlock()

	for _, record := range a.records {
		data, err := json.Marshal(record)
		if err != nil {
			return err
		}
		if _, err := writer.Write(append(data, '\n')); err != nil {
			return err
		}
	}
	return nil
}

func (a *HashChainAuditTrail) Prune(ctx context.Context, olderThan time.Time) (int, error) {
	a.mu.Lock()
	defer a.mu.Unlock()

	var remaining []domain.AuditRecord
	pruned := 0

	for _, record := range a.records {
		if record.Timestamp.After(olderThan) {
			remaining = append(remaining, record)
		} else {
			pruned++
		}
	}

	if pruned > 0 {
		a.records = remaining
		if err := a.rewriteAll(); err != nil {
			return 0, err
		}
	}

	return pruned, nil
}

func (a *HashChainAuditTrail) rewriteAll() error {
	path := filepath.Join(a.storagePath, "audit.log")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	if err != nil {
		return err
	}
	defer file.Close()

	for _, record := range a.records {
		data, err := json.Marshal(record)
		if err != nil {
			return err
		}
		if _, err := file.Write(append(data, '\n')); err != nil {
			return err
		}
	}
	return nil
}
