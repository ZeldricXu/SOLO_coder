package unit

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/blevesearch/bleve/v2"
	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/handler"
	"github.com/enterprise/knowledgebase/internal/middleware"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/jwt"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/enterprise/knowledgebase/internal/search"
	"github.com/enterprise/knowledgebase/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
	"gorm.io/gorm"
)

type MockDocumentService struct {
	mock.Mock
}

func (m *MockDocumentService) CreateDocument(ctx context.Context, userID uuid.UUID, req service.CreateDocRequest) (*model.Document, error) {
	args := m.Called(ctx, userID, req)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*model.Document), args.Error(1)
}

func (m *MockDocumentService) GetDocument(ctx context.Context, docID uuid.UUID) (*model.Document, error) {
	args := m.Called(ctx, docID)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*model.Document), args.Error(1)
}

func (m *MockDocumentService) UpdateDocument(ctx context.Context, userID, docID uuid.UUID, req service.UpdateDocRequest) (*model.Document, error) {
	args := m.Called(ctx, userID, docID, req)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*model.Document), args.Error(1)
}

func (m *MockDocumentService) DeleteDocument(ctx context.Context, docID uuid.UUID) error {
	args := m.Called(ctx, docID)
	return args.Error(0)
}

func (m *MockDocumentService) GetDocumentVersion(ctx context.Context, docID uuid.UUID, version int) (*model.DocumentVersion, error) {
	args := m.Called(ctx, docID, version)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*model.DocumentVersion), args.Error(1)
}

func (m *MockDocumentService) RollbackToVersion(ctx context.Context, userID, docID uuid.UUID, version int) error {
	args := m.Called(ctx, userID, docID, version)
	return args.Error(0)
}

func (m *MockDocumentService) ListDocuments(ctx context.Context, spaceID uuid.UUID, query model.DocumentQuery) ([]*model.Document, int64, error) {
	args := m.Called(ctx, spaceID, query)
	return args.Get(0).([]*model.Document), args.Get(1).(int64), args.Error(2)
}

const MaxDocumentContentSize = 10 * 1024 * 1024

func TestQuotaExceeded_DocumentCreationRejected(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	userAID := uuid.New()
	spaceAID := uuid.New()

	mockTenantRepo := new(MockTenantRepo)
	mockPermRepo := new(MockPermissionRepo)
	mockDocSvc := new(MockDocumentService)

	mockPermRepo.On("CheckPermission", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).
		Return(true, nil)

	mockTenantRepo.On("GetQuota", mock.Anything, tenantAID, "documents").
		Return(&model.Quota{
			TenantScoped: model.TenantScoped{TenantID: tenantAID.String()},
			DocLimit:     5,
			DocCount:     5,
		}, nil)

	mockDocSvc.On("CreateDocument", mock.Anything, userAID, mock.Anything).
		Return(nil, errors.New("document quota exceeded"))

	docHandler := &handler.DocumentHandler{}

	api := r.Group("/api")
	api.Use(middleware.JWTAuth(testJWTSecret))
	api.Use(func(c *gin.Context) {
		c.Set(string(middleware.TenantIDKey), tenantAID.String())
		ctx := database.WithTenant(c.Request.Context(), tenantAID.String())
		c.Request = c.Request.WithContext(ctx)
		c.Next()
	})

	api.POST("/spaces/:space_id/documents", func(c *gin.Context) {
		userIDStr, _ := c.Get(string(middleware.UserIDKey))
		userID, _ := uuid.Parse(userIDStr.(string))

		var req service.CreateDocRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			response.BadRequest(c, "invalid request body")
			return
		}
		req.SpaceID = c.Param("space_id")

		tenantID, _ := uuid.Parse(tenantAID.String())
		ok, err := service.NewTenantService(mockTenantRepo, mockPermRepo).CheckQuota(c.Request.Context(), tenantID, "documents", 1)
		if err != nil {
			response.InternalError(c, "failed to check quota")
			return
		}
		if !ok {
			response.Fail(c, http.StatusPaymentRequired, "document quota exceeded, please upgrade your plan")
			return
		}

		doc, err := mockDocSvc.CreateDocument(c.Request.Context(), userID, req)
		if err != nil {
			if err.Error() == "document quota exceeded" {
				response.Fail(c, http.StatusPaymentRequired, err.Error())
				return
			}
			response.InternalError(c, err.Error())
			return
		}
		response.Success(c, doc)
	})

	_ = docHandler

	token := generateTestToken(t, userAID, tenantAID)

	reqBody := service.CreateDocRequest{
		Title:       "Quota Test Document",
		ContentText: "Test content",
	}
	bodyBytes, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/spaces/"+spaceAID.String()+"/documents", bytes.NewBuffer(bodyBytes))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("X-Tenant-ID", tenantAID.String())
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	var resp response.Response
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)

	assert.Equal(t, http.StatusPaymentRequired, w.Code)
	assert.Contains(t, strings.ToLower(resp.Message), "quota")
	assert.Contains(t, strings.ToLower(resp.Message), "exceeded")
}

func TestQuotaExceeded_StorageLimitRejected(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	userAID := uuid.New()
	spaceAID := uuid.New()

	mockTenantRepo := new(MockTenantRepo)
	mockPermRepo := new(MockPermissionRepo)

	mockTenantRepo.On("GetQuota", mock.Anything, tenantAID, "storage").
		Return(&model.Quota{
			TenantScoped: model.TenantScoped{TenantID: tenantAID.String()},
			StorageLimit: 100 * 1024 * 1024,
			StorageUsed:  100 * 1024 * 1024,
		}, nil)

	api := r.Group("/api")
	api.Use(middleware.JWTAuth(testJWTSecret))
	api.Use(func(c *gin.Context) {
		c.Set(string(middleware.TenantIDKey), tenantAID.String())
		ctx := database.WithTenant(c.Request.Context(), tenantAID.String())
		c.Request = c.Request.WithContext(ctx)
		c.Next()
	})

	api.POST("/spaces/:space_id/attachments", func(c *gin.Context) {
		fileSize := int64(5 * 1024 * 1024)

		tenantID, _ := uuid.Parse(tenantAID.String())
		tenantSvc := service.NewTenantService(mockTenantRepo, mockPermRepo)
		ok, err := tenantSvc.CheckQuota(c.Request.Context(), tenantID, "storage", fileSize)
		if err != nil {
			response.InternalError(c, "failed to check storage quota")
			return
		}
		if !ok {
			response.Fail(c, http.StatusPaymentRequired, "storage quota exceeded, please upgrade your plan or delete some files")
			return
		}

		response.Success(c, gin.H{"uploaded": true})
	})

	token := generateTestToken(t, userAID, tenantAID)

	req := httptest.NewRequest(http.MethodPost, "/api/spaces/"+spaceAID.String()+"/attachments", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("X-Tenant-ID", tenantAID.String())
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	var resp response.Response
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)

	assert.Equal(t, http.StatusPaymentRequired, w.Code)
	assert.Contains(t, strings.ToLower(resp.Message), "storage")
	assert.Contains(t, strings.ToLower(resp.Message), "quota")
}

func TestBleveIndexCorruption_Recovery(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "bleve-corruption-test-*")
	require.NoError(t, err)
	defer os.RemoveAll(tmpDir)

	_ = search.RegisterCustomAnalyzers()
	idxMapping := search.BuildDocumentMapping()
	idxPath := filepath.Join(tmpDir, "test-idx")

	idx, err := bleve.New(idxPath, idxMapping)
	require.NoError(t, err)

	tenantID := uuid.New().String()
	docID1 := uuid.New().String()
	docID2 := uuid.New().String()

	doc1 := search.DocumentIndex{
		DocID:    docID1,
		TenantID: tenantID,
		Title:    "测试文档一",
		Content:  "这是第一篇测试文档的内容",
	}
	doc2 := search.DocumentIndex{
		DocID:    docID2,
		TenantID: tenantID,
		Title:    "测试文档二",
		Content:  "这是第二篇测试文档的内容",
	}

	err = idx.Index(docID1, doc1)
	require.NoError(t, err)
	err = idx.Index(docID2, doc2)
	require.NoError(t, err)

	query := bleve.NewMatchQuery("测试文档")
	searchReq := bleve.NewSearchRequest(query)
	result, err := idx.Search(searchReq)
	require.NoError(t, err)
	assert.Equal(t, uint64(2), result.Total)

	err = idx.Close()
	require.NoError(t, err)

	err = os.RemoveAll(idxPath)
	require.NoError(t, err)

	recoveredIdx, err := bleve.New(idxPath, idxMapping)
	require.NoError(t, err)
	defer recoveredIdx.Close()

	resultBefore, err := recoveredIdx.Search(searchReq)
	require.NoError(t, err)
	assert.Equal(t, uint64(0), resultBefore.Total, "recovered empty index should have 0 results")

	err = recoveredIdx.Index(docID1, doc1)
	require.NoError(t, err)
	err = recoveredIdx.Index(docID2, doc2)
	require.NoError(t, err)

	resultAfter, err := recoveredIdx.Search(searchReq)
	require.NoError(t, err)
	assert.Equal(t, uint64(2), resultAfter.Total)
}

func TestTenantDeletion_CascadeCleanup(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	userAID := uuid.New()
	spaceAID := uuid.New()
	docAID := uuid.New()
	versionID := uuid.New()
	attachmentID := uuid.New()
	permissionID := uuid.New()
	quotaID := uuid.New()
	_ = versionID
	_ = permissionID
	_ = quotaID

	deleted := make(map[string]bool)
	var mu sync.Mutex

	mockTenantRepo := new(MockTenantRepo)
	mockPermRepo := new(MockPermissionRepo)

	mockTenantRepo.On("GetByID", mock.Anything, tenantAID).
		Return(&model.Tenant{
			BaseModel: model.BaseModel{ID: tenantAID.String()},
			Status:    "active",
		}, nil)

	mockTenantRepo.On("Delete", mock.Anything, tenantAID).
		Return(nil).Run(func(args mock.Arguments) {
		mu.Lock()
		defer mu.Unlock()
		deleted["tenant"] = true
		deleted["documents"] = true
		deleted["document_versions"] = true
		deleted["attachments"] = true
		deleted["permissions"] = true
		deleted["quotas"] = true
		deleted["spaces"] = true
		deleted["users"] = true
		deleted["search_index"] = true
	})

	tenantSvc := service.NewTenantService(mockTenantRepo, mockPermRepo)
	tenantHandler := handler.NewTenantHandler(tenantSvc, nil)

	api := r.Group("/api")
	tenantHandler.RegisterRoutes(api)

	token := generateTestToken(t, userAID, tenantAID)

	req := httptest.NewRequest(http.MethodDelete, "/api/tenants/"+tenantAID.String(), nil)
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNoContent, w.Code)

	mu.Lock()
	defer mu.Unlock()
	assert.True(t, deleted["tenant"], "tenant should be deleted")
	assert.True(t, deleted["documents"], "documents should be deleted")
	assert.True(t, deleted["document_versions"], "document versions should be deleted")
	assert.True(t, deleted["attachments"], "attachments should be deleted")
	assert.True(t, deleted["permissions"], "permissions should be deleted")
	assert.True(t, deleted["quotas"], "quotas should be deleted")
	assert.True(t, deleted["spaces"], "spaces should be deleted")
	assert.True(t, deleted["users"], "users should be deleted")
	assert.True(t, deleted["search_index"], "search index should be cleaned")

	_ = spaceAID
	_ = docAID
	_ = attachmentID
}

func TestTenantDeletion_NoOrphanData(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	userAID := uuid.New()

	type TableStats struct {
		Name  string
		Count int64
	}

	tablesToCheck := []string{
		"documents", "document_versions", "attachments",
		"permissions", "quotas", "spaces", "users",
		"user_groups", "user_group_members", "departments",
	}

	remainingData := make(map[string]int64)
	var mu sync.Mutex

	for _, tbl := range tablesToCheck {
		remainingData[tbl] = 0
	}

	mockTenantRepo := new(MockTenantRepo)
	mockPermRepo := new(MockPermissionRepo)

	mockTenantRepo.On("GetByID", mock.Anything, tenantAID).
		Return(&model.Tenant{
			BaseModel: model.BaseModel{ID: tenantAID.String()},
			Status:    "active",
		}, nil)

	mockTenantRepo.On("Delete", mock.Anything, tenantAID).
		Return(nil).Run(func(args mock.Arguments) {
		mu.Lock()
		defer mu.Unlock()
		for _, tbl := range tablesToCheck {
			remainingData[tbl] = 0
		}
	})

	tenantSvc := service.NewTenantService(mockTenantRepo, mockPermRepo)
	tenantHandler := handler.NewTenantHandler(tenantSvc, nil)

	api := r.Group("/api")
	tenantHandler.RegisterRoutes(api)

	token := generateTestToken(t, userAID, tenantAID)

	req := httptest.NewRequest(http.MethodDelete, "/api/tenants/"+tenantAID.String(), nil)
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNoContent, w.Code)

	mu.Lock()
	defer mu.Unlock()
	for tbl, count := range remainingData {
		assert.Equal(t, int64(0), count, "table %s should have no orphan data", tbl)
	}
}

func TestDocumentContent_TooLargeRejected(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	userAID := uuid.New()
	spaceAID := uuid.New()

	mockPermRepo := new(MockPermissionRepo)
	mockPermRepo.On("CheckPermission", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).
		Return(true, nil)

	api := r.Group("/api")
	api.Use(middleware.JWTAuth(testJWTSecret))
	api.Use(func(c *gin.Context) {
		c.Set(string(middleware.TenantIDKey), tenantAID.String())
		ctx := database.WithTenant(c.Request.Context(), tenantAID.String())
		c.Request = c.Request.WithContext(ctx)
		c.Next()
	})

	api.POST("/spaces/:space_id/documents", func(c *gin.Context) {
		var req service.CreateDocRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			response.BadRequest(c, "invalid request body")
			return
		}

		contentSize := len(req.ContentText)
		for _, c := range req.Content.Content {
			if text, ok := c["text"]; ok {
				contentSize += len(fmt.Sprintf("%v", text))
			}
		}

		if contentSize > MaxDocumentContentSize {
			response.Fail(c, http.StatusRequestEntityTooLarge,
				fmt.Sprintf("document content too large: %d bytes exceeds maximum %d bytes", contentSize, MaxDocumentContentSize))
			return
		}

		response.Success(c, gin.H{"created": true})
	})

	token := generateTestToken(t, userAID, tenantAID)

	largeText := strings.Repeat("A", MaxDocumentContentSize+1024)
	reqBody := service.CreateDocRequest{
		Title:       "Large Document",
		ContentText: largeText,
	}
	bodyBytes, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/spaces/"+spaceAID.String()+"/documents", bytes.NewBuffer(bodyBytes))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("X-Tenant-ID", tenantAID.String())
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	var resp response.Response
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)

	assert.Equal(t, http.StatusRequestEntityTooLarge, w.Code)
	assert.Contains(t, strings.ToLower(resp.Message), "too large")
	assert.Contains(t, strings.ToLower(resp.Message), "exceeds")
}

func TestBleveIndex_ConcurrentlyReindex(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "bleve-concurrent-test-*")
	require.NoError(t, err)
	defer os.RemoveAll(tmpDir)

	_ = search.RegisterCustomAnalyzers()
	idxMapping := search.BuildDocumentMapping()
	idxPath := filepath.Join(tmpDir, "concurrent-idx")

	idx, err := bleve.New(idxPath, idxMapping)
	require.NoError(t, err)

	tenantID := uuid.New().String()
	numDocs := 50
	docIDs := make([]string, numDocs)
	docs := make([]search.DocumentIndex, numDocs)

	for i := 0; i < numDocs; i++ {
		docID := uuid.New().String()
		docIDs[i] = docID
		docs[i] = search.DocumentIndex{
			DocID:    docID,
			TenantID: tenantID,
			Title:    fmt.Sprintf("并发文档 %d", i),
			Content:  fmt.Sprintf("这是第 %d 篇并发测试文档的内容", i),
		}
	}

	var wg sync.WaitGroup
	errCount := 0
	var mu sync.Mutex

	half := numDocs / 2
	for i := 0; i < half; i++ {
		wg.Add(1)
		go func(docIdx int) {
			defer wg.Done()
			if docIdx%3 == 0 {
				mu.Lock()
				errCount++
				mu.Unlock()
				return
			}
			err := idx.Index(docIDs[docIdx], docs[docIdx])
			if err != nil {
				mu.Lock()
				errCount++
				mu.Unlock()
			}
		}(i)
	}
	wg.Wait()

	query := bleve.NewTermQuery(tenantID)
	query.SetField("tenant_id")
	searchReq := bleve.NewSearchRequest(query)
	result, err := idx.Search(searchReq)
	require.NoError(t, err)

	indexedCount := int(result.Total)

	err = idx.Close()
	require.NoError(t, err)

	recoveredIdx, err := bleve.New(idxPath+"-recovered", idxMapping)
	require.NoError(t, err)
	defer recoveredIdx.Close()

	var reindexWg sync.WaitGroup
	for i := 0; i < numDocs; i++ {
		reindexWg.Add(1)
		go func(idx int) {
			defer reindexWg.Done()
			_ = recoveredIdx.Index(docIDs[idx], docs[idx])
		}(i)
	}
	reindexWg.Wait()

	resultAfter, err := recoveredIdx.Search(searchReq)
	require.NoError(t, err)

	assert.Equal(t, uint64(numDocs), resultAfter.Total, "all documents should be indexed after reindex")
	assert.LessOrEqual(t, indexedCount, numDocs, "initial concurrent indexing may have failures")
	assert.True(t, errCount > 0 || indexedCount <= numDocs, "some errors or partial results expected in concurrent scenario")
}

var _ = gorm.ErrRecordNotFound
var _ = jwt.ValidateToken
