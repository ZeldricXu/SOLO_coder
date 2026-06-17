package cloud

import (
	"context"

	"github.com/multicloud/cli/internal/common"
)

type ResourceProvider interface {
	GetProvider() common.CloudProvider

	Authenticate(ctx context.Context, cred *common.Credential) error

	ListResources(ctx context.Context, resourceType common.ResourceType, region string) ([]common.Resource, error)

	GetResource(ctx context.Context, resourceID string, resourceType common.ResourceType) (*common.Resource, error)

	CreateResource(ctx context.Context, config *common.ResourceConfig) (*common.Resource, error)

	UpdateResource(ctx context.Context, resourceID string, config *common.ResourceConfig) (*common.Resource, error)

	DeleteResource(ctx context.Context, resourceID string, resourceType common.ResourceType) error

	ValidateConfig(ctx context.Context, config *common.ResourceConfig) error

	HealthCheck(ctx context.Context) error
}

type ProviderFactory func(*common.Credential) (ResourceProvider, error)

var providerFactories = make(map[common.CloudProvider]ProviderFactory)

func RegisterProvider(provider common.CloudProvider, factory ProviderFactory) {
	providerFactories[provider] = factory
}

func NewProvider(provider common.CloudProvider, cred *common.Credential) (ResourceProvider, error) {
	factory, exists := providerFactories[provider]
	if !exists {
		return nil, common.NewError(common.ErrNotFound, "unsupported cloud provider")
	}
	return factory(cred)
}

func GetSupportedProviders() []common.CloudProvider {
	providers := make([]common.CloudProvider, 0, len(providerFactories))
	for p := range providerFactories {
		providers = append(providers, p)
	}
	return providers
}
