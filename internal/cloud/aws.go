package cloud

import (
	"context"
	"fmt"
	"time"

	"github.com/multicloud/cli/internal/common"
)

type AWSProvider struct {
	cred      *common.Credential
	region    string
	resources map[string]*common.Resource
}

func NewAWSProvider(cred *common.Credential) (ResourceProvider, error) {
	if cred == nil {
		return nil, common.NewError(common.ErrInvalidConfig, "AWS credential is required")
	}
	if cred.AccessKey == "" {
		return nil, common.NewError(common.ErrInvalidConfig, "AWS access key is required")
	}

	region := cred.Region
	if region == "" {
		region = common.GetEnv("AWS_DEFAULT_REGION", "us-east-1")
	}

	return &AWSProvider{
		cred:      cred,
		region:    region,
		resources: make(map[string]*common.Resource),
	}, nil
}

func (p *AWSProvider) GetProvider() common.CloudProvider {
	return common.ProviderAWS
}

func (p *AWSProvider) Authenticate(ctx context.Context, cred *common.Credential) error {
	if cred.AccessKey == "" || cred.SecretKey == "" {
		return common.NewError(common.ErrUnauthorized, "invalid AWS credentials")
	}
	return nil
}

func (p *AWSProvider) ListResources(ctx context.Context, resourceType common.ResourceType, region string) ([]common.Resource, error) {
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

func (p *AWSProvider) GetResource(ctx context.Context, resourceID string, resourceType common.ResourceType) (*common.Resource, error) {
	resource, exists := p.resources[resourceID]
	if !exists {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s not found", resourceID))
	}
	if resource.Type != resourceType {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s is not of type %s", resourceID, resourceType))
	}
	return resource, nil
}

func (p *AWSProvider) CreateResource(ctx context.Context, config *common.ResourceConfig) (*common.Resource, error) {
	if err := p.ValidateConfig(ctx, config); err != nil {
		return nil, err
	}

	resourceID := fmt.Sprintf("aws-%s-%s", string(config.Type), common.GenerateID("res"))
	now := time.Now()

	resource := &common.Resource{
		ID:         resourceID,
		Name:       config.Name,
		Type:       config.Type,
		Provider:   common.ProviderAWS,
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
	resource.Tags["CloudProvider"] = "aws"

	switch config.Type {
	case common.ResourceCompute:
		resource.Properties["instance_type"] = config.Properties["instance_type"]
		resource.Properties["ami"] = config.Properties["ami"]
		resource.Properties["public_ip"] = fmt.Sprintf("34.%d.%d.%d", time.Now().Unix()%256, time.Now().Unix()%256, time.Now().Unix()%256)
		resource.Properties["private_ip"] = fmt.Sprintf("10.0.%d.%d", time.Now().Unix()%256, time.Now().Unix()%256)
	case common.ResourceStorage:
		resource.Properties["bucket_name"] = fmt.Sprintf("%s-%s", config.Name, common.GenerateID("bucket"))
		resource.Properties["storage_class"] = config.Properties["storage_class"]
	case common.ResourceKubernetes:
		resource.Properties["cluster_name"] = config.Name
		resource.Properties["node_count"] = config.Properties["node_count"]
		resource.Properties["version"] = config.Properties["version"]
	}

	p.resources[resourceID] = resource
	return resource, nil
}

func (p *AWSProvider) UpdateResource(ctx context.Context, resourceID string, config *common.ResourceConfig) (*common.Resource, error) {
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

func (p *AWSProvider) DeleteResource(ctx context.Context, resourceID string, resourceType common.ResourceType) error {
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

func (p *AWSProvider) ValidateConfig(ctx context.Context, config *common.ResourceConfig) error {
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
		if _, ok := config.Properties["instance_type"]; !ok {
			return common.NewError(common.ErrInvalidConfig, "instance_type is required for compute resources")
		}
	case common.ResourceStorage:
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

func (p *AWSProvider) HealthCheck(ctx context.Context) error {
	if p.cred == nil || p.cred.AccessKey == "" {
		return common.NewError(common.ErrUnauthorized, "AWS credentials not configured")
	}
	return nil
}

func init() {
	RegisterProvider(common.ProviderAWS, NewAWSProvider)
}
