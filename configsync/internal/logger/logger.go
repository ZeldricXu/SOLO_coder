package logger

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"configsync/internal/models"
)

type Logger struct {
	logDir    string
	logFile   string
	mu        sync.RWMutex
}

func NewLogger(logDir string) (*Logger, error) {
	l := &Logger{
		logDir:  logDir,
		logFile: filepath.Join(logDir, "changes.json"),
	}

	if err := os.MkdirAll(logDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create log directory: %w", err)
	}

	if _, err := os.Stat(l.logFile); os.IsNotExist(err) {
		if err := os.WriteFile(l.logFile, []byte("[]"), 0644); err != nil {
			return nil, fmt.Errorf("failed to create log file: %w", err)
		}
	}

	return l, nil
}

func (l *Logger) loadRecords() ([]models.ChangeRecord, error) {
	data, err := os.ReadFile(l.logFile)
	if err != nil {
		return nil, fmt.Errorf("failed to read log file: %w", err)
	}

	var records []models.ChangeRecord
	if err := json.Unmarshal(data, &records); err != nil {
		return nil, fmt.Errorf("failed to parse log file: %w", err)
	}

	return records, nil
}

func (l *Logger) saveRecords(records []models.ChangeRecord) error {
	data, err := json.MarshalIndent(records, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal records: %w", err)
	}

	if err := os.WriteFile(l.logFile, data, 0644); err != nil {
		return fmt.Errorf("failed to write log file: %w", err)
	}

	return nil
}

func (l *Logger) LogChange(record *models.ChangeRecord) error {
	l.mu.Lock()
	defer l.mu.Unlock()

	records, err := l.loadRecords()
	if err != nil {
		return err
	}

	if record.ChangeID == "" {
		record.ChangeID = generateChangeID(len(records) + 1)
	}

	if record.ExecutedAt.IsZero() {
		record.ExecutedAt = time.Now().UTC()
	}

	records = append(records, *record)
	return l.saveRecords(records)
}

func (l *Logger) GetHistory(configFile string, limit int) ([]models.ChangeRecord, error) {
	l.mu.RLock()
	defer l.mu.RUnlock()

	records, err := l.loadRecords()
	if err != nil {
		return nil, err
	}

	var filtered []models.ChangeRecord
	for _, r := range records {
		if configFile == "" || strings.EqualFold(r.ConfigFile, configFile) {
			filtered = append(filtered, r)
		}
	}

	sort.Slice(filtered, func(i, j int) bool {
		return filtered[i].ExecutedAt.After(filtered[j].ExecutedAt)
	})

	if limit > 0 && len(filtered) > limit {
		filtered = filtered[:limit]
	}

	return filtered, nil
}

func (l *Logger) GetChangeByID(changeID string) (*models.ChangeRecord, error) {
	l.mu.RLock()
	defer l.mu.RUnlock()

	records, err := l.loadRecords()
	if err != nil {
		return nil, err
	}

	for _, r := range records {
		if r.ChangeID == changeID {
			return &r, nil
		}
	}

	return nil, fmt.Errorf("change record not found: %s", changeID)
}

func (l *Logger) GetChangesByGroup(groupName string, limit int) ([]models.ChangeRecord, error) {
	l.mu.RLock()
	defer l.mu.RUnlock()

	records, err := l.loadRecords()
	if err != nil {
		return nil, err
	}

	var filtered []models.ChangeRecord
	for _, r := range records {
		if r.TargetGroup == groupName {
			filtered = append(filtered, r)
		}
	}

	sort.Slice(filtered, func(i, j int) bool {
		return filtered[i].ExecutedAt.After(filtered[j].ExecutedAt)
	})

	if limit > 0 && len(filtered) > limit {
		filtered = filtered[:limit]
	}

	return filtered, nil
}

func generateChangeID(index int) string {
	return fmt.Sprintf("change_%03d", index)
}
