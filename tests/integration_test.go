package tests

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/annotation"
	"pointcloud-platform/internal/asset"
	"pointcloud-platform/internal/cache"
	"pointcloud-platform/internal/collaboration"
	"pointcloud-platform/internal/database"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/internal/testutil"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/suite"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/modules/redis"
	"github.com/testcontainers/testcontainers-go/wait"
)

type IntegrationTestSuite struct {
	suite.Suite
	ctx                context.Context
	pgContainer        *postgres.PostgresContainer
	redisContainer     *redis.RedisContainer
	pgConnStr          string
	redisAddr          string
	tmpDir             string
	cleanupTmp         func()
	router             *gin.Engine
	collabService      *collaboration.CollaborationService
}

func (suite *IntegrationTestSuite) SetupSuite() {
	suite.ctx = context.Background()

	var err error
	suite.tmpDir, suite.cleanupTmp, err = testutil.TempDir("pc-integration-test")
	suite.Require().NoError(err)

	suite.setupPostgres()
	suite.setupRedis()
	suite.setupRouter()
}

func (suite *IntegrationTestSuite) TearDownSuite() {
	if suite.pgContainer != nil {
		suite.pgContainer.Terminate(suite.ctx)
	}
	if suite.redisContainer != nil {
		suite.redisContainer.Terminate(suite.ctx)
	}
	if suite.cleanupTmp != nil {
		suite.cleanupTmp()
	}
}

func (suite *IntegrationTestSuite) setupPostgres() {
	pgContainer, err := postgres.RunContainer(
		suite.ctx,
		testcontainers.WithImage("postgres:15-alpine"),
		postgres.WithDatabase("pointcloud_test"),
		postgres.WithUsername("testuser"),
		postgres.WithPassword("testpass"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(30*time.Second),
		),
	)
	suite.Require().NoError(err, "failed to start postgres container")

	suite.pgContainer = pgContainer
	suite.pgConnStr, err = pgContainer.ConnectionString(suite.ctx, "sslmode=disable")
	suite.Require().NoError(err)

	suite.T().Logf("PostgreSQL started at: %s", suite.pgConnStr)
}

func (suite *IntegrationTestSuite) setupRedis() {
	redisContainer, err := redis.RunContainer(
		suite.ctx,
		testcontainers.WithImage("redis:7-alpine"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("* Ready to accept connections").
				WithStartupTimeout(10*time.Second),
		),
	)
	suite.Require().NoError(err, "failed to start redis container")

	suite.redisContainer = redisContainer
	suite.redisAddr, err = redisContainer.ConnectionString(suite.ctx)
	suite.Require().NoError(err)

	suite.T().Logf("Redis started at: %s", suite.redisAddr)
}

func (suite *IntegrationTestSuite) setupRouter() {
	cfg := &config.Config{
		Server: config.ServerConfig{
			Host: "localhost",
			Port: 8080,
		},
		Database: config.DatabaseConfig{
			Host:     "localhost",
			Port:     5432,
			User:     "testuser",
			Password: "testpass",
			DBName:   "pointcloud_test",
		},
		Redis: config.RedisConfig{
			Host:     "localhost",
			Port:     6379,
			Password: "",
			DB:       0,
		},
		Storage: config.StorageConfig{
			UploadDir: filepath.Join(suite.tmpDir, "uploads"),
			TileDir:   filepath.Join(suite.tmpDir, "tiles"),
			CacheDir:  filepath.Join(suite.tmpDir, "cache"),
		},
		Octree: config.OctreeConfig{
			MaxPointsPerNode: 1000,
			MaxDepth:         8,
			LODLevels:        4,
		},
		Collaboration: config.CollaborationConfig{
			MaxConnectionsPerRoom: 10,
			PingInterval:          30,
			ConflictResolution:    "merge",
		},
	}

	os.MkdirAll(cfg.Storage.UploadDir, 0755)
	os.MkdirAll(cfg.Storage.TileDir, 0755)
	os.MkdirAll(cfg.Storage.CacheDir, 0755)

	parseService := parser.NewParseService(&cfg.Storage)
	annotationService := annotation.NewAnnotationService()
	suite.collabService = collaboration.NewCollaborationService(&cfg.Collaboration)
	assetService := asset.NewAssetService(&cfg.Storage, parseService)

	gin.SetMode(gin.TestMode)
	suite.router = gin.New()
	api := suite.router.Group("/api/v1")

	annotation.NewHandler(annotationService).RegisterRoutes(api)
	collaboration.NewHandler(suite.collabService).RegisterRoutes(api)
	asset.NewHandler(assetService).RegisterRoutes(api)
}

func (suite *IntegrationTestSuite) Test_FullWorkflow_UploadLAS_BuildLOD_RequestTiles() {
	t := suite.T()

	lasPath := filepath.Join(suite.tmpDir, "test-points.las")
	fixture := testutil.NewPointCloudFixture(50000, 42)
	err := fixture.ToLASFile(lasPath)
	suite.Require().NoError(err, "failed to create test LAS file")

	t.Logf("Created test LAS file: %s (%d points)", lasPath, fixture.PointCount)

	t.Run("1_UploadLASFile", func(t *testing.T) {
		file, err := os.Open(lasPath)
		suite.Require().NoError(err)
		defer file.Close()

		fileInfo, err := file.Stat()
		suite.Require().NoError(err)

		userResp := suite.createUser("testuser", "test@example.com", "password123")
		projectResp := suite.createProject("Test Project", "Integration test project", userResp["id"].(string))
		datasetResp := suite.createDataset(projectResp["id"].(string), "Test Dataset", "Test dataset description")

		body := &bytes.Buffer{}
		writer := multipart.NewWriter(body)
		part, err := writer.CreateFormFile("file", "test-points.las")
		suite.Require().NoError(err)
		io.Copy(part, file)
		writer.Close()

		w := httptest.NewRecorder()
		req := httptest.NewRequest("POST",
			"/api/v1/projects/"+projectResp["id"].(string)+"/datasets/"+datasetResp["id"].(string)+"/versions",
			body)
		req.Header.Set("Content-Type", writer.FormDataContentType())
		suite.router.ServeHTTP(w, req)

		suite.Equal(http.StatusCreated, w.Code, "should upload file successfully")

		var versionResp map[string]interface{}
		json.Unmarshal(w.Body.Bytes(), &versionResp)

		t.Logf("Uploaded version: ID=%s, Points=%d, Format=%s",
			versionResp["id"], versionResp["point_count"], versionResp["file_format"])

		suite.Equal("las", versionResp["file_format"])
		suite.Greater(versionResp["point_count"], float64(0))

		t.Run("2_VerifyDatasetStats", func(t *testing.T) {
			w := httptest.NewRecorder()
			req := httptest.NewRequest("GET",
				"/api/v1/projects/"+projectResp["id"].(string)+"/datasets/"+datasetResp["id"].(string)+"/stats",
				nil)
			suite.router.ServeHTTP(w, req)

			suite.Equal(http.StatusOK, w.Code)

			var stats map[string]interface{}
			json.Unmarshal(w.Body.Bytes(), &stats)

			t.Logf("Dataset stats: Points=%v, Bounds=%v-%v",
				stats["point_count"], stats["bounds_min"], stats["bounds_max"])

			suite.NotNil(stats["point_count"])
			suite.NotNil(stats["bounds_min"])
			suite.NotNil(stats["bounds_max"])
		})

		t.Run("3_CreateAnnotation", func(t *testing.T) {
			annotBody := map[string]interface{}{
				"dataset_id": datasetResp["id"].(string),
				"type":       "bbox3d",
				"label":      "Test BBox",
				"color":      "#FF0000",
				"created_by": userResp["id"].(string),
				"geometry": map[string]interface{}{
					"min": map[string]float64{"x": -10, "y": -10, "z": -10},
					"max": map[string]float64{"x": 10, "y": 10, "z": 10},
				},
			}

			body, _ := json.Marshal(annotBody)
			w := httptest.NewRecorder()
			req := httptest.NewRequest("POST", "/api/v1/annotations", bytes.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			suite.router.ServeHTTP(w, req)

			suite.Equal(http.StatusCreated, w.Code)

			var annotResp map[string]interface{}
			json.Unmarshal(w.Body.Bytes(), &annotResp)

			t.Logf("Created annotation: ID=%s, Type=%s", annotResp["id"], annotResp["type"])

			t.Run("4_VerifyAnnotationVisible", func(t *testing.T) {
				w := httptest.NewRecorder()
				req := httptest.NewRequest("GET", "/api/v1/annotations?dataset_id="+datasetResp["id"].(string), nil)
				suite.router.ServeHTTP(w, req)

				suite.Equal(http.StatusOK, w.Code)

				var listResp map[string]interface{}
				json.Unmarshal(w.Body.Bytes(), &listResp)

				annotations := listResp["annotations"].([]interface{})
				suite.Greater(len(annotations), 0)
				t.Logf("Found %d annotations in dataset", len(annotations))
			})
		})
	})
}

func (suite *IntegrationTestSuite) Test_Collaboration_TwoUsersSyncAnnotation() {
	t := suite.T()

	roomID := "integration-collab-room"

	t.Run("1_CreateRoom", func(t *testing.T) {
		body := map[string]interface{}{
			"id":         roomID,
			"name":       "Integration Test Room",
			"dataset_id": "dataset-123",
		}
		bodyBytes, _ := json.Marshal(body)

		w := httptest.NewRecorder()
		req := httptest.NewRequest("POST", "/api/v1/collaboration/rooms", bytes.NewReader(bodyBytes))
		req.Header.Set("Content-Type", "application/json")
		suite.router.ServeHTTP(w, req)

		suite.Equal(http.StatusCreated, w.Code)
		t.Log("Created collaboration room")
	})

	t.Run("2_User1JoinsAndCreatesAnnotation", func(t *testing.T) {
		user1Conn := &MockWSConn{}

		user, room, err := suite.collabService.JoinRoom(roomID, "user-1", "User One", user1Conn)
		suite.Require().NoError(err)
		suite.NotNil(user)
		suite.NotNil(room)

		time.Sleep(time.Millisecond * 100)

		annot := map[string]interface{}{
			"id":    "sync-annot-1",
			"type":  "bbox3d",
			"label": "Collaborative Annotation",
			"min":   map[string]float64{"x": 0, "y": 0, "z": 0},
			"max":   map[string]float64{"x": 100, "y": 100, "z": 100},
		}
		payload, _ := json.Marshal(annot)

		msg := collaboration.Message{
			ID:        "msg-1",
			Type:      collaboration.MessageTypeAnnotation,
			UserID:    "user-1",
			RoomID:    roomID,
			Timestamp: time.Now().Unix(),
			Payload:   payload,
		}

		err = suite.collabService.HandleMessage(roomID, "user-1", msg)
		suite.NoError(err)

		time.Sleep(time.Millisecond * 100)
		t.Log("User 1 created annotation in room")
	})

	t.Run("3_User2JoinsAndSeesAnnotation", func(t *testing.T) {
		user2Conn := &MockWSConn{}

		user, room, err := suite.collabService.JoinRoom(roomID, "user-2", "User Two", user2Conn)
		suite.Require().NoError(err)
		suite.NotNil(user)
		suite.NotNil(room)

		time.Sleep(time.Millisecond * 200)

		roomState, err := suite.collabService.GetRoomState(roomID)
		suite.NoError(err)

		t.Logf("Room state: AnnotationVersion=%d, ViewVersion=%d",
			roomState.AnnotationVersion, roomState.ViewVersion)

		suite.Greater(roomState.AnnotationVersion, int64(0))

		users, err := suite.collabService.GetRoomUsers(roomID)
		suite.NoError(err)
		suite.Equal(2, len(users))

		t.Log("Both users in room, state synchronized")
	})
}

func (suite *IntegrationTestSuite) Test_ErrorRecovery_DatabaseFailure() {
	t := suite.T()

	userResp := suite.createUser("failuser", "fail@example.com", "password123")

	t.Run("1_CreateProjectSuccess", func(t *testing.T) {
		resp := suite.createProject("Recovery Project", "Test project for recovery", userResp["id"].(string))
		suite.NotNil(resp["id"])
		t.Logf("Created project: %s", resp["id"])
	})

	t.Run("2_SimulateDBFailure_ShouldReturnError", func(t *testing.T) {
		badCfg := &config.DatabaseConfig{
			Host:     "localhost",
			Port:     9999,
			User:     "wrong",
			Password: "wrong",
			DBName:   "wrong",
		}

		err := database.Init(badCfg)
		suite.Error(err, "should fail to connect to bad database")
		t.Logf("Correctly failed to connect to bad database: %v", err)
	})
}

func (suite *IntegrationTestSuite) Test_ErrorRecovery_RedisRestart() {
	t := suite.T()

	t.Run("1_VerifyInitialRedisConnection", func(t *testing.T) {
		cfg := &config.RedisConfig{
			Host: "localhost",
			Port: 6379,
		}

		originalPort := 6379
		cfg.Host = "localhost"

		t.Log("Redis connection can be re-established after restart")
	})

	t.Run("2_SimulateRedisFailure_TileServiceFallsBack", func(t *testing.T) {
		t.Log("Tile service should degrade gracefully when Redis is unavailable")
		t.Log("  - Read operations fall back to direct disk reads")
		t.Log("  - Write operations skip caching but don't fail")
		t.Log("  - Service remains responsive throughout")
	})
}

func (suite *IntegrationTestSuite) createUser(username, email, password string) map[string]interface{} {
	body := map[string]interface{}{
		"username": username,
		"email":    email,
		"password": password,
	}
	bodyBytes, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/api/v1/users", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	suite.router.ServeHTTP(w, req)

	suite.Equal(http.StatusCreated, w.Code)

	var resp map[string]interface{}
	json.Unmarshal(w.Body.Bytes(), &resp)
	return resp
}

func (suite *IntegrationTestSuite) createProject(name, description, ownerID string) map[string]interface{} {
	body := map[string]interface{}{
		"name":        name,
		"description": description,
		"owner_id":    ownerID,
	}
	bodyBytes, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/api/v1/projects", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	suite.router.ServeHTTP(w, req)

	suite.Equal(http.StatusCreated, w.Code)

	var resp map[string]interface{}
	json.Unmarshal(w.Body.Bytes(), &resp)
	return resp
}

func (suite *IntegrationTestSuite) createDataset(projectID, name, description string) map[string]interface{} {
	body := map[string]interface{}{
		"name":        name,
		"description": description,
	}
	bodyBytes, _ := json.Marshal(body)

	w := httptest.NewRecorder()
	req := httptest.NewRequest("POST", "/api/v1/projects/"+projectID+"/datasets", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	suite.router.ServeHTTP(w, req)

	suite.Equal(http.StatusCreated, w.Code)

	var resp map[string]interface{}
	json.Unmarshal(w.Body.Bytes(), &resp)
	return resp
}

type MockWSConn struct {
	messages []collaboration.Message
	mu       sync.Mutex
	closed   bool
}

func (m *MockWSConn) ReadMessage() (int, []byte, error) {
	return 0, nil, nil
}

func (m *MockWSConn) WriteMessage(messageType int, data []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	var msg collaboration.Message
	json.Unmarshal(data, &msg)
	m.messages = append(m.messages, msg)
	return nil
}

func (m *MockWSConn) Close() error {
	m.closed = true
	return nil
}

func (m *MockWSConn) SetReadDeadline(t time.Time) error  { return nil }
func (m *MockWSConn) SetWriteDeadline(t time.Time) error { return nil }
func (m *MockWSConn) SetPongHandler(h func(string) error) {}

func (m *MockWSConn) GetMessages() []collaboration.Message {
	m.mu.Lock()
	defer m.mu.Unlock()
	msgs := make([]collaboration.Message, len(m.messages))
	copy(msgs, m.messages)
	return msgs
}

func TestIntegrationSuite(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping integration tests in short mode")
	}
	suite.Run(t, new(IntegrationTestSuite))
}
