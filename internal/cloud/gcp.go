package cloud

import (
	"context"
	"fmt"
	"time"

	"github.com/multicloud/cli/internal/common"
)

type GCPProvider struct {
	cred      *common.Credential
	region    string
	projectID string
	resources map[string]*common.Resource
}

func NewGCPProvider(cred *common.Credential) (ResourceProvider, error) {
	if cred == nil {
		return nil, common.NewError(common.ErrInvalidConfig, "GCP credential is required")
	}
	if cred.ProjectID == "" {
		return nil, common.NewError(common.ErrInvalidConfig, "GCP project_id is required")
	}

	region := cred.Region
	if region == "" {
		region = common.GetEnv("GCP_REGION", "us-central1")
	}

	return &GCPProvider{
		cred:      cred,
		region:    region,
		projectID: cred.ProjectID,
		resources: make(map[string]*common.Resource),
	}, nil
}

func (p *GCPProvider) GetProvider() common.CloudProvider {
	return common.ProviderGCP
}

func (p *GCPProvider) Authenticate(ctx context.Context, cred *common.Credential) error {
	if cred.ProjectID == "" || cred.AccessKey == "" {
		return common.NewError(common.ErrUnauthorized, "invalid GCP credentials")
	}
	return nil
}

func (p *GCPProvider) ListResources(ctx context.Context, resourceType common.ResourceType, region string) ([]common.Resource, error) {
	if region == "" {
		region = p.region
	}

	var resources []common.Resource
	for _, r := range p.resources {
		if r.Type == resourceType && r.Region == region {
			resources = append(resources, *r)
		}
	}
	return resources, nil
}

func (p *GCPProvider) GetResource(ctx context.Context, resourceID string, resourceType common.ResourceType) (*common.Resource, error) {
	resource, exists := p.resources[resourceID]
	if !exists {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s not found", resourceID))
	}
	if resource.Type != resourceType {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s is not of type %s", resourceID, resourceType))
	}
	return resource, nil
}

func (p *GCPProvider) CreateResource(ctx context.Context, config *common.ResourceConfig) (*common.Resource, error) {
	if err := p.ValidateConfig(ctx, config); err != nil {
		return nil, err
	}

	resourceID := fmt.Sprintf("gcp-%s-%s", string(config.Type), common.GenerateID("res"))
	now := time.Now()

	resource := &common.Resource{
		ID:         resourceID,
		Name:       config.Name,
		Type:       config.Type,
		Provider:   common.ProviderGCP,
		Region:     config.Region,
		Properties: make(map[string]interface{}),
		Tags:       make(map[string]string),
		Status:     common.StatusRunning,
		CreatedAt:  now,
		UpdatedAt:  now,
	}

	for k, v := range config.Properties {
		resource.Properties[k] = v
	}
	for k, v := range config.Tags {
		resource.Tags[k] = v
	}
	resource.Tags["ManagedBy"] = "multicloud-cli"
	resource.Tags["CloudProvider"] = "gcp"
	resource.Tags["Project"] = p.projectID

	switch config.Type {
	case common.ResourceCompute:
		resource.Properties["machine_type"] = config.Properties["machine_type"]
		resource.Properties["image_family"] = config.Properties["image_family"]
		resource.Properties["external_ip"] = fmt.Sprintf("35.%d.%d.%d", time.Now().Unix()%256, time.Now().Unix()%256, time.Now().Unix()%256)
		resource.Properties["internal_ip"] = fmt.Sprintf("10.2.%d.%d", time.Now().Unix()%256, time.Now().Unix()%256)
	case common.ResourceStorage:
		resource.Properties["bucket_name"] = fmt.Sprintf("%s-%s-%s", common.SanitizeResourceName(config.Name), p.projectID, common.GenerateID("bucket")[:8])
		resource.Properties["location"] = config.Properties["location"]
		resource.Properties["storage_class"] = config.Properties["storage_class"]
	case common.ResourceKubernetes:
		resource.Properties["cluster_name"] = config.Name
		resource.Properties["node_count"] = config.Properties["node_count"]
		resource.Properties["initial_node_count"] = config.Properties["node_count"]
		resource.Properties["master_version"] = config.Properties["master_version"]
	}

	p.resources[resourceID] = resource
	return resource, nil
}

func (p *GCPProvider) UpdateResource(ctx context.Context, resourceID string, config *common.ResourceConfig) (*common.Resource, error) {
	resource, exists := p.resources[resourceID]
	if !exists {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s not found", resourceID))
	}

	if err := p.ValidateConfig(ctx, config); err != nil {
		return nil, err
	}

	resource.Status = common.StatusUpdating
	resource.UpdatedAt = time.Now()

	for k, v := range config.Properties {
		resource.Properties[k] = v
	}
	for k, v := range config.Tags {
		resource.Tags[k] = v
	}

	resource.Status = common.StatusRunning
	return resource, nil
}

func (p *GCPProvider) DeleteResource(ctx context.Context, resourceID string, resourceType common.ResourceType) error {
	resource, exists := p.resources[resourceID]
	if !exists {
		return common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s not found", resourceID))
	}
	if resource.Type != resourceType {
		return common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s is not of type %s", resourceID, resourceType))
	}

	resource.Status = common.StatusDeleting
	delete(p.resources, resourceID)
	resource.Status = common.StatusDeleted
	return nil
}

func (p *GCPProvider) ValidateConfig(ctx context.Context, config *common.ResourceConfig) error {
	if config.Name == "" {
		return common.NewError(common.ErrInvalidConfig, "resource name is required")
	}
	if config.Type == "" {
		return common.NewError(common.ErrInvalidConfig, "resource type is required")
	}
	if config.Region == "" {
		return common.NewError(common.ErrInvalidConfig, "region is required")
	}

	switch config.Type {
	case common.ResourceCompute:
		if _, ok := config.Properties["machine_type"]; !ok {
			return common.NewError(common.ErrInvalidConfig, "machine_type is required for compute resources")
		}
	case common.ResourceStorage:
		if _, ok := config.Properties["location"]; !ok {
			config.Properties["location"] = config.Region
		}
		if _, ok := config.Properties["storage_class"]; !ok {
			config.Properties["storage_class"] = "STANDARD"
		}
	case common.ResourceKubernetes:
		if _, ok := config.Properties["node_count"]; !ok {
			config.Properties["node_count"] = 3
		}
	}

	return nil
}

func (p *GCPProvider) HealthCheck(ctx context.Context) error {
	if p.cred == nil || p.cred.ProjectID == "" {
		return common.NewError(common.ErrUnauthorized, "GCP credentials not configured")
	}
	return nil
}

func init() {
	RegisterProvider(common.ProviderGCP, NewGCPProvider)
}
