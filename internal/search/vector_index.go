package search

import (
	"encoding/json"
	"math"
	"os"
	"sort"
	"sync"
)

type VectorSearchResult struct {
	NoteID uint    `json:"note_id"`
	Score  float64 `json:"score"`
}

type VectorIndex struct {
	indexPath string
	vectors   map[uint][]float32
	dimension int
	dirty     bool
	mu        sync.RWMutex
}

type vectorIndexData struct {
	Vectors   map[uint][]float32 `json:"vectors"`
	Dimension int                `json:"dimension"`
}

func NewVectorIndex(indexPath string) *VectorIndex {
	vi := &VectorIndex{
		indexPath: indexPath,
		vectors:   make(map[uint][]float32),
	}
	if err := vi.Load(); err != nil {
		vi.vectors = make(map[uint][]float32)
	}
	return vi
}

func (vi *VectorIndex) AddVector(noteID uint, vector []float32) error {
	vi.mu.Lock()
	defer vi.mu.Unlock()

	if vi.dimension == 0 && len(vector) > 0 {
		vi.dimension = len(vector)
	}

	vi.vectors[noteID] = vector
	vi.dirty = true
	return nil
}

func (vi *VectorIndex) RemoveVector(noteID uint) error {
	vi.mu.Lock()
	defer vi.mu.Unlock()

	delete(vi.vectors, noteID)
	vi.dirty = true
	return nil
}

func (vi *VectorIndex) Search(queryVector []float32, topK int) []VectorSearchResult {
	vi.mu.RLock()
	defer vi.mu.RUnlock()

	if len(vi.vectors) == 0 || len(queryVector) == 0 {
		return nil
	}

	var results []VectorSearchResult
	for noteID, vec := range vi.vectors {
		score := cosineSimilarity(queryVector, vec)
		results = append(results, VectorSearchResult{
			NoteID: noteID,
			Score:  score,
		})
	}

	sort.Slice(results, func(i, j int) bool {
		return results[i].Score > results[j].Score
	})

	if topK > 0 && len(results) > topK {
		results = results[:topK]
	}

	return results
}

func (vi *VectorIndex) Save() error {
	vi.mu.RLock()
	if !vi.dirty {
		vi.mu.RUnlock()
		return nil
	}

	data := vectorIndexData{
		Vectors:   vi.vectors,
		Dimension: vi.dimension,
	}
	vi.mu.RUnlock()

	jsonData, err := json.MarshalIndent(data, "", "  ")
	if err != nil {
		return err
	}

	if err := os.WriteFile(vi.indexPath, jsonData, 0644); err != nil {
		return err
	}

	vi.mu.Lock()
	vi.dirty = false
	vi.mu.Unlock()

	return nil
}

func (vi *VectorIndex) Load() error {
	data, err := os.ReadFile(vi.indexPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	var idxData vectorIndexData
	if err := json.Unmarshal(data, &idxData); err != nil {
		return err
	}

	vi.mu.Lock()
	vi.vectors = idxData.Vectors
	if vi.vectors == nil {
		vi.vectors = make(map[uint][]float32)
	}
	vi.dimension = idxData.Dimension
	vi.dirty = false
	vi.mu.Unlock()

	return nil
}

func (vi *VectorIndex) Size() int {
	vi.mu.RLock()
	defer vi.mu.RUnlock()
	return len(vi.vectors)
}

func (vi *VectorIndex) HasVector(noteID uint) bool {
	vi.mu.RLock()
	defer vi.mu.RUnlock()
	_, ok := vi.vectors[noteID]
	return ok
}

func cosineSimilarity(a, b []float32) float64 {
	if len(a) != len(b) || len(a) == 0 {
		return 0
	}

	var dotProduct float64
	var normA float64
	var normB float64

	for i := 0; i < len(a); i++ {
		dotProduct += float64(a[i]) * float64(b[i])
		normA += float64(a[i]) * float64(a[i])
		normB += float64(b[i]) * float64(b[i])
	}

	if normA == 0 || normB == 0 {
		return 0
	}

	return dotProduct / (math.Sqrt(normA) * math.Sqrt(normB))
}
