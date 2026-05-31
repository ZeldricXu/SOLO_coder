package documentpipeline

import (
	"bytes"
	"context"
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/logging"
)

type DocumentFormat string

const (
	FormatPDF      DocumentFormat = "pdf"
	FormatDOCX     DocumentFormat = "docx"
	FormatTXT      DocumentFormat = "txt"
	FormatMD       DocumentFormat = "md"
	FormatHTML     DocumentFormat = "html"
	FormatCSV      DocumentFormat = "csv"
	FormatJSON     DocumentFormat = "json"
	FormatXML      DocumentFormat = "xml"
	FormatPPTX     DocumentFormat = "pptx"
	FormatXLSX     DocumentFormat = "xlsx"
)

type ChunkStrategy string

const (
	ChunkStrategyFixedSize     ChunkStrategy = "fixed_size"
	ChunkStrategySemantic      ChunkStrategy = "semantic"
	ChunkStrategyByParagraph   ChunkStrategy = "paragraph"
	ChunkStrategyByHeading     ChunkStrategy = "heading"
	ChunkStrategyRecursive     ChunkStrategy = "recursive"
)

type Document struct {
	ID              string                 `json:"id"`
	Name            string                 `json:"name"`
	Format          DocumentFormat         `json:"format"`
	Size            int64                  `json:"size"`
	Path            string                 `json:"path"`
	Content         string                 `json:"content,omitempty"`
	Metadata        map[string]interface{} `json:"metadata"`
	Encoding        string                 `json:"encoding"`
	NumPages        int                    `json:"num_pages,omitempty"`
	NumChars        int                    `json:"num_chars"`
	NumWords        int                    `json:"num_words"`
	DetectedLanguage string                `json:"detected_language"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
}

type DocumentChunk struct {
	ID            string                 `json:"id"`
	DocumentID    string                 `json:"document_id"`
	Index         int                    `json:"index"`
	Content       string                 `json:"content"`
	StartPosition int                    `json:"start_position"`
	EndPosition   int                    `json:"end_position"`
	WordCount     int                    `json:"word_count"`
	TokenCount    int                    `json:"token_count"`
	Headings      []string               `json:"headings,omitempty"`
	Embedding     []float32              `json:"embedding,omitempty"`
	Metadata      map[string]interface{} `json:"metadata"`
	CreatedAt     time.Time              `json:"created_at"`
}

type Parser interface {
	Parse(ctx context.Context, path string) (*Document, error)
	Supports(format DocumentFormat) bool
}

type Chunker interface {
	Chunk(ctx context.Context, doc *Document, config ChunkConfig) ([]DocumentChunk, error)
	Strategy() ChunkStrategy
}

type Vectorizer interface {
	Vectorize(ctx context.Context, chunks []DocumentChunk) error
	Dimension() int
	ModelName() string
}

type ChunkConfig struct {
	Strategy       ChunkStrategy `json:"strategy"`
	ChunkSize      int           `json:"chunk_size"`
	ChunkOverlap   int           `json:"chunk_overlap"`
	MaxChunkSize   int           `json:"max_chunk_size"`
	MinChunkSize   int           `json:"min_chunk_size"`
	Separator      string        `json:"separator"`
	PreserveHeadings bool         `json:"preserve_headings"`
}

type PipelineConfig struct {
	ChunkConfig    ChunkConfig
	EnableOCR      bool
	EnableVectorization bool
	TargetLanguages []string
	ExtractImages  bool
	ExtractTables  bool
}

type TextParser struct{}

func NewTextParser() *TextParser {
	return &TextParser{}
}

func (p *TextParser) Parse(ctx context.Context, path string) (*Document, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}

	textContent := string(content)
	ext := strings.ToLower(filepath.Ext(path))
	format := detectFormat(ext)

	return &Document{
		ID:               "doc_" + time.Now().Format("20060102150405"),
		Name:             filepath.Base(path),
		Format:           format,
		Size:             info.Size(),
		Path:             path,
		Content:          textContent,
		Metadata:         make(map[string]interface{}),
		Encoding:         "utf-8",
		NumChars:         len(textContent),
		NumWords:         countWords(textContent),
		DetectedLanguage: detectLanguage(textContent),
		CreatedAt:        time.Now(),
		UpdatedAt:        time.Now(),
	}, nil
}

func (p *TextParser) Supports(format DocumentFormat) bool {
	return format == FormatTXT || format == FormatMD || format == FormatHTML
}

type CSVParser struct{}

func NewCSVParser() *CSVParser {
	return &CSVParser{}
}

func (p *CSVParser) Parse(ctx context.Context, path string) (*Document, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	info, _ := os.Stat(path)
	reader := csv.NewReader(file)
	records, err := reader.ReadAll()
	if err != nil {
		return nil, err
	}

	var content strings.Builder
	for _, row := range records {
		content.WriteString(strings.Join(row, " | "))
		content.WriteString("\n")
	}

	textContent := content.String()
	return &Document{
		ID:               "doc_" + time.Now().Format("20060102150405"),
		Name:             filepath.Base(path),
		Format:           FormatCSV,
		Size:             info.Size(),
		Path:             path,
		Content:          textContent,
		Metadata: map[string]interface{}{
			"rows":    len(records),
			"columns": len(records[0]),
		},
		Encoding:         "utf-8",
		NumChars:         len(textContent),
		NumWords:         countWords(textContent),
		DetectedLanguage: detectLanguage(textContent),
		CreatedAt:        time.Now(),
		UpdatedAt:        time.Now(),
	}, nil
}

func (p *CSVParser) Supports(format DocumentFormat) bool {
	return format == FormatCSV
}

type JSONParser struct{}

func NewJSONParser() *JSONParser {
	return &JSONParser{}
}

func (p *JSONParser) Parse(ctx context.Context, path string) (*Document, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	info, _ := os.Stat(path)

	var parsed interface{}
	if err := json.Unmarshal(content, &parsed); err != nil {
		return nil, err
	}

	flattened := flattenJSON(parsed, "", "\n")
	textContent := flattened

	return &Document{
		ID:               "doc_" + time.Now().Format("20060102150405"),
		Name:             filepath.Base(path),
		Format:           FormatJSON,
		Size:             info.Size(),
		Path:             path,
		Content:          textContent,
		Metadata:         make(map[string]interface{}),
		Encoding:         "utf-8",
		NumChars:         len(textContent),
		NumWords:         countWords(textContent),
		DetectedLanguage: detectLanguage(textContent),
		CreatedAt:        time.Now(),
		UpdatedAt:        time.Now(),
	}, nil
}

func (p *JSONParser) Supports(format DocumentFormat) bool {
	return format == FormatJSON
}

type FixedSizeChunker struct{}

func NewFixedSizeChunker() *FixedSizeChunker {
	return &FixedSizeChunker{}
}

func (c *FixedSizeChunker) Strategy() ChunkStrategy {
	return ChunkStrategyFixedSize
}

func (c *FixedSizeChunker) Chunk(ctx context.Context, doc *Document, config ChunkConfig) ([]DocumentChunk, error) {
	if config.ChunkSize <= 0 {
		config.ChunkSize = 1000
	}
	if config.ChunkOverlap < 0 {
		config.ChunkOverlap = 200
	}

	content := doc.Content
	var chunks []DocumentChunk
	start := 0
	index := 0

	for start < len(content) {
		end := start + config.ChunkSize
		if end > len(content) {
			end = len(content)
		}

		if end < len(content) {
			adjustedEnd := findSentenceEnd(content, start, end)
			if adjustedEnd > start {
				end = adjustedEnd
			}
		}

		chunkContent := content[start:end]
		chunks = append(chunks, DocumentChunk{
			ID:            fmt.Sprintf("%s_chunk_%d", doc.ID, index),
			DocumentID:    doc.ID,
			Index:         index,
			Content:       chunkContent,
			StartPosition: start,
			EndPosition:   end,
			WordCount:     countWords(chunkContent),
			TokenCount:    estimateTokenCount(chunkContent),
			Metadata:      map[string]interface{}{"strategy": string(c.Strategy())},
			CreatedAt:     time.Now(),
		})

		if end >= len(content) {
			break
		}

		start = end - config.ChunkOverlap
		if start < 0 {
			start = 0
		}
		index++
	}

	return chunks, nil
}

type ParagraphChunker struct{}

func NewParagraphChunker() *ParagraphChunker {
	return &ParagraphChunker{}
}

func (c *ParagraphChunker) Strategy() ChunkStrategy {
	return ChunkStrategyByParagraph
}

func (c *ParagraphChunker) Chunk(ctx context.Context, doc *Document, config ChunkConfig) ([]DocumentChunk, error) {
	content := doc.Content
	paragraphs := strings.Split(content, "\n\n")
	var chunks []DocumentChunk
	position := 0

	for i, para := range paragraphs {
		para = strings.TrimSpace(para)
		if para == "" {
			position += len(para) + 2
			continue
		}

		chunks = append(chunks, DocumentChunk{
			ID:            fmt.Sprintf("%s_chunk_%d", doc.ID, i),
			DocumentID:    doc.ID,
			Index:         i,
			Content:       para,
			StartPosition: position,
			EndPosition:   position + len(para),
			WordCount:     countWords(para),
			TokenCount:    estimateTokenCount(para),
			Metadata:      map[string]interface{}{"strategy": string(c.Strategy())},
			CreatedAt:     time.Now(),
		})

		position += len(para) + 2
	}

	return chunks, nil
}

type HeadingChunker struct{}

func NewHeadingChunker() *HeadingChunker {
	return &HeadingChunker{}
}

func (c *HeadingChunker) Strategy() ChunkStrategy {
	return ChunkStrategyByHeading
}

func (c *HeadingChunker) Chunk(ctx context.Context, doc *Document, config ChunkConfig) ([]DocumentChunk, error) {
	content := doc.Content
	lines := strings.Split(content, "\n")
	var chunks []DocumentChunk
	var currentContent bytes.Buffer
	var currentHeadings []string
	var currentStart int
	index := 0
	position := 0

	for _, line := range lines {
		if isHeading(line) {
			if currentContent.Len() > 0 {
				chunks = append(chunks, DocumentChunk{
					ID:            fmt.Sprintf("%s_chunk_%d", doc.ID, index),
					DocumentID:    doc.ID,
					Index:         index,
					Content:       currentContent.String(),
					StartPosition: currentStart,
					EndPosition:   position,
					WordCount:     countWords(currentContent.String()),
					TokenCount:    estimateTokenCount(currentContent.String()),
					Headings:      append([]string{}, currentHeadings...),
					Metadata:      map[string]interface{}{"strategy": string(c.Strategy())},
					CreatedAt:     time.Now(),
				})
				index++
			}

			headingLevel := getHeadingLevel(line)
			headingText := stripHeadingMarkers(line)

			for len(currentHeadings) >= headingLevel {
				currentHeadings = currentHeadings[:headingLevel-1]
			}
			currentHeadings = append(currentHeadings, headingText)

			currentContent.Reset()
			currentContent.WriteString(line)
			currentStart = position
		} else {
			if currentContent.Len() > 0 {
				currentContent.WriteString("\n")
			}
			currentContent.WriteString(line)
		}

		position += len(line) + 1
	}

	if currentContent.Len() > 0 {
		chunks = append(chunks, DocumentChunk{
			ID:            fmt.Sprintf("%s_chunk_%d", doc.ID, index),
			DocumentID:    doc.ID,
			Index:         index,
			Content:       currentContent.String(),
			StartPosition: currentStart,
			EndPosition:   position,
			WordCount:     countWords(currentContent.String()),
			TokenCount:    estimateTokenCount(currentContent.String()),
			Headings:      append([]string{}, currentHeadings...),
			Metadata:      map[string]interface{}{"strategy": string(c.Strategy())},
			CreatedAt:     time.Now(),
		})
	}

	return chunks, nil
}

type MockVectorizer struct {
	dimensions int
	modelName  string
}

func NewMockVectorizer(dimensions int, modelName string) *MockVectorizer {
	return &MockVectorizer{
		dimensions: dimensions,
		modelName:  modelName,
	}
}

func (v *MockVectorizer) Dimension() int {
	return v.dimensions
}

func (v *MockVectorizer) ModelName() string {
	return v.modelName
}

func (v *MockVectorizer) Vectorize(ctx context.Context, chunks []DocumentChunk) error {
	for i := range chunks {
		embedding := make([]float32, v.dimensions)
		seed := int64(0)
		for _, c := range chunks[i].Content {
			seed += int64(c)
		}
		for j := range embedding {
			seed = (seed*1103515245 + 12345) & 0x7fffffff
			embedding[j] = float32(seed)/float32(0x7fffffff)*2 - 1
		}
		chunks[i].Embedding = embedding
	}
	return nil
}

type DocumentPipeline struct {
	parsers    map[DocumentFormat]Parser
	chunkers   map[ChunkStrategy]Chunker
	vectorizer Vectorizer
	config     PipelineConfig
}

func NewDocumentPipeline(config PipelineConfig) *DocumentPipeline {
	dp := &DocumentPipeline{
		parsers:  make(map[DocumentFormat]Parser),
		chunkers: make(map[ChunkStrategy]Chunker),
		config:   config,
	}

	dp.registerDefaultParsers()
	dp.registerDefaultChunkers()
	dp.vectorizer = NewMockVectorizer(1536, "text-embedding-3-small")

	return dp
}

func (dp *DocumentPipeline) registerDefaultParsers() {
	textParser := NewTextParser()
	dp.parsers[FormatTXT] = textParser
	dp.parsers[FormatMD] = textParser
	dp.parsers[FormatHTML] = textParser
	dp.parsers[FormatCSV] = NewCSVParser()
	dp.parsers[FormatJSON] = NewJSONParser()
}

func (dp *DocumentPipeline) registerDefaultChunkers() {
	dp.chunkers[ChunkStrategyFixedSize] = NewFixedSizeChunker()
	dp.chunkers[ChunkStrategyByParagraph] = NewParagraphChunker()
	dp.chunkers[ChunkStrategyByHeading] = NewHeadingChunker()
}

func (dp *DocumentPipeline) RegisterParser(parser Parser, formats ...DocumentFormat) {
	for _, format := range formats {
		dp.parsers[format] = parser
	}
}

func (dp *DocumentPipeline) RegisterChunker(chunker Chunker) {
	dp.chunkers[chunker.Strategy()] = chunker
}

func (dp *DocumentPipeline) SetVectorizer(vectorizer Vectorizer) {
	dp.vectorizer = vectorizer
}

func (dp *DocumentPipeline) ProcessDocument(ctx context.Context, path string) (*Document, []DocumentChunk, error) {
	logging.Info(ctx, "Processing document", zap.String("path", path))

	doc, err := dp.Parse(ctx, path)
	if err != nil {
		return nil, nil, fmt.Errorf("parse failed: %w", err)
	}

	chunks, err := dp.Chunk(ctx, doc, dp.config.ChunkConfig)
	if err != nil {
		return doc, nil, fmt.Errorf("chunk failed: %w", err)
	}

	if dp.config.EnableVectorization && dp.vectorizer != nil {
		if err := dp.Vectorize(ctx, chunks); err != nil {
			logging.Warn(ctx, "Vectorization failed, continuing without embeddings", zap.Error(err))
		}
	}

	logging.Info(ctx, "Document processed successfully",
		zap.String("doc_id", doc.ID),
		zap.Int("num_chunks", len(chunks)),
		zap.Int("num_words", doc.NumWords))

	return doc, chunks, nil
}

func (dp *DocumentPipeline) Parse(ctx context.Context, path string) (*Document, error) {
	ext := strings.ToLower(filepath.Ext(path))
	format := detectFormat(ext)

	parser, exists := dp.parsers[format]
	if !exists {
		for _, p := range dp.parsers {
			if p.Supports(format) {
				parser = p
				break
			}
		}
	}

	if parser == nil {
		return nil, fmt.Errorf("no parser found for format: %s", format)
	}

	return parser.Parse(ctx, path)
}

func (dp *DocumentPipeline) Chunk(ctx context.Context, doc *Document, config ChunkConfig) ([]DocumentChunk, error) {
	chunker, exists := dp.chunkers[config.Strategy]
	if !exists {
		chunker = dp.chunkers[ChunkStrategyFixedSize]
	}

	if chunker == nil {
		return nil, errors.New("no chunker available")
	}

	return chunker.Chunk(ctx, doc, config)
}

func (dp *DocumentPipeline) Vectorize(ctx context.Context, chunks []DocumentChunk) error {
	if dp.vectorizer == nil {
		return errors.New("no vectorizer configured")
	}
	return dp.vectorizer.Vectorize(ctx, chunks)
}

func (dp *DocumentPipeline) ProcessBatch(ctx context.Context, paths []string, concurrency int) ([]*Document, [][]DocumentChunk, error) {
	if concurrency <= 0 {
		concurrency = 4
	}

	sem := make(chan struct{}, concurrency)
	var wg sync.WaitGroup
	var mu sync.Mutex

	results := make([]*Document, 0, len(paths))
	allChunks := make([][]DocumentChunk, 0, len(paths))
	errors := make([]error, 0)

	for _, path := range paths {
		wg.Add(1)
		go func(p string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			doc, chunks, err := dp.ProcessDocument(ctx, p)
			mu.Lock()
			defer mu.Unlock()

			if err != nil {
				errors = append(errors, err)
				return
			}

			results = append(results, doc)
			allChunks = append(allChunks, chunks)
		}(path)
	}

	wg.Wait()

	if len(errors) > 0 {
		return results, allChunks, fmt.Errorf("encountered %d errors: %v", len(errors), errors[0])
	}

	return results, allChunks, nil
}

func (dp *DocumentPipeline) SupportedFormats() []DocumentFormat {
	formats := make([]DocumentFormat, 0, len(dp.parsers))
	for f := range dp.parsers {
		formats = append(formats, f)
	}
	return formats
}

func (dp *DocumentPipeline) SupportedStrategies() []ChunkStrategy {
	strategies := make([]ChunkStrategy, 0, len(dp.chunkers))
	for s := range dp.chunkers {
		strategies = append(strategies, s)
	}
	return strategies
}

func detectFormat(ext string) DocumentFormat {
	switch strings.TrimPrefix(ext, ".") {
	case "pdf":
		return FormatPDF
	case "docx":
		return FormatDOCX
	case "txt":
		return FormatTXT
	case "md", "markdown":
		return FormatMD
	case "html", "htm":
		return FormatHTML
	case "csv":
		return FormatCSV
	case "json":
		return FormatJSON
	case "xml":
		return FormatXML
	case "pptx":
		return FormatPPTX
	case "xlsx":
		return FormatXLSX
	default:
		return FormatTXT
	}
}

func countWords(text string) int {
	count := 0
	inWord := false
	for _, r := range text {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			if !inWord {
				count++
				inWord = true
			}
		} else {
			inWord = false
		}
	}
	return count
}

func estimateTokenCount(text string) int {
	return (len(text) + 3) / 4
}

func detectLanguage(text string) string {
	if len(text) == 0 {
		return "unknown"
	}

	englishWords := []string{"the", "and", "is", "in", "to", "of", "a", "that", "it", "for"}
	lowerText := strings.ToLower(text)
	englishCount := 0
	for _, word := range englishWords {
		if strings.Contains(lowerText, " "+word+" ") {
			englishCount++
		}
	}

	if englishCount >= 2 {
		return "en"
	}

	chineseCount := 0
	for _, r := range text {
		if unicode.Is(unicode.Han, r) {
			chineseCount++
		}
	}

	if chineseCount > len(text)/10 {
		return "zh"
	}

	return "unknown"
}

func findSentenceEnd(content string, start, end int) int {
	if end > len(content) {
		end = len(content)
	}

	for i := end - 1; i > start; i-- {
		c := content[i]
		if c == '.' || c == '!' || c == '?' || c == '\n' {
			return i + 1
		}
	}

	return end
}

func isHeading(line string) bool {
	trimmed := strings.TrimSpace(line)
	if strings.HasPrefix(trimmed, "#") {
		return true
	}
	if len(trimmed) > 0 && len(trimmed) < 100 && !strings.Contains(trimmed, ". ") {
		if strings.HasSuffix(trimmed, ":") {
			return true
		}
	}
	return false
}

func getHeadingLevel(line string) int {
	trimmed := strings.TrimSpace(line)
	level := 0
	for _, c := range trimmed {
		if c == '#' {
			level++
		} else {
			break
		}
	}
	if level == 0 {
		return 1
	}
	return level
}

func stripHeadingMarkers(line string) string {
	trimmed := strings.TrimSpace(line)
	return strings.TrimLeft(strings.TrimLeft(trimmed, "#"), " ")
}

func flattenJSON(v interface{}, prefix, indent string) string {
	var buf bytes.Buffer

	switch val := v.(type) {
	case map[string]interface{}:
		keys := make([]string, 0, len(val))
		for k := range val {
			keys = append(keys, k)
		}
		sortStrings(keys)
		for _, k := range keys {
			subPrefix := k
			if prefix != "" {
				subPrefix = prefix + "." + k
			}
			flattened := flattenJSON(val[k], subPrefix, indent)
			if flattened != "" {
				if buf.Len() > 0 {
					buf.WriteString("\n")
				}
				buf.WriteString(flattened)
			}
		}
	case []interface{}:
		for i, item := range val {
			subPrefix := fmt.Sprintf("%s[%d]", prefix, i)
			flattened := flattenJSON(item, subPrefix, indent)
			if flattened != "" {
				if buf.Len() > 0 {
					buf.WriteString("\n")
				}
				buf.WriteString(flattened)
			}
		}
	default:
		if val != nil {
			buf.WriteString(fmt.Sprintf("%s: %v", prefix, val))
		}
	}

	return buf.String()
}

func sortStrings(s []string) {
	for i := 1; i < len(s); i++ {
		for j := i; j > 0 && s[j-1] > s[j]; j-- {
			s[j-1], s[j] = s[j], s[j-1]
		}
	}
}

type DocumentStore struct {
	documents map[string]*Document
	chunks    map[string][]DocumentChunk
	mu        sync.RWMutex
}

func NewDocumentStore() *DocumentStore {
	return &DocumentStore{
		documents: make(map[string]*Document),
		chunks:    make(map[string][]DocumentChunk),
	}
}

func (ds *DocumentStore) Save(doc *Document, chunks []DocumentChunk) {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	ds.documents[doc.ID] = doc
	ds.chunks[doc.ID] = chunks
}

func (ds *DocumentStore) GetDocument(docID string) (*Document, bool) {
	ds.mu.RLock()
	defer ds.mu.RUnlock()
	doc, exists := ds.documents[docID]
	return doc, exists
}

func (ds *DocumentStore) GetChunks(docID string) ([]DocumentChunk, bool) {
	ds.mu.RLock()
	defer ds.mu.RUnlock()
	chunks, exists := ds.chunks[docID]
	return chunks, exists
}

func (ds *DocumentStore) SearchChunks(query string, topK int) []DocumentChunk {
	ds.mu.RLock()
	defer ds.mu.RUnlock()

	type scoredChunk struct {
		chunk DocumentChunk
		score int
	}

	queryLower := strings.ToLower(query)
	queryWords := strings.Fields(queryLower)
	var results []scoredChunk

	for _, chunks := range ds.chunks {
		for _, chunk := range chunks {
			score := 0
			contentLower := strings.ToLower(chunk.Content)
			for _, word := range queryWords {
				score += strings.Count(contentLower, word)
			}
			if score > 0 {
				results = append(results, scoredChunk{chunk: chunk, score: score})
			}
		}
	}

	sortScoredChunks(results)

	if topK > len(results) {
		topK = len(results)
	}

	output := make([]DocumentChunk, topK)
	for i := 0; i < topK; i++ {
		output[i] = results[i].chunk
	}

	return output
}

func sortScoredChunks(s []scoredChunk) {
	for i := 1; i < len(s); i++ {
		for j := i; j > 0 && s[j-1].score < s[j].score; j-- {
			s[j-1], s[j] = s[j], s[j-1]
		}
	}
}

func (ds *DocumentStore) ListDocuments() []*Document {
	ds.mu.RLock()
	defer ds.mu.RUnlock()

	docs := make([]*Document, 0, len(ds.documents))
	for _, doc := range ds.documents {
		docs = append(docs, doc)
	}
	return docs
}
