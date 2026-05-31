package scaffold

type CreateTemplateRequest struct {
	Name        string                 `json:"name" binding:"required,max=128"`
	Description string                 `json:"description"`
	Language    string                 `json:"language" binding:"required"`
	Framework   string                 `json:"framework"`
	Version     string                 `json:"version"`
	Tags        []string               `json:"tags"`
	Parameters  map[string]interface{} `json:"parameters"`
	FileTree    map[string]interface{} `json:"file_tree" binding:"required"`
	IsPublic    bool                   `json:"is_public"`
	Author      string                 `json:"author"`
}

type UpdateTemplateRequest struct {
	Name        string                 `json:"name" binding:"max=128"`
	Description string                 `json:"description"`
	Language    string                 `json:"language"`
	Framework   string                 `json:"framework"`
	Version     string                 `json:"version"`
	Tags        []string               `json:"tags"`
	Parameters  map[string]interface{} `json:"parameters"`
	FileTree    map[string]interface{} `json:"file_tree"`
	IsPublic    *bool                  `json:"is_public"`
}

type GenerateProjectRequest struct {
	Name        string                 `json:"name" binding:"required,max=128"`
	Description string                 `json:"description"`
	TemplateID  string                 `json:"template_id" binding:"required"`
	Namespace   string                 `json:"namespace" binding:"required"`
	Config      map[string]interface{} `json:"config"`
	OwnerID     string                 `json:"owner_id"`
}

type InteractiveStartRequest struct {
	TemplateID string `json:"template_id" binding:"required"`
	UserID     string `json:"user_id"`
}

type InteractiveAnswerRequest struct {
	QuestionID string      `json:"question_id" binding:"required"`
	Answer     interface{} `json:"answer" binding:"required"`
}

type GenerateTaskResponse struct {
	TaskID     string  `json:"task_id"`
	Status     string  `json:"status"`
	Progress   float64 `json:"progress"`
	ProjectID  string `json:"project_id"`
	TemplateID string `json:"template_id"`
}
