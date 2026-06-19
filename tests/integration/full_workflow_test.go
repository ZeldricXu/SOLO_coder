//go:build integration

package integration

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/enterprise/knowledgebase/internal/handler"
	"github.com/enterprise/knowledgebase/internal/middleware"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/jwt"
	"github.com/enterprise/knowledgebase/internal/repository"
	"github.com/enterprise/knowledgebase/internal/search"
	"github.com/enterprise/knowledgebase/internal/service"
	"github.com/enterprise/knowledgebase/internal/testutil"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/gorm"
)

var (
	testInfra  *testutil.TestInfrastructure
	testServer *httptest.Server
	jwtSecret  = "test-jwt-secret-integration"
)

func setupRouter(db *gorm.DB, bleveIndex interface{}) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantRepo := repository.NewTenantRepo(db)
	permissionRepo := repository.NewPermissionRepo(db)
	tenantSvc := service.NewTenantService(tenantRepo, permissionRepo)
	docSvc := service.NewDocumentService(db, tenantRepo, tenantSvc, permissionRepo)

	tenantHandler := handler.NewTenantHandler(tenantSvc, tenantRepo)
	docHandler := handler.NewDocumentHandler(db, docSvc, permissionRepo)

	api := r.Group("/api")
	tenantHandler.RegisterRoutes(api)
	api.Use(middleware.TenantFromHeader())
	authMiddleware := middleware.JWTAuth(jwtSecret)
	permMiddleware := middleware.RequirePermission
	docHandler.RegisterRoutes(api, authMiddleware, permMiddleware)

	return r
}

func TestMain(m *testing.M) {
	var err error
	testInfra, err = testutil.NewTestInfrastructure()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to create test infrastructure: %v\n", err)
		os.Exit(1)
	}
	defer testInfra.Cleanup()

	_ = search.RegisterCustomAnalyzers()
	router := setupRouter(testInfra.DB, testInfra.BleveIndex)
	testServer = httptest.NewServer(router)
	defer testServer.Close()

	code := m.Run()
	os.Exit(code)
}

func generateToken(userID, tenantID uuid.UUID) string {
	token, _ := jwt.GenerateToken(userID, tenantID, jwtSecret, 24, "test")
	return token
}

type apiResponse struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

func doRequest(t *testing.T, method, path string, body interface{}, tenantID string, token string) *apiResponse {
	var reqBody io.Reader
	if body != nil {
		jsonBytes, err := json.Marshal(body)
		require.NoError(t, err)
		reqBody = bytes.NewReader(jsonBytes)
	}

	req, err := http.NewRequest(method, testServer.URL+path, reqBody)
	require.NoError(t, err)

	req.Header.Set("Content-Type", "application/json")
	if tenantID != "" {
		req.Header.Set("X-Tenant-ID", tenantID)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	require.NoError(t, err)

	var apiResp apiResponse
	if len(respBody) > 0 {
		err = json.Unmarshal(respBody, &apiResp)
		require.NoError(t, err)
	}
	return &apiResp
}

func TestIntegration_FullWorkflow(t *testing.T) {
	require.NoError(t, testInfra.ClearDB())
	require.NoError(t, testInfra.ClearRedis())

	factory := testutil.NewFactory()

	resp := doRequest(t, "POST", "/api/tenants", map[string]interface{}{
		"name":        factory.NextTenantName(),
		"domain":      fmt.Sprintf("%s.example.com", testutil.RandomString(8)),
		"namespace":   fmt.Sprintf("ns_%s", testutil.RandomString(8)),
		"description": "Integration test tenant",
	}, "", "")
	require.Equal(t, 0, resp.Code)

	var tenant model.Tenant
	require.NoError(t, json.Unmarshal(resp.Data, &tenant))
	tenantID, err := uuid.Parse(tenant.ID)
	require.NoError(t, err)

	adminUser := factory.BuildUser(tenantID.String())
	adminCtx := testInfra.TenantContext(tenantID.String())
	require.NoError(t, testInfra.DB.WithContext(adminCtx).Create(adminUser).Error)
	adminUserID, _ := uuid.Parse(adminUser.ID)
	adminToken := generateToken(adminUserID, tenantID)

	space := factory.BuildSpace(tenantID.String(), adminUserID.String())
	require.NoError(t, testInfra.DB.WithContext(adminCtx).Create(space).Error)
	spaceID, _ := uuid.Parse(space.ID)

	adminPerm := factory.BuildPermission(tenantID.String(), model.ResourceTypeSpace, spaceID.String(), model.RoleAdmin, model.SubjectTypeUser, adminUserID.String())
	require.NoError(t, testInfra.DB.WithContext(adminCtx).Create(adminPerm).Error)

	quota := factory.BuildQuota(tenantID.String(), func(q *model.Quota) {
		q.DocLimit = 100
	})
	require.NoError(t, testInfra.DB.WithContext(adminCtx).Create(quota).Error)

	for i := 0; i < 3; i++ {
		resp = doRequest(t, "POST", fmt.Sprintf("/api/spaces/%s/documents", spaceID.String()), map[string]interface{}{
			"title":        fmt.Sprintf("文档_%d_%s", i, testutil.RandomString(4)),
			"content_text": fmt.Sprintf("This is test document number %d with searchable content about Go programming.", i),
			"content": map[string]interface{}{
				"type": "doc",
				"content": []interface{}{
					map[string]interface{}{
						"type": "paragraph",
						"content": []interface{}{
							map[string]interface{}{"type": "text", "text": fmt.Sprintf("Content %d", i)},
						},
					},
				},
			},
			"tags": []string{"test", fmt.Sprintf("tag%d", i)},
		}, tenantID.String(), adminToken)
		require.Equal(t, 0, resp.Code, "Failed to create doc %d: %v", i, resp.Message)

		var doc model.Document
		require.NoError(t, json.Unmarshal(resp.Data, &doc))
		docID, _ := uuid.Parse(doc.ID)

		resp = doRequest(t, "PUT", fmt.Sprintf("/api/documents/%s", docID.String()), map[string]interface{}{
			"title":        fmt.Sprintf("Updated 文档_%d", i),
			"content_text": fmt.Sprintf("Updated content for document %d - Go programming language is great.", i),
			"change_log":   "update test",
		}, tenantID.String(), adminToken)
		require.Equal(t, 0, resp.Code)
	}

	resp = doRequest(t, "GET", fmt.Sprintf("/api/spaces/%s/documents", spaceID.String()), nil, tenantID.String(), adminToken)
	require.Equal(t, 0, resp.Code)

	var pageResp struct {
		Total int64 `json:"total"`
	}
	require.NoError(t, json.Unmarshal(resp.Data, &pageResp))
	assert.GreaterOrEqual(t, pageResp.Total, int64(3))
}

func TestIntegration_PermissionChain(t *testing.T) {
	require.NoError(t, testInfra.ClearDB())
	require.NoError(t, testInfra.ClearRedis())

	factory := testutil.NewFactory()

	tenant := factory.BuildTenant()
	require.NoError(t, testInfra.DB.WithContext(testInfra.Ctx).Create(tenant).Error)
	tenantID, _ := uuid.Parse(tenant.ID)
	ctx := testInfra.TenantContext(tenantID.String())

	adminUser := factory.BuildUser(tenantID.String())
	editorUser := factory.BuildUser(tenantID.String())
	viewerUser := factory.BuildUser(tenantID.String())
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(adminUser).Error)
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(editorUser).Error)
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(viewerUser).Error)

	adminID, _ := uuid.Parse(adminUser.ID)
	editorID, _ := uuid.Parse(editorUser.ID)
	viewerID, _ := uuid.Parse(viewerUser.ID)

	space := factory.BuildSpace(tenantID.String(), adminID.String())
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(space).Error)
	spaceID, _ := uuid.Parse(space.ID)

	doc := factory.BuildDocument(tenantID.String(), spaceID.String(), adminID.String())
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(doc).Error)
	docID, _ := uuid.Parse(doc.ID)

	adminPerm := factory.BuildPermission(tenantID.String(), model.ResourceTypeSpace, spaceID.String(), model.RoleAdmin, model.SubjectTypeUser, adminID.String())
	editorPerm := factory.BuildPermission(tenantID.String(), model.ResourceTypeDocument, docID.String(), model.RoleEditor, model.SubjectTypeUser, editorID.String())
	viewerPerm := factory.BuildPermission(tenantID.String(), model.ResourceTypeDocument, docID.String(), model.RoleViewer, model.SubjectTypeUser, viewerID.String())
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(adminPerm).Error)
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(editorPerm).Error)
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(viewerPerm).Error)

	adminToken := generateToken(adminID, tenantID)
	editorToken := generateToken(editorID, tenantID)
	viewerToken := generateToken(viewerID, tenantID)

	viewerResp := doRequest(t, "PUT", fmt.Sprintf("/api/documents/%s", docID.String()), map[string]interface{}{
		"title":      "Viewer should not edit this",
		"change_log": "test",
	}, tenantID.String(), viewerToken)
	assert.Equal(t, 403, viewerResp.Code)

	editorResp := doRequest(t, "PUT", fmt.Sprintf("/api/documents/%s", docID.String()), map[string]interface{}{
		"title":      "Editor can edit this",
		"change_log": "editor edit",
	}, tenantID.String(), editorToken)
	assert.Equal(t, 0, editorResp.Code)

	adminResp := doRequest(t, "PUT", fmt.Sprintf("/api/documents/%s", docID.String()), map[string]interface{}{
		"title":      "Admin can do everything",
		"change_log": "admin edit",
	}, tenantID.String(), adminToken)
	assert.Equal(t, 0, adminResp.Code)

	getResp := doRequest(t, "GET", fmt.Sprintf("/api/documents/%s", docID.String()), nil, tenantID.String(), viewerToken)
	assert.Equal(t, 0, getResp.Code)
}

func TestIntegration_CrossTenantSearch(t *testing.T) {
	require.NoError(t, testInfra.ClearDB())
	require.NoError(t, testInfra.ClearRedis())

	factory := testutil.NewFactory()

	tenant1 := factory.BuildTenant()
	tenant2 := factory.BuildTenant()
	require.NoError(t, testInfra.DB.WithContext(testInfra.Ctx).Create(tenant1).Error)
	require.NoError(t, testInfra.DB.WithContext(testInfra.Ctx).Create(tenant2).Error)
	tenant1ID, _ := uuid.Parse(tenant1.ID)
	tenant2ID, _ := uuid.Parse(tenant2.ID)

	user1 := factory.BuildUser(tenant1ID.String())
	user2 := factory.BuildUser(tenant2ID.String())
	require.NoError(t, testInfra.DB.WithContext(testInfra.TenantContext(tenant1ID.String())).Create(user1).Error)
	require.NoError(t, testInfra.DB.WithContext(testInfra.TenantContext(tenant2ID.String())).Create(user2).Error)
	user1ID, _ := uuid.Parse(user1.ID)
	user2ID, _ := uuid.Parse(user2.ID)

	space1 := factory.BuildSpace(tenant1ID.String(), user1ID.String())
	space2 := factory.BuildSpace(tenant2ID.String(), user2ID.String())
	require.NoError(t, testInfra.DB.WithContext(testInfra.TenantContext(tenant1ID.String())).Create(space1).Error)
	require.NoError(t, testInfra.DB.WithContext(testInfra.TenantContext(tenant2ID.String())).Create(space2).Error)
	space1ID, _ := uuid.Parse(space1.ID)
	space2ID, _ := uuid.Parse(space2.ID)

	doc1 := factory.BuildDocument(tenant1ID.String(), space1ID.String(), user1ID.String(), func(d *model.Document) {
		d.Title = "Tenant1 Exclusive Document UniqueKeyword123"
		d.ContentText = "This document belongs to tenant 1 only UniqueKeyword123"
	})
	doc2 := factory.BuildDocument(tenant2ID.String(), space2ID.String(), user2ID.String(), func(d *model.Document) {
		d.Title = "Tenant2 Exclusive Document"
		d.ContentText = "This document belongs to tenant 2 only"
	})
	require.NoError(t, testInfra.DB.WithContext(testInfra.TenantContext(tenant1ID.String())).Create(doc1).Error)
	require.NoError(t, testInfra.DB.WithContext(testInfra.TenantContext(tenant2ID.String())).Create(doc2).Error)

	perm1 := factory.BuildPermission(tenant1ID.String(), model.ResourceTypeSpace, space1ID.String(), model.RoleAdmin, model.SubjectTypeUser, user1ID.String())
	perm2 := factory.BuildPermission(tenant2ID.String(), model.ResourceTypeSpace, space2ID.String(), model.RoleAdmin, model.SubjectTypeUser, user2ID.String())
	require.NoError(t, testInfra.DB.WithContext(testInfra.TenantContext(tenant1ID.String())).Create(perm1).Error)
	require.NoError(t, testInfra.DB.WithContext(testInfra.TenantContext(tenant2ID.String())).Create(perm2).Error)

	token1 := generateToken(user1ID, tenant1ID)
	resp1 := doRequest(t, "GET", fmt.Sprintf("/api/spaces/%s/documents?keyword=UniqueKeyword123", space1ID.String()), nil, tenant1ID.String(), token1)
	require.Equal(t, 0, resp1.Code)

	var page1 struct {
		Total int64           `json:"total"`
		Data  json.RawMessage `json:"data"`
	}
	require.NoError(t, json.Unmarshal(resp1.Data, &page1))
	assert.Equal(t, int64(1), page1.Total)

	token2 := generateToken(user2ID, tenant2ID)
	resp2 := doRequest(t, "GET", fmt.Sprintf("/api/spaces/%s/documents?keyword=UniqueKeyword123", space2ID.String()), nil, tenant2ID.String(), token2)
	require.Equal(t, 0, resp2.Code)

	var page2 struct {
		Total int64 `json:"total"`
	}
	require.NoError(t, json.Unmarshal(resp2.Data, &page2))
	assert.Equal(t, int64(0), page2.Total)
}

func TestIntegration_DocumentVersions(t *testing.T) {
	require.NoError(t, testInfra.ClearDB())
	require.NoError(t, testInfra.ClearRedis())

	factory := testutil.NewFactory()

	tenant := factory.BuildTenant()
	require.NoError(t, testInfra.DB.WithContext(testInfra.Ctx).Create(tenant).Error)
	tenantID, _ := uuid.Parse(tenant.ID)
	ctx := testInfra.TenantContext(tenantID.String())

	user := factory.BuildUser(tenantID.String())
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(user).Error)
	userID, _ := uuid.Parse(user.ID)

	space := factory.BuildSpace(tenantID.String(), userID.String())
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(space).Error)
	spaceID, _ := uuid.Parse(space.ID)

	perm := factory.BuildPermission(tenantID.String(), model.ResourceTypeSpace, spaceID.String(), model.RoleAdmin, model.SubjectTypeUser, userID.String())
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(perm).Error)

	token := generateToken(userID, tenantID)

	resp := doRequest(t, "POST", fmt.Sprintf("/api/spaces/%s/documents", spaceID.String()), map[string]interface{}{
		"title":        "Version Test Doc",
		"content_text": "Initial content version 1",
		"content": map[string]interface{}{
			"type":    "doc",
			"content": []interface{}{},
		},
	}, tenantID.String(), token)
	require.Equal(t, 0, resp.Code)

	var doc model.Document
	require.NoError(t, json.Unmarshal(resp.Data, &doc))
	docID, _ := uuid.Parse(doc.ID)
	assert.Equal(t, 1, doc.Version)

	for i := 2; i <= 4; i++ {
		resp = doRequest(t, "PUT", fmt.Sprintf("/api/documents/%s", docID.String()), map[string]interface{}{
			"title":        fmt.Sprintf("Version Test Doc v%d", i),
			"content_text": fmt.Sprintf("Content version %d", i),
			"change_log":   fmt.Sprintf("update to v%d", i),
		}, tenantID.String(), token)
		require.Equal(t, 0, resp.Code)
		require.NoError(t, json.Unmarshal(resp.Data, &doc))
	}
	assert.Equal(t, 4, doc.Version)

	versionsResp := doRequest(t, "GET", fmt.Sprintf("/api/documents/%s/versions", docID.String()), nil, tenantID.String(), token)
	require.Equal(t, 0, versionsResp.Code)

	var versionsPage struct {
		Total int64 `json:"total"`
	}
	require.NoError(t, json.Unmarshal(versionsResp.Data, &versionsPage))
	assert.GreaterOrEqual(t, versionsPage.Total, int64(3))

	rollbackResp := doRequest(t, "POST", fmt.Sprintf("/api/documents/%s/rollback", docID.String()), map[string]interface{}{
		"version": 2,
	}, tenantID.String(), token)
	require.Equal(t, 0, rollbackResp.Code)

	var rolledDoc model.Document
	require.NoError(t, json.Unmarshal(rollbackResp.Data, &rolledDoc))
	assert.Equal(t, 5, rolledDoc.Version)
}

func TestIntegration_I18nMultiLanguage(t *testing.T) {
	require.NoError(t, testInfra.ClearDB())
	require.NoError(t, testInfra.ClearRedis())

	factory := testutil.NewFactory()

	tenant := factory.BuildTenant()
	require.NoError(t, testInfra.DB.WithContext(testInfra.Ctx).Create(tenant).Error)
	tenantID, _ := uuid.Parse(tenant.ID)
	ctx := testInfra.TenantContext(tenantID.String())

	user := factory.BuildUser(tenantID.String())
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(user).Error)
	userID, _ := uuid.Parse(user.ID)

	space := factory.BuildSpace(tenantID.String(), userID.String())
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(space).Error)
	spaceID, _ := uuid.Parse(space.ID)

	sourceDoc := factory.BuildDocument(tenantID.String(), spaceID.String(), userID.String(), func(d *model.Document) {
		d.LangCode = "zh-CN"
		d.Title = "中文源文档"
		d.ContentText = "这是中文文档内容"
	})
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(sourceDoc).Error)
	sourceDocID, _ := uuid.Parse(sourceDoc.ID)

	enDoc := &model.I18nDoc{
		TenantScoped: model.TenantScoped{TenantID: tenantID.String()},
		SourceDocID:  sourceDocID.String(),
		SourceLang:   "zh-CN",
		TargetLang:   "en",
		Status:       "published",
	}
	jaDoc := &model.I18nDoc{
		TenantScoped: model.TenantScoped{TenantID: tenantID.String()},
		SourceDocID:  sourceDocID.String(),
		SourceLang:   "zh-CN",
		TargetLang:   "ja",
		Status:       "published",
	}
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(enDoc).Error)
	require.NoError(t, testInfra.DB.WithContext(ctx).Create(jaDoc).Error)

	var count int64
	err := testInfra.DB.WithContext(ctx).Model(&model.I18nDoc{}).Where("source_doc_id = ?", sourceDocID.String()).Count(&count).Error
	require.NoError(t, err)
	assert.Equal(t, int64(2), count)

	var translations []*model.I18nDoc
	err = testInfra.DB.WithContext(ctx).Where("source_doc_id = ?", sourceDocID.String()).Find(&translations).Error
	require.NoError(t, err)

	langs := make([]string, 0)
	for _, tr := range translations {
		langs = append(langs, tr.TargetLang)
	}
	assert.Contains(t, langs, "en")
	assert.Contains(t, langs, "ja")
}
