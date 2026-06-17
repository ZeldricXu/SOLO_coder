package planner

import (
	"fmt"
	"os"
	"strings"

	"github.com/hashicorp/hcl/v2"
	"github.com/hashicorp/hcl/v2/gohcl"
	"github.com/hashicorp/hcl/v2/hclparse"
	"github.com/multicloud/cli/internal/common"
	"github.com/zclconf/go-cty/cty"
)

type HCLConfig struct {
	Terraform *TerraformBlock `hcl:"terraform,block"`
	Provider  []ProviderBlock `hcl:"provider,block"`
	Resource  []ResourceBlock `hcl:"resource,block"`
	Variable  []VariableBlock `hcl:"variable,block"`
	Output    []OutputBlock   `hcl:"output,block"`
}

type TerraformBlock struct {
	RequiredVersion string        `hcl:"required_version,optional"`
	Backend         *BackendBlock `hcl:"backend,block"`
}

type BackendBlock struct {
	Type   string   `hcl:"type,label"`
	Config hcl.Body `hcl:",remain"`
}

type ProviderBlock struct {
	Name   string   `hcl:"name,label"`
	Region string   `hcl:"region,optional"`
	Config hcl.Body `hcl:",remain"`
}

type ResourceBlock struct {
	Type      string            `hcl:"type,label"`
	Name      string            `hcl:"name,label"`
	DependsOn []string          `hcl:"depends_on,optional"`
	Tags      map[string]string `hcl:"tags,optional"`
	Config    hcl.Body          `hcl:",remain"`
}

type VariableBlock struct {
	Name        string      `hcl:"name,label"`
	Type        string      `hcl:"type,optional"`
	Default     interface{} `hcl:"default,optional"`
	Description string      `hcl:"description,optional"`
	Sensitive   bool        `hcl:"sensitive,optional"`
}

type OutputBlock struct {
	Name  string      `hcl:"name,label"`
	Value interface{} `hcl:"value"`
}

type ParsedConfig struct {
	Resources []*common.ResourceConfig
	Variables map[string]interface{}
	Outputs   map[string]interface{}
	Providers map[string]ProviderConfig
	Backend   *BackendConfig
}

type ProviderConfig struct {
	Name   string
	Region string
	Config map[string]interface{}
}

type BackendConfig struct {
	Type   string
	Config map[string]interface{}
}

func ParseHCLFile(filePath string) (*ParsedConfig, error) {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return nil, common.NewError(common.ErrInvalidConfig, fmt.Sprintf("failed to read config file: %s", filePath), err)
	}

	return ParseHCL(data, filePath)
}

func ParseHCL(data []byte, filename string) (*ParsedConfig, error) {
	parser := hclparse.NewParser()
	file, diags := parser.ParseHCL(data, filename)
	if diags.HasErrors() {
		return nil, common.NewError(common.ErrInvalidConfig, fmt.Sprintf("HCL parse error: %s", diags.Error()))
	}

	var hclConfig HCLConfig
	diags = gohcl.DecodeBody(file.Body, nil, &hclConfig)
	if diags.HasErrors() {
		return nil, common.NewError(common.ErrInvalidConfig, fmt.Sprintf("HCL decode error: %s", diags.Error()))
	}

	parsed := &ParsedConfig{
		Resources: make([]*common.ResourceConfig, 0),
		Variables: make(map[string]interface{}),
		Outputs:   make(map[string]interface{}),
		Providers: make(map[string]ProviderConfig),
	}

	for _, v := range hclConfig.Variable {
		parsed.Variables[v.Name] = v.Default
	}

	for _, p := range hclConfig.Provider {
		configMap := hclBodyToMap(p.Config)
		parsed.Providers[p.Name] = ProviderConfig{
			Name:   p.Name,
			Region: p.Region,
			Config: configMap,
		}
	}

	if hclConfig.Terraform != nil && hclConfig.Terraform.Backend != nil {
		parsed.Backend = &BackendConfig{
			Type:   hclConfig.Terraform.Backend.Type,
			Config: hclBodyToMap(hclConfig.Terraform.Backend.Config),
		}
	}

	for _, r := range hclConfig.Resource {
		resourceConfig, err := parseResourceBlock(r, parsed.Providers)
		if err != nil {
			return nil, err
		}
		parsed.Resources = append(parsed.Resources, resourceConfig)
	}

	for _, o := range hclConfig.Output {
		parsed.Outputs[o.Name] = o.Value
	}

	return parsed, nil
}

func parseResourceBlock(rb ResourceBlock, providers map[string]ProviderConfig) (*common.ResourceConfig, error) {
	provider, region, err := extractProviderFromType(rb.Type, providers)
	if err != nil {
		return nil, err
	}

	resourceType := mapHCLResourceType(rb.Type)
	config := hclBodyToMap(rb.Config)

	rc := &common.ResourceConfig{
		Name:       rb.Name,
		Type:       resourceType,
		Provider:   provider,
		Region:     region,
		Properties: config,
		Tags:       rb.Tags,
		DependsOn:  rb.DependsOn,
	}

	return rc, nil
}

func extractProviderFromType(typeName string, providers map[string]ProviderConfig) (common.CloudProvider, string, error) {
	parts := strings.SplitN(typeName, "_", 2)
	if len(parts) < 2 {
		return "", "", common.NewError(common.ErrInvalidConfig, fmt.Sprintf("invalid resource type: %s", typeName))
	}

	providerPrefix := parts[0]
	var provider common.CloudProvider
	var defaultRegion string

	switch providerPrefix {
	case "aws":
		provider = common.ProviderAWS
	case "azurerm", "azure":
		provider = common.ProviderAzure
	case "google", "gcp":
		provider = common.ProviderGCP
	default:
		return "", "", common.NewError(common.ErrInvalidConfig, fmt.Sprintf("unsupported provider in resource type: %s", typeName))
	}

	if pc, ok := providers[string(provider)]; ok {
		defaultRegion = pc.Region
	}

	if defaultRegion == "" {
		defaultRegion = "us-east-1"
	}

	return provider, defaultRegion, nil
}

func mapHCLResourceType(typeName string) common.ResourceType {
	lower := strings.ToLower(typeName)
	switch {
	case strings.Contains(lower, "instance") || strings.Contains(lower, "compute") || strings.Contains(lower, "vm"):
		return common.ResourceCompute
	case strings.Contains(lower, "bucket") || strings.Contains(lower, "storage") || strings.Contains(lower, "blob"):
		return common.ResourceStorage
	case strings.Contains(lower, "network") || strings.Contains(lower, "vpc") || strings.Contains(lower, "subnet"):
		return common.ResourceNetwork
	case strings.Contains(lower, "database") || strings.Contains(lower, "sql") || strings.Contains(lower, "db"):
		return common.ResourceDatabase
	case strings.Contains(lower, "kubernetes") || strings.Contains(lower, "cluster") || strings.Contains(lower, "aks") || strings.Contains(lower, "eks") || strings.Contains(lower, "gke"):
		return common.ResourceKubernetes
	case strings.Contains(lower, "security") || strings.Contains(lower, "iam") || strings.Contains(lower, "firewall"):
		return common.ResourceSecurity
	default:
		return common.ResourceCompute
	}
}

func hclBodyToMap(body hcl.Body) map[string]interface{} {
	result := make(map[string]interface{})

	attrs, diags := body.JustAttributes()
	if diags.HasErrors() {
		return result
	}

	for name, attr := range attrs {
		val, diags := attr.Expr.Value(nil)
		if diags.HasErrors() {
			continue
		}
		result[name] = ctyValueToInterface(val)
	}

	return result
}

func ctyValueToInterface(val cty.Value) interface{} {
	if val.IsNull() {
		return nil
	}

	switch val.Type() {
	case cty.String:
		return val.AsString()
	case cty.Number:
		f := val.AsBigFloat()
		if i, accuracy := f.Int64(); accuracy == 0 {
			return i
		}
		f64, _ := f.Float64()
		return f64
	case cty.Bool:
		return val.True()
	case cty.List(cty.String):
		var result []string
		for _, v := range val.AsValueSlice() {
			result = append(result, v.AsString())
		}
		return result
	case cty.Map(cty.String):
		result := make(map[string]string)
		for k, v := range val.AsValueMap() {
			result[k] = v.AsString()
		}
		return result
	default:
		if val.Type().IsListType() || val.Type().IsTupleType() {
			var result []interface{}
			for _, v := range val.AsValueSlice() {
				result = append(result, ctyValueToInterface(v))
			}
			return result
		}
		if val.Type().IsMapType() || val.Type().IsObjectType() {
			result := make(map[string]interface{})
			for k, v := range val.AsValueMap() {
				result[k] = ctyValueToInterface(v)
			}
			return result
		}
		return val.AsString()
	}
}
