package handler

import (
	"fmt"
	"os"
	"text/tabwriter"

	"configsync/internal/cli"

	"github.com/spf13/cobra"
)

type HistoryHandler struct {
	ctx   *cli.AppContext
	file  string
	limit int
}

func NewHistoryHandler() *HistoryHandler {
	return &HistoryHandler{}
}

func (h *HistoryHandler) Name() string {
	return "history"
}

func (h *HistoryHandler) Description() string {
	return "查看配置变更历史"
}

func (h *HistoryHandler) SetAppContext(ctx *cli.AppContext) {
	h.ctx = ctx
}

func (h *HistoryHandler) CreateCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   h.Name(),
		Short: h.Description(),
		Run:   h.Execute,
	}

	cmd.Flags().StringVar(&h.file, "file", "", "配置文件名称 (可选，不指定则显示全部)")
	cmd.Flags().IntVar(&h.limit, "limit", 10, "返回记录条数限制")

	return cmd
}

func (h *HistoryHandler) Execute(cmd *cobra.Command, args []string) {
	if h.limit <= 0 {
		h.limit = 10
	}

	records, err := h.ctx.Logger.GetHistory(h.file, h.limit)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: 查询历史记录失败: %v\n", err)
		os.Exit(1)
	}

	if len(records) == 0 {
		fmt.Println("暂无变更历史记录")
		return
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 3, ' ', 0)
	fmt.Fprintln(w, "变更ID\t配置文件\t目标组\t版本标签\t操作类型\t执行时间\t操作人\t成功\t失败")
	fmt.Fprintln(w, "------\t--------\t------\t--------\t--------\t--------\t------\t----\t----")

	for _, r := range records {
		execTime := r.ExecutedAt.Format("2006-01-02 15:04:05")
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%d\t%d\n",
			r.ChangeID, r.ConfigFile, r.TargetGroup, r.VersionTag,
			r.ChangeType, execTime, r.Operator, r.Result.Success, r.Result.Failed)
	}
	w.Flush()
}
