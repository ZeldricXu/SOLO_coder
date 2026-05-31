package apicontract

import (
	"depguard/internal/common/utils"
	"depguard/internal/database"
	"time"
)

type SchemaRepository interface {
	Create(schema *APISchema) error
	Update(id string, updates map[string]interface{}) error
	Delete(id string) error
	GetByID(id string) (*APISchema, error)
	List(page, pageSize int, schemaType, serviceName string) ([]APISchema, int64, error)
}

type ValidationResultRepository interface {
	Create(result *ValidationResult) error
	GetByID(id string) (*ValidationResult, error)
	ListBySchemaID(schemaID string, limit int) ([]ValidationResult, error)
}

type MockServerRepository interface {
	Create(server *MockServer) error
	Update(id string, updates map[string]interface{}) error
	Delete(id string) error
	GetByID(id string) (*MockServer, error)
	GetByServerID(serverID string) (*MockServer, error)
	List(page, pageSize int, status string) ([]MockServer, int64, error)
}

type ContractTestRepository interface {
	Create(test *ContractTest) error
	Update(id string, updates map[string]interface{}) error
	Delete(id string) error
	GetByID(id string) (*ContractTest, error)
	GetByTestID(testID string) (*ContractTest, error)
	ListBySchemaID(schemaID string) ([]ContractTest, error)
}

type schemaRepo struct{}

func NewSchemaRepository() SchemaRepository {
	return &schemaRepo{}
}

func (r *schemaRepo) Create(schema *APISchema) error {
	schema.ID = utils.GenerateID("sch")
	return database.DB.Create(schema).Error
}

func (r *schemaRepo) Update(id string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	return database.DB.Model(&APISchema{}).Where("id = ?", id).Updates(updates).Error
}

func (r *schemaRepo) Delete(id string) error {
	return database.DB.Delete(&APISchema{}, "id = ?", id).Error
}

func (r *schemaRepo) GetByID(id string) (*APISchema, error) {
	var schema APISchema
	err := database.DB.Where("id = ?", id).First(&schema).Error
	if err != nil {
		return nil, err
	}
	return &schema, nil
}

func (r *schemaRepo) List(page, pageSize int, schemaType, serviceName string) ([]APISchema, int64, error) {
	var schemas []APISchema
	var total int64
	query := database.DB.Model(&APISchema{})

	if schemaType != "" {
		query = query.Where("schema_type = ?", schemaType)
	}
	if serviceName != "" {
		query = query.Where("service_name = ?", serviceName)
	}

	err := query.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err = query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&schemas).Error
	return schemas, total, err
}

type validationResultRepo struct{}

func NewValidationResultRepository() ValidationResultRepository {
	return &validationResultRepo{}
}

func (r *validationResultRepo) Create(result *ValidationResult) error {
	result.ID = utils.GenerateID("vr")
	return database.DB.Create(result).Error
}

func (r *validationResultRepo) GetByID(id string) (*ValidationResult, error) {
	var result ValidationResult
	err := database.DB.Where("id = ?", id).First(&result).Error
	if err != nil {
		return nil, err
	}
	return &result, nil
}

func (r *validationResultRepo) ListBySchemaID(schemaID string, limit int) ([]ValidationResult, error) {
	var results []ValidationResult
	err := database.DB.Where("schema_id = ?", schemaID).Order("created_at DESC").Limit(limit).Find(&results).Error
	return results, err
}

type mockServerRepo struct{}

func NewMockServerRepository() MockServerRepository {
	return &mockServerRepo{}
}

func (r *mockServerRepo) Create(server *MockServer) error {
	server.ID = utils.GenerateID("ms")
	return database.DB.Create(server).Error
}

func (r *mockServerRepo) Update(id string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	return database.DB.Model(&MockServer{}).Where("id = ?", id).Updates(updates).Error
}

func (r *mockServerRepo) Delete(id string) error {
	return database.DB.Delete(&MockServer{}, "id = ?", id).Error
}

func (r *mockServerRepo) GetByID(id string) (*MockServer, error) {
	var server MockServer
	err := database.DB.Where("id = ?", id).First(&server).Error
	if err != nil {
		return nil, err
	}
	return &server, nil
}

func (r *mockServerRepo) GetByServerID(serverID string) (*MockServer, error) {
	var server MockServer
	err := database.DB.Where("server_id = ?", serverID).First(&server).Error
	if err != nil {
		return nil, err
	}
	return &server, nil
}

func (r *mockServerRepo) List(page, pageSize int, status string) ([]MockServer, int64, error) {
	var servers []MockServer
	var total int64
	query := database.DB.Model(&MockServer{})

	if status != "" {
		query = query.Where("status = ?", status)
	}

	err := query.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err = query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&servers).Error
	return servers, total, err
}

type contractTestRepo struct{}

func NewContractTestRepository() ContractTestRepository {
	return &contractTestRepo{}
}

func (r *contractTestRepo) Create(test *ContractTest) error {
	test.ID = utils.GenerateID("ct")
	return database.DB.Create(test).Error
}

func (r *contractTestRepo) Update(id string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	return database.DB.Model(&ContractTest{}).Where("id = ?", id).Updates(updates).Error
}

func (r *contractTestRepo) Delete(id string) error {
	return database.DB.Delete(&ContractTest{}, "id = ?", id).Error
}

func (r *contractTestRepo) GetByID(id string) (*ContractTest, error) {
	var test ContractTest
	err := database.DB.Where("id = ?", id).First(&test).Error
	if err != nil {
		return nil, err
	}
	return &test, nil
}

func (r *contractTestRepo) GetByTestID(testID string) (*ContractTest, error) {
	var test ContractTest
	err := database.DB.Where("test_id = ?", testID).First(&test).Error
	if err != nil {
		return nil, err
	}
	return &test, nil
}

func (r *contractTestRepo) ListBySchemaID(schemaID string) ([]ContractTest, error) {
	var tests []ContractTest
	err := database.DB.Where("schema_id = ?", schemaID).Order("created_at DESC").Find(&tests).Error
	return tests, err
}
