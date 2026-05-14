package cli

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"text/tabwriter"
	"time"

	"backupmanager/internal/backup"
	"backupmanager/internal/logger"
	"backupmanager/internal/restore"
	"backupmanager/internal/scheduler"
	"backupmanager/internal/storage"
	"backupmanager/internal/verify"
	"backupmanager/internal/version"
	"backupmanager/pkg/models"
)

type CLI struct {
	storage    *storage.Storage
	logger     *logger.Logger
	versionMgr *version.Manager
	verifier   *verify.Verifier
	backupEng  *backup.Engine
	restoreEng *restore.Engine
	scheduler  *scheduler.Scheduler
	configPath string
}

func NewCLI(storageRoot string) (*CLI, error) {
	absRoot, err := filepath.Abs(storageRoot)
	if err != nil {
		return nil, fmt.Errorf("failed to get absolute path: %w", err)
	}

	logPath := filepath.Join(absRoot, "logs", "backupmanager.log")
	log := logger.NewLogger(logPath)
	if err := log.Init(); err != nil {
		return nil, fmt.Errorf("failed to initialize logger: %w", err)
	}

	store := storage.NewStorage(absRoot)
	if err := store.Init(); err != nil {
		return nil, fmt.Errorf("failed to initialize storage: %w", err)
	}

	configPath := filepath.Join(absRoot, "config.json")
	appConfig, err := loadAppConfig(configPath)
	if err != nil {
		log.Warn("Failed to load app config, using defaults: %v", err)
		appConfig = &models.AppConfig{}
	}

	var versionMgr *version.Manager
	if appConfig.VersionRetention > 0 {
		versionMgr = version.NewManagerWithOptions(store, appConfig.VersionRetention)
	} else {
		versionMgr = version.NewManager(store)
	}

	verifier := verify.NewVerifier(store, versionMgr)

	backupEng := backup.NewEngineWithOptions(store, versionMgr, verifier, log, appConfig.HashWorkers, true)

	restoreEng := restore.NewEngine(store, versionMgr, verifier, log)

	sched := scheduler.NewScheduler(absRoot, log, backupEng)
	if err := sched.Init(); err != nil {
		log.Warn("Failed to initialize scheduler: %v", err)
	}

	return &CLI{
		storage:    store,
		logger:     log,
		versionMgr: versionMgr,
		verifier:   verifier,
		backupEng:  backupEng,
		restoreEng: restoreEng,
		scheduler:  sched,
		configPath: configPath,
	}, nil
}

func loadAppConfig(configPath string) (*models.AppConfig, error) {
	data, err := os.ReadFile(configPath)
	if err != nil {
		return nil, err
	}

	var config models.AppConfig
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, err
	}

	return &config, nil
}

func (c *CLI) saveAppConfig(config *models.AppConfig) error {
	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal config: %w", err)
	}

	if err := os.MkdirAll(filepath.Dir(c.configPath), 0755); err != nil {
		return fmt.Errorf("failed to create config directory: %w", err)
	}

	if err := os.WriteFile(c.configPath, data, 0644); err != nil {
		return fmt.Errorf("failed to save config: %w", err)
	}

	return nil
}

func (c *CLI) Execute() {
	if len(os.Args) < 2 {
		c.printUsage()
		os.Exit(1)
	}

	command := os.Args[1]
	switch command {
	case "backup":
		c.handleBackup()
	case "restore":
		c.handleRestore()
	case "versions":
		c.handleVersions()
	case "verify":
		c.handleVerify()
	case "schedule":
		c.handleSchedule()
	case "delete":
		c.handleDelete()
	case "config":
		c.handleConfig()
	case "cleanup":
		c.handleCleanup()
	case "help", "--help", "-h":
		c.printUsage()
	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n", command)
		c.printUsage()
		os.Exit(1)
	}
}

func (c *CLI) handleBackup() {
	backupCmd := flag.NewFlagSet("backup", flag.ExitOnError)
	source := backupCmd.String("source", "", "Source directory to backup")
	workers := backupCmd.Int("workers", 0, "Number of hash workers (0 = use CPU count)")

	if len(os.Args) >= 3 {
		backupCmd.Parse(os.Args[2:])
	}

	if *source == "" {
		fmt.Fprintln(os.Stderr, "Error: --source is required")
		fmt.Fprintln(os.Stderr)
		c.printBackupUsage()
		os.Exit(1)
	}

	if *workers > 0 {
		c.backupEng.SetHashWorkers(*workers)
	}

	absSource, err := filepath.Abs(*source)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: failed to resolve source path: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Starting backup from: %s (hash workers: %d)\n", absSource, c.backupEng.GetHashWorkers())

	result, err := c.backupEng.Backup(absSource)
	if err != nil && result == nil {
		fmt.Fprintf(os.Stderr, "Backup failed: %v\n", err)
		os.Exit(1)
	}

	c.printBackupResult(result)

	if !result.Success {
		os.Exit(1)
	}
}

func (c *CLI) handleRestore() {
	restoreCmd := flag.NewFlagSet("restore", flag.ExitOnError)
	versionID := restoreCmd.String("version", "", "Backup version ID to restore")
	target := restoreCmd.String("target", "", "Target directory for restoration")
	verifyOpt := restoreCmd.Bool("verify", true, "Verify backup integrity before restore")

	if len(os.Args) >= 3 {
		restoreCmd.Parse(os.Args[2:])
	}

	if *versionID == "" {
		fmt.Fprintln(os.Stderr, "Error: --version is required")
		fmt.Fprintln(os.Stderr)
		c.printRestoreUsage()
		os.Exit(1)
	}

	if *target == "" {
		fmt.Fprintln(os.Stderr, "Error: --target is required")
		fmt.Fprintln(os.Stderr)
		c.printRestoreUsage()
		os.Exit(1)
	}

	absTarget, err := filepath.Abs(*target)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: failed to resolve target path: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Starting restore: version=%s, target=%s\n", *versionID, absTarget)

	result, err := c.restoreEng.Restore(*versionID, absTarget, *verifyOpt)
	if err != nil && result == nil {
		fmt.Fprintf(os.Stderr, "Restore failed: %v\n", err)
		os.Exit(1)
	}

	c.printRestoreResult(result)

	if !result.Success {
		os.Exit(1)
	}
}

func (c *CLI) handleVersions() {
	versionsCmd := flag.NewFlagSet("versions", flag.ExitOnError)
	source := versionsCmd.String("source", "", "Source directory to list versions for (optional)")

	if len(os.Args) >= 3 {
		versionsCmd.Parse(os.Args[2:])
	}

	var versions []*version.BackupVersion
	var err error
	if *source != "" {
		absSource, absErr := filepath.Abs(*source)
		if absErr != nil {
			fmt.Fprintf(os.Stderr, "Error: failed to resolve source path: %v\n", absErr)
			os.Exit(1)
		}
		versions, err = c.versionMgr.ListVersions(absSource)
	} else {
		versions, err = c.versionMgr.ListVersions("")
	}

	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to list versions: %v\n", err)
		os.Exit(1)
	}

	sort.Slice(versions, func(i, j int) bool {
		return versions[i].CreatedAt.After(versions[j].CreatedAt)
	})

	c.printVersions(versions)
}

func (c *CLI) handleVerify() {
	verifyCmd := flag.NewFlagSet("verify", flag.ExitOnError)
	versionID := verifyCmd.String("version", "", "Backup version ID to verify")

	if len(os.Args) >= 3 {
		verifyCmd.Parse(os.Args[2:])
	}

	if *versionID == "" {
		fmt.Fprintln(os.Stderr, "Error: --version is required")
		fmt.Fprintln(os.Stderr)
		c.printVerifyUsage()
		os.Exit(1)
	}

	fmt.Printf("Verifying backup version: %s\n", *versionID)

	result, err := c.verifier.VerifyVersion(*versionID)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Verification failed: %v\n", err)
		os.Exit(1)
	}

	c.printVerifyResult(result)

	if !result.IsValid {
		os.Exit(1)
	}
}

func (c *CLI) handleSchedule() {
	if len(os.Args) < 3 {
		c.printScheduleUsage()
		os.Exit(1)
	}

	subCmd := os.Args[2]
	switch subCmd {
	case "add":
		c.handleScheduleAdd()
	case "remove":
		c.handleScheduleRemove()
	case "list":
		c.handleScheduleList()
	case "enable", "disable":
		c.handleScheduleToggle(subCmd == "enable")
	case "reload":
		c.handleScheduleReload()
	default:
		fmt.Fprintf(os.Stderr, "Unknown schedule subcommand: %s\n", subCmd)
		c.printScheduleUsage()
		os.Exit(1)
	}
}

func (c *CLI) handleScheduleAdd() {
	addCmd := flag.NewFlagSet("schedule add", flag.ExitOnError)
	source := addCmd.String("source", "", "Source directory to backup")
	schedule := addCmd.String("interval", "daily", "Schedule interval (minute, hourly, daily, weekly, or custom like '2h', '30m', '1d')")

	if len(os.Args) >= 4 {
		addCmd.Parse(os.Args[3:])
	}

	if *source == "" {
		fmt.Fprintln(os.Stderr, "Error: --source is required")
		os.Exit(1)
	}

	absSource, err := filepath.Abs(*source)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: failed to resolve source path: %v\n", err)
		os.Exit(1)
	}

	task, err := c.scheduler.AddTask(absSource, *schedule)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to add scheduled task: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Scheduled task added:\n")
	fmt.Printf("  Task ID:  %s\n", task.TaskID)
	fmt.Printf("  Source:   %s\n", task.SourcePath)
	fmt.Printf("  Interval: %s\n", task.Schedule)
	fmt.Printf("  Status:   enabled\n")
}

func (c *CLI) handleScheduleRemove() {
	removeCmd := flag.NewFlagSet("schedule remove", flag.ExitOnError)
	taskID := removeCmd.String("id", "", "Task ID to remove")

	if len(os.Args) >= 4 {
		removeCmd.Parse(os.Args[3:])
	}

	if *taskID == "" {
		fmt.Fprintln(os.Stderr, "Error: --id is required")
		os.Exit(1)
	}

	if err := c.scheduler.RemoveTask(*taskID); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to remove task: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Task %s removed\n", *taskID)
}

func (c *CLI) handleScheduleList() {
	tasks := c.scheduler.ListTasks()
	if len(tasks) == 0 {
		fmt.Println("No scheduled tasks")
		return
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "TASK ID\tSOURCE\tINTERVAL\tENABLED\tLAST RUN\tSTATUS")
	for _, task := range tasks {
		enabled := "yes"
		if !task.Enabled {
			enabled = "no"
		}
		lastRun := "never"
		if !task.LastRunAt.IsZero() {
			lastRun = task.LastRunAt.Format("2006-01-02 15:04:05")
		}
		status := task.LastRunStatus
		if status == "" {
			status = "-"
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\n",
			task.TaskID,
			task.SourcePath,
			task.Schedule,
			enabled,
			lastRun,
			status,
		)
	}
	w.Flush()
}

func (c *CLI) handleScheduleToggle(enable bool) {
	toggleCmd := flag.NewFlagSet("schedule "+map[bool]string{true: "enable", false: "disable"}[enable], flag.ExitOnError)
	taskID := toggleCmd.String("id", "", "Task ID")

	if len(os.Args) >= 4 {
		toggleCmd.Parse(os.Args[3:])
	}

	if *taskID == "" {
		fmt.Fprintln(os.Stderr, "Error: --id is required")
		os.Exit(1)
	}

	if err := c.scheduler.EnableTask(*taskID, enable); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to update task: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Task %s %s\n", *taskID, map[bool]string{true: "enabled", false: "disabled"}[enable])
}

func (c *CLI) handleScheduleReload() {
	fmt.Println("Reloading scheduler configuration...")
	if err := c.scheduler.ReloadConfig(); err != nil {
		fmt.Fprintf(os.Stderr, "Warning: %v\n", err)
	}
	fmt.Println("Scheduler configuration reloaded")
}

func (c *CLI) handleDelete() {
	deleteCmd := flag.NewFlagSet("delete", flag.ExitOnError)
	versionID := deleteCmd.String("version", "", "Backup version ID to delete")

	if len(os.Args) >= 3 {
		deleteCmd.Parse(os.Args[2:])
	}

	if *versionID == "" {
		fmt.Fprintln(os.Stderr, "Error: --version is required")
		os.Exit(1)
	}

	fmt.Printf("Deleting backup version: %s\n", *versionID)

	if err := c.versionMgr.DeleteVersion(*versionID); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to delete version: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Version %s deleted successfully\n", *versionID)
}

func (c *CLI) handleConfig() {
	if len(os.Args) < 3 {
		c.printConfigUsage()
		os.Exit(1)
	}

	subCmd := os.Args[2]
	switch subCmd {
	case "show":
		c.handleConfigShow()
	case "set":
		c.handleConfigSet()
	default:
		fmt.Fprintf(os.Stderr, "Unknown config subcommand: %s\n", subCmd)
		c.printConfigUsage()
		os.Exit(1)
	}
}

func (c *CLI) handleConfigShow() {
	config := &models.AppConfig{
		VersionRetention: c.versionMgr.GetMaxVersions(),
		HashWorkers:      c.backupEng.GetHashWorkers(),
	}

	fmt.Printf("Configuration file: %s\n", c.configPath)
	fmt.Println()
	fmt.Println("Current settings:")
	fmt.Printf("  version_retention: %d\n", config.VersionRetention)
	fmt.Printf("  hash_workers:      %d\n", config.HashWorkers)

	savedConfig, err := loadAppConfig(c.configPath)
	if err == nil {
		fmt.Println()
		fmt.Println("Saved scheduled tasks:")
		if len(savedConfig.ScheduleTasks) == 0 {
			fmt.Println("  (none)")
		} else {
			for i, task := range savedConfig.ScheduleTasks {
				enabled := "enabled"
				if !task.Enabled {
					enabled = "disabled"
				}
				fmt.Printf("  [%d] %s (%s, %s)\n", i+1, task.Source, task.Schedule, enabled)
			}
		}
	}
}

func (c *CLI) handleConfigSet() {
	configSetCmd := flag.NewFlagSet("config set", flag.ExitOnError)
	retention := configSetCmd.Int("retention", 0, "Maximum number of versions to keep per source (0 = keep all)")
	workers := configSetCmd.Int("workers", 0, "Number of hash workers (0 = use CPU count)")

	if len(os.Args) >= 4 {
		configSetCmd.Parse(os.Args[3:])
	}

	config, err := loadAppConfig(c.configPath)
	if err != nil {
		config = &models.AppConfig{}
	}

	changed := false
	if *retention > 0 {
		config.VersionRetention = *retention
		c.versionMgr.SetMaxVersions(*retention)
		changed = true
		fmt.Printf("Set version_retention to %d\n", *retention)
	}

	if *workers > 0 {
		config.HashWorkers = *workers
		c.backupEng.SetHashWorkers(*workers)
		changed = true
		fmt.Printf("Set hash_workers to %d\n", *workers)
	}

	if changed {
		if err := c.saveAppConfig(config); err != nil {
			fmt.Fprintf(os.Stderr, "Warning: failed to save config: %v\n", err)
		}
	}
}

func (c *CLI) handleCleanup() {
	cleanupCmd := flag.NewFlagSet("cleanup", flag.ExitOnError)
	source := cleanupCmd.String("source", "", "Source directory to cleanup versions for")
	keep := cleanupCmd.Int("keep", 0, "Number of versions to keep (0 = use configured policy)")

	if len(os.Args) >= 3 {
		cleanupCmd.Parse(os.Args[2:])
	}

	if *source == "" {
		fmt.Fprintln(os.Stderr, "Error: --source is required")
		os.Exit(1)
	}

	absSource, err := filepath.Abs(*source)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: failed to resolve source path: %v\n", err)
		os.Exit(1)
	}

	keepCount := *keep
	if keepCount <= 0 {
		keepCount = c.versionMgr.GetMaxVersions()
	}

	fmt.Printf("Cleaning up old versions for: %s\n", absSource)
	fmt.Printf("Keeping latest %d versions\n", keepCount)

	deleted, err := c.versionMgr.CleanupOldVersions(absSource, keepCount)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Cleanup failed: %v\n", err)
		os.Exit(1)
	}

	if len(deleted) == 0 {
		fmt.Println("No versions to clean up")
		return
	}

	fmt.Printf("Deleted %d old versions:\n", len(deleted))
	for _, v := range deleted {
		fmt.Printf("  - %s (created: %s)\n", v.VersionID, v.CreatedAt.Format("2006-01-02 15:04:05"))
	}
}

func (c *CLI) printBackupResult(result *backup.BackupResult) {
	fmt.Println()
	fmt.Println("=== Backup Result ===")
	fmt.Printf("Version ID:    %s\n", result.VersionID)
	fmt.Printf("Status:        %s\n", map[bool]string{true: "SUCCESS", false: "PARTIAL/FAILED"}[result.Success])
	fmt.Printf("Total Files:   %d\n", result.FileCount)
	fmt.Printf("Changed Files: %d\n", result.ChangedCount)
	fmt.Printf("  - Added:     %d\n", result.AddedCount)
	fmt.Printf("  - Modified:  %d\n", result.ModifiedCount)
	fmt.Printf("  - Deleted:   %d\n", result.DeletedCount)
	fmt.Printf("Backup Size:   %d bytes\n", result.BackupSize)
	fmt.Printf("Duration:      %v\n", result.Duration)

	if len(result.Errors) > 0 {
		fmt.Println()
		fmt.Println("Errors:")
		for _, err := range result.Errors {
			fmt.Printf("  - %s\n", err)
		}
	}
}

func (c *CLI) printRestoreResult(result *restore.RestoreResult) {
	fmt.Println()
	fmt.Println("=== Restore Result ===")
	fmt.Printf("Version ID:     %s\n", result.VersionID)
	fmt.Printf("Status:         %s\n", map[bool]string{true: "SUCCESS", false: "PARTIAL/FAILED"}[result.Success])
	fmt.Printf("Restored:       %d files\n", result.RestoredCount)
	fmt.Printf("Failed:         %d files\n", result.FailedCount)
	fmt.Printf("Total Size:     %d bytes\n", result.TotalSize)
	fmt.Printf("Duration:       %v\n", result.Duration)

	if len(result.Errors) > 0 {
		fmt.Println()
		fmt.Println("Errors:")
		for _, err := range result.Errors {
			fmt.Printf("  - %s\n", err)
		}
	}
}

func (c *CLI) printVersions(versions []*version.BackupVersion) {
	if len(versions) == 0 {
		fmt.Println("No backup versions found")
		return
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "VERSION ID\tCREATED\tTYPE\tFILES\tCHANGES\tSIZE")
	for _, v := range versions {
		fmt.Fprintf(w, "%s\t%s\t%s\t%d\t%d\t%d\n",
			v.VersionID,
			v.CreatedAt.Format("2006-01-02 15:04:05"),
			v.Type,
			v.FileCount,
			v.ChangedCount,
			v.BackupSize,
		)
	}
	w.Flush()
}

func (c *CLI) printVerifyResult(result *verify.VerifyResult) {
	fmt.Println()
	fmt.Println("=== Verify Result ===")
	fmt.Printf("Version ID:       %s\n", result.VersionID)
	fmt.Printf("Status:           %s\n", map[bool]string{true: "VALID", false: "INVALID"}[result.IsValid])
	fmt.Printf("Total Files:      %d\n", result.TotalFiles)
	fmt.Printf("Valid Files:      %d\n", result.ValidFiles)
	fmt.Printf("Invalid Files:    %d\n", len(result.InvalidFiles))
	fmt.Printf("Missing Files:    %d\n", len(result.MissingFiles))
	fmt.Printf("Checksum Match:   %v\n", result.ChecksumMatch)
	if !result.ChecksumMatch {
		fmt.Printf("  Expected: %s\n", result.ExpectedChecksum)
		fmt.Printf("  Actual:   %s\n", result.ActualChecksum)
	}
	fmt.Printf("Duration:         %v\n", result.Duration)

	if len(result.InvalidFiles) > 0 {
		fmt.Println()
		fmt.Println("Invalid Files:")
		for _, f := range result.InvalidFiles {
			fmt.Printf("  - %s\n", f)
		}
	}

	if len(result.MissingFiles) > 0 {
		fmt.Println()
		fmt.Println("Missing Files:")
		for _, f := range result.MissingFiles {
			fmt.Printf("  - %s\n", f)
		}
	}

	if len(result.Errors) > 0 {
		fmt.Println()
		fmt.Println("Errors:")
		for _, err := range result.Errors {
			fmt.Printf("  - %s\n", err)
		}
	}
}

func (c *CLI) printUsage() {
	fmt.Println("BackupManager - File Backup and Recovery Tool (Enhanced)")
	fmt.Println()
	fmt.Println("Usage:")
	fmt.Println("  backupmgr <command> [options]")
	fmt.Println()
	fmt.Println("Commands:")
	fmt.Println("  backup     Create a backup")
	fmt.Println("  restore    Restore from a backup")
	fmt.Println("  versions   List backup versions")
	fmt.Println("  verify     Verify backup integrity")
	fmt.Println("  delete     Delete a backup version")
	fmt.Println("  schedule   Manage scheduled backups")
	fmt.Println("  config     Manage application configuration")
	fmt.Println("  cleanup    Clean up old backup versions")
	fmt.Println("  help       Show this help message")
	fmt.Println()
	fmt.Println("Use 'backupmgr <command> --help' for more information about a command.")
}

func (c *CLI) printBackupUsage() {
	fmt.Println("Usage: backupmgr backup --source <directory> [--workers <count>]")
	fmt.Println()
	fmt.Println("Options:")
	fmt.Println("  --source    Source directory to backup (required)")
	fmt.Println("  --workers   Number of hash workers (0 = use CPU count, default: 0)")
}

func (c *CLI) printRestoreUsage() {
	fmt.Println("Usage: backupmgr restore --version <version-id> --target <directory> [--verify=true|false]")
	fmt.Println()
	fmt.Println("Options:")
	fmt.Println("  --version   Backup version ID to restore (required)")
	fmt.Println("  --target    Target directory for restoration (required)")
	fmt.Println("  --verify    Verify backup integrity before restore (default: true)")
}

func (c *CLI) printVerifyUsage() {
	fmt.Println("Usage: backupmgr verify --version <version-id>")
	fmt.Println()
	fmt.Println("Options:")
	fmt.Println("  --version   Backup version ID to verify (required)")
}

func (c *CLI) printScheduleUsage() {
	fmt.Println("Usage: backupmgr schedule <subcommand> [options]")
	fmt.Println()
	fmt.Println("Subcommands:")
	fmt.Println("  add         Add a new scheduled backup")
	fmt.Println("  remove      Remove a scheduled backup")
	fmt.Println("  list        List all scheduled backups")
	fmt.Println("  enable      Enable a scheduled backup")
	fmt.Println("  disable     Disable a scheduled backup")
	fmt.Println("  reload      Reload scheduler configuration from file")
	fmt.Println()
	fmt.Println("Schedule intervals:")
	fmt.Println("  minute, hourly, daily, weekly")
	fmt.Println("  Custom: 2h (2 hours), 30m (30 minutes), 1d (1 day)")
	fmt.Println()
	fmt.Println("Examples:")
	fmt.Println("  backupmgr schedule add --source /data --interval daily")
	fmt.Println("  backupmgr schedule add --source /data --interval 6h")
	fmt.Println("  backupmgr schedule list")
	fmt.Println("  backupmgr schedule remove --id task_123456")
}

func (c *CLI) printConfigUsage() {
	fmt.Println("Usage: backupmgr config <subcommand> [options]")
	fmt.Println()
	fmt.Println("Subcommands:")
	fmt.Println("  show        Show current configuration")
	fmt.Println("  set         Set configuration values")
	fmt.Println()
	fmt.Println("Examples:")
	fmt.Println("  backupmgr config show")
	fmt.Println("  backupmgr config set --retention 10")
	fmt.Println("  backupmgr config set --workers 8")
}
