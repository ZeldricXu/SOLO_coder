package classification

import "time"

var defaultScenarios = map[string]*ClassificationScenario{
	"default": {
		Name:        "default",
		Description: "默认分类分级策略，适用于通用场景",
		Patterns: []ScenarioPattern{
			{"phone", `1[3-9]\d{9}`, "high", "personal_identity", 4, true},
			{"id_card", `[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]`, "high", "personal_identity", 5, true},
			{"email", `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`, "medium", "contact_info", 3, true},
			{"bank_card", `\d{16,19}`, "high", "financial", 5, true},
			{"address", `(北京|上海|广州|深圳|杭州|成都|武汉|西安|南京|重庆)[市]?[\u4e00-\u9fa5]{2,}(区|县|街道|镇|路|街|巷|号|楼|栋|单元|室)`, "medium", "location", 3, true},
		},
		Policies: []ScenarioPolicy{
			{1, "none", "公开数据，无限制", true},
			{2, "log", "内部数据，记录访问日志", true},
			{3, "mask", "敏感数据，默认脱敏显示", true},
			{4, "encrypt", "高敏感数据，加密存储与传输", true},
			{5, "restrict", "核心敏感数据，严格访问控制", true},
		},
		Rules: map[string]interface{}{
			"auto_detect": true,
			"scan_depth":  3,
		},
		Enabled:   true,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	},
	"financial": {
		Name:        "financial",
		Description: "金融行业专用策略，强化财务数据保护",
		Patterns: []ScenarioPattern{
			{"phone", `1[3-9]\d{9}`, "high", "personal_identity", 4, true},
			{"id_card", `[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]`, "high", "personal_identity", 5, true},
			{"bank_card", `\d{16,19}`, "high", "financial", 5, true},
			{"credit_card", `\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}`, "high", "financial", 5, true},
			{"cvv", `\d{3,4}`, "high", "financial", 5, true},
			{"iban", `[A-Z]{2}\d{2}[A-Z0-9]{1,30}`, "high", "financial", 5, true},
			{"swift", `[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?`, "high", "financial", 5, true},
			{"amount", `\d+\.?\d*\s*(元|￥|CNY|USD|\$)`, "medium", "financial", 3, true},
			{"email", `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`, "medium", "contact_info", 3, true},
			{"address", `(北京|上海|广州|深圳|杭州|成都|武汉|西安|南京|重庆)[市]?[\u4e00-\u9fa5]{2,}(区|县|街道|镇|路|街|巷|号|楼|栋|单元|室)`, "medium", "location", 3, true},
		},
		Policies: []ScenarioPolicy{
			{1, "none", "公开数据，无限制", true},
			{2, "log", "内部数据，记录访问日志", true},
			{3, "mask", "敏感数据，默认脱敏显示", true},
			{4, "encrypt", "高敏感数据，加密存储与传输", true},
			{5, "restrict", "核心敏感数据，严格访问控制", true},
		},
		Rules: map[string]interface{}{
			"encrypt_fields": []string{"bank_card", "credit_card", "cvv"},
			"require_audit":  true,
			"scan_depth":     5,
		},
		Enabled:   true,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	},
	"healthcare": {
		Name:        "healthcare",
		Description: "医疗健康行业策略，强化患者隐私保护",
		Patterns: []ScenarioPattern{
			{"phone", `1[3-9]\d{9}`, "high", "personal_identity", 4, true},
			{"id_card", `[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]`, "high", "personal_identity", 5, true},
			{"medical_record", `(病历|诊断|处方|检查|检验)[号]?[:：]?\s*[A-Za-z0-9]{5,}`, "high", "health", 5, true},
			{"insurance_id", `[A-Z0-9]{8,20}`, "high", "health", 4, true},
			{"disease", `(癌症|肿瘤|艾滋|乙肝|丙肝|梅毒|淋病|精神)`, "high", "health", 5, true},
			{"drug", `(吗啡|海洛因|可卡因|冰毒|摇头丸)`, "high", "health", 5, true},
			{"email", `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`, "medium", "contact_info", 3, true},
			{"address", `(北京|上海|广州|深圳|杭州|成都|武汉|西安|南京|重庆)[市]?[\u4e00-\u9fa5]{2,}(区|县|街道|镇|路|街|巷|号|楼|栋|单元|室)`, "medium", "location", 3, true},
		},
		Policies: []ScenarioPolicy{
			{1, "none", "公开数据，无限制", true},
			{2, "log", "内部数据，记录访问日志", true},
			{3, "mask", "敏感数据，默认脱敏显示", true},
			{4, "encrypt", "高敏感数据，加密存储与传输", true},
			{5, "restrict", "核心敏感数据，严格访问控制", true},
		},
		Rules: map[string]interface{}{
			"de_identification": true,
			"require_consent":   true,
			"scan_depth":        5,
		},
		Enabled:   true,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	},
	"government": {
		Name:        "government",
		Description: "政府机关专用策略，严格等级保护",
		Patterns: []ScenarioPattern{
			{"phone", `1[3-9]\d{9}`, "high", "personal_identity", 4, true},
			{"id_card", `[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]`, "high", "personal_identity", 5, true},
			{"id_government", `(京|沪|粤|深)[0-9]{8,}`, "high", "government", 5, true},
			{"secret", `(机密|绝密|秘密|内部)`, "high", "government", 5, true},
			{"document_num", `(政|办|发|函|号)〔\d{4}〕\d+号`, "high", "government", 5, true},
			{"email", `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`, "medium", "contact_info", 3, true},
			{"address", `(北京|上海|广州|深圳|杭州|成都|武汉|西安|南京|重庆)[市]?[\u4e00-\u9fa5]{2,}(区|县|街道|镇|路|街|巷|号|楼|栋|单元|室)`, "medium", "location", 3, true},
		},
		Policies: []ScenarioPolicy{
			{1, "none", "公开数据，无限制", true},
			{2, "log", "内部数据，记录访问日志", true},
			{3, "mask", "敏感数据，默认脱敏显示", true},
			{4, "encrypt", "高敏感数据，加密存储与传输", true},
			{5, "restrict", "核心敏感数据，严格访问控制", true},
		},
		Rules: map[string]interface{}{
			"level_protection": "level3",
			"watermark":        true,
			"scan_depth":       10,
		},
		Enabled:   true,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	},
	"marketing": {
		Name:        "marketing",
		Description: "市场营销场景策略，平衡隐私与可用性",
		Patterns: []ScenarioPattern{
			{"phone", `1[3-9]\d{9}`, "high", "personal_identity", 4, true},
			{"email", `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`, "medium", "contact_info", 3, true},
			{"address", `(北京|上海|广州|深圳|杭州|成都|武汉|西安|南京|重庆)[市]?[\u4e00-\u9fa5]{2,}(区|县|街道|镇|路|街|巷|号|楼|栋|单元|室)`, "medium", "location", 3, true},
			{"consumption", `(消费|购买|订单|金额)[:：]?\s*\d+`, "low", "behavior", 2, true},
			{"preference", `(兴趣|爱好|偏好)[:：]?\s*[\u4e00-\u9fa5]+`, "low", "behavior", 2, true},
			{"id_card", `[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]`, "high", "personal_identity", 5, true},
		},
		Policies: []ScenarioPolicy{
			{1, "none", "公开数据，无限制", true},
			{2, "none", "行为数据，可用于分析", true},
			{3, "mask", "敏感数据，默认脱敏显示", true},
			{4, "encrypt", "高敏感数据，加密存储与传输", true},
			{5, "restrict", "核心敏感数据，严格访问控制", true},
		},
		Rules: map[string]interface{}{
			"allow_aggregation": true,
			"anonymization":     true,
			"scan_depth":        2,
		},
		Enabled:   true,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	},
}

func GetDefaultScenarios() map[string]*ClassificationScenario {
	return defaultScenarios
}

func GetScenario(name string) (*ClassificationScenario, bool) {
	scenario, ok := defaultScenarios[name]
	return scenario, ok
}

func LoadDefaultScenario(name string) (*ClassificationScenario, error) {
	scenario, ok := defaultScenarios[name]
	if !ok {
		return nil, nil
	}
	return scenario, nil
}
