package main

import (
	"fmt"
	"time"

	"session287/internal/approval"
	"session287/internal/assignment"
	"session287/internal/billing"
	"session287/internal/docdiff"
	"session287/internal/flow"
	"session287/internal/skillgraph"
	"session287/internal/sla"
	"session287/internal/tenant"
)

var (
	version      = "0.0.1-dev"
	gitCommit    = "unknown"
	buildTime    = "unknown"
	buildProfile = "dev"
)

func main() {
	fmt.Println("============================================================")
	fmt.Println("  基于技能匹配与负载均衡的工单路由分配系统 - 综合演示")
	fmt.Printf("  version=%s  commit=%s  profile=%s  built=%s\n", version, gitCommit, buildProfile, buildTime)
	fmt.Println("============================================================")
	fmt.Println()

	demoFlowDesigner()
	demoDocDiff()
	demoBilling()
	demoSkillGraph()
	demoAssignment()
	demoTenant()
	demoSLA()
	demoApproval()

	fmt.Println("============================================================")
	fmt.Println("  所有模块演示完成")
	fmt.Println("============================================================")
}

func demoFlowDesigner() {
	fmt.Println(">>> 模块1: 可视化流程设计模块")
	fmt.Println("------------------------------------------------------------")
	designer := flow.NewFlowDesigner()
	f, err := designer.CreateFlow("flow-001", "工单处理流程", "tenant-1")
	if err != nil {
		fmt.Printf("  创建流程失败: %s\n", err)
		return
	}
	designer.AddNode("flow-001", flow.Node{
		ID:   "start",
		Name: "开始",
		Type: flow.NodeTypeStart,
		Position: flow.Position{X: 100, Y: 50},
	})
	designer.AddNode("flow-001", flow.Node{
		ID:   "classify",
		Name: "工单分类",
		Type: flow.NodeTypeDecision,
		Position: flow.Position{X: 300, Y: 50},
	})
	designer.AddNode("flow-001", flow.Node{
		ID:   "assign",
		Name: "智能分配",
		Type: flow.NodeTypeTask,
		Position: flow.Position{X: 500, Y: 50},
	})
	designer.AddNode("flow-001", flow.Node{
		ID:   "approve",
		Name: "审批确认",
		Type: flow.NodeTypeApproval,
		Position: flow.Position{X: 700, Y: 50},
	})
	designer.AddNode("flow-001", flow.Node{
		ID:   "end",
		Name: "结束",
		Type: flow.NodeTypeEnd,
		Position: flow.Position{X: 900, Y: 50},
	})
	designer.AddConnection("flow-001", flow.Connection{
		ID: "c1", FromNode: "start", ToNode: "classify",
	})
	designer.AddConnection("flow-001", flow.Connection{
		ID: "c2", FromNode: "classify", ToNode: "assign", Label: "技术类",
	})
	designer.AddConnection("flow-001", flow.Connection{
		ID: "c3", FromNode: "classify", ToNode: "approve", Label: "行政类",
	})
	designer.AddConnection("flow-001", flow.Connection{
		ID: "c4", FromNode: "assign", ToNode: "end",
	})
	designer.AddConnection("flow-001", flow.Connection{
		ID: "c5", FromNode: "approve", ToNode: "end",
	})
	fmt.Printf("  流程名称: %s (v%d, state=%s)\n", f.Name, f.Version, f.State)
	fmt.Printf("  节点数: %d, 连线数: %d\n", len(f.Nodes), len(f.Connections))
	errs := designer.Validate("flow-001")
	if len(errs) == 0 {
		fmt.Println("  流程校验: ✓ 通过")
	} else {
		fmt.Println("  流程校验: ✗ 存在错误")
		for _, e := range errs {
			fmt.Printf("    - %s\n", e.Error())
		}
	}
	if err := designer.ActivateFlow("flow-001"); err != nil {
		fmt.Printf("  激活流程失败: %s\n", err)
	} else {
		fmt.Printf("  激活流程: ✓ (state=%s)\n", flow.FlowStateActive)
	}
	if err := designer.AddNode("flow-001", flow.Node{ID: "hack", Name: "非法修改", Type: flow.NodeTypeTask}); err != nil {
		fmt.Printf("  激活后修改拦截: ✓ (%s)\n", err)
	}
	fmt.Println()
}

func demoDocDiff() {
	fmt.Println(">>> 模块2: 文档智能比对模块")
	fmt.Println("------------------------------------------------------------")
	comparator := docdiff.NewDocComparator()
	comparator.RegisterClause("c1", "保密条款", []string{"保密", "机密", "confidential"})
	comparator.RegisterClause("c2", "付款条款", []string{"付款", "结算", "payment"})
	comparator.RegisterClause("c3", "违约条款", []string{"违约", "赔偿", "breach"})
	oldDoc := docdiff.Document{ID: "doc-v1", Content: "合同编号: HT-2024-001\n甲方: 公司A\n乙方: 公司B\n保密条款: 双方应对商业机密严格保密\n付款条款: 甲方应在30日内付款\n违约条款: 违约方需赔偿全部损失\n", Version: 1}
	newDoc := docdiff.Document{ID: "doc-v2", Content: "合同编号: HT-2024-001\n甲方: 公司A\n乙方: 公司C\n保密条款: 双方应对商业机密严格保密,期限为5年\n付款条款: 甲方应在60日内付款\n违约条款: 违约方需赔偿全部损失及利息\n", Version: 2}
	result, err := comparator.Compare(oldDoc, newDoc, docdiff.WithTimeout(10*time.Second))
	if err != nil {
		fmt.Printf("  比对失败: %s\n", err)
		return
	}
	fmt.Printf(docdiff.FormatDiffResult(result))
	fmt.Println()
}

func demoBilling() {
	fmt.Println(">>> 模块3: 用量计量与计费模块")
	fmt.Println("------------------------------------------------------------")
	collector := billing.NewUsageCollector()
	now := time.Now()
	collector.RecordUsage(billing.UsageRecord{TenantID: "tenant-1", Resource: billing.ResourceCPU, Quantity: 120, Unit: "core-hours", Timestamp: now})
	collector.RecordUsage(billing.UsageRecord{TenantID: "tenant-1", Resource: billing.ResourceMemory, Quantity: 256, Unit: "GB-hours", Timestamp: now})
	collector.RecordUsage(billing.UsageRecord{TenantID: "tenant-1", Resource: billing.ResourceStorage, Quantity: 500, Unit: "GB", Timestamp: now})
	collector.RecordUsage(billing.UsageRecord{TenantID: "tenant-1", Resource: billing.ResourceAPI, Quantity: 10000, Unit: "calls", Timestamp: now})
	engine := billing.NewBillingEngine()
	engine.SetPricing(billing.PricingRule{Resource: billing.ResourceCPU, UnitPrice: 0.5, Currency: "CNY"})
	engine.SetPricing(billing.PricingRule{Resource: billing.ResourceMemory, UnitPrice: 0.1, Currency: "CNY"})
	engine.SetPricing(billing.PricingRule{Resource: billing.ResourceStorage, UnitPrice: 0.05, Currency: "CNY"})
	engine.SetPricing(billing.PricingRule{Resource: billing.ResourceAPI, UnitPrice: 0.001, Currency: "CNY", TierPricing: []billing.TierPrice{
		{MinQuantity: 0, MaxQuantity: 5000, UnitPrice: 0.001},
		{MinQuantity: 5000, MaxQuantity: 20000, UnitPrice: 0.0008},
		{MinQuantity: 20000, MaxQuantity: 0, UnitPrice: 0.0005},
	}})
	start := now.AddDate(0, 0, -30)
	end := now
	usage := collector.AggregateUsage("tenant-1", start, end)
	bill := engine.GenerateBill("tenant-1", usage, "2024-01")
	fmt.Print(bill.Format())
	fmt.Println()
}

func demoSkillGraph() {
	fmt.Println(">>> 模块4: 技能图谱建模模块")
	fmt.Println("------------------------------------------------------------")
	sg := skillgraph.NewSkillGraph()
	sg.AddSkill(skillgraph.Skill{ID: "go", Name: "Go语言", Category: "编程语言", Weight: 1.0, Prerequisites: []string{}})
	sg.AddSkill(skillgraph.Skill{ID: "k8s", Name: "Kubernetes", Category: "云原生", Weight: 1.5, Prerequisites: []string{"docker"}})
	sg.AddSkill(skillgraph.Skill{ID: "docker", Name: "Docker", Category: "容器技术", Weight: 1.2, Prerequisites: []string{"linux"}})
	sg.AddSkill(skillgraph.Skill{ID: "linux", Name: "Linux", Category: "操作系统", Weight: 1.0, Prerequisites: []string{}})
	sg.AddSkill(skillgraph.Skill{ID: "db", Name: "数据库管理", Category: "数据", Weight: 1.3, Prerequisites: []string{}})
	sg.AddEmployee(skillgraph.Employee{ID: "emp-1", Name: "张三", Department: "技术部", Skills: map[string]skillgraph.SkillAssessment{
		"go":    {SkillID: "go", Level: skillgraph.LevelAdvanced, Score: 85},
		"linux": {SkillID: "linux", Level: skillgraph.LevelIntermediate, Score: 70},
	}})
	sg.AddEmployee(skillgraph.Employee{ID: "emp-2", Name: "李四", Department: "运维部", Skills: map[string]skillgraph.SkillAssessment{
		"docker": {SkillID: "docker", Level: skillgraph.LevelExpert, Score: 95},
		"linux":  {SkillID: "linux", Level: skillgraph.LevelAdvanced, Score: 88},
		"k8s":    {SkillID: "k8s", Level: skillgraph.LevelAdvanced, Score: 82},
	}})
	required := map[string]skillgraph.SkillLevel{
		"k8s": skillgraph.LevelAdvanced,
		"docker": skillgraph.LevelIntermediate,
	}
	matches := sg.FindQualifiedEmployees(required, 0.3)
	fmt.Println("  技能匹配结果 (K8s高级 + Docker中级):")
	for _, m := range matches {
		fmt.Printf("    %s (%s): 匹配度 %.2f%%\n", m.EmployeeName, m.EmployeeID, m.MatchScore*100)
	}
	lp, _ := sg.GenerateLearningPath("emp-1", "k8s")
	fmt.Printf("  学习路径 (张三 -> K8s): %s", lp.Format())
	fmt.Println()
}

func demoAssignment() {
	fmt.Println(">>> 模块5: 工单智能分配模块 (核心)")
	fmt.Println("------------------------------------------------------------")
	engine := assignment.NewAssignmentEngine(assignment.StrategyWeighted)
	engine.SetWeights(0.6, 0.4)
	lb := engine.GetLoadBalancer()
	lb.RegisterAgent(assignment.Agent{
		ID: "agent-1", Name: "张三", Department: "技术部", TenantID: "tenant-1",
		MaxLoad: 5, ActiveOrders: 1, IsOnline: true,
		Skills: map[string]float64{"go": 90, "linux": 70, "docker": 60},
	})
	lb.RegisterAgent(assignment.Agent{
		ID: "agent-2", Name: "李四", Department: "运维部", TenantID: "tenant-1",
		MaxLoad: 5, ActiveOrders: 3, IsOnline: true,
		Skills: map[string]float64{"k8s": 85, "docker": 95, "linux": 88},
	})
	lb.RegisterAgent(assignment.Agent{
		ID: "agent-3", Name: "王五", Department: "技术部", TenantID: "tenant-1",
		MaxLoad: 5, ActiveOrders: 0, IsOnline: true,
		Skills: map[string]float64{"go": 75, "docker": 70, "k8s": 80, "linux": 60},
	})
	orders := []*assignment.WorkOrder{
		{
			ID: "WO-001", Title: "K8s集群故障排查", Priority: assignment.PriorityHigh,
			RequiredSkills: map[string]float64{"k8s": 80, "docker": 70},
			TenantID: "tenant-1", Status: assignment.StatusPending,
			SkillWeight: 0.6, LoadWeight: 0.4,
		},
		{
			ID: "WO-002", Title: "Go服务性能优化", Priority: assignment.PriorityMedium,
			RequiredSkills: map[string]float64{"go": 80, "linux": 60},
			TenantID: "tenant-1", Status: assignment.StatusPending,
			SkillWeight: 0.6, LoadWeight: 0.4,
		},
		{
			ID: "WO-003", Title: "Docker镜像构建优化", Priority: assignment.PriorityLow,
			RequiredSkills: map[string]float64{"docker": 75},
			TenantID: "tenant-1", Status: assignment.StatusPending,
			SkillWeight: 0.6, LoadWeight: 0.4,
		},
	}
	fmt.Println("  加权策略 (技能60% + 负载40%) 批量分配:")
	results, errs := engine.BatchAssign(orders)
	for _, r := range results {
		fmt.Printf("    %s\n", r.Format())
	}
	if len(errs) > 0 {
		for _, e := range errs {
			fmt.Printf("    分配失败: %s\n", e.Error())
		}
	}
	fmt.Println()
	fmt.Println("  自适应策略分配:")
	adaptiveEngine := assignment.NewAssignmentEngine(assignment.StrategyAdaptive)
	adaptiveLb := adaptiveEngine.GetLoadBalancer()
	adaptiveLb.RegisterAgent(assignment.Agent{
		ID: "a1", Name: "赵六", Department: "技术部", TenantID: "tenant-1",
		MaxLoad: 5, ActiveOrders: 0, IsOnline: true,
		Skills: map[string]float64{"k8s": 90, "docker": 85},
	})
	adaptiveLb.RegisterAgent(assignment.Agent{
		ID: "a2", Name: "钱七", Department: "运维部", TenantID: "tenant-1",
		MaxLoad: 5, ActiveOrders: 4, IsOnline: true,
		Skills: map[string]float64{"k8s": 88, "docker": 90},
	})
	order4 := &assignment.WorkOrder{
		ID: "WO-004", Title: "紧急容器迁移", Priority: assignment.PriorityCritical,
		RequiredSkills: map[string]float64{"k8s": 85, "docker": 80},
		TenantID: "tenant-1", Status: assignment.StatusPending,
	}
	result4, err := adaptiveEngine.AssignWorkOrder(order4)
	if err != nil {
		fmt.Printf("    分配失败: %s\n", err)
	} else {
		fmt.Printf("    %s\n", result4.Format())
	}
	fmt.Println()
}

func demoTenant() {
	fmt.Println(">>> 模块6: 多租户隔离策略模块")
	fmt.Println("------------------------------------------------------------")
	tm := tenant.NewTenantManager()
	tm.CreateTenant("tenant-1", "企业A", tenant.IsolationDatabase, tenant.ResourceQuota{
		MaxCPU: 16, MaxMemory: 32768, MaxStorage: 1000, MaxAPICalls: 100000, MaxUsers: 50, MaxWorkOrders: 5000,
	})
	tm.CreateTenant("tenant-2", "企业B", tenant.IsolationSchema, tenant.ResourceQuota{
		MaxCPU: 8, MaxMemory: 16384, MaxStorage: 500, MaxAPICalls: 50000, MaxUsers: 20, MaxWorkOrders: 2000,
	})
	tm.CreateTenant("tenant-3", "企业C", tenant.IsolationShared, tenant.ResourceQuota{
		MaxCPU: 4, MaxMemory: 8192, MaxStorage: 200, MaxAPICalls: 20000, MaxUsers: 10, MaxWorkOrders: 500,
	})
	for _, t := range tm.ListTenants() {
		fmt.Printf("  %s\n", t.Format())
		config, _ := tm.GetDataIsolationConfig(t.ID)
		fmt.Printf("    隔离配置: %v\n", config)
	}
	tm.RecordUsage("tenant-1", "cpu", 8)
	tm.RecordUsage("tenant-1", "memory", 4096)
	tm.RecordUsage("tenant-1", "work_orders", 1200)
	usage, _ := tm.GetUsage("tenant-1")
	fmt.Printf("  企业A当前用量: CPU=%.0f, Memory=%.0fMB, WorkOrders=%d\n",
		usage.CPUUsage, usage.MemoryUsage, usage.WorkOrderCount)
	err := tm.CheckQuota("tenant-1", "cpu", 10)
	if err != nil {
		fmt.Printf("  配额检查: CPU超额 - %s\n", err)
	} else {
		fmt.Println("  配额检查: CPU额度充足")
	}
	fmt.Println()
}

func demoSLA() {
	fmt.Println(">>> 模块7: SLA时效监控模块")
	fmt.Println("------------------------------------------------------------")
	monitor := sla.NewSLAMonitor()
	monitor.AddPolicy(sla.SLAPolicy{
		ID: "policy-1", Name: "工单响应SLA", Category: "work_order",
		TargetDuration: 4 * time.Hour, WarningThreshold: 75, CriticalThreshold: 90,
		Priority: "high", TenantID: "tenant-1",
		EscalationActions: []sla.EscalationAction{
			{Level: 1, Threshold: 75, NotifyUsers: []string{"manager-1"}, Action: "notify"},
			{Level: 2, Threshold: 90, NotifyUsers: []string{"manager-1", "director-1"}, Action: "escalate"},
			{Level: 3, Threshold: 100, NotifyUsers: []string{"vp-1"}, Action: "auto_reassign"},
		},
	})
	tracker, _ := monitor.StartTracking("sla-001", "work_order", "WO-001", "policy-1", "tenant-1")
	fmt.Printf("  创建SLA追踪: %s\n", tracker.Format())
	remaining, _ := monitor.GetRemainingTime("sla-001")
	fmt.Printf("  剩余时间: %v\n", remaining.Round(time.Minute))
	fmt.Println("  执行SLA检查(模拟超时)...")
	notifications := monitor.CheckAndEscalate()
	fmt.Printf("  产生通知: %d条\n", len(notifications))
	monitor.GetTracker("sla-001")
	fmt.Println()
}

func demoApproval() {
	fmt.Println(">>> 模块8: 审批规则引擎模块")
	fmt.Println("------------------------------------------------------------")
	resolver := func(role, dept string, context map[string]interface{}) ([]approval.Approver, error) {
		if role == "tech_lead" {
			return []approval.Approver{{ID: "tl-1", Name: "技术主管", Role: "tech_lead"}}, nil
		}
		return []approval.Approver{}, nil
	}
	engine := approval.NewApprovalEngine(resolver)
	branches := []approval.Branch{
		{
			ID: "branch-low", Name: "低额审批",
			Conditions: []approval.Condition{
				{Field: "amount", Operator: approval.OpLessThan, Value: 10000},
			},
			Strategy:  approval.StrategyOrsign,
			Approvers: []approval.Approver{{ID: "mgr-1", Name: "部门经理", Role: "manager"}},
		},
		{
			ID: "branch-high", Name: "高额审批",
			Conditions: []approval.Condition{
				{Field: "amount", Operator: approval.OpGreaterThan, Value: 10000},
			},
			Strategy:  approval.StrategyCountersign,
			MinApproval: 2,
			Approvers: []approval.Approver{
				{ID: "mgr-1", Name: "部门经理", Role: "manager"},
				{ID: "dir-1", Name: "总监", Role: "director"},
				{ID: "tl-1", Name: "技术主管(动态)", Role: "tech_lead", External: true},
			},
		},
	}
	req1, _ := engine.CreateRequest("ap-001", "设备采购申请(低额)", "购买开发用显示器", "emp-1", "tenant-1",
		map[string]interface{}{"amount": 5000}, branches)
	fmt.Printf("  创建审批: %s", req1.Format())
	engine.Approve("ap-001", "mgr-1", "同意采购")
	fmt.Printf("  审批后: %s", req1.Format())
	req2, _ := engine.CreateRequest("ap-002", "服务器采购申请(高额)", "购买生产环境服务器", "emp-2", "tenant-1",
		map[string]interface{}{"amount": 50000}, branches)
	fmt.Printf("  创建审批: %s", req2.Format())
	engine.Approve("ap-002", "mgr-1", "同意")
	engine.Approve("ap-002", "dir-1", "同意")
	engine.Approve("ap-002", "tl-1", "技术方案可行,同意")
	fmt.Printf("  审批后: %s", req2.Format())
	fmt.Println()
}
