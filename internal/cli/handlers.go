package cli

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/multicloud/cli/internal/cloud"
	"github.com/multicloud/cli/internal/common"
	"github.com/multicloud/cli/internal/compliance"
	"github.com/multicloud/cli/internal/db"
	"github.com/multicloud/cli/internal/deploy"
	"github.com/multicloud/cli/internal/diff"
	"github.com/multicloud/cli/internal/planner"
	"github.com/multicloud/cli/internal/state"
	"github.com/multicloud/cli/internal/vault"
)

const (
	version = "1.0.0"
)

func (c *CLI) initVault() (*vault.Vault, error) {
	v, err := vault.NewVault(c.vaultFile)
	if err != nil {
		return nil, err
	}
	if err := v.Load(); err != nil {
		return nil, err
	}
	return v, nil
}

func (c *CLI) initStateManager() (*state.StateManager, error) {
	backend, err := state.NewLocalBackend(c.stateFile)
	if err != nil {
		return nil, err
	}
	sm := state.NewStateManager(backend)
	if _, err := sm.Load(); err != nil {
		return nil, err
	}
	return sm, nil
}

func (c *CLI) initDatabase() (*db.Database, error) {
	dbPath := filepath.Join(c.configDir, "multicloud.db")
	return db.NewDatabase(dbPath)
}

func (c *CLI) initProviders(v *vault.Vault) error {
	cloud.RegisterProvider(common.ProviderAWS, cloud.NewAWSProvider)
	cloud.RegisterProvider(common.ProviderAzure, cloud.NewAzureProvider)
	cloud.RegisterProvider(common.ProviderGCP, cloud.NewGCPProvider)
	return nil
}

func (c *CLI) getProviderFactory(v *vault.Vault) func(common.CloudProvider) (cloud.ResourceProvider, error) {
	return func(provider common.CloudProvider) (cloud.ResourceProvider, error) {
		cred, err := v.GetCredential(provider)
		if err != nil {
			return nil, err
		}
		return cloud.NewProvider(provider, cred)
	}
}

func (c *CLI) loadConfig() ([]*common.ResourceConfig, error) {
	if _, err := os.Stat(c.configFile); os.IsNotExist(err) {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("config file %s not found", c.configFile))
	}
	parsed, err := planner.ParseHCLFile(c.configFile)
	if err != nil {
		return nil, err
	}
	return parsed.Resources, nil
}

func (c *CLI) runInit() error {
	c.println("Initializing multi-cloud project...")

	if err := common.EnsureDir(c.configDir); err != nil {
		return err
	}

	v, err := c.initVault()
	if err != nil {
		return err
	}

	if err := v.Save(); err != nil {
		return err
	}

	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	if err := sm.Save("Initial state"); err != nil {
		return err
	}

	if _, err := c.initDatabase(); err != nil {
		return err
	}

	if err := c.initProviders(v); err != nil {
		return err
	}

	c.println("✓ Project initialized successfully")
	c.println("  Config directory: %s", c.configDir)
	c.println("  State file: %s", c.stateFile)
	c.println("  Vault file: %s", c.vaultFile)
	c.println("\nNext steps:")
	c.println("  1. Add credentials: multicloud credentials add <provider>")
	c.println("  2. Edit configuration: %s", c.configFile)
	c.println("  3. Generate plan: multicloud plan")
	c.println("  4. Apply changes: multicloud apply")

	return nil
}

func (c *CLI) runPlan(detailed bool, outFile string) error {
	c.verbosePrintln("Loading configuration from %s", c.configFile)

	configs, err := c.loadConfig()
	if err != nil {
		return err
	}

	c.verbosePrintln("Loaded %d resources from configuration", len(configs))

	v, err := c.initVault()
	if err != nil {
		return err
	}

	if err := c.initProviders(v); err != nil {
		return err
	}

	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	currentState := sm.GetState().GetResourcesMap()
	c.verbosePrintln("Current state has %d resources", len(currentState))

	graph, err := planner.BuildResourceGraph(configs)
	if err != nil {
		return err
	}

	c.verbosePrintln("Validating resource graph...")
	if err := graph.Validate(); err != nil {
		return err
	}

	c.verbosePrintln("Building execution plan...")
	plan, err := planner.BuildPlan(graph, currentState)
	if err != nil {
		return err
	}

	skipCompliance, _ := c.rootCmd.Flags().GetBool("skip-compliance")
	if !skipCompliance {
		c.verbosePrintln("Running compliance checks...")
		scanner := compliance.NewComplianceScanner()
		result := scanner.Scan(configs, "cis", compliance.SeverityMedium)
		if result.Failed > 0 {
			formatter := compliance.FormatResult(result, !c.noColor)
			c.print("%s", formatter)
			c.println("\n⚠️  Compliance issues found. Use --skip-compliance to bypass.")
		} else {
			c.println("✓ All compliance checks passed")
		}
	}

	formatter := diff.NewDiffFormatter()
	formatter.SetUseColor(!c.noColor)

	if detailed {
		formatter.PrintChanges(plan.Changes)
	} else {
		formatter.PrintPlan(plan)
	}

	if outFile != "" {
		data, err := json.MarshalIndent(plan, "", "  ")
		if err != nil {
			return err
		}
		if err := os.WriteFile(outFile, data, 0644); err != nil {
			return err
		}
		c.println("Plan saved to: %s", outFile)
	}

	if !plan.HasChanges() {
		c.println("No changes needed. Infrastructure is up-to-date.")
	}

	return nil
}

func (c *CLI) runApply(planFile string, parallel int, rollback bool) error {
	var plan *planner.Plan
	var configs []*common.ResourceConfig

	if planFile != "" {
		c.verbosePrintln("Loading plan from %s", planFile)
		data, err := os.ReadFile(planFile)
		if err != nil {
			return err
		}
		if err := json.Unmarshal(data, &plan); err != nil {
			return err
		}
	} else {
		c.verbosePrintln("Loading configuration from %s", c.configFile)
		var err error
		configs, err = c.loadConfig()
		if err != nil {
			return err
		}

		v, err := c.initVault()
		if err != nil {
			return err
		}

		if err := c.initProviders(v); err != nil {
			return err
		}

		sm, err := c.initStateManager()
		if err != nil {
			return err
		}

		currentState := sm.GetState().GetResourcesMap()

		graph, err := planner.BuildResourceGraph(configs)
		if err != nil {
			return err
		}

		if err := graph.Validate(); err != nil {
			return err
		}

		plan, err = planner.BuildPlan(graph, currentState)
		if err != nil {
			return err
		}
	}

	if !plan.HasChanges() {
		c.println("No changes to apply.")
		return nil
	}

	formatter := diff.NewDiffFormatter()
	formatter.SetUseColor(!c.noColor)
	formatter.PrintPlan(plan)

	if !c.autoApprove {
		if !promptConfirmation("Do you want to apply these changes?") {
			c.println("Apply cancelled.")
			return nil
		}
	}

	v, err := c.initVault()
	if err != nil {
		return err
	}

	if err := c.initProviders(v); err != nil {
		return err
	}

	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	database, err := c.initDatabase()
	if err != nil {
		return err
	}
	defer database.Close()

	c.verbosePrintln("Acquiring state lock...")
	lock, err := sm.Lock("apply")
	if err != nil {
		return err
	}
	defer sm.Unlock(lock.ID)

	providerFactory := c.getProviderFactory(v)

	for _, provider := range v.ListCredentials() {
		prov, err := providerFactory(provider)
		if err != nil {
			c.verbosePrintln("Warning: failed to authenticate %s: %v", provider, err)
			continue
		}
		if err := prov.Authenticate(c.ctx, nil); err != nil {
			c.verbosePrintln("Warning: failed to authenticate %s: %v", provider, err)
		}
	}

	pb := deploy.NewProgressBar(len(plan.Changes))
	pb.SetUseColor(!c.noColor)

	options := deploy.DeployOptions{
		MaxParallel:     parallel,
		EnableRollback:  rollback,
		ProviderFactory: providerFactory,
		ProgressCallback: func(p *deploy.DeployProgress) {
			fmt.Print(pb.Update(p.Completed, p.Current))
		},
	}

	deployer := deploy.NewDeployer(options)

	c.println("\nApplying changes...")
	fmt.Print(pb.Update(0, "starting"))

	results, err := deployer.Execute(c.ctx, plan)
	fmt.Print(pb.Finish())

	if err != nil {
		c.println("Error during deployment: %v", err)
	}

	resultStr := deploy.PrintResults(results, !c.noColor)
	c.print("%s", resultStr)

	successCount := 0
	for _, r := range results {
		if r.Status == deploy.StatusSuccess && r.Resource != nil {
			sm.SetResource(r.ResourceName, r.Resource)
			database.SaveResource(r.Resource)
			successCount++

			auditLog := &common.AuditLog{
				User:     os.Getenv("USER"),
				Action:   string(r.Action),
				Resource: r.ResourceName,
				Provider: r.Resource.Provider,
				Status:   "success",
				Message:  fmt.Sprintf("Resource %s successfully", r.Action),
			}
			database.AddAuditLog(auditLog)
		} else if r.Status == deploy.StatusFailed {
			auditLog := &common.AuditLog{
				User:     os.Getenv("USER"),
				Action:   string(r.Action),
				Resource: r.ResourceName,
				Provider: common.CloudProvider(""),
				Status:   "failed",
				Message:  r.Error.Error(),
			}
			database.AddAuditLog(auditLog)
		}
	}

	if successCount > 0 {
		if err := sm.Save(fmt.Sprintf("Applied %d changes", successCount)); err != nil {
			c.println("Warning: failed to save state: %v", err)
		}

		stateData, _ := json.Marshal(sm.GetState())
		database.SaveStateCache(sm.GetState().Serial, sm.GetState().Lineage, stateData)
	}

	needsRotation := v.CheckRotation()
	if len(needsRotation) > 0 {
		c.println("\n⚠️  Credentials need rotation:")
		for _, p := range needsRotation {
			c.println("  - %s", p)
		}
	}

	return nil
}

func (c *CLI) runDestroy(force bool, targets []string) error {
	v, err := c.initVault()
	if err != nil {
		return err
	}

	if err := c.initProviders(v); err != nil {
		return err
	}

	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	currentState := sm.GetState().GetResourcesMap()
	if len(currentState) == 0 {
		c.println("No resources to destroy.")
		return nil
	}

	resourcesToDestroy := make(map[string]*common.Resource)
	if len(targets) > 0 {
		for _, t := range targets {
			if r, exists := currentState[t]; exists {
				resourcesToDestroy[t] = r
			} else {
				c.println("Warning: resource %s not found in state", t)
			}
		}
	} else {
		resourcesToDestroy = currentState
	}

	if len(resourcesToDestroy) == 0 {
		c.println("No matching resources to destroy.")
		return nil
	}

	c.println("\nResources to destroy:")
	for name := range resourcesToDestroy {
		c.println("  - %s", name)
	}

	if !c.autoApprove && !force {
		if !promptConfirmation("Do you REALLY want to destroy these resources? This action cannot be undone.") {
			c.println("Destroy cancelled.")
			return nil
		}
	}

	c.verbosePrintln("Acquiring state lock...")
	lock, err := sm.Lock("destroy")
	if err != nil {
		return err
	}
	defer sm.Unlock(lock.ID)

	database, err := c.initDatabase()
	if err != nil {
		return err
	}
	defer database.Close()

	providerFactory := c.getProviderFactory(v)

	names := make([]string, 0, len(resourcesToDestroy))
	for name := range resourcesToDestroy {
		names = append(names, name)
	}
	sort.Sort(sort.Reverse(sort.StringSlice(names)))

	successCount := 0
	failedCount := 0

	for _, name := range names {
		r := resourcesToDestroy[name]
		c.print("Destroying %s... ", name)

		prov, err := providerFactory(r.Provider)
		if err != nil {
			c.println("failed: %v", err)
			failedCount++
			continue
		}

		if err := prov.Authenticate(c.ctx, nil); err != nil {
			c.verbosePrintln("Warning: auth failed, proceeding with state cleanup")
		}

		err = prov.DeleteResource(c.ctx, r.ID, r.Type)
		if err != nil {
			c.println("failed: %v", err)
			failedCount++
		} else {
			c.println("done")
			sm.RemoveResource(name)
			database.DeleteResource(r.ID)
			successCount++
		}

		auditLog := &common.AuditLog{
			User:     os.Getenv("USER"),
			Action:   "destroy",
			Resource: name,
			Provider: r.Provider,
			Status:   "success",
			Message:  "Resource destroyed",
		}
		if err != nil {
			auditLog.Status = "failed"
			auditLog.Message = err.Error()
		}
		database.AddAuditLog(auditLog)
	}

	if successCount > 0 {
		sm.Save(fmt.Sprintf("Destroyed %d resources", successCount))
	}

	c.println("\nDestroy complete: %d succeeded, %d failed", successCount, failedCount)

	return nil
}

func (c *CLI) runCredAdd(provider common.CloudProvider, accessKey, secretKey, sessionToken, tenantID, subscriptionID, projectID, region string) error {
	v, err := c.initVault()
	if err != nil {
		return err
	}

	cred := &common.Credential{
		Provider:       provider,
		AccessKey:      accessKey,
		SecretKey:      secretKey,
		SessionToken:   sessionToken,
		TenantID:       tenantID,
		SubscriptionID: subscriptionID,
		ProjectID:      projectID,
		Region:         region,
	}

	if err := v.SetCredential(provider, cred); err != nil {
		return err
	}

	if err := v.Save(); err != nil {
		return err
	}

	c.println("✓ Credentials for %s added successfully", provider)

	database, err := c.initDatabase()
	if err != nil {
		return err
	}
	defer database.Close()

	auditLog := &common.AuditLog{
		User:     os.Getenv("USER"),
		Action:   "credentials_add",
		Resource: string(provider),
		Provider: provider,
		Status:   "success",
		Message:  "Credentials added",
	}
	database.AddAuditLog(auditLog)

	return nil
}

func (c *CLI) runCredList() error {
	v, err := c.initVault()
	if err != nil {
		return err
	}

	providers := v.ListCredentials()
	if len(providers) == 0 {
		c.println("No credentials configured.")
		return nil
	}

	c.println("Configured credentials:")
	for _, p := range providers {
		cred, err := v.GetCredential(p)
		if err != nil {
			c.println("  %s: error - %v", p, err)
			continue
		}

		metadata, _ := v.GetMetadata(p)
		status := "✓"
		if cred.ExpiresAt != nil && cred.ExpiresAt.Before(time.Now()) {
			status = "✗ (expired)"
		}

		c.println("  %s %s", status, p)
		c.println("    Region: %s", cred.Region)
		if metadata != nil {
			c.println("    Created: %s", metadata.CreatedAt.Format("2006-01-02"))
			if !metadata.NextRotationAt.IsZero() {
				c.println("    Next rotation: %s", metadata.NextRotationAt.Format("2006-01-02"))
			}
		}
		if cred.AccessKey != "" {
			masked := cred.AccessKey
			if len(masked) > 8 {
				masked = masked[:4] + "..." + masked[len(masked)-4:]
			}
			c.println("    Access Key: %s", masked)
		}
	}

	validationResults := v.ValidateCredentials()
	for p, err := range validationResults {
		if err != nil {
			c.println("\n  ⚠️  %s: %v", p, err)
		}
	}

	return nil
}

func (c *CLI) runCredRemove(provider common.CloudProvider) error {
	v, err := c.initVault()
	if err != nil {
		return err
	}

	if !promptConfirmation(fmt.Sprintf("Are you sure you want to remove credentials for %s?", provider)) {
		c.println("Remove cancelled.")
		return nil
	}

	if err := v.DeleteCredential(provider); err != nil {
		return err
	}

	if err := v.Save(); err != nil {
		return err
	}

	c.println("✓ Credentials for %s removed successfully", provider)

	database, err := c.initDatabase()
	if err != nil {
		return err
	}
	defer database.Close()

	auditLog := &common.AuditLog{
		User:     os.Getenv("USER"),
		Action:   "credentials_remove",
		Resource: string(provider),
		Provider: provider,
		Status:   "success",
		Message:  "Credentials removed",
	}
	database.AddAuditLog(auditLog)

	return nil
}

func (c *CLI) runCredRotate(provider common.CloudProvider) error {
	v, err := c.initVault()
	if err != nil {
		return err
	}

	_, err = v.GetCredential(provider)
	if err != nil {
		return err
	}

	c.println("Rotating credentials for %s...", provider)
	c.println("Please provide new credentials:")

	var accessKey, secretKey string
	c.print("  New Access Key: ")
	fmt.Scanln(&accessKey)
	c.print("  New Secret Key: ")
	fmt.Scanln(&secretKey)

	newCred := &common.Credential{
		Provider:  provider,
		AccessKey: accessKey,
		SecretKey: secretKey,
	}

	if err := v.RotateCredential(provider, newCred); err != nil {
		return err
	}

	if err := v.Save(); err != nil {
		return err
	}

	c.println("✓ Credentials for %s rotated successfully", provider)

	database, err := c.initDatabase()
	if err != nil {
		return err
	}
	defer database.Close()

	auditLog := &common.AuditLog{
		User:     os.Getenv("USER"),
		Action:   "credentials_rotate",
		Resource: string(provider),
		Provider: provider,
		Status:   "success",
		Message:  "Credentials rotated",
	}
	database.AddAuditLog(auditLog)

	return nil
}

func (c *CLI) runCredExport(provider common.CloudProvider) error {
	v, err := c.initVault()
	if err != nil {
		return err
	}

	envVars, err := v.Export(provider)
	if err != nil {
		return err
	}

	c.println("# Environment variables for %s", provider)
	keys := make([]string, 0, len(envVars))
	for k := range envVars {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		c.println("export %s=%s", k, envVars[k])
	}

	return nil
}

func (c *CLI) runCredCheck() error {
	v, err := c.initVault()
	if err != nil {
		return err
	}

	c.println("Checking credentials...")

	validationResults := v.ValidateCredentials()
	allValid := true

	for provider, err := range validationResults {
		if err != nil {
			c.println("  ✗ %s: %v", provider, err)
			allValid = false
		} else {
			c.println("  ✓ %s: valid", provider)
		}
	}

	needsRotation := v.CheckRotation()
	if len(needsRotation) > 0 {
		c.println("\n⚠️  Credentials needing rotation soon (%d days):", v.GetRotationAlertDays())
		for _, p := range needsRotation {
			c.println("  - %s", p)
		}
	}

	if allValid && len(needsRotation) == 0 {
		c.println("\n✓ All credentials are valid and up-to-date")
	}

	return nil
}

func (c *CLI) runStateList() error {
	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	state := sm.GetState()
	resources := state.GetResourcesMap()

	if len(resources) == 0 {
		c.println("No resources in state.")
		return nil
	}

	c.println("Resources in state:")
	names := make([]string, 0, len(resources))
	for name := range resources {
		names = append(names, name)
	}
	sort.Strings(names)

	for _, name := range names {
		r := resources[name]
		c.println("  %s", name)
		c.println("    ID: %s", r.ID)
		c.println("    Type: %s", r.Type)
		c.println("    Provider: %s", r.Provider)
		c.println("    Region: %s", r.Region)
		c.println("    Status: %s", r.Status)
	}

	c.println("\nState metadata:")
	c.println("  Version: %d", state.Version)
	c.println("  Serial: %d", state.Serial)
	c.println("  Lineage: %s", state.Lineage)
	c.println("  Last updated: %s", state.UpdatedAt.Format(time.RFC3339))

	return nil
}

func (c *CLI) runStateShow(resourceName string) error {
	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	r, exists := sm.GetResource(resourceName)
	if !exists {
		return common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s not found in state", resourceName))
	}

	data, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return err
	}

	c.println("%s", string(data))

	return nil
}

func (c *CLI) runStateRm(resourceName string, force bool) error {
	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	_, exists := sm.GetResource(resourceName)
	if !exists {
		return common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s not found in state", resourceName))
	}

	if !force && !promptConfirmation(fmt.Sprintf("Remove %s from state? (This does NOT destroy the actual resource)", resourceName)) {
		c.println("Remove cancelled.")
		return nil
	}

	sm.RemoveResource(resourceName)
	if err := sm.Save(fmt.Sprintf("Removed %s from state", resourceName)); err != nil {
		return err
	}

	c.println("✓ %s removed from state", resourceName)

	return nil
}

func (c *CLI) runStateMv(source, dest string) error {
	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	r, exists := sm.GetResource(source)
	if !exists {
		return common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s not found in state", source))
	}

	if _, exists := sm.GetResource(dest); exists {
		return common.NewError(common.ErrInvalidConfig, fmt.Sprintf("resource %s already exists in state", dest))
	}

	r.Name = dest
	sm.SetResource(dest, r)
	sm.RemoveResource(source)

	if err := sm.Save(fmt.Sprintf("Moved %s to %s", source, dest)); err != nil {
		return err
	}

	c.println("✓ Moved %s to %s", source, dest)

	return nil
}

func (c *CLI) runStatePull() error {
	c.println("Pulling remote state...")

	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	_, err = sm.Load()
	if err != nil {
		return err
	}

	c.println("✓ State pulled successfully")

	resources := sm.GetState().GetResourcesMap()
	c.println("  %d resources in state", len(resources))

	return nil
}

func (c *CLI) runStatePush(force bool) error {
	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	currentState := sm.GetState()
	resources := currentState.GetResourcesMap()

	c.println("Pushing local state (%d resources)...", len(resources))

	if err := sm.Save("Manual state push"); err != nil {
		return err
	}

	c.println("✓ State pushed successfully")

	return nil
}

func (c *CLI) runStateUnlock(lockID string, force bool) error {
	sm, err := c.initStateManager()
	if err != nil {
		return err
	}

	state := sm.GetState()
	if state.LockInfo == nil {
		c.println("State is not locked.")
		return nil
	}

	c.println("Current lock:")
	c.println("  ID: %s", state.LockInfo.ID)
	c.println("  Operation: %s", state.LockInfo.Operation)
	c.println("  Who: %s", state.LockInfo.Who)
	c.println("  Created: %s", state.LockInfo.CreatedAt.Format(time.RFC3339))

	if state.LockInfo.ID != lockID && !force {
		return common.NewError(common.ErrStateLocked, "lock ID mismatch. Use --force to override.")
	}

	if !force && !promptConfirmation("Force unlock the state? This may cause corruption if another operation is in progress.") {
		c.println("Unlock cancelled.")
		return nil
	}

	if err := sm.Unlock(lockID); err != nil {
		return err
	}

	c.println("✓ State unlocked successfully")

	return nil
}

func (c *CLI) runCompliance(framework, severity string) error {
	configs, err := c.loadConfig()
	if err != nil {
		return err
	}

	scanner := compliance.NewComplianceScanner()

	customRules, _ := c.rootCmd.Flags().GetString("rules")
	if customRules != "" {
		c.verbosePrintln("Loading custom rules from %s", customRules)
		if err := scanner.LoadRulesFromFile(customRules); err != nil {
			return err
		}
	}

	minSeverity := compliance.Severity(severity)
	result := scanner.Scan(configs, framework, minSeverity)

	formatter := compliance.FormatResult(result, !c.noColor)
	c.print("%s", formatter)

	autoFix, _ := c.rootCmd.Flags().GetBool("auto-fix")
	if autoFix && len(result.Violations) > 0 {
		c.println("\nAttempting auto-fix...")
		fixed, err := scanner.AutoFix(configs, result.Violations)
		if err != nil {
			return err
		}
		c.println("✓ Fixed %d violations", fixed)

		if fixed > 0 {
			data, _ := json.MarshalIndent(configs, "", "  ")
			backupFile := c.configFile + ".bak-" + time.Now().Format("20060102-150405")
			os.WriteFile(backupFile, data, 0644)
			c.println("  Backup saved to: %s", backupFile)
		}
	}

	if result.Failed > 0 {
		return fmt.Errorf("compliance scan failed with %d violations", result.Failed)
	}

	return nil
}

func (c *CLI) runAuditLog(limit int, action, provider string) error {
	database, err := c.initDatabase()
	if err != nil {
		return err
	}
	defer database.Close()

	logs, err := database.ListAuditLogs(limit, action, common.CloudProvider(provider))
	if err != nil {
		return err
	}

	if len(logs) == 0 {
		c.println("No audit logs found.")
		return nil
	}

	c.println("Audit Logs:")
	c.println("%-30s %-15s %-20s %-10s %s", "TIMESTAMP", "ACTION", "RESOURCE", "STATUS", "USER")
	c.println("%s", "─")

	for _, log := range logs {
		c.println("%-30s %-15s %-20s %-10s %s",
			log.Timestamp.Format("2006-01-02 15:04:05"),
			log.Action,
			log.Resource,
			log.Status,
			log.User,
		)
		if c.verbose && log.Message != "" {
			c.println("  Message: %s", log.Message)
		}
	}

	return nil
}

func (c *CLI) runAuditExport(outputFile, format string) error {
	database, err := c.initDatabase()
	if err != nil {
		return err
	}
	defer database.Close()

	logs, err := database.ListAuditLogs(0, "", "")
	if err != nil {
		return err
	}

	var data []byte
	switch format {
	case "json":
		data, err = json.MarshalIndent(logs, "", "  ")
		if err != nil {
			return err
		}
	case "csv":
		csv := "TIMESTAMP,ACTION,RESOURCE,PROVIDER,STATUS,USER,MESSAGE\n"
		for _, log := range logs {
			csv += fmt.Sprintf("%s,%s,%s,%s,%s,%s,\"%s\"\n",
				log.Timestamp.Format(time.RFC3339),
				log.Action,
				log.Resource,
				log.Provider,
				log.Status,
				log.User,
				log.Message,
			)
		}
		data = []byte(csv)
	default:
		return common.NewError(common.ErrInvalidConfig, fmt.Sprintf("unsupported format: %s", format))
	}

	if err := os.WriteFile(outputFile, data, 0644); err != nil {
		return err
	}

	c.println("✓ Audit logs exported to %s (%d entries)", outputFile, len(logs))

	return nil
}

func (c *CLI) runVersion() error {
	c.println("multicloud v%s", version)
	c.println("Multi-Cloud Infrastructure Management CLI")
	c.println("\nSupported providers:")
	c.println("  - AWS (EC2, S3, EKS)")
	c.println("  - Azure (VM, Storage, AKS)")
	c.println("  - GCP (GCE, GCS, GKE)")
	c.println("\nFeatures:")
	c.println("  - Declarative HCL configuration")
	c.println("  - Resource dependency graph")
	c.println("  - State management (local/S3)")
	c.println("  - Compliance scanning (CIS, 等保)")
	c.println("  - Encrypted credential vault")
	c.println("  - Parallel deployment with rollback")
	c.println("  - SQLite audit logging")

	return nil
}
