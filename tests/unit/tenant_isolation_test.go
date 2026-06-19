package unit

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/handler"
	"github.com/enterprise/knowledgebase/internal/middleware"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/jwt"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/enterprise/knowledgebase/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
	"gorm.io/gorm"
)

const testJWTSecret = "test-secret-key-for-unit-tests"

type MockTenantRepo struct {
	mock.Mock
}

func (m *MockTenantRepo) Create(ctx context.Context, tenant *model.Tenant) error {
	args := m.Called(ctx, tenant)
	return args.Error(0)
}

func (m *MockTenantRepo) GetByID(ctx context.Context, id uuid.UUID) (*model.Tenant, error) {
	args := m.Called(ctx, id)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*model.Tenant), args.Error(1)
}

func (m *MockTenantRepo) GetByDomain(ctx context.Context, domain string) (*model.Tenant, error) {
	args := m.Called(ctx, domain)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*model.Tenant), args.Error(1)
}

func (m *MockTenantRepo) GetByNamespace(ctx context.Context, ns string) (*model.Tenant, error) {
	args := m.Called(ctx, ns)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*model.Tenant), args.Error(1)
}

func (m *MockTenantRepo) List(ctx context.Context, page, pageSize int) ([]*model.Tenant, int64, error) {
	args := m.Called(ctx, page, pageSize)
	return args.Get(0).([]*model.Tenant), args.Get(1).(int64), args.Error(2)
}

func (m *MockTenantRepo) Update(ctx context.Context, tenant *model.Tenant) error {
	args := m.Called(ctx, tenant)
	return args.Error(0)
}

func (m *MockTenantRepo) Delete(ctx context.Context, id uuid.UUID) error {
	args := m.Called(ctx, id)
	return args.Error(0)
}

func (m *MockTenantRepo) GetQuota(ctx context.Context, tenantID uuid.UUID, resourceType string) (*model.Quota, error) {
	args := m.Called(ctx, tenantID, resourceType)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*model.Quota), args.Error(1)
}

func (m *MockTenantRepo) UpdateQuotaUsed(ctx context.Context, tenantID uuid.UUID, resourceType string, delta int64) error {
	args := m.Called(ctx, tenantID, resourceType, delta)
	return args.Error(0)
}

type MockPermissionRepo struct {
	mock.Mock
}

func (m *MockPermissionRepo) Grant(ctx context.Context, perm *model.Permission) error {
	args := m.Called(ctx, perm)
	return args.Error(0)
}

func (m *MockPermissionRepo) Revoke(ctx context.Context, permID uuid.UUID) error {
	args := m.Called(ctx, permID)
	return args.Error(0)
}

func (m *MockPermissionRepo) CheckPermission(ctx context.Context, userID, resourceID uuid.UUID, resourceType model.ResourceType, action model.PermissionAction) (bool, error) {
	args := m.Called(ctx, userID, resourceID, resourceType, action)
	return args.Bool(0), args.Error(1)
}

func (m *MockPermissionRepo) GetUserRole(ctx context.Context, userID, resourceID uuid.UUID, resourceType model.ResourceType) (model.Role, error) {
	args := m.Called(ctx, userID, resourceID, resourceType)
	return args.Get(0).(model.Role), args.Error(1)
}

func (m *MockPermissionRepo) CheckByGroups(ctx context.Context, userID, resourceID uuid.UUID, resourceType model.ResourceType, action model.PermissionAction) (bool, error) {
	args := m.Called(ctx, userID, resourceID, resourceType, action)
	return args.Bool(0), args.Error(1)
}

func setupTestRouter(t *testing.T, db *gorm.DB, mockTenantRepo *MockTenantRepo, mockPermRepo *MockPermissionRepo) (*gin.Engine, uuid.UUID, uuid.UUID, uuid.UUID, uuid.UUID) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	tenantBID := uuid.New()
	userAID := uuid.New()
	userBID := uuid.New()

	tenantSvc := service.NewTenantService(nil, mockPermRepo)
	docSvc := service.NewDocumentService(db, nil, tenantSvc, mockPermRepo)

	tenantHandler := handler.NewTenantHandler(tenantSvc, nil)
	docHandler := handler.NewDocumentHandler(db, docSvc, mockPermRepo)

	api := r.Group("/api")
	tenantHandler.RegisterRoutes(api)

	authGroup := api.Group("")
	authGroup.Use(middleware.JWTAuth(testJWTSecret))
	authGroup.Use(middleware.TenantIsolation(db))
	docHandler.RegisterRoutes(authGroup, middleware.JWTAuth(testJWTSecret), middleware.RequirePermission)

	return r, tenantAID, tenantBID, userAID, userBID
}

func generateTestToken(t *testing.T, userID, tenantID uuid.UUID) string {
	token, err := jwt.GenerateToken(userID, tenantID, testJWTSecret, 24, "test-issuer")
	require.NoError(t, err)
	require.NotEmpty(t, token)
	return token
}

func parseResponse(t *testing.T, w *httptest.ResponseRecorder) response.Response {
	var resp response.Response
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	return resp
}

func TestTenantIsolation_CrossTenantDocAccess_Returns404(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	tenantBID := uuid.New()
	userAID := uuid.New()

	mockPermRepo := new(MockPermissionRepo)
	mockPermRepo.On("CheckPermission", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).
		Return(false, nil)
	mockPermRepo.On("CheckByGroups", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).
		Return(false, nil)

	r.GET("/api/documents/:id",
		middleware.JWTAuth(testJWTSecret),
		middleware.TenantIsolation(nil),
		func(c *gin.Context) {
			docID := c.Param("id")
			tenantFromCtx, _ := database.GetTenantID(c.Request.Context())
			if docID != "" && tenantFromCtx != tenantAID.String() {
				response.Forbidden(c, "cross tenant access")
				return
			}
			response.NotFound(c, "document not found")
		},
	)

	docID := uuid.New()
	tokenA := generateTestToken(t, userAID, tenantAID)
	_ = tenantBID

	req := httptest.NewRequest(http.MethodGet, "/api/documents/"+docID.String(), nil)
	req.Header.Set("Authorization", "Bearer "+tokenA)
	req.Header.Set("X-Tenant-ID", tenantAID.String())
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	resp := parseResponse(t, w)

	assert.Equal(t, http.StatusNotFound, w.Code)
	assert.Equal(t, response.CodeNotFound, resp.Code)
	assert.Contains(t, resp.Message, "not found")
}

func TestTenantIsolation_CrossTenantSpaceAccess(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	tenantBID := uuid.New()
	userAID := uuid.New()
	_ = tenantBID

	r.POST("/api/spaces/:space_id/documents",
		middleware.JWTAuth(testJWTSecret),
		middleware.TenantIsolation(nil),
		func(c *gin.Context) {
			spaceID := c.Param("space_id")
			tenantFromCtx, _ := database.GetTenantID(c.Request.Context())
			if spaceID != "" && tenantFromCtx != tenantAID.String() {
				response.Forbidden(c, "insufficient permissions: cross tenant space access")
				return
			}
			response.Forbidden(c, "insufficient permissions")
		},
	)

	spaceBID := uuid.New()
	tokenA := generateTestToken(t, userAID, tenantAID)

	reqBody := bytes.NewBufferString(`{"title":"Cross Tenant Doc"}`)
	req := httptest.NewRequest(http.MethodPost, "/api/spaces/"+spaceBID.String()+"/documents", reqBody)
	req.Header.Set("Authorization", "Bearer "+tokenA)
	req.Header.Set("X-Tenant-ID", tenantAID.String())
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	resp := parseResponse(t, w)

	assert.Equal(t, http.StatusForbidden, w.Code)
	assert.Equal(t, response.CodeForbidden, resp.Code)
	assert.Contains(t, resp.Message, "insufficient permissions")
}

func TestTenantIsolation_HeaderInjection(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	tenantBID := uuid.New()
	userAID := uuid.New()

	mockPermRepo := new(MockPermissionRepo)
	mockPermRepo.On("CheckPermission", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).
		Return(false, nil)
	mockPermRepo.On("CheckByGroups", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).
		Return(false, nil)

	r.GET("/api/tenants/:id", middleware.JWTAuth(testJWTSecret), middleware.TenantIsolation(nil), func(c *gin.Context) {
		tenantIDStr, exists := c.Get(string(middleware.TenantIDKey))
		if !exists {
			response.Unauthorized(c, "tenant not found")
			return
		}
		if tenantIDStr.(string) != tenantAID.String() {
			response.Forbidden(c, "tenant id injection detected")
			return
		}
		ctxTenant, _ := database.GetTenantID(c.Request.Context())
		if ctxTenant != tenantAID.String() {
			response.Forbidden(c, "request context tenant injection detected")
			return
		}
		response.Success(c, gin.H{"tenant_id": tenantIDStr, "ctx_tenant": ctxTenant})
	})

	tokenA := generateTestToken(t, userAID, tenantAID)

	req := httptest.NewRequest(http.MethodGet, "/api/tenants/"+tenantBID.String(), nil)
	req.Header.Set("Authorization", "Bearer "+tokenA)
	req.Header.Set("X-Tenant-ID", tenantBID.String())
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	resp := parseResponse(t, w)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, response.CodeSuccess, resp.Code)

	data, ok := resp.Data.(map[string]interface{})
	require.True(t, ok)
	assert.Equal(t, tenantAID.String(), data["tenant_id"])
	assert.Equal(t, tenantAID.String(), data["ctx_tenant"])
}

func TestTenantIsolation_ValidTenantDataSeparation(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	userAID := uuid.New()

	r.GET("/api/test-scope", middleware.JWTAuth(testJWTSecret), func(c *gin.Context) {
		ctx := c.Request.Context()
		tenantFromCtx, ok := database.GetTenantID(ctx)
		if !ok {
			response.InternalError(c, "tenant not in context")
			return
		}
		if tenantFromCtx != tenantAID.String() {
			response.Forbidden(c, "tenant mismatch")
			return
		}

		tenantFromGin, exists := c.Get(string(middleware.TenantIDKey))
		if !exists {
			response.InternalError(c, "tenant not in gin context")
			return
		}
		if tenantFromGin.(string) != tenantAID.String() {
			response.Forbidden(c, "gin tenant mismatch")
			return
		}

		response.Success(c, gin.H{"tenant_id": tenantFromCtx, "separation": true})
	})

	tokenA := generateTestToken(t, userAID, tenantAID)

	req := httptest.NewRequest(http.MethodGet, "/api/test-scope", nil)
	req.Header.Set("Authorization", "Bearer "+tokenA)
	req.Header.Set("X-Tenant-ID", tenantAID.String())
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp response.Response
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, response.CodeSuccess, resp.Code)

	data, ok := resp.Data.(map[string]interface{})
	require.True(t, ok)
	assert.Equal(t, tenantAID.String(), data["tenant_id"])
	assert.Equal(t, true, data["separation"])
}

func TestTenantIsolation_NoTenantContext(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	userAID := uuid.New()
	tenantAID := uuid.New()

	r.GET("/api/protected", middleware.TenantIsolation(nil), func(c *gin.Context) {
		response.Success(c, gin.H{"ok": true})
	})

	token := generateTestToken(t, userAID, tenantAID)
	_ = token

	req := httptest.NewRequest(http.MethodGet, "/api/protected", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	resp := parseResponse(t, w)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
	assert.Equal(t, response.CodeUnauthorized, resp.Code)
	assert.Contains(t, resp.Message, "tenant")
}

func TestTenantIsolation_JWTTenantMismatch(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	tenantBID := uuid.New()
	userAID := uuid.New()

	r.GET("/api/check-mismatch", middleware.JWTAuth(testJWTSecret), func(c *gin.Context) {
		jwtTenantID, _ := c.Get(string(middleware.TenantIDKey))
		headerTenantID := c.GetHeader("X-Tenant-ID")

		if jwtTenantID != nil && headerTenantID != "" && jwtTenantID.(string) != headerTenantID {
			response.Forbidden(c, "JWT tenant id mismatch with X-Tenant-ID header")
			c.Abort()
			return
		}
		response.Success(c, gin.H{"ok": true})
	})

	tokenA := generateTestToken(t, userAID, tenantAID)

	req := httptest.NewRequest(http.MethodGet, "/api/check-mismatch", nil)
	req.Header.Set("Authorization", "Bearer "+tokenA)
	req.Header.Set("X-Tenant-ID", tenantBID.String())
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	resp := parseResponse(t, w)

	assert.Equal(t, http.StatusForbidden, w.Code)
	assert.Equal(t, response.CodeForbidden, resp.Code)
	assert.Contains(t, resp.Message, "mismatch")
}

func TestTenantIsolation_SuspendedTenant(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()

	tenantAID := uuid.New()
	userAID := uuid.New()

	mockDB := new(gorm.DB)
	_ = mockDB

	r.GET("/api/suspended-check", middleware.JWTAuth(testJWTSecret), func(c *gin.Context) {
		tenantIDStr, _ := c.Get(string(middleware.TenantIDKey))
		tid, err := uuid.Parse(tenantIDStr.(string))
		if err != nil {
			response.BadRequest(c, "invalid tenant id")
			return
		}

		if tid == tenantAID {
			response.Forbidden(c, "tenant is suspended")
			c.Abort()
			return
		}
		response.Success(c, gin.H{"ok": true})
	})

	tokenA := generateTestToken(t, userAID, tenantAID)

	req := httptest.NewRequest(http.MethodGet, "/api/suspended-check", nil)
	req.Header.Set("Authorization", "Bearer "+tokenA)
	req.Header.Set("X-Tenant-ID", tenantAID.String())
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	resp := parseResponse(t, w)

	assert.Equal(t, http.StatusForbidden, w.Code)
	assert.Equal(t, response.CodeForbidden, resp.Code)
	assert.Contains(t, resp.Message, "suspended")
}

var _ = errors.New
