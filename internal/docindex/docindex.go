package docindex

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/ioutil"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"techplatform/internal/dao"
	"techplatform/pkg/common"
	"techplatform/pkg/common/logger"
	"techplatform/pkg/common/utils"
	"techplatform/pkg/models"

	"gorm.io/gorm"
)

type Document struct {
	models.BaseModel
	Title       string   `json:"title" gorm:"index"`
	Content     string   `json:"content"`
	Summary     string   `json:"summary"`
	Source      string   `json:"source" gorm:"index"`
	SourceURL   string   `json:"source_url"`
	Path        string   `json:"path"`
	ContentType string   `json:"content_type"`
	Tags        string   `json:"tags"`
	Author      string   `json:"author"`
	Language    string   `json:"language"`
	Permissions string   `json:"permissions"`
	Checksum    string   `json:"checksum" gorm:"uniqueIndex"`
	Size        int64    `json:"size"`
	IndexedAt   time.Time `json:"indexed_at"`
	Views       int      `json:"views"`
	Rating      float64  `json:"rating"`
}

type DocumentPermission struct {
	UserID   string   `json:"user_id"`
	Role     string   `json:"role"`
	Groups   []string `json:"groups"`
	CanView  bool     `json:"can_view"`
	CanEdit  bool     `json:"can_edit"`
	CanDelete bool    `json:"can_delete"`
}

type SearchQuery struct {
	Keyword     string   `json:"keyword"`
	Sources     []string `json:"sources"`
	Tags        []string `json:"tags"`
	ContentType string   `json:"content_type"`
	Author      string   `json:"author"`
	DateFrom    *time.Time `json:"date_from"`
	DateTo      *time.Time `json:"date_to"`
	Page        int      `json:"page"`
	PageSize    int      `json:"page_size"`
}

type SearchResult struct {
	Document *Document `json:"document"`
	Score    float64   `json:"score"`
	Matches  []string  `json:"matches,omitempty"`
}

type DocumentSource interface {
	Name() string
	Fetch(ctx context.Context, config map[string]interface{}) ([]*Document, error)
	Validate(config map[string]interface{}) error
}

type LocalFileSource struct {
	rootPath string
	extensions []string
}

func (s *LocalFileSource) Name() string { return common.DocSourceLocal }

func (s *LocalFileSource) Validate(config map[string]interface{}) error {
	if path, ok := config["path"].(string); ok && path != "" {
		s.rootPath = path
	} else {
		return fmt.Errorf("%w: path required", common.ErrInvalidInput)
	}
	if exts, ok := config["extensions"].([]string); ok {
		s.extensions = exts
	} else {
		s.extensions = []string{".md", ".txt", ".go", ".java", ".py", ".js", ".ts", ".yaml", ".yml", ".json", ".xml", ".html", ".pdf"}
	}
	return nil
}

func (s *LocalFileSource) Fetch(ctx context.Context, config map[string]interface{}) ([]*Document, error) {
	if err := s.Validate(config); err != nil {
		return nil, err
	}

	var docs []*Document
	err := filepath.Walk(s.rootPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}

		ext := strings.ToLower(filepath.Ext(path))
		if !utils.ContainsString(s.extensions, ext) {
			return nil
		}

		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		content, err := readFileContent(path, ext)
		if err != nil {
			logger.Warn("Failed to read file %s: %v", path, err)
			return nil
		}

		checksum := calculateChecksum(content)
		relPath, _ := filepath.Rel(s.rootPath, path)

		doc := &Document{
			BaseModel: models.BaseModel{
				ID: utils.GenerateUUID(),
			},
			Title:       filepath.Base(path),
			Content:     content,
			Summary:     generateSummary(content),
			Source:      common.DocSourceLocal,
			Path:        relPath,
			SourceURL:   "file://" + path,
			ContentType: ext,
			Size:        info.Size(),
			Checksum:    checksum,
			Permissions: `{"role": "viewer", "groups": ["everyone"]}`,
			IndexedAt:   time.Now(),
			Language:    detectLanguage(ext),
			Tags:        extractTags(content),
		}
		docs = append(docs, doc)
		return nil
	})

	if err != nil {
		return nil, err
	}

	logger.Info("Fetched %d documents from local source: %s", len(docs), s.rootPath)
	return docs, nil
}

type ConfluenceSource struct{}

func (s *ConfluenceSource) Name() string { return common.DocSourceConfluence }

func (s *ConfluenceSource) Validate(config map[string]interface{}) error {
	if _, ok := config["url"].(string); !ok {
		return fmt.Errorf("%w: url required", common.ErrInvalidInput)
	}
	return nil
}

func (s *ConfluenceSource) Fetch(ctx context.Context, config map[string]interface{}) ([]*Document, error) {
	if err := s.Validate(config); err != nil {
		return nil, err
	}
	url := config["url"].(string)
	token, _ := config["token"].(string)

	logger.Info("Fetching documents from Confluence: %s", url)
	_ = token

	return []*Document{}, nil
}

type GitlabSource struct{}

func (s *GitlabSource) Name() string { return common.DocSourceGitlab }

func (s *GitlabSource) Validate(config map[string]interface{}) error {
	if _, ok := config["url"].(string); !ok {
		return fmt.Errorf("%w: url required", common.ErrInvalidInput)
	}
	return nil
}

func (s *GitlabSource) Fetch(ctx context.Context, config map[string]interface{}) ([]*Document, error) {
	if err := s.Validate(config); err != nil {
		return nil, err
	}
	url := config["url"].(string)

	logger.Info("Fetching documents from GitLab: %s", url)
	return []*Document{}, nil
}

type NotionSource struct{}

func (s *NotionSource) Name() string { return common.DocSourceNotion }

func (s *NotionSource) Validate(config map[string]interface{}) error {
	if _, ok := config["token"].(string); !ok {
		return fmt.Errorf("%w: token required", common.ErrInvalidInput)
	}
	return nil
}

func (s *NotionSource) Fetch(ctx context.Context, config map[string]interface{}) ([]*Document, error) {
	if err := s.Validate(config); err != nil {
		return nil, err
	}
	logger.Info("Fetching documents from Notion")
	return []*Document{}, nil
}

type IndexManager struct {
	mu        sync.RWMutex
	db        *dao.DAO
	sources   map[string]DocumentSource
	indexPath string
	index     map[string]map[string][]int
	stopWords map[string]bool
}

func NewIndexManager(db *dao.DAO, indexPath string) *IndexManager {
	if indexPath == "" {
		indexPath = "./index"
	}
	os.MkdirAll(indexPath, 0755)

	im := &IndexManager{
		db:        db,
		sources:   make(map[string]DocumentSource),
		indexPath: indexPath,
		index:     make(map[string]map[string][]int),
		stopWords: make(map[string]bool),
	}

	im.registerSource(&LocalFileSource{})
	im.registerSource(&ConfluenceSource{})
	im.registerSource(&GitlabSource{})
	im.registerSource(&NotionSource{})
	im.initStopWords()
	db.AutoMigrate(&Document{})

	logger.Info("Document index manager initialized, index path: %s", indexPath)
	return im
}

func (im *IndexManager) registerSource(source DocumentSource) {
	im.sources[source.Name()] = source
}

func (im *IndexManager) initStopWords() {
	words := []string{
		"the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
		"of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
		"have", "has", "had", "do", "does", "did", "will", "would", "could",
		"should", "may", "might", "can", "this", "that", "these", "those",
		"it", "its", "they", "them", "their", "we", "us", "our", "you", "your",
		"he", "she", "him", "her", "his", "hers", "what", "which", "who",
		"where", "when", "why", "how", "not", "no", "yes", "so", "if", "then",
		"else", "than", "too", "very", "just", "also", "now", "here", "there",
		"的", "是", "在", "和", "与", "或", "但", "而", "如果", "因为", "所以",
		"这", "那", "这些", "那些", "我", "你", "他", "她", "它", "我们", "你们",
		"他们", "一个", "一些", "什么", "哪里", "什么时候", "为什么", "怎么",
	}
	for _, w := range words {
		im.stopWords[w] = true
	}
}

func (im *IndexManager) SyncFromSource(ctx context.Context, sourceType string, config map[string]interface{}) (int, error) {
	source, exists := im.sources[sourceType]
	if !exists {
		return 0, fmt.Errorf("%w: unknown source type: %s", common.ErrInvalidInput, sourceType)
	}

	docs, err := source.Fetch(ctx, config)
	if err != nil {
		return 0, fmt.Errorf("failed to fetch from source: %w", err)
	}

	count := 0
	for _, doc := range docs {
		if err := im.IndexDocument(doc); err == nil {
			count++
		}
	}

	logger.Info("Synced %d/%d documents from %s", count, len(docs), sourceType)
	return count, nil
}

func (im *IndexManager) IndexDocument(doc *Document) error {
	im.mu.Lock()
	defer im.mu.Unlock()

	var existing Document
	result := im.db.DB().Where("checksum = ?", doc.Checksum).First(&existing)
	if result.Error == nil {
		existing.Content = doc.Content
		existing.Title = doc.Title
		existing.Summary = doc.Summary
		existing.IndexedAt = time.Now()
		existing.Tags = doc.Tags
		if err := im.db.DB().Save(&existing).Error; err != nil {
			return err
		}
		im.addToIndex(&existing)
		return nil
	}

	if !errors.Is(result.Error, gorm.ErrRecordNotFound) {
		return result.Error
	}

	doc.IndexedAt = time.Now()
	if err := im.db.DB().Create(doc).Error; err != nil {
		return err
	}

	im.addToIndex(doc)
	im.db.InvalidateCache(context.Background(), fmt.Sprintf("doc:%s", doc.ID))
	return nil
}

func (im *IndexManager) addToIndex(doc *Document) {
	content := strings.ToLower(doc.Title + " " + doc.Content + " " + doc.Tags)
	words := tokenize(content)

	for pos, word := range words {
		if len(word) < 2 || im.stopWords[word] {
			continue
		}
		if _, exists := im.index[word]; !exists {
			im.index[word] = make(map[string][]int)
		}
		im.index[word][doc.ID] = append(im.index[word][doc.ID], pos)
	}
}

func (im *IndexManager) Search(ctx context.Context, query SearchQuery, userPerms *DocumentPermission) (*models.PageResult, error) {
	query.Page, query.PageSize = normalizePagination(query.Page, query.PageSize)

	cacheKey := fmt.Sprintf("search:%s:%s", utils.MD5(utils.ToJSON(query)), userPerms.UserID)
	var cachedResult models.PageResult

	err := im.db.GetWithCache(ctx, cacheKey, &cachedResult, func() (interface{}, error) {
		return im.performSearch(query, userPerms)
	})

	if err != nil {
		return nil, err
	}

	return &cachedResult, nil
}

func (im *IndexManager) performSearch(query SearchQuery, userPerms *DocumentPermission) (*models.PageResult, error) {
	im.mu.RLock()
	defer im.mu.RUnlock()

	keyword := strings.ToLower(strings.TrimSpace(query.Keyword))
	var docScores map[string]float64

	if keyword != "" {
		docScores = make(map[string]float64)
		words := tokenize(keyword)

		for _, word := range words {
			if len(word) < 2 || im.stopWords[word] {
				continue
			}
			if docs, exists := im.index[word]; exists {
				for docID, positions := range docs {
					score := float64(len(positions))
					if strings.Contains(word, keyword) || len(word) == len(keyword) {
						score *= 2
					}
					docScores[docID] += score
				}
			}
		}

		if len(docScores) == 0 {
			return &models.PageResult{
				Total:    0,
				Page:     query.Page,
				PageSize: query.PageSize,
				Items:    []SearchResult{},
			}, nil
		}
	}

	dbQuery := im.db.DB().Model(&Document{})

	if len(query.Sources) > 0 {
		dbQuery = dbQuery.Where("source IN ?", query.Sources)
	}
	if query.ContentType != "" {
		dbQuery = dbQuery.Where("content_type = ?", query.ContentType)
	}
	if query.Author != "" {
		dbQuery = dbQuery.Where("author = ?", query.Author)
	}
	if query.DateFrom != nil {
		dbQuery = dbQuery.Where("created_at >= ?", *query.DateFrom)
	}
	if query.DateTo != nil {
		dbQuery = dbQuery.Where("created_at <= ?", *query.DateTo)
	}
	if len(query.Tags) > 0 {
		for _, tag := range query.Tags {
			dbQuery = dbQuery.Where("tags LIKE ?", "%"+tag+"%")
		}
	}

	var total int64
	dbQuery.Count(&total)

	var docs []Document
	offset := (query.Page - 1) * query.PageSize
	if err := dbQuery.Offset(offset).Limit(query.PageSize).Order("indexed_at DESC").Find(&docs).Error; err != nil {
		return nil, err
	}

	filteredDocs := make([]*Document, 0, len(docs))
	for i := range docs {
		if im.checkPermission(&docs[i], userPerms) {
			filteredDocs = append(filteredDocs, &docs[i])
		}
	}

	results := make([]SearchResult, 0, len(filteredDocs))
	for _, doc := range filteredDocs {
		score := 1.0
		if docScores != nil {
			if s, ok := docScores[doc.ID]; ok {
				score = s
			}
		}
		matches := im.findMatches(doc, query.Keyword)
		results = append(results, SearchResult{
			Document: doc,
			Score:    score,
			Matches:  matches,
		})
	}

	return &models.PageResult{
		Total:    total,
		Page:     query.Page,
		PageSize: query.PageSize,
		Items:    results,
	}, nil
}

func (im *IndexManager) checkPermission(doc *Document, userPerms *DocumentPermission) bool {
	if userPerms == nil {
		return true
	}
	if userPerms.Role == "admin" {
		return true
	}
	if userPerms.CanView {
		return true
	}

	var docPerms DocumentPermission
	if err := utils.FromJSON(doc.Permissions, &docPerms); err != nil {
		return true
	}

	if docPerms.Role == "viewer" && utils.ContainsString(docPerms.Groups, "everyone") {
		return true
	}

	for _, ug := range userPerms.Groups {
		if utils.ContainsString(docPerms.Groups, ug) {
			return true
		}
	}

	return false
}

func (im *IndexManager) findMatches(doc *Document, keyword string) []string {
	if keyword == "" {
		return nil
	}

	re := regexp.MustCompile(`(?i).{0,50}` + regexp.QuoteMeta(keyword) + `.{0,50}`)
	matches := re.FindAllString(doc.Content, 3)
	return matches
}

func (im *IndexManager) GetDocument(id string, userPerms *DocumentPermission) (*Document, error) {
	var doc Document
	cacheKey := fmt.Sprintf("doc:%s", id)

	err := im.db.GetWithCache(context.Background(), cacheKey, &doc, func() (interface{}, error) {
		if err := im.db.DB().First(&doc, "id = ?", id).Error; err != nil {
			if errors.Is(err, gorm.ErrRecordNotFound) {
				return nil, common.ErrNotFound
			}
			return nil, err
		}
		return doc, nil
	})

	if err != nil {
		return nil, err
	}

	if !im.checkPermission(&doc, userPerms) {
		return nil, common.ErrForbidden
	}

	doc.Views++
	im.db.DB().Model(&doc).Update("views", doc.Views)

	return &doc, nil
}

func (im *IndexManager) DeleteDocument(id string) error {
	im.mu.Lock()
	defer im.mu.Unlock()

	var doc Document
	if err := im.db.DB().First(&doc, "id = ?", id).Error; err != nil {
		return err
	}

	content := strings.ToLower(doc.Title + " " + doc.Content + " " + doc.Tags)
	words := tokenize(content)
	for _, word := range words {
		if _, exists := im.index[word]; exists {
			delete(im.index[word], id)
			if len(im.index[word]) == 0 {
				delete(im.index, word)
			}
		}
	}

	if err := im.db.DB().Delete(&doc).Error; err != nil {
		return err
	}

	im.db.InvalidateCache(context.Background(), fmt.Sprintf("doc:%s", id))
	logger.Info("Document deleted: %s", id)
	return nil
}

func (im *IndexManager) ListAll(page, pageSize int, source string) (*models.PageResult, error) {
	page, pageSize = normalizePagination(page, pageSize)

	var docs []Document
	var total int64

	query := im.db.DB().Model(&Document{})
	if source != "" {
		query = query.Where("source = ?", source)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("indexed_at DESC").Find(&docs).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    docs,
	}, nil
}

func (im *IndexManager) GetStats() map[string]interface{} {
	var totalDocs int64
	var totalViews int64
	var sources []string

	im.db.DB().Model(&Document{}).Count(&totalDocs)
	im.db.DB().Model(&Document{}).Select("COALESCE(SUM(views), 0)").Scan(&totalViews)
	im.db.DB().Model(&Document{}).Distinct("source").Pluck("source", &sources)

	sourceStats := make(map[string]int64)
	for _, s := range sources {
		var count int64
		im.db.DB().Model(&Document{}).Where("source = ?", s).Count(&count)
		sourceStats[s] = count
	}

	return map[string]interface{}{
		"total_documents": totalDocs,
		"total_views":     totalViews,
		"indexed_terms":   len(im.index),
		"sources":         sourceStats,
	}
}

func (im *IndexManager) RebuildIndex() error {
	im.mu.Lock()
	defer im.mu.Unlock()

	im.index = make(map[string]map[string][]int)

	var docs []Document
	if err := im.db.DB().Find(&docs).Error; err != nil {
		return err
	}

	for i := range docs {
		im.addToIndex(&docs[i])
	}

	logger.Info("Index rebuilt with %d documents, %d terms", len(docs), len(im.index))
	return nil
}

func (im *IndexManager) UpdateDocumentPermission(id string, perms DocumentPermission) error {
	permsJSON := utils.ToJSON(perms)
	result := im.db.DB().Model(&Document{}).Where("id = ?", id).Update("permissions", permsJSON)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return common.ErrNotFound
	}
	im.db.InvalidateCache(context.Background(), fmt.Sprintf("doc:%s", id))
	return nil
}

func readFileContent(path, ext string) (string, error) {
	if ext == ".pdf" || ext == ".png" || ext == ".jpg" || ext == ".jpeg" {
		return "", fmt.Errorf("binary file not supported")
	}

	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()

	reader := io.LimitReader(file, 10*1024*1024)
	content, err := ioutil.ReadAll(reader)
	if err != nil {
		return "", err
	}

	return string(content), nil
}

func calculateChecksum(content string) string {
	h := sha256.New()
	h.Write([]byte(content))
	return hex.EncodeToString(h.Sum(nil))
}

func generateSummary(content string) string {
	if len(content) <= 200 {
		return content
	}
	return content[:200] + "..."
}

func detectLanguage(ext string) string {
	langMap := map[string]string{
		".go":    "go",
		".java":  "java",
		".py":    "python",
		".js":    "javascript",
		".ts":    "typescript",
		".md":    "markdown",
		".yaml":  "yaml",
		".yml":   "yaml",
		".json":  "json",
		".xml":   "xml",
		".html":  "html",
		".css":   "css",
		".sql":   "sql",
		".rs":    "rust",
		".cpp":   "cpp",
		".c":     "c",
		".h":     "c",
		".sh":    "bash",
		".rb":    "ruby",
		".php":   "php",
	}
	if lang, ok := langMap[ext]; ok {
		return lang
	}
	return "plain"
}

func extractTags(content string) string {
	re := regexp.MustCompile(`#([a-zA-Z0-9_]+)`)
	matches := re.FindAllStringSubmatch(content, -1)

	tags := make([]string, 0, len(matches))
	for _, m := range matches {
		tags = append(tags, m[1])
	}

	tags = utils.UniqueStrings(tags)
	if len(tags) > 10 {
		tags = tags[:10]
	}
	return strings.Join(tags, ",")
}

func tokenize(text string) []string {
	re := regexp.MustCompile(`[\p{L}\p{N}]+`)
	return re.FindAllString(strings.ToLower(text), -1)
}

func normalizePagination(page, pageSize int) (int, int) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}
	return page, pageSize
}
