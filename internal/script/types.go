package script

import "time"

type TestScript struct {
	Name      string               `yaml:"name"`
	Description string             `yaml:"description"`
	Env       string               `yaml:"env"`
	Variables map[string]VariableDef `yaml:"variables"`
	Steps     []Step               `yaml:"steps"`
	Output    map[string]string    `yaml:"output"`
}

type VariableDef struct {
	Value string `yaml:"value"`
	Env   string `yaml:"env"`
	Shell string `yaml:"shell"`
}

type Step struct {
	Name     string            `yaml:"name"`
	Protocol string            `yaml:"protocol"`
	Request  RequestDef        `yaml:"request"`
	Extract  map[string]ExtractDef `yaml:"extract"`
	Assert   []AssertDef       `yaml:"assert"`
	Delay    string            `yaml:"delay"`
	Loop     LoopDef           `yaml:"loop"`
}

type RequestDef struct {
	Method    string            `yaml:"method"`
	URL       string            `yaml:"url"`
	Headers   map[string]string `yaml:"headers"`
	Body      string            `yaml:"body"`
	Query     string            `yaml:"query"`
	Service   string            `yaml:"service"`
	GrpcMethod string           `yaml:"grpc_method" json:"grpc_method"`
	Message   string            `yaml:"message"`
	Timeout   int               `yaml:"timeout"`
}

type ExtractDef struct {
	From     string `yaml:"from"`
	JSONPath string `yaml:"jsonpath"`
	Header   string `yaml:"header"`
	Regex    string `yaml:"regex"`
}

type AssertDef struct {
	Type     string      `yaml:"type"`
	Expected interface{} `yaml:"expected"`
	JSONPath string      `yaml:"jsonpath"`
	Operator string      `yaml:"operator"`
}

type LoopDef struct {
	Count    int    `yaml:"count"`
	While    string `yaml:"while"`
	Interval string `yaml:"interval"`
}

type StepResult struct {
	StepName   string         `yaml:"step_name"`
	Status     string         `yaml:"status"`
	Response   interface{}    `yaml:"response"`
	Extracted  map[string]string `yaml:"extracted"`
	Duration   time.Duration  `yaml:"duration"`
	Error      string         `yaml:"error"`
	Assertions []AssertResult `yaml:"assertions"`
}

type AssertResult struct {
	Assert  AssertDef  `yaml:"assert"`
	Pass    bool       `yaml:"pass"`
	Actual  interface{} `yaml:"actual"`
	Message string     `yaml:"message"`
}

type RunResult struct {
	ScriptName    string         `yaml:"script_name"`
	Status        string         `yaml:"status"`
	Steps         []StepResult   `yaml:"steps"`
	TotalDuration time.Duration  `yaml:"total_duration"`
	Variables     map[string]string `yaml:"variables"`
}
