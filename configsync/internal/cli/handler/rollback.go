package handler

import (
	"fmt"
	"os"
	"text/tabwriter"

	"configsync/internal/cli"
	"configsync/internal/models"
	"configsync/internal/sync"

	"github.com/spf13/cobra"
)

type RollbackHandler struct {
	ctx     *cli.AppContext
	version string
	group   string
	file    string
	reload  bool
}

func NewRollbackHandler() *RollbackHandler {
	return &RollbackHandler{}
}

func (h *RollbackHandler) Name() string {
	return "rollback"
}

func (h *RollbackHandler) Description() string {
	return "回滚配置到指定版本"
}

func (h *RollbackHandler) SetAppContext(ctx *cli.AppContext) {
	h.ctx = ctx
}

func (h *RollbackHandler) CreateCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   h.Name(),
		Short: h.Description(),
		Run:   h.Execute,
	}

	cmd.Flags().StringVar(&h.version, "version", "", "目标版本标签 (必填)")
	cmd.Flags().StringVar(&h.group, "group", "", "目标服务器组名称 (必填)")
	cmd.Flags().StringVar(&h.file, "file", "", "配置文件名称 (必填)")
	cmd.Flags().BoolVar(&h.reload, "reload", false, "是否执行配置生效命令")

	cmd.MarkFlagRequired("version")
	cmd.MarkFlagRequired("group")
	cmd.MarkFlagRequired("file")

	return cmd
}

func (h *RollbackHandler) Execute(cmd *cobra.Command, args []string) {
	group, err := h.ctx.ServerManager.GetGroup(h.group)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: 服务器组不存在: %v\n", err)
		os.Exit(1)
	}

	configContent, err := h.ctx.ConfigManager.ReadConfigAtVersion(h.file, h.version)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: 读取指定版本配置失败: %v\n", err)
		os.Exit(1)
	}

	meta, _ := h.ctx.ConfigManager.GetVersionMeta(h.version)

	fmt.Printf("开始回滚配置到服务器组: %s\n", h.group)
	fmt.Printf("配置文件: %s\n", h.file)
	fmt.Printf("目标版本: %s\n", h.version)
	if meta != nil {
		fmt.Printf("原操作者: %s\n", meta.Operator)
		fmt.Printf("原目标组: %s\n", meta.TargetGroup)
	}
	fmt.Println("----------------------------------------")

	syncService := sync.NewSyncService(h.ctx.ConnectionPool)
	defer syncService.Close()

	results := make([]sync.PushResult, len(group.Servers))
	for i, srv := range group.Servers {
		result := syncService.PushConfigToServer(&srv, configContent, h.reload, group.ReloadCommand)
		results[i] = result
	}

	successCount := 0
	failedCount := 0

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 3, ' ', 0)
	fmt.Fprintln(w, "服务器ID\t主机\t状态\t错误")
	fmt.Fprintln(w, "--------\t----\t----\t----")

	for _, r := range results {
		status := "成功"
		errorMsg := ""
		if r.Success {
			successCount++
			if r.ReloadError != "" {
				status = "成功(重载警告)"
				errorMsg = r.ReloadError
			}
		} else {
			failedCount++
			status = "失败"
			errorMsg = r.Error
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", r.ServerID, r.Host, status, errorMsg)
	}
	w.Flush()

	fmt.Println("----------------------------------------")
	fmt.Printf("回滚完成: 成功 %d, 失败 %d, 总计 %d\n", successCount, failedCount, len(group.Servers))

	operator := getCurrentUser()
	record := &models.ChangeRecord{
		ConfigFile:  h.file,
		TargetGroup: h.group,
		VersionTag:  h.version,
		ChangeType:  "rollback",
		DiffSummary:  "",
		Operator:    operator,
		Result: models.PushResult{
			Success: successCount,
			Failed:  failedCount,
		},
	}

	if err := h.ctx.Logger.LogChange(record); err != nil {
		fmt.Fprintf(os.Stderr, "Warning: 记录操作日志失败: %v\n", err)
	}

	if failedCount > 0 {
		os.Exit(1)
	}
}
