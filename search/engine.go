package search

import (
	"depguard/config"
	"github.com/blevesearch/bleve/v2"
	"github.com/blevesearch/bleve/v2/mapping"
	"os"
	"path/filepath"
	"sync"
)

type Engine struct {
	index bleve.Index
	mu    sync.RWMutex
}

var (
	engine *Engine
	once   sync.Once
)

func Get() *Engine {
	once.Do(func() {
		idxPath := config.Get().Search.IndexPath
		var idx bleve.Index
		var err error

		_ = os.MkdirAll(filepath.Dir(idxPath), 0755)

		if _, err = os.Stat(idxPath); os.IsNotExist(err) {
			indexMapping := bleve.NewIndexMapping()
			idx, err = bleve.New(idxPath, indexMapping)
		} else {
			idx, err = bleve.Open(idxPath)
		}

		if err != nil {
			panic("failed to open search index: " + err.Error())
		}

		engine = &Engine{index: idx}
	})
	return engine
}

func (e *Engine) Index(id string, doc interface{}) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.index.Index(id, doc)
}

func (e *Engine) Delete(id string) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.index.Delete(id)
}

func (e *Engine) Search(query string, page, size int) (*bleve.SearchResult, error) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	q := bleve.NewQueryStringQuery(query)
	req := bleve.NewSearchRequestOptions(q, size, page*size, false)
	return e.index.Search(req)
}

func (e *Engine) Close() {
	if engine != nil && engine.index != nil {
		_ = engine.index.Close()
	}
}

func DocumentMapping() *mapping.DocumentMapping {
	docMapping := bleve.NewDocumentMapping()
	textField := bleve.NewTextFieldMapping()
	textField.Store = true
	textField.IncludeInAll = true
	docMapping.AddFieldMappingsAt("title", textField)
	docMapping.AddFieldMappingsAt("content", textField)
	docMapping.AddFieldMappingsAt("tags", textField)
	return docMapping
}
