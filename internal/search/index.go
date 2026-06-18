package search

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/blevesearch/bleve/v2"
	"github.com/blevesearch/bleve/v2/mapping"
	"github.com/goccy/go-json"
)

const (
	IndexTypeDocument   = "document"
	IndexTypeAttachment = "attachment"
	indexDirName        = "search_indexes"
)

type IndexManager struct {
	basePath     string
	indexes      map[string]*IndexEntry
	mu           sync.RWMutex
	indexWriters map[string]*IndexWriterPool
}

type IndexEntry struct {
	Index     bleve.Index
	Path      string
	TenantID  string
	SpaceID   string
	IndexType string
	CreatedAt time.Time
	UpdatedAt time.Time
	DocCount  uint64
}

type IndexWriterPool struct {
	pool    chan *indexWriter
	mu      sync.Mutex
	count   int
	maxSize int
}

type indexWriter struct {
	index bleve.Index
}

type IndexKey struct {
	TenantID  string
	SpaceID   string
	IndexType string
}

func (k IndexKey) String() string {
	return fmt.Sprintf("%s_%s_%s", k.TenantID, k.SpaceID, k.IndexType)
}

func NewIndexManager(basePath string) (*IndexManager, error) {
	indexPath := filepath.Join(basePath, indexDirName)
	if err := os.MkdirAll(indexPath, 0755); err != nil {
		return nil, fmt.Errorf("failed to create index directory: %w", err)
	}

	im := &IndexManager{
		basePath:     indexPath,
		indexes:      make(map[string]*IndexEntry),
		indexWriters: make(map[string]*IndexWriterPool),
	}

	if err := RegisterCustomAnalyzers(); err != nil {
		return nil, fmt.Errorf("failed to register custom analyzers: %w", err)
	}

	return im, nil
}

func (im *IndexManager) Shutdown() error {
	im.mu.Lock()
	defer im.mu.Unlock()

	for key, entry := range im.indexes {
		if entry.Index != nil {
			if err := entry.Index.Close(); err != nil {
				_ = err
			}
		}
		delete(im.indexes, key)
	}

	for key, pool := range im.indexWriters {
		if pool != nil {
			close(pool.pool)
		}
		delete(im.indexWriters, key)
	}

	return nil
}

func (im *IndexManager) IndexPath(key IndexKey) string {
	return filepath.Join(im.basePath, fmt.Sprintf("%s_%s_%s.bleve", key.TenantID, key.SpaceID, key.IndexType))
}

func (im *IndexManager) GetOrCreateIndex(key IndexKey) (bleve.Index, error) {
	keyStr := key.String()

	im.mu.RLock()
	if entry, ok := im.indexes[keyStr]; ok {
		im.mu.RUnlock()
		return entry.Index, nil
	}
	im.mu.RUnlock()

	im.mu.Lock()
	defer im.mu.Unlock()

	if entry, ok := im.indexes[keyStr]; ok {
		return entry.Index, nil
	}

	idxPath := im.IndexPath(key)
	var idx bleve.Index
	var err error

	if _, statErr := os.Stat(idxPath); statErr == nil {
		idx, err = bleve.Open(idxPath)
		if err != nil {
			return nil, fmt.Errorf("failed to open existing index at %s: %w", idxPath, err)
		}
	} else {
		var indexMapping mapping.IndexMapping
		switch key.IndexType {
		case IndexTypeDocument:
			indexMapping = BuildDocumentMapping()
		case IndexTypeAttachment:
			indexMapping = BuildAttachmentMapping()
		default:
			indexMapping = BuildDocumentMapping()
		}

		idx, err = bleve.New(idxPath, indexMapping)
		if err != nil {
			return nil, fmt.Errorf("failed to create new index at %s: %w", idxPath, err)
		}
	}

	now := time.Now()
	docCount, _ := idx.DocCount()

	im.indexes[keyStr] = &IndexEntry{
		Index:     idx,
		Path:      idxPath,
		TenantID:  key.TenantID,
		SpaceID:   key.SpaceID,
		IndexType: key.IndexType,
		CreatedAt: now,
		UpdatedAt: now,
		DocCount:  docCount,
	}

	return idx, nil
}

func (im *IndexManager) GetIndex(key IndexKey) (bleve.Index, error) {
	keyStr := key.String()

	im.mu.RLock()
	entry, ok := im.indexes[keyStr]
	im.mu.RUnlock()

	if !ok {
		return nil, fmt.Errorf("index not found for key: %s", keyStr)
	}
	return entry.Index, nil
}

func (im *IndexManager) DeleteIndex(key IndexKey) error {
	keyStr := key.String()

	im.mu.Lock()
	defer im.mu.Unlock()

	entry, ok := im.indexes[keyStr]
	if !ok {
		return fmt.Errorf("index not found for key: %s", keyStr)
	}

	if entry.Index != nil {
		if err := entry.Index.Close(); err != nil {
			_ = err
		}
	}

	if err := os.RemoveAll(entry.Path); err != nil {
		return fmt.Errorf("failed to remove index files: %w", err)
	}

	delete(im.indexes, keyStr)
	delete(im.indexWriters, keyStr)

	return nil
}

func (im *IndexManager) IndexDocument(key IndexKey, docID string, doc interface{}) error {
	idx, err := im.GetOrCreateIndex(key)
	if err != nil {
		return err
	}

	docBytes, err := json.Marshal(doc)
	if err != nil {
		return fmt.Errorf("failed to marshal document: %w", err)
	}

	var docMap map[string]interface{}
	if err := json.Unmarshal(docBytes, &docMap); err != nil {
		return fmt.Errorf("failed to unmarshal document to map: %w", err)
	}

	batch := idx.NewBatch()
	if err := batch.Index(docID, docMap); err != nil {
		return fmt.Errorf("failed to index document in batch: %w", err)
	}

	if err := idx.Batch(batch); err != nil {
		return fmt.Errorf("failed to execute batch: %w", err)
	}

	im.updateIndexMeta(key)

	return nil
}

func (im *IndexManager) IndexDocumentsBatch(key IndexKey, docs map[string]interface{}) error {
	if len(docs) == 0 {
		return nil
	}

	idx, err := im.GetOrCreateIndex(key)
	if err != nil {
		return err
	}

	batch := idx.NewBatch()
	batchSize := 0
	maxBatchSize := 100

	for docID, doc := range docs {
		docBytes, marshalErr := json.Marshal(doc)
		if marshalErr != nil {
			return fmt.Errorf("failed to marshal document %s: %w", docID, marshalErr)
		}

		var docMap map[string]interface{}
		if unmarshalErr := json.Unmarshal(docBytes, &docMap); unmarshalErr != nil {
			return fmt.Errorf("failed to unmarshal document %s: %w", docID, unmarshalErr)
		}

		if indexErr := batch.Index(docID, docMap); indexErr != nil {
			return fmt.Errorf("failed to index document %s in batch: %w", docID, indexErr)
		}

		batchSize++
		if batchSize >= maxBatchSize {
			if batchErr := idx.Batch(batch); batchErr != nil {
				return fmt.Errorf("failed to execute batch: %w", batchErr)
			}
			batch = idx.NewBatch()
			batchSize = 0
		}
	}

	if batchSize > 0 {
		if batchErr := idx.Batch(batch); batchErr != nil {
			return fmt.Errorf("failed to execute final batch: %w", batchErr)
		}
	}

	im.updateIndexMeta(key)

	return nil
}

func (im *IndexManager) DeleteDocument(key IndexKey, docID string) error {
	idx, err := im.GetOrCreateIndex(key)
	if err != nil {
		return err
	}

	if err := idx.Delete(docID); err != nil {
		return fmt.Errorf("failed to delete document %s: %w", docID, err)
	}

	im.updateIndexMeta(key)

	return nil
}

func (im *IndexManager) DeleteDocumentsBatch(key IndexKey, docIDs []string) error {
	if len(docIDs) == 0 {
		return nil
	}

	idx, err := im.GetOrCreateIndex(key)
	if err != nil {
		return err
	}

	batch := idx.NewBatch()
	for _, docID := range docIDs {
		batch.Delete(docID)
	}

	if err := idx.Batch(batch); err != nil {
		return fmt.Errorf("failed to execute delete batch: %w", err)
	}

	im.updateIndexMeta(key)

	return nil
}

func (im *IndexManager) UpdateDocument(key IndexKey, docID string, doc interface{}) error {
	idx, err := im.GetOrCreateIndex(key)
	if err != nil {
		return err
	}

	docBytes, err := json.Marshal(doc)
	if err != nil {
		return fmt.Errorf("failed to marshal document: %w", err)
	}

	var docMap map[string]interface{}
	if err := json.Unmarshal(docBytes, &docMap); err != nil {
		return fmt.Errorf("failed to unmarshal document to map: %w", err)
	}

	batch := idx.NewBatch()
	batch.Delete(docID)
	if err := batch.Index(docID, docMap); err != nil {
		return fmt.Errorf("failed to re-index document: %w", err)
	}

	if err := idx.Batch(batch); err != nil {
		return fmt.Errorf("failed to execute batch: %w", err)
	}

	im.updateIndexMeta(key)

	return nil
}

func (im *IndexManager) DocumentExists(key IndexKey, docID string) (bool, error) {
	idx, err := im.GetOrCreateIndex(key)
	if err != nil {
		return false, err
	}

	doc, err := idx.Document(docID)
	if err != nil {
		return false, err
	}

	return doc != nil, nil
}

func (im *IndexManager) DocCount(key IndexKey) (uint64, error) {
	keyStr := key.String()

	im.mu.RLock()
	entry, ok := im.indexes[keyStr]
	im.mu.RUnlock()

	if !ok {
		idx, err := im.GetOrCreateIndex(key)
		if err != nil {
			return 0, err
		}
		return idx.DocCount()
	}

	return entry.DocCount, nil
}

func (im *IndexManager) updateIndexMeta(key IndexKey) {
	keyStr := key.String()

	im.mu.Lock()
	defer im.mu.Unlock()

	if entry, ok := im.indexes[keyStr]; ok {
		docCount, _ := entry.Index.DocCount()
		entry.DocCount = docCount
		entry.UpdatedAt = time.Now()
	}
}

func (im *IndexManager) ListIndexes() []IndexKey {
	im.mu.RLock()
	defer im.mu.RUnlock()

	keys := make([]IndexKey, 0, len(im.indexes))
	for _, entry := range im.indexes {
		keys = append(keys, IndexKey{
			TenantID:  entry.TenantID,
			SpaceID:   entry.SpaceID,
			IndexType: entry.IndexType,
		})
	}

	return keys
}

func (im *IndexManager) GetIndexStats(key IndexKey) (map[string]interface{}, error) {
	idx, err := im.GetOrCreateIndex(key)
	if err != nil {
		return nil, err
	}

	docCount, _ := idx.DocCount()
	fields, _ := idx.Fields()
	mapping := idx.Mapping()

	stats := map[string]interface{}{
		"tenant_id":   key.TenantID,
		"space_id":    key.SpaceID,
		"index_type":  key.IndexType,
		"doc_count":   docCount,
		"fields":      fields,
		"has_mapping": mapping != nil,
	}

	return stats, nil
}

func (im *IndexManager) CompactIndex(key IndexKey) error {
	idx, err := im.GetOrCreateIndex(key)
	if err != nil {
		return err
	}

	_ = idx

	im.updateIndexMeta(key)
	return nil
}

func (im *IndexManager) BackupIndex(key IndexKey, backupPath string) error {
	idx, err := im.GetOrCreateIndex(key)
	if err != nil {
		return err
	}

	_ = idx
	_ = backupPath

	return nil
}

func (im *IndexManager) RestoreIndex(key IndexKey, backupPath string) error {
	keyStr := key.String()

	im.mu.Lock()
	defer im.mu.Unlock()

	if entry, ok := im.indexes[keyStr]; ok {
		if entry.Index != nil {
			_ = entry.Index.Close()
		}
	}

	_ = backupPath

	idx, createErr := im.GetOrCreateIndex(key)
	if createErr != nil {
		return createErr
	}

	_ = idx

	return nil
}
