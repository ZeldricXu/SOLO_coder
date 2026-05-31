package document

import (
	"bytes"
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"math/rand"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/solocoder/logrotate/internal/domain"
)

type Parser interface {
	Parse(ctx context.Context, data []byte) (string, map[string]interface{}, error)
	SupportedFormats() []string
}

type Chunker interface {
	Split(ctx context.Context, content string, metadata map[string]interface{}) ([]domain.DocumentChunk, error)
}

type Vectorizer interface {
	Vectorize(ctx context.Context, text string) ([]float64, error)
}

type PipelineStage string

const (
	StageParse     PipelineStage = "parse"
	StageChunk     PipelineStage = "chunk"
	StageVectorize PipelineStage = "vectorize"
)

type Pipeline struct {
	mu         sync.RWMutex
	parsers    map[string]Parser
	chunker    Chunker
	vectorizer Vectorizer
	docStore   map[string]*domain.Document
}

type ProcessingOptions struct {
	SkipParsing     bool
	SkipChunking    bool
	SkipVectorizing bool
	ChunkSize       int
	ChunkOverlap    int
}

func NewPipeline() *Pipeline {
	p := &Pipeline{
		parsers:  make(map[string]Parser),
		docStore: make(map[string]*domain.Document),
	}

	p.RegisterParser(&TextParser{})
	p.RegisterParser(&JSONParser{})
	p.RegisterParser(&MarkdownParser{})
	p.RegisterParser(&XMLParser{})

	p.chunker = &SemanticChunker{
		ChunkSize:    512,
		ChunkOverlap: 50,
	}

	p.vectorizer = &MockVectorizer{
		Dimension: 1536,
	}

	return p
}

func (p *Pipeline) RegisterParser(parser Parser) {
	p.mu.Lock()
	defer p.mu.Unlock()

	for _, format := range parser.SupportedFormats() {
		p.parsers[format] = parser
	}
}

func (p *Pipeline) SetChunker(chunker Chunker) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.chunker = chunker
}

func (p *Pipeline) SetVectorizer(vectorizer Vectorizer) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.vectorizer = vectorizer
}

func (p *Pipeline) Process(ctx context.Context, data []byte, format string, opts ...ProcessingOptions) (*domain.Document, error) {
	var options ProcessingOptions
	if len(opts) > 0 {
		options = opts[0]
	} else {
		options = ProcessingOptions{
			ChunkSize:    512,
			ChunkOverlap: 50,
		}
	}

	doc := &domain.Document{
		ID:        uuid.New().String(),
		Format:    format,
		Metadata:  make(map[string]interface{}),
		CreatedAt: time.Now(),
	}

	if !options.SkipParsing {
		parser, ok := p.parsers[format]
		if !ok {
			return nil, fmt.Errorf("no parser for format: %s", format)
		}

		content, metadata, err := parser.Parse(ctx, data)
		if err != nil {
			return nil, fmt.Errorf("parse error: %w", err)
		}

		doc.Content = content
		doc.Metadata = metadata
	} else {
		doc.Content = string(data)
	}

	if !options.SkipChunking {
		if chunker, ok := p.chunker.(*SemanticChunker); ok {
			if options.ChunkSize > 0 {
				chunker.ChunkSize = options.ChunkSize
			}
			if options.ChunkOverlap > 0 {
				chunker.ChunkOverlap = options.ChunkOverlap
			}
		}

		chunks, err := p.chunker.Split(ctx, doc.Content, doc.Metadata)
		if err != nil {
			return nil, fmt.Errorf("chunk error: %w", err)
		}

		doc.Chunks = chunks
	}

	if !options.SkipVectorizing && p.vectorizer != nil {
		for i := range doc.Chunks {
			embedding, err := p.vectorizer.Vectorize(ctx, doc.Chunks[i].Content)
			if err != nil {
				return nil, fmt.Errorf("vectorize error: %w", err)
			}
			doc.Chunks[i].Embedding = embedding
		}
	}

	p.mu.Lock()
	p.docStore[doc.ID] = doc
	p.mu.Unlock()

	return doc, nil
}

func (p *Pipeline) ProcessFile(ctx context.Context, filePath string, opts ...ProcessingOptions) (*domain.Document, error) {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return nil, fmt.Errorf("read file: %w", err)
	}

	ext := strings.ToLower(filepath.Ext(filePath))
	format := strings.TrimPrefix(ext, ".")

	return p.Process(ctx, data, format, opts...)
}

func (p *Pipeline) GetDocument(docID string) (*domain.Document, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	doc, ok := p.docStore[docID]
	return doc, ok
}

func (p *Pipeline) ListDocuments() []*domain.Document {
	p.mu.RLock()
	defer p.mu.RUnlock()

	docs := make([]*domain.Document, 0, len(p.docStore))
	for _, doc := range p.docStore {
		docs = append(docs, doc)
	}
	return docs
}

func (p *Pipeline) DeleteDocument(docID string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()

	if _, ok := p.docStore[docID]; ok {
		delete(p.docStore, docID)
		return true
	}
	return false
}

func (p *Pipeline) SearchSimilar(ctx context.Context, query string, topK int) ([]*domain.DocumentChunk, error) {
	if p.vectorizer == nil {
		return nil, fmt.Errorf("vectorizer not configured")
	}

	queryEmbedding, err := p.vectorizer.Vectorize(ctx, query)
	if err != nil {
		return nil, fmt.Errorf("vectorize query: %w", err)
	}

	p.mu.RLock()
	defer p.mu.RUnlock()

	type scoredChunk struct {
		chunk domain.DocumentChunk
		score float64
	}

	var results []scoredChunk

	for _, doc := range p.docStore {
		for _, chunk := range doc.Chunks {
			if len(chunk.Embedding) > 0 {
				score := cosineSimilarity(queryEmbedding, chunk.Embedding)
				results = append(results, scoredChunk{chunk: chunk, score: score})
			}
		}
	}

	for i := range results {
		for j := i + 1; j < len(results); j++ {
			if results[i].score < results[j].score {
				results[i], results[j] = results[j], results[i]
			}
		}
	}

	if topK > len(results) {
		topK = len(results)
	}

	chunks := make([]*domain.DocumentChunk, topK)
	for i := 0; i < topK; i++ {
		chunks[i] = &results[i].chunk
	}

	return chunks, nil
}

func cosineSimilarity(a, b []float64) float64 {
	if len(a) != len(b) {
		return 0
	}

	var dotProduct, normA, normB float64
	for i := range a {
		dotProduct += a[i] * b[i]
		normA += a[i] * a[i]
		normB += b[i] * b[i]
	}

	if normA == 0 || normB == 0 {
		return 0
	}

	return dotProduct / (normA * normB)
}

type TextParser struct{}

func (p *TextParser) Parse(ctx context.Context, data []byte) (string, map[string]interface{}, error) {
	content := string(data)
	metadata := map[string]interface{}{
		"length":      len(content),
		"line_count":  strings.Count(content, "\n") + 1,
		"word_count":  len(strings.Fields(content)),
		"parse_time":  time.Now(),
	}
	return content, metadata, nil
}

func (p *TextParser) SupportedFormats() []string {
	return []string{"txt", "text", "log", "csv", "tsv"}
}

type JSONParser struct{}

func (p *JSONParser) Parse(ctx context.Context, data []byte) (string, map[string]interface{}, error) {
	var buf bytes.Buffer
	if err := buf.Write(data); err != nil {
		return "", nil, err
	}

	metadata := map[string]interface{}{
		"format":     "json",
		"raw_length": len(data),
		"parse_time": time.Now(),
	}

	var prettyJSON bytes.Buffer
	if err := prettyJSON.Indent(data, "", "  "); err == nil {
		return prettyJSON.String(), metadata, nil
	}

	return string(data), metadata, nil
}

func (p *JSONParser) SupportedFormats() []string {
	return []string{"json", "jsonl", "ndjson"}
}

type MarkdownParser struct{}

func (p *MarkdownParser) Parse(ctx context.Context, data []byte) (string, map[string]interface{}, error) {
	content := string(data)

	headingCount := strings.Count(content, "# ")
	boldCount := strings.Count(content, "**")
	italicCount := strings.Count(content, "*")
	codeBlocks := strings.Count(content, "```")

	metadata := map[string]interface{}{
		"format":        "markdown",
		"length":        len(content),
		"headings":      headingCount,
		"bold_elements": boldCount / 2,
		"italic_elements": italicCount / 2,
		"code_blocks":   codeBlocks / 2,
		"parse_time":    time.Now(),
	}

	return content, metadata, nil
}

func (p *MarkdownParser) SupportedFormats() []string {
	return []string{"md", "markdown"}
}

type XMLParser struct{}

func (p *XMLParser) Parse(ctx context.Context, data []byte) (string, map[string]interface{}, error) {
	content := string(data)

	decoder := xml.NewDecoder(bytes.NewReader(data))
	tagCount := 0
	for {
		tok, err := decoder.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			break
		}
		if _, ok := tok.(xml.StartElement); ok {
			tagCount++
		}
	}

	metadata := map[string]interface{}{
		"format":     "xml",
		"length":     len(content),
		"tag_count":  tagCount,
		"parse_time": time.Now(),
	}

	return content, metadata, nil
}

func (p *XMLParser) SupportedFormats() []string {
	return []string{"xml", "html", "xhtml"}
}

type SemanticChunker struct {
	ChunkSize    int
	ChunkOverlap int
	Delimiters   []string
}

func (c *SemanticChunker) Split(ctx context.Context, content string, metadata map[string]interface{}) ([]domain.DocumentChunk, error) {
	if c.Delimiters == nil {
		c.Delimiters = []string{"\n\n", "\n", ". ", "! ", "? ", "; ", "，", "。"}
	}

	if c.ChunkSize <= 0 {
		c.ChunkSize = 512
	}

	runes := []rune(content)
	chunks := make([]domain.DocumentChunk, 0)
	index := 0

	for i := 0; i < len(runes); {
		end := i + c.ChunkSize
		if end > len(runes) {
			end = len(runes)
		}

		chunkText := string(runes[i:end])

		if end < len(runes) {
			actualEnd := end
			for _, delim := range c.Delimiters {
				if pos := strings.LastIndex(chunkText, delim); pos > c.ChunkSize/2 {
					actualEnd = i + pos + len(delim)
					chunkText = string(runes[i:actualEnd])
					break
				}
			}
			i = actualEnd - c.ChunkOverlap
		} else {
			i = end
		}

		chunk := domain.DocumentChunk{
			ID:       uuid.New().String(),
			Content:  strings.TrimSpace(chunkText),
			Index:    index,
			StartPos: strings.Index(content, chunkText),
			EndPos:   strings.Index(content, chunkText) + len(chunkText),
		}

		if chunk.StartPos < 0 {
			chunk.StartPos = i
			chunk.EndPos = end
		}

		chunks = append(chunks, chunk)
		index++

		if i >= len(runes) {
			break
		}
	}

	return chunks, nil
}

type MockVectorizer struct {
	Dimension int
}

func (v *MockVectorizer) Vectorize(ctx context.Context, text string) ([]float64, error) {
	if v.Dimension <= 0 {
		v.Dimension = 1536
	}

	rand.Seed(time.Now().UnixNano())
	embedding := make([]float64, v.Dimension)

	hash := 0
	for _, r := range text {
		hash = (hash*31 + int(r)) % 1000000
	}

	for i := range embedding {
		embedding[i] = (float64(hash+i) / 1000000.0) * 2 - 1
		if embedding[i] > 1 {
			embedding[i] = 1
		}
		if embedding[i] < -1 {
			embedding[i] = -1
		}
	}

	norm := 0.0
	for _, v := range embedding {
		norm += v * v
	}
	norm = 1.0 / (norm + 0.000001)
	for i := range embedding {
		embedding[i] *= norm
	}

	return embedding, nil
}
