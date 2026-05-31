package apicontract

type CreateSchemaRequest struct {
	Name        string                 `json:"name" binding:"required,max=128"`
	Version     string                 `json:"version" binding:"required,max=32"`
	SchemaType  string                 `json:"schema_type" binding:"required,oneof=openapi graphql"`
	Content     string                 `json:"content" binding:"required"`
	Format      string                 `json:"format" binding:"required,oneof=json yaml"`
	ServiceName string                 `json:"service_name"`
	Metadata    map[string]interface{} `json:"metadata"`
	IsActive    bool                   `json:"is_active"`
}

type UpdateSchemaRequest struct {
	Name        string                 `json:"name" binding:"max=128"`
	Version     string                 `json:"version" binding:"max=32"`
	Content     string                 `json:"content"`
	ServiceName string                 `json:"service_name"`
	Metadata    map[string]interface{} `json:"metadata"`
	IsActive    *bool                  `json:"is_active"`
}

type ValidateSchemaRequest struct {
	SchemaID string `json:"schema_id" binding:"required"`
}

type CreateMockServerRequest struct {
	SchemaID string                 `json:"schema_id" binding:"required"`
	Name     string                 `json:"name" binding:"required,max=128"`
	Config   map[string]interface{} `json:"config"`
}

type CreateContractTestRequest struct {
	SchemaID string                 `json:"schema_id" binding:"required"`
	Name     string                 `json:"name" binding:"required,max=128"`
	TestType string                 `json:"test_type" binding:"required"`
	Request  map[string]interface{} `json:"request" binding:"required"`
	Expected map[string]interface{} `json:"expected" binding:"required"`
}

type RunContractTestRequest struct {
	TestID string `json:"test_id" binding:"required"`
}
