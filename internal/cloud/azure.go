package cloud

import (
	"context"
	"fmt"
	"time"

	"github.com/multicloud/cli/internal/common"
)

type AzureProvider struct {
	cred      *common.Credential
	region    string
	resources map[string]*common.Resource
}

func NewAzureProvider(cred *common.Credential) (ResourceProvider, error) {
	if cred == nil {
		return nil, common.NewError(common.ErrInvalidConfig, "Azure credential is required")
	}
	if cred.TenantID == "" || cred.SubscriptionID == "" {
		return nil, common.NewError(common.ErrInvalidConfig, "Azure tenant_id and subscription_id are required")
	}

	region := cred.Region
	if region == "" {
		region = common.GetEnv("AZURE_REGION", "eastus")
	}

	return &AzureProvider{
		cred:      cred,
		region:    region,
		resources: make(map[string]*common.Resource),
	}, nil
}

func (p *AzureProvider) GetProvider() common.CloudProvider {
	return common.ProviderAzure
}

func (p *AzureProvider) Authenticate(ctx context.Context, cred *common.Credential) error {
	if cred.TenantID == "" || cred.SubscriptionID == "" || cred.ClientSecret == "" {
		return common.NewError(common.ErrUnauthorized, "invalid Azure credentials")
	}
	return nil
}

func (p *AzureProvider) ListResources(ctx context.Context, resourceType common.ResourceType, region string) ([]common.Resource, error) {
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

func (p *AzureProvider) GetResource(ctx context.Context, resourceID string, resourceType common.ResourceType) (*common.Resource, error) {
	resource, exists := p.resources[resourceID]
	if !exists {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s not found", resourceID))
	}
	if resource.Type != resourceType {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s is not of type %s", resourceID, resourceType))
	}
	return resource, nil
}

func (p *AzureProvider) CreateResource(ctx context.Context, config *common.ResourceConfig) (*common.Resource, error) {
	if err := p.ValidateConfig(ctx, config); err != nil {
		return nil, err
	}

	resourceID := fmt.Sprintf("azure-%s-%s", string(config.Type), common.GenerateID("res"))
	now := time.Now()

	resource := &common.Resource{
		ID:         resourceID,
		Name:       config.Name,
		Type:       config.Type,
		Provider:   common.ProviderAzure,
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
	resource.Tags["CloudProvider"] = "azure"

	switch config.Type {
	case common.ResourceCompute:
		resource.Properties["vm_size"] = config.Properties["vm_size"]
		resource.Properties["image_publisher"] = config.Properties["image_publisher"]
		resource.Properties["public_ip"] = fmt.Sprintf("20.%d.%d.%d", time.Now().Unix()%256, time.Now().Unix()%256, time.Now().Unix()%256)
		resource.Properties["private_ip"] = fmt.Sprintf("10.1.%d.%d", time.Now().Unix()%256, time.Now().Unix()%256)
	case common.ResourceStorage:
		resource.Properties["storage_account_name"] = fmt.Sprintf("%sstorage%s", common.SanitizeResourceName(config.Name), common.GenerateID("sa")[:8])
		resource.Properties["sku"] = config.Properties["sku"]
	case common.ResourceKubernetes:
		resource.Properties["cluster_name"] = config.Name
		resource.Properties["node_count"] = config.Properties["node_count"]
		resource.Properties["kubernetes_version"] = config.Properties["kubernetes_version"]
	}

	p.resources[resourceID] = resource
	return resource, nil
}

func (p *AzureProvider) UpdateResource(ctx context.Context, resourceID string, config *common.ResourceConfig) (*common.Resource, error) {
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

func (p *AzureProvider) DeleteResource(ctx context.Context, resourceID string, resourceType common.ResourceType) error {
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

func (p *AzureProvider) ValidateConfig(ctx context.Context, config *common.ResourceConfig) error {
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
		if _, ok := config.Properties["vm_size"]; !ok {
			return common.NewError(common.ErrInvalidConfig, "vm_size is required for compute resources")
		}
	case common.ResourceStorage:
		if _, ok := config.Properties["sku"]; !ok {
			config.Properties["sku"] = "Standard_LRS"
		}
	case common.ResourceKubernetes:
		if _, ok := config.Properties["node_count"]; !ok {
			config.Properties["node_count"] = 3
		}
	}

	return nil
}

func (p *AzureProvider) HealthCheck(ctx context.Context) error {
	if p.cred == nil || p.cred.TenantID == "" || p.cred.SubscriptionID == "" {
		return common.NewError(common.ErrUnauthorized, "Azure credentials not configured")
	}
	return nil
}

func init() {
	RegisterProvider(common.ProviderAzure, NewAzureProvider)
}
