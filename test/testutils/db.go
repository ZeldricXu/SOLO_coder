package testutils

import (
	"depguard/modules/docindex"
	"depguard/modules/featureflags"
	"depguard/modules/qualitygate"
	"fmt"

	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type TestDB struct {
	*gorm.DB
}

func NewTestDB() (*TestDB, error) {
	dsn := fmt.Sprintf("file::memory:?cache=shared")
	db, err := gorm.Open(sqlite.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		return nil, err
	}

	err = db.AutoMigrate(
		&docindex.Document{},
		&docindex.DocumentSource{},
		&docindex.SyncJob{},
		&qualitygate.AnalysisRule{},
		&qualitygate.QualityProfile{},
		&qualitygate.QualityGate{},
		&qualitygate.AnalysisReport{},
		&featureflags.FeatureFlag{},
		&featureflags.UserSegment{},
		&featureflags.RolloutRule{},
		&featureflags.RolloutEvent{},
	)
	if err != nil {
		return nil, err
	}

	return &TestDB{DB: db}, nil
}

func (db *TestDB) Close() error {
	sqlDB, err := db.DB.DB()
	if err != nil {
		return err
	}
	return sqlDB.Close()
}

type TestIndex struct {
	docs map[string]map[string]interface{}
}

func NewTestIndex() *TestIndex {
	return &TestIndex{
		docs: make(map[string]map[string]interface{}),
	}
}

func (idx *TestIndex) Index(id string, data map[string]interface{}) error {
	idx.docs[id] = data
	return nil
}

func (idx *TestIndex) Delete(id string) error {
	delete(idx.docs, id)
	return nil
}

func (idx *TestIndex) Search(query string, page, size int) (*SearchResults, error) {
	var hits []SearchHit
	offset := page * size
	count := 0

	for id, doc := range idx.docs {
		if count >= offset+size {
			break
		}
		if matchesQuery(doc, query) {
			if count >= offset {
				hits = append(hits, SearchHit{
					ID:    id,
					Score: 1.0,
				})
			}
			count++
		}
	}

	return &SearchResults{
		Total: int64(count),
		Hits:  hits,
	}, nil
}

func matchesQuery(doc map[string]interface{}, query string) bool {
	if query == "*" || query == "" {
		return true
	}
	lowerQuery := toLower(query)
	for _, v := range doc {
		if str, ok := v.(string); ok {
			if contains(toLower(str), lowerQuery) {
				return true
			}
		}
	}
	return false
}

func toLower(s string) string {
	result := make([]byte, len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'A' && c <= 'Z' {
			result[i] = c + 32
		} else {
			result[i] = c
		}
	}
	return string(result)
}

func contains(haystack, needle string) bool {
	if len(needle) == 0 {
		return true
	}
	if len(haystack) < len(needle) {
		return false
	}
	for i := 0; i <= len(haystack)-len(needle); i++ {
		if haystack[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}

type SearchResults struct {
	Total int64
	Hits  []SearchHit
}

type SearchHit struct {
	ID    string
	Score float64
}
