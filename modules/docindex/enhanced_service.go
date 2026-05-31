package docindex

import (
	"context"
	"depguard/database"
	"depguard/dynamicconfig"
	"depguard/logger"
	"depguard/search"
	"depguard/utils"
	"encoding/json"
	"errors"
	"fmt"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"strings"
	"sync"
	"time"
)

type SearchStrategy string

const (
	StrategyDefault       SearchStrategy = "default"
	StrategyKeyword       SearchStrategy = "keyword"
	StrategySemantic      SearchStrategy = "semantic"
	StrategyHybrid        SearchStrategy = "hybrid"
	StrategyFuzzy         SearchStrategy = "fuzzy"
)

type IndexingMode string

const (
	IndexingModeSync  IndexingMode = "sync"
	IndexingModeAsync IndexingMode = "async"
	IndexingModeBatch IndexingMode = "batch"
)

type ScenarioConfig struct {
	SearchStrategy     SearchStrategy           `json:"search_strategy"`
	IndexingMode       IndexingMode             `json:"indexing_mode"`
	MaxResultSize      int                      `json:"max_result_size"`
	DefaultResultSize  int                      `json:"default_result_size"`
	EnableFuzzySearch  bool                     `json:"enable_fuzzy_search"`
	EnableHighlighting bool                     `json:"enable_highlighting"`
	SnippetSize        int                      `json:"snippet_size"`
	SnippetBefore      int                      `json:"snippet_before"`
	SnippetAfter       int                      `json:"snippet_after"`
	CacheTTLSeconds    int                      `json:"cache_ttl_seconds"`
	WeightTitle        float64                  `json:"weight_title"`
	WeightContent      float64                  `json:"weight_content"`
	WeightTags         float64                  `json:"weight_tags"`
	SearchableFields   []string                 `json:"searchable_fields"`
	StopWords          []string                 `json:"stop_words"`
	StemmingEnabled    bool                     `json:"stemming_enabled"`
	AutoComplete       bool                     `json:"auto_complete"`
	SourceBoost        map[string]float64       `json:"source_boost"`
	PermissionStrict   bool                     `json:"permission_strict"`
	RealtimeIndex      bool                     `json:"realtime_index"`
}

var defaultScenarioConfig = ScenarioConfig{
	SearchStrategy:     StrategyDefault,
	IndexingMode:       IndexingModeSync,
	MaxResultSize:      100,
	DefaultResultSize:  20,
	EnableFuzzySearch:  true,
	EnableHighlighting: true,
	SnippetSize:        200,
	SnippetBefore:      50,
	SnippetAfter:       150,
	CacheTTLSeconds:    300,
	WeightTitle:        3.0,
	WeightContent:      1.0,
	WeightTags:         2.0,
	SearchableFields:   []string{"title", "content", "tags", "source"},
	StopWords:          []string{"the", "a", "an", "is", "are", "was", "were"},
	StemmingEnabled:    false,
	AutoComplete:       false,
	SourceBoost:        map[string]float64{},
	PermissionStrict:   false,
	RealtimeIndex:      true,
}

var scenarioPresets = map[dynamicconfig.ConfigScenario]ScenarioConfig{
	dynamicconfig.ScenarioDefault: defaultScenarioConfig,
	dynamicconfig.ScenarioProduction: {
		SearchStrategy:     StrategyHybrid,
		IndexingMode:       IndexingModeAsync,
		MaxResultSize:      200,
		DefaultResultSize:  50,
		EnableFuzzySearch:  false,
		EnableHighlighting: false,
		CacheTTLSeconds:    600,
		PermissionStrict:   true,
		RealtimeIndex:      false,
	},
	dynamicconfig.ScenarioStaging: {
		SearchStrategy:     StrategyHybrid,
		IndexingMode:       IndexingModeSync,
		MaxResultSize:      100,
		DefaultResultSize:  30,
		EnableFuzzySearch:  true,
		EnableHighlighting: true,
		CacheTTLSeconds:    120,
		RealtimeIndex:      true,
	},
	dynamicconfig.ScenarioDevelopment: {
		SearchStrategy:     StrategyKeyword,
		IndexingMode:       IndexingModeSync,
		MaxResultSize:      50,
		DefaultResultSize:  10,
		EnableFuzzySearch:  true,
		EnableHighlighting: true,
		CacheTTLSeconds:    30,
		PermissionStrict:   false,
		RealtimeIndex:      true,
	},
	dynamicconfig.ScenarioPerformance: {
		SearchStrategy:     StrategyKeyword,
		IndexingMode:       IndexingModeBatch,
		MaxResultSize:      500,
		DefaultResultSize:  100,
		EnableFuzzySearch:  false,
		EnableHighlighting: false,
		CacheTTLSeconds:    1800,
		PermissionStrict:   false,
		RealtimeIndex:      false,
	},
	dynamicconfig.ScenarioSecurity: {
		SearchStrategy:     StrategyKeyword,
		IndexingMode:       IndexingModeSync,
		MaxResultSize:      100,
		DefaultResultSize:  20,
		EnableFuzzySearch:  false,
		EnableHighlighting: true,
		CacheTTLSeconds:    60,
		PermissionStrict:   true,
		RealtimeIndex:      true,
	},
}

type EnhancedService struct {
	Service
	configManager  *dynamicconfig.Manager
	currentConfig  ScenarioConfig
	configMu       sync.RWMutex
	asyncIndexCh   chan *Document
	batchCh        chan []*Document
	stopCh         chan struct{}
	wg             sync.WaitGroup
	initialized    bool
}

var enhancedInstance *EnhancedService
var enhancedOnce sync.Once

func NewEnhancedService() *EnhancedService {
	enhancedOnce.Do(func() {
		db := database.Get()
		index := &searchAdapter{engine: search.Get()}
		configManager := dynamicconfig.GetManager()

		enhancedInstance = &EnhancedService{
			Service: Service{
				db:    db,
				index: index,
			},
			configManager:  configManager,
			currentConfig:  defaultScenarioConfig,
			asyncIndexCh:   make(chan *Document, 1000),
			batchCh:        make(chan []*Document, 100),
			stopCh:         make(chan struct{}),
			initialized:    false,
		}

		enhancedInstance.initialize()
	})
	return enhancedInstance
}

func NewEnhancedServiceWithDeps(db *gorm.DB, index SearchEngine, configManager *dynamicconfig.Manager) *EnhancedService {
	if configManager == nil {
		configManager = dynamicconfig.GetManager()
	}

	svc := &EnhancedService{
		Service: Service{
			db:    db,
			index: index,
		},
		configManager:  configManager,
		currentConfig:  defaultScenarioConfig,
		asyncIndexCh:   make(chan *Document, 1000),
		batchCh:        make(chan []*Document, 100),
		stopCh:         make(chan struct{}),
		initialized:    false,
	}

	svc.initialize()
	return svc
}

func (s *EnhancedService) initialize() {
	s.applyScenarioConfig(s.configManager.GetScenario())
	s.configManager.RegisterListener(dynamicconfig.ConfigTypeDocIndex, s)
	s.startAsyncWorkers()
	s.initialized = true
	logger.Get().Info("DocIndex EnhancedService initialized",
		zap.String("scenario", string(s.configManager.GetScenario())),
		zap.String("strategy", string(s.currentConfig.SearchStrategy)))
}

func (s *EnhancedService) OnConfigChange(event dynamicconfig.ConfigChangeEvent) {
	logger.Get().Info("DocIndex config change received",
		zap.String("change_type", event.ChangeType),
		zap.String("scenario", string(event.Scenario)),
		zap.String("key", event.Key))

	if event.ChangeType == "scenario_switch" {
		s.applyScenarioConfig(event.Scenario)
	}
}

func (s *EnhancedService) applyScenarioConfig(scenario dynamicconfig.ConfigScenario) {
	config, exists := scenarioPresets[scenario]
	if !exists {
		config = defaultScenarioConfig
	}

	s.configMu.Lock()
	s.currentConfig = config
	s.configMu.Unlock()

	customConfigs := s.configManager.GetAll(dynamicconfig.ConfigTypeDocIndex, scenario)
	for key, cfg := range customConfigs {
		switch key {
		case "search_strategy":
			s.configMu.Lock()
			s.currentConfig.SearchStrategy = SearchStrategy(cfg.Value)
			s.configMu.Unlock()
		case "indexing_mode":
			s.configMu.Lock()
			s.currentConfig.IndexingMode = IndexingMode(cfg.Value)
			s.configMu.Unlock()
		case "max_result_size":
			var val int
			json.Unmarshal([]byte(cfg.Value), &val)
			s.configMu.Lock()
			s.currentConfig.MaxResultSize = val
			s.configMu.Unlock()
		case "default_result_size":
			var val int
			json.Unmarshal([]byte(cfg.Value), &val)
			s.configMu.Lock()
			s.currentConfig.DefaultResultSize = val
			s.configMu.Unlock()
		case "enable_fuzzy_search":
			var val bool
			json.Unmarshal([]byte(cfg.Value), &val)
			s.configMu.Lock()
			s.currentConfig.EnableFuzzySearch = val
			s.configMu.Unlock()
		case "enable_highlighting":
			var val bool
			json.Unmarshal([]byte(cfg.Value), &val)
			s.configMu.Lock()
			s.currentConfig.EnableHighlighting = val
			s.configMu.Unlock()
		case "permission_strict":
			var val bool
			json.Unmarshal([]byte(cfg.Value), &val)
			s.configMu.Lock()
			s.currentConfig.PermissionStrict = val
			s.configMu.Unlock()
		case "realtime_index":
			var val bool
			json.Unmarshal([]byte(cfg.Value), &val)
			s.configMu.Lock()
			s.currentConfig.RealtimeIndex = val
			s.configMu.Unlock()
		}
	}

	logger.Get().Info("DocIndex scenario config applied",
		zap.String("scenario", string(scenario)),
		zap.String("strategy", string(s.currentConfig.SearchStrategy)),
		zap.String("indexing_mode", string(s.currentConfig.IndexingMode)))
}

func (s *EnhancedService) startAsyncWorkers() {
	for i := 0; i < 3; i++ {
		s.wg.Add(1)
		go s.worker(i)
	}
}

func (s *EnhancedService) worker(id int) {
	defer s.wg.Done()

	for {
		select {
		case doc := <-s.asyncIndexCh:
			s.indexDocument(doc)
		case docs := <-s.batchCh:
			s.batchIndexDocuments(docs)
		case <-s.stopCh:
			return
		}
	}
}

func (s *EnhancedService) Shutdown() {
	close(s.stopCh)
	s.wg.Wait()
	close(s.asyncIndexCh)
	close(s.batchCh)
	s.initialized = false
}

func (s *EnhancedService) GetConfig() ScenarioConfig {
	s.configMu.RLock()
	defer s.configMu.RUnlock()
	return s.currentConfig
}

func (s *EnhancedService) SetConfig(key string, value interface{}) error {
	return s.configManager.Set(dynamicconfig.ConfigTypeDocIndex, key, value)
}

func (s *EnhancedService) SwitchScenario(scenario dynamicconfig.ConfigScenario) {
	s.configManager.SetScenario(scenario)
}

func (s *EnhancedService) CreateDocument(ctx context.Context, doc *Document) (*Document, error) {
	doc.ID = utils.GenerateID("doc")
	doc.CreatedAt = time.Now()
	doc.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(doc).Error; err != nil {
		return nil, err
	}

	config := s.GetConfig()
	if config.RealtimeIndex {
		switch config.IndexingMode {
		case IndexingModeSync:
			s.indexDocument(doc)
		case IndexingModeAsync:
			select {
			case s.asyncIndexCh <- doc:
			default:
				logger.Get().Warn("async index queue full, falling back to sync",
					zap.String("doc_id", doc.ID))
				s.indexDocument(doc)
			}
		case IndexingModeBatch:
		}
	}

	return doc, nil
}

func (s *EnhancedService) indexDocument(doc *Document) {
	err := s.index.Index(doc.ID, map[string]interface{}{
		"title":   doc.Title,
		"content": doc.Content,
		"tags":    strings.Join(doc.Tags, " "),
		"source":  doc.Source,
	})
	if err != nil {
		logger.Get().Warn("failed to index document", zap.String("id", doc.ID), zap.Error(err))
	}
}

func (s *EnhancedService) batchIndexDocuments(docs []*Document) {
	for _, doc := range docs {
		s.indexDocument(doc)
	}
}

func (s *EnhancedService) Search(ctx context.Context, q *SearchQuery) ([]SearchResult, int64, error) {
	config := s.GetConfig()

	if q.Page < 0 {
		q.Page = 0
	}
	if q.Size <= 0 {
		q.Size = config.DefaultResultSize
	}
	if q.Size > config.MaxResultSize {
		q.Size = config.MaxResultSize
	}

	enhancedQuery := s.buildEnhancedQuery(q, config)

	results, err := s.index.Search(enhancedQuery, q.Page, q.Size)
	if err != nil {
		return nil, 0, err
	}

	var docIDs []string
	idToScore := make(map[string]float64)
	for _, hit := range results.Hits {
		docIDs = append(docIDs, hit.ID)
		idToScore[hit.ID] = hit.Score * s.getScoreWeight("", config)
	}

	if len(docIDs) == 0 {
		return []SearchResult{}, results.Total, nil
	}

	var docs []Document
	if err := s.db.WithContext(ctx).Where("id IN ?", docIDs).Find(&docs).Error; err != nil {
		return nil, 0, err
	}

	filteredDocs := s.filterByPermissionsEnhanced(docs, q.UserID, q.Roles, config)

	var searchResults []SearchResult
	for _, doc := range filteredDocs {
		searchResults = append(searchResults, SearchResult{
			ID:      doc.ID,
			Title:   doc.Title,
			Source:  doc.Source,
			Tags:    doc.Tags,
			Score:   idToScore[doc.ID],
			Snippet: s.extractEnhancedSnippet(doc.Content, q.Query, config),
		})
	}

	return searchResults, int64(len(filteredDocs)), nil
}

func (s *EnhancedService) buildEnhancedQuery(q *SearchQuery, config ScenarioConfig) string {
	var queryParts []string

	if q.Query != "" {
		switch config.SearchStrategy {
		case StrategyFuzzy:
			queryParts = append(queryParts, fmt.Sprintf("%s~", q.Query))
		case StrategySemantic:
			queryParts = append(queryParts, fmt.Sprintf("content:%s^%f", q.Query, config.WeightContent))
		default:
			queryParts = append(queryParts, q.Query)
		}
	}

	if q.Source != "" {
		boost := config.SourceBoost[q.Source]
		if boost > 0 {
			queryParts = append(queryParts, fmt.Sprintf("source:%s^%f", q.Source, boost))
		} else {
			queryParts = append(queryParts, fmt.Sprintf("source:%s", q.Source))
		}
	}

	for _, tag := range q.Tags {
		queryParts = append(queryParts, fmt.Sprintf("tags:%s^%f", tag, config.WeightTags))
	}

	searchQuery := strings.Join(queryParts, " AND ")
	if searchQuery == "" {
		searchQuery = "*"
	}

	return searchQuery
}

func (s *EnhancedService) getScoreWeight(source string, config ScenarioConfig) float64 {
	if boost, ok := config.SourceBoost[source]; ok {
		return boost
	}
	return 1.0
}

func (s *EnhancedService) extractEnhancedSnippet(content, query string, config ScenarioConfig) string {
	if config.SnippetSize <= 0 {
		if len(content) > 200 {
			return content[:200] + "..."
		}
		return content
	}

	if query == "" || content == "" {
		if len(content) > config.SnippetSize {
			return content[:config.SnippetSize] + "..."
		}
		return content
	}

	lowerContent := strings.ToLower(content)
	lowerQuery := strings.ToLower(query)
	idx := strings.Index(lowerContent, lowerQuery)
	if idx == -1 {
		if len(content) > config.SnippetSize {
			return content[:config.SnippetSize] + "..."
		}
		return content
	}

	start := idx - config.SnippetBefore
	if start < 0 {
		start = 0
	}
	end := idx + len(query) + config.SnippetAfter
	if end > len(content) {
		end = len(content)
	}
	if end-start > config.SnippetSize {
		end = start + config.SnippetSize
		if end > len(content) {
			end = len(content)
		}
	}

	snippet := content[start:end]
	if start > 0 {
		snippet = "..." + snippet
	}
	if end < len(content) {
		snippet = snippet + "..."
	}

	if config.EnableHighlighting {
		snippet = s.highlightMatches(snippet, query)
	}

	return snippet
}

func (s *EnhancedService) highlightMatches(snippet, query string) string {
	if query == "" {
		return snippet
	}

	lowerSnippet := strings.ToLower(snippet)
	lowerQuery := strings.ToLower(query)
	var result strings.Builder
	lastIdx := 0

	for {
		idx := strings.Index(lowerSnippet[lastIdx:], lowerQuery)
		if idx == -1 {
			result.WriteString(snippet[lastIdx:])
			break
		}
		result.WriteString(snippet[lastIdx : lastIdx+idx])
		result.WriteString("<mark>")
		result.WriteString(snippet[lastIdx+idx : lastIdx+idx+len(query)])
		result.WriteString("</mark>")
		lastIdx = lastIdx + idx + len(query)
		if lastIdx >= len(snippet) {
			break
		}
	}

	return result.String()
}

func (s *EnhancedService) filterByPermissionsEnhanced(docs []Document, userID string, roles []string, config ScenarioConfig) []Document {
	var filtered []Document
	for _, doc := range docs {
		if s.hasAccessEnhanced(&doc, userID, roles, config) {
			filtered = append(filtered, doc)
		}
	}
	return filtered
}

func (s *EnhancedService) hasAccessEnhanced(doc *Document, userID string, roles []string, config ScenarioConfig) bool {
	if doc.Permissions.Public {
		return true
	}

	if doc.Permissions.OwnerID == userID {
		return true
	}

	if config.PermissionStrict {
		hasUser := false
		for _, allowedUser := range doc.Permissions.ReadUsers {
			if allowedUser == userID {
				hasUser = true
				break
			}
		}
		if !hasUser {
			return false
		}
	} else {
		for _, allowedUser := range doc.Permissions.ReadUsers {
			if allowedUser == userID {
				return true
			}
		}
	}

	for _, role := range roles {
		for _, allowedRole := range doc.Permissions.ReadRoles {
			if allowedRole == role {
				return true
			}
		}
	}

	return false
}

func (s *EnhancedService) SyncSource(ctx context.Context, sourceID string) (*SyncJob, error) {
	var src DocumentSource
	if err := s.db.WithContext(ctx).First(&src, "id = ?", sourceID).Error; err != nil {
		return nil, err
	}
	if !src.Enabled {
		return nil, errors.New("source is disabled")
	}

	config := s.GetConfig()
	job := &SyncJob{
		ID:        utils.GenerateID("job"),
		SourceID:  sourceID,
		Status:    "running",
		StartedAt: time.Now(),
	}
	if err := s.db.WithContext(ctx).Create(job).Error; err != nil {
		return nil, err
	}

	switch config.IndexingMode {
	case IndexingModeSync:
		s.runSyncJob(ctx, job, &src)
	case IndexingModeAsync:
		go s.runSyncJob(ctx, job, &src)
	case IndexingModeBatch:
		go s.runSyncJob(ctx, job, &src)
	}

	return job, nil
}

func (s *EnhancedService) runSyncJob(ctx context.Context, job *SyncJob, src *DocumentSource) {
	count := 0
	var err error

	defer func() {
		now := time.Now()
		job.CompletedAt = &now
		if err != nil {
			errStr := err.Error()
			job.Error = &errStr
			job.Status = "failed"
		} else {
			job.Status = "completed"
			job.DocumentCount = count
			src.LastSync = &now
			s.db.Save(src)
		}
		s.db.Save(job)
	}()

	docs, err := s.Service.fetchFromSource(src)
	if err != nil {
		return
	}

	config := s.GetConfig()
	if config.IndexingMode == IndexingModeBatch && len(docs) > 10 {
		for i := 0; i < len(docs); i += 50 {
			end := i + 50
			if end > len(docs) {
				end = len(docs)
			}
			batch := docs[i:end]
			for _, doc := range batch {
				doc.Source = src.Type
				created, createErr := s.Service.CreateDocument(ctx, doc)
				if createErr != nil {
					logger.Get().Warn("failed to create synced doc", zap.Error(createErr))
					continue
				}
				count++
				if config.RealtimeIndex {
					s.indexDocument(created)
				}
			}
		}
	} else {
		for _, doc := range docs {
			doc.Source = src.Type
			_, createErr := s.CreateDocument(ctx, doc)
			if createErr != nil {
				logger.Get().Warn("failed to create synced doc", zap.Error(createErr))
				continue
			}
			count++
		}
	}
}

func (s *EnhancedService) IsInitialized() bool {
	return s.initialized
}

func (s *EnhancedService) GetScenarios() []dynamicconfig.ConfigScenario {
	return []dynamicconfig.ConfigScenario{
		dynamicconfig.ScenarioDefault,
		dynamicconfig.ScenarioProduction,
		dynamicconfig.ScenarioStaging,
		dynamicconfig.ScenarioDevelopment,
		dynamicconfig.ScenarioPerformance,
		dynamicconfig.ScenarioSecurity,
	}
}

func (s *EnhancedService) GetScenarioPreset(scenario dynamicconfig.ConfigScenario) (ScenarioConfig, bool) {
	config, exists := scenarioPresets[scenario]
	return config, exists
}

func (s *EnhancedService) SetCustomPreset(scenario dynamicconfig.ConfigScenario, config ScenarioConfig) {
	scenarioPresets[scenario] = config
	s.applyScenarioConfig(scenario)
}
