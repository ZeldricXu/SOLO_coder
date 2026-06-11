package search

import (
	"log"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
)

type HybridSearchEngine struct {
	engine          *SearchEngine
	embeddingClient *EmbeddingClient
	vectorIndex     *VectorIndex
	bm25Weight      float64
	vectorWeight    float64

	backgroundRunning bool
	stopCh           chan struct{}
	indexingTotal    int
	indexingDone     int
	mu               sync.Mutex
}

func NewHybridSearchEngine(engine *SearchEngine, embeddingClient *EmbeddingClient, vectorIndex *VectorIndex) *HybridSearchEngine {
	return &HybridSearchEngine{
		engine:          engine,
		embeddingClient: embeddingClient,
		vectorIndex:     vectorIndex,
		bm25Weight:      0.6,
		vectorWeight:    0.4,
		stopCh:         make(chan struct{}),
	}
}

func (h *HybridSearchEngine) SetWeights(bm25Weight, vectorWeight float64) {
	h.bm25Weight = bm25Weight
	h.vectorWeight = vectorWeight
}

func (h *HybridSearchEngine) Search(query SearchQuery) ([]models.SearchResult, int, error) {
	bm25Results, total, err := h.engine.Search(query)
	if err != nil {
		return nil, 0, err
	}

	if len(bm25Results) == 0 {
		return bm25Results, total, nil
	}

	queryVector, embedErr := h.embeddingClient.Embed(query.Query)
	if embedErr != nil || queryVector == nil {
		log.Printf("HybridSearch: vector search unavailable, falling back to BM25: %v", embedErr)
		return bm25Results, total, nil
	}

	vectorResults := h.vectorIndex.Search(queryVector, len(bm25Results)*2)

	vectorScoreMap := make(map[uint]float64)
	for _, vr := range vectorResults {
		vectorScoreMap[vr.NoteID] = vr.Score
	}

	var maxBM25Score float64
	for _, r := range bm25Results {
		if r.Score > maxBM25Score {
			maxBM25Score = r.Score
		}
	}

	for i := range bm25Results {
		var normalizedBM25 float64
		if maxBM25Score > 0 {
			normalizedBM25 = bm25Results[i].Score / maxBM25Score
		}

		vecScore := vectorScoreMap[bm25Results[i].NoteID]

		bm25Results[i].Score = h.bm25Weight*normalizedBM25 + h.vectorWeight*vecScore
	}

	sort.Slice(bm25Results, func(i, j int) bool {
		return bm25Results[i].Score > bm25Results[j].Score
	})

	return bm25Results, total, nil
}

func (h *HybridSearchEngine) IndexNoteWithVector(noteID uint, title, content string) error {
	if err := h.engine.IndexNote(noteID, title, content); err != nil {
		return err
	}

	go func() {
		text := title + "\n" + content
		vector, err := h.embeddingClient.Embed(text)
		if err != nil {
			log.Printf("IndexNoteWithVector: failed to embed note %d: %v", noteID, err)
			return
		}
		if vector == nil {
			return
		}
		if err := h.vectorIndex.AddVector(noteID, vector); err != nil {
			log.Printf("IndexNoteWithVector: failed to add vector for note %d: %v", noteID, err)
			return
		}
		if err := h.vectorIndex.Save(); err != nil {
			log.Printf("IndexNoteWithVector: failed to save vector index: %v", err)
		}
	}()

	return nil
}

func (h *HybridSearchEngine) StartBackgroundIndexing(database *db.Database) {
	h.mu.Lock()
	if h.backgroundRunning {
		h.mu.Unlock()
		return
	}
	h.backgroundRunning = true
	h.stopCh = make(chan struct{})
	h.mu.Unlock()

	go func() {
		defer func() {
			h.mu.Lock()
			h.backgroundRunning = false
			h.mu.Unlock()
		}()

		notes, err := database.GetAllNotes()
		if err != nil {
			log.Printf("BackgroundIndexing: failed to get notes: %v", err)
			return
		}

		hasVectorsStatusTable := h.hasVectorsStatusTable(database)

		var pending []*models.Note
		for _, note := range notes {
			needsIndex := false
			if hasVectorsStatusTable {
				needsIndex = h.needsVectorByStatus(database, note.ID)
			} else {
				needsIndex = !h.vectorIndex.HasVector(note.ID)
			}
			if needsIndex {
				pending = append(pending, note)
			}
		}

		h.mu.Lock()
		h.indexingTotal = len(pending)
		h.indexingDone = 0
		h.mu.Unlock()

		if len(pending) == 0 {
			return
		}

		batchSize := 10
		for i := 0; i < len(pending); i += batchSize {
			select {
			case <-h.stopCh:
				return
			default:
			}

			end := i + batchSize
			if end > len(pending) {
				end = len(pending)
			}

			batch := pending[i:end]
			texts := make([]string, len(batch))
			for j, note := range batch {
				texts[j] = note.Title
				if note.Path != "" {
					vaultPath := ""
					home, _ := os.UserHomeDir()
					vaultPath = filepath.Join(home, "KnowledgeVault")
					fullPath := note.Path
					if !filepath.IsAbs(fullPath) {
						fullPath = filepath.Join(vaultPath, note.Path)
					}
					data, err := os.ReadFile(fullPath)
					if err == nil {
						texts[j] = note.Title + "\n" + string(data)
					}
				}
			}

			embeddings, _ := h.embeddingClient.EmbedBatch(texts)

			for j, embedding := range embeddings {
				if embedding != nil {
					h.vectorIndex.AddVector(batch[j].ID, embedding)
				}
				h.mu.Lock()
				h.indexingDone++
				h.mu.Unlock()
			}

			if err := h.vectorIndex.Save(); err != nil {
				log.Printf("BackgroundIndexing: failed to save index: %v", err)
			}

			time.Sleep(500 * time.Millisecond)
		}
	}()
}

func (h *HybridSearchEngine) StopBackgroundIndexing() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.backgroundRunning {
		close(h.stopCh)
	}
}

func (h *HybridSearchEngine) GetIndexingProgress() (total, completed int, running bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.indexingTotal, h.indexingDone, h.backgroundRunning
}

func (h *HybridSearchEngine) IsOllamaAvailable() bool {
	return h.embeddingClient.IsAvailable()
}

func (h *HybridSearchEngine) hasVectorsStatusTable(database *db.Database) bool {
	_, err := database.Exec("SELECT 1 FROM note_vectors_status LIMIT 1")
	return err == nil
}

func (h *HybridSearchEngine) needsVectorByStatus(database *db.Database, noteID uint) bool {
	var status string
	err := database.QueryRow(
		"SELECT vector_status FROM note_vectors_status WHERE note_id = ?", noteID,
	).Scan(&status)
	if err != nil {
		return !h.vectorIndex.HasVector(noteID)
	}
	return status != "indexed"
}
