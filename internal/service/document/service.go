package document

import (
	"errors"
	"fmt"
	"math"
	"strings"
	"time"

	"gorm.io/gorm"

	"llmgateway/internal/domain/entity"
	"llmgateway/internal/infrastructure/database"
	"llmgateway/internal/infrastructure/logger"
	"llmgateway/pkg/utils"
)

type Service struct {
	db *gorm.DB
}

func NewService() *Service {
	return &Service{
		db: database.DB(),
	}
}

type UploadDocumentRequest struct {
	Name     string                 `json:"name" binding:"required"`
	Type     string                 `json:"type" binding:"required"`
	Source   string                 `json:"source"`
	Content  string                 `json:"content"`
	Size     int64                  `json:"size"`
	Metadata map[string]interface{} `json:"metadata"`
	CreatedBy string                `json:"-"`
}

func (s *Service) UploadDocument(req *UploadDocumentRequest) (*entity.Document, error) {
	now := utils.Now()
	doc := &entity.Document{
		ID:        utils.GenerateID("doc"),
		Name:      req.Name,
		Type:      req.Type,
		Size:      req.Size,
		Source:    req.Source,
		Status:    "uploaded",
		Metadata:  req.Metadata,
		CreatedBy: req.CreatedBy,
		CreatedAt: now,
		UpdatedAt: now,
	}

	if err := s.db.Create(doc).Error; err != nil {
		return nil, fmt.Errorf("failed to create document: %w", err)
	}

	logger.Info("document uploaded", "document_id", doc.ID, "name", doc.Name, "type", doc.Type)
	return doc, nil
}

func (s *Service) GetDocument(id string) (*entity.Document, error) {
	var doc entity.Document
	if err := s.db.Where("id = ?", id).First(&doc).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("document not found")
		}
		return nil, fmt.Errorf("failed to get document: %w", err)
	}
	return &doc, nil
}

func (s *Service) ListDocuments(page, pageSize int, docType, status string) ([]entity.Document, int64, error) {
	var docs []entity.Document
	var total int64

	query := s.db.Model(&entity.Document{})
	if docType != "" {
		query = query.Where("type = ?", docType)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to count documents: %w", err)
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&docs).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to list documents: %w", err)
	}

	return docs, total, nil
}

func (s *Service) DeleteDocument(id string) error {
	result := s.db.Delete(&entity.Document{}, "id = ?", id)
	if result.Error != nil {
		return fmt.Errorf("failed to delete document: %w", result.Error)
	}
	if result.RowsAffected == 0 {
		return errors.New("document not found")
	}
	return nil
}

type CreatePipelineRequest struct {
	Name        string                 `json:"name" binding:"required"`
	Description string                 `json:"description"`
	Steps       []entity.PipelineStep  `json:"steps" binding:"required"`
	Config      map[string]interface{} `json:"config"`
}

func (s *Service) CreatePipeline(req *CreatePipelineRequest) (*entity.ParsePipeline, error) {
	now := utils.Now()
	pipeline := &entity.ParsePipeline{
		ID:          utils.GenerateID("pipe"),
		Name:        req.Name,
		Description: req.Description,
		Steps:       req.Steps,
		Config:      req.Config,
		Enabled:     true,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.db.Create(pipeline).Error; err != nil {
		return nil, fmt.Errorf("failed to create pipeline: %w", err)
	}

	logger.Info("pipeline created", "pipeline_id", pipeline.ID, "name", pipeline.Name)
	return pipeline, nil
}

func (s *Service) GetPipeline(id string) (*entity.ParsePipeline, error) {
	var pipeline entity.ParsePipeline
	if err := s.db.Where("id = ?", id).First(&pipeline).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("pipeline not found")
		}
		return nil, fmt.Errorf("failed to get pipeline: %w", err)
	}
	return &pipeline, nil
}

func (s *Service) ListPipelines(page, pageSize int) ([]entity.ParsePipeline, int64, error) {
	var pipelines []entity.ParsePipeline
	var total int64

	if err := s.db.Model(&entity.ParsePipeline{}).Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to count pipelines: %w", err)
	}

	offset := (page - 1) * pageSize
	if err := s.db.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&pipelines).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to list pipelines: %w", err)
	}

	return pipelines, total, nil
}

type ExecutePipelineRequest struct {
	PipelineID string `json:"pipeline_id" binding:"required"`
	DocumentID string `json:"document_id" binding:"required"`
}

func (s *Service) ExecutePipeline(req *ExecutePipelineRequest) (*entity.PipelineExecution, error) {
	pipeline, err := s.GetPipeline(req.PipelineID)
	if err != nil {
		return nil, err
	}

	doc, err := s.GetDocument(req.DocumentID)
	if err != nil {
		return nil, err
	}

	now := utils.Now()
	execution := &entity.PipelineExecution{
		ID:          utils.GenerateID("exec"),
		PipelineID:  req.PipelineID,
		DocumentID:  req.DocumentID,
		Status:      "running",
		CurrentStep: 0,
		TotalSteps:  len(pipeline.Steps),
		StartedAt:   now,
		CreatedAt:   now,
	}

	if err := s.db.Create(execution).Error; err != nil {
		return nil, fmt.Errorf("failed to create execution: %w", err)
	}

	logger.Info("pipeline execution started", "execution_id", execution.ID, "pipeline_id", req.PipelineID, "document_id", req.DocumentID)

	go s.runPipeline(execution, pipeline, doc)

	return execution, nil
}

func (s *Service) runPipeline(exec *entity.PipelineExecution, pipeline *entity.ParsePipeline, doc *entity.Document) {
	var content string
	var chunks []entity.DocumentChunk
	var vectors []entity.ChunkVector

	for i, step := range pipeline.Steps {
		exec.CurrentStep = i + 1
		s.db.Save(exec)

		logger.Info("executing pipeline step", "execution_id", exec.ID, "step", step.Name, "type", step.Type)

		switch entity.PipelineStepType(step.Type) {
		case entity.StepTypeExtract:
			content = s.extractContent(doc, step.Config)
		case entity.StepTypeClean:
			content = s.cleanContent(content, step.Config)
		case entity.StepTypeSplit:
			chunks = s.splitContent(doc.ID, content, step.Config)
		case entity.StepTypeEmbed:
			vectors = s.generateVectors(chunks, step.Config)
		case entity.StepTypeStore:
			s.storeChunks(chunks)
			s.storeVectors(vectors)
		case entity.StepTypeIndex:
			s.indexVectors(vectors, step.Config)
		}

		time.Sleep(100 * time.Millisecond)
	}

	now := utils.Now()
	exec.Status = string(entity.PipelineStatusCompleted)
	exec.ChunkCount = len(chunks)
	exec.CompletedAt = &now

	s.db.Save(exec)
	logger.Info("pipeline execution completed", "execution_id", exec.ID, "chunks", len(chunks))
}

func (s *Service) extractContent(doc *entity.Document, config map[string]interface{}) string {
	return fmt.Sprintf("Extracted content from document: %s", doc.Name)
}

func (s *Service) cleanContent(content string, config map[string]interface{}) string {
	content = strings.ReplaceAll(content, "\n\n", "\n")
	content = strings.TrimSpace(content)
	return content
}

func (s *Service) splitContent(docID string, content string, config map[string]interface{}) []entity.DocumentChunk {
	chunkSize := utils.SafeGetMapInt(config, "chunk_size")
	if chunkSize == 0 {
		chunkSize = 512
	}
	chunkOverlap := utils.SafeGetMapInt(config, "chunk_overlap")

	runes := []rune(content)
	var chunks []entity.DocumentChunk
	index := 0

	for i := 0; i < len(runes); i += chunkSize - chunkOverlap {
		end := i + chunkSize
		if end > len(runes) {
			end = len(runes)
		}

		chunk := entity.DocumentChunk{
			ID:         utils.GenerateID("chk"),
			DocumentID: docID,
			Index:      index,
			Content:    string(runes[i:end]),
			StartPos:   i,
			EndPos:     end,
			TokenCount: (end - i) / 4,
			CreatedAt:  utils.Now(),
		}
		chunks = append(chunks, chunk)
		index++

		if end == len(runes) {
			break
		}
	}

	return chunks
}

func (s *Service) generateVectors(chunks []entity.DocumentChunk, config map[string]interface{}) []entity.ChunkVector {
	modelID := utils.SafeGetMapString(config, "model_id")
	dimensions := utils.SafeGetMapInt(config, "dimensions")
	if dimensions == 0 {
		dimensions = 1536
	}

	var vectors []entity.ChunkVector
	for _, chunk := range chunks {
		vector := make([]float64, dimensions)
		for i := range vector {
			vector[i] = float64((len(chunk.Content) + i) % 100) / 100.0
		}

		vectors = append(vectors, entity.ChunkVector{
			ID:         utils.GenerateID("vec"),
			ChunkID:    chunk.ID,
			Vector:     vector,
			ModelID:    modelID,
			Dimensions: dimensions,
			CreatedAt:  utils.Now(),
		})
	}

	return vectors
}

func (s *Service) storeChunks(chunks []entity.DocumentChunk) {
	for _, chunk := range chunks {
		s.db.Create(&chunk)
	}
}

func (s *Service) storeVectors(vectors []entity.ChunkVector) {
	for _, vec := range vectors {
		s.db.Create(&vec)
	}
}

func (s *Service) indexVectors(vectors []entity.ChunkVector, config map[string]interface{}) {
	logger.Info("indexing vectors", "count", len(vectors))
}

func (s *Service) GetExecution(id string) (*entity.PipelineExecution, error) {
	var exec entity.PipelineExecution
	if err := s.db.Where("id = ?", id).First(&exec).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("execution not found")
		}
		return nil, fmt.Errorf("failed to get execution: %w", err)
	}
	return &exec, nil
}

func (s *Service) GetDocumentChunks(docID string, page, pageSize int) ([]entity.DocumentChunk, int64, error) {
	var chunks []entity.DocumentChunk
	var total int64

	query := s.db.Model(&entity.DocumentChunk{}).Where("document_id = ?", docID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to count chunks: %w", err)
	}

	offset := (page - 1) * pageSize
	if err := query.Order("index ASC").Offset(offset).Limit(pageSize).Find(&chunks).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to list chunks: %w", err)
	}

	return chunks, total, nil
}

func (s *Service) SearchSimilarVectors(queryVector []float64, topK int) ([]entity.ChunkVector, []float64, error) {
	var vectors []entity.ChunkVector
	if err := s.db.Limit(topK).Find(&vectors).Error; err != nil {
		return nil, nil, fmt.Errorf("failed to search vectors: %w", err)
	}

	scores := make([]float64, len(vectors))
	for i, vec := range vectors {
		scores[i] = s.cosineSimilarity(queryVector, vec.Vector)
	}

	return vectors, scores, nil
}

func (s *Service) cosineSimilarity(a, b []float64) float64 {
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

	return dotProduct / (float64(math.Sqrt(float64(normA))) * float64(math.Sqrt(float64(normB))))
}
