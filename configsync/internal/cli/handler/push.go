package handler

import (
	"fmt"
	"os"
	"os/user"
	"path/filepath"
	"text/tabwriter"

	"configsync/internal/cli"
	"configsync/internal/models"
	"configsync/internal/sync"

	"github.com/spf13/cobra"
)

type PushHandler struct {
	ctx         *cli.AppContext
	group       string
	file        string
	reload      bool
	concurrency int
}

func NewPushHandler() *PushHandler {
	return &PushHandler{}
}

func (h *PushHandler) Name() string {
	return "push"
}

func (h *PushHandler) Description() string {
	return "推送配置文件到目标服务器组"
}

func (h *PushHandler) SetAppContext(ctx *cli.AppContext) {
	h.ctx = ctx
}

func (h *PushHandler) CreateCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   h.Name(),
		Short: h.Description(),
		Run:   h.Execute,
	}

	cmd.Flags().StringVar(&h.group, "group", "", "目标服务器组名称 (必填)")
	cmd.Flags().StringVar(&h.file, "file", "", "配置文件路径 (必填)")
	cmd.Flags().BoolVar(&h.reload, "reload", false, "是否执行配置生效命令")
	cmd.Flags().IntVar(&h.concurrency, "concurrency", 5, "并发推送数量")

	cmd.MarkFlagRequired("group")
	cmd.MarkFlagRequired("file")

	return cmd
}

func (h *PushHandler) Execute(cmd *cobra.Command, args []string) {
	if h.concurrency <= 0 {
		h.concurrency = 5
	}

	group, err := h.ctx.ServerManager.GetGroup(h.group)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: 服务器组不存在: %v\n", err)
		os.Exit(1)
	}

	fileName := filepath.Base(h.file)
	if !filepath.IsAbs(h.file) {
		cwd, _ := os.Getwd()
		h.file = filepath.Join(cwd, h.file)
	}

	var configContent []byte
	if _, err := os.Stat(h.file); err == nil {
		if err := h.ctx.ConfigManager.ImportConfig(h.file, fileName); err != nil {
			fmt.Fprintf(os.Stderr, "Error: 导入配置文件失败: %v\n", err)
			os.Exit(1)
		}
		configContent, err = os.ReadFile(h.file)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: 读取配置文件失败: %v\n", err)
			os.Exit(1)
		}
	} else {
		configContent, err = h.ctx.ConfigManager.ReadConfig(fileName)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: 配置文件不存在: %v\n", err)
			os.Exit(1)
		}
	}

	operator := getCurrentUser()
	versionTag, meta, err := h.ctx.ConfigManager.CreatePushSnapshotWithMeta(
		fileName,
		h.group,
		operator,
		len(group.Servers),
		"update",
	)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Warning: 创建版本快照失败: %v\n", err)
	}

	fmt.Printf("开始推送配置到服务器组: %s\n", h.group)
	fmt.Printf("配置文件: %s\n", h.file)
	if versionTag != "" {
		fmt.Printf("版本标签: %s\n", versionTag)
		if meta != nil {
			fmt.Printf("操作者: %s\n", meta.Operator)
			fmt.Printf("目标服务器数: %d\n", meta.ServerCount)
		}
	}
	fmt.Printf("并发数: %d\n", h.concurrency)
	fmt.Println("----------------------------------------")

	syncService := sync.NewSyncService(h.ctx.ConnectionPool)
	defer syncService.Close()

	results := make([]sync.PushResult, len(group.Servers))
	resultChan := make(chan struct {
		index  int
		result sync.PushResult
	}, len(group.Servers))

	semaphore := make(chan struct{}, h.concurrency)

	for i, srv := range group.Servers {
		semaphore <- struct{}{}
		go func(idx int, server models.Server) {
			defer func() { <-semaphore }()
			result := syncService.PushConfigToServer(&server, configContent, h.reload, group.ReloadCommand)
			resultChan <- struct {
				index  int
				result sync.PushResult
			}{idx, result}
		}(i, srv)
	}

	for range group.Servers {
		r := <-resultChan
		results[r.index] = r.result
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
	fmt.Printf("推送完成: 成功 %d, 失败 %d, 总计 %d\n", successCount, failedCount, len(group.Servers))

	record := &models.ChangeRecord{
		ConfigFile:  fileName,
		TargetGroup: h.group,
		VersionTag:  versionTag,
		ChangeType:  "update",
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

func getCurrentUser() string {
	usr, err := user.Current()
	if err != nil {
		return "unknown"
	}
	return usr.Username
}
