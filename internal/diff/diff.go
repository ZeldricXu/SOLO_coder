package diff

import (
	"fmt"
	"io"
	"os"
	"sort"
	"strings"

	"github.com/fatih/color"
	"github.com/multicloud/cli/internal/common"
	"github.com/multicloud/cli/internal/planner"
)

type DiffEngine struct {
	useColor bool
	writer   io.Writer
}

type DiffResult struct {
	Additions map[string]interface{}
	Removals  map[string]interface{}
	Changes   map[string]ChangeDetail
}

type ChangeDetail struct {
	Old  interface{}
	New  interface{}
	Path string
}

type DiffFormatter struct {
	useColor bool
	writer   io.Writer
}

func NewDiffEngine() *DiffEngine {
	return &DiffEngine{
		useColor: true,
		writer:   os.Stdout,
	}
}

func (e *DiffEngine) SetUseColor(useColor bool) {
	e.useColor = useColor
}

func (e *DiffEngine) SetWriter(w io.Writer) {
	e.writer = w
}

func (e *DiffEngine) ComputeDiff(old, new map[string]interface{}) *DiffResult {
	result := &DiffResult{
		Additions: make(map[string]interface{}),
		Removals:  make(map[string]interface{}),
		Changes:   make(map[string]ChangeDetail),
	}

	allKeys := make(map[string]bool)
	for k := range old {
		allKeys[k] = true
	}
	for k := range new {
		allKeys[k] = true
	}

	for k := range allKeys {
		oldVal, oldExists := old[k]
		newVal, newExists := new[k]

		switch {
		case !oldExists && newExists:
			result.Additions[k] = newVal
		case oldExists && !newExists:
			result.Removals[k] = oldVal
		case oldExists && newExists:
			if !valuesEqual(oldVal, newVal) {
				result.Changes[k] = ChangeDetail{
					Old:  oldVal,
					New:  newVal,
					Path: k,
				}
			}
		}
	}

	return result
}

func valuesEqual(a, b interface{}) bool {
	if a == nil && b == nil {
		return true
	}
	if a == nil || b == nil {
		return false
	}

	switch av := a.(type) {
	case map[string]interface{}:
		bv, ok := b.(map[string]interface{})
		if !ok {
			return false
		}
		if len(av) != len(bv) {
			return false
		}
		for k, v := range av {
			if !valuesEqual(v, bv[k]) {
				return false
			}
		}
		return true
	case []interface{}:
		bv, ok := b.([]interface{})
		if !ok {
			return false
		}
		if len(av) != len(bv) {
			return false
		}
		for i, v := range av {
			if !valuesEqual(v, bv[i]) {
				return false
			}
		}
		return true
	case string:
		bv, ok := b.(string)
		return ok && av == bv
	case int:
		bv, ok := b.(int)
		return ok && av == bv
	case int64:
		bv, ok := b.(int64)
		return ok && av == bv
	case float64:
		bv, ok := b.(float64)
		return ok && av == bv
	case bool:
		bv, ok := b.(bool)
		return ok && av == bv
	default:
		return fmt.Sprintf("%v", a) == fmt.Sprintf("%v", b)
	}
}

func NewDiffFormatter() *DiffFormatter {
	return &DiffFormatter{
		useColor: true,
		writer:   os.Stdout,
	}
}

func (f *DiffFormatter) SetUseColor(useColor bool) {
	f.useColor = useColor
}

func (f *DiffFormatter) SetWriter(w io.Writer) {
	f.writer = w
}

func (f *DiffFormatter) FormatPlan(plan *planner.Plan) string {
	var sb strings.Builder

	green := color.New(color.FgGreen).SprintFunc()
	red := color.New(color.FgRed).SprintFunc()
	yellow := color.New(color.FgYellow).SprintFunc()
	cyan := color.New(color.FgCyan).SprintFunc()
	bold := color.New(color.Bold).SprintFunc()

	if !f.useColor {
		green = func(a ...interface{}) string { return fmt.Sprint(a...) }
		red = func(a ...interface{}) string { return fmt.Sprint(a...) }
		yellow = func(a ...interface{}) string { return fmt.Sprint(a...) }
		cyan = func(a ...interface{}) string { return fmt.Sprint(a...) }
		bold = func(a ...interface{}) string { return fmt.Sprint(a...) }
	}

	summary := plan.Summary()
	sb.WriteString(bold("\nMulti-Cloud Infrastructure Plan\n"))
	sb.WriteString(strings.Repeat("=", 60) + "\n\n")

	sb.WriteString(fmt.Sprintf("Summary: %s to add, %s to change, %s to destroy.\n\n",
		green(fmt.Sprintf("%d", summary["create"])),
		yellow(fmt.Sprintf("%d", summary["update"])),
		red(fmt.Sprintf("%d", summary["delete"]))))

	if len(plan.Create) > 0 {
		sb.WriteString(bold(green("Resources to create:\n")))
		sb.WriteString(strings.Repeat("-", 60) + "\n")
		for _, node := range plan.Create {
			sb.WriteString(fmt.Sprintf("  %s %s\n", green("+"), cyan(node.Name)))
			if node.Resource != nil {
				sb.WriteString(fmt.Sprintf("    provider: %s\n", node.Resource.Provider))
				sb.WriteString(fmt.Sprintf("    type:     %s\n", node.Resource.Type))
				sb.WriteString(fmt.Sprintf("    region:   %s\n", node.Resource.Region))
			}
		}
		sb.WriteString("\n")
	}

	if len(plan.Update) > 0 {
		sb.WriteString(bold(yellow("Resources to update:\n")))
		sb.WriteString(strings.Repeat("-", 60) + "\n")
		for _, node := range plan.Update {
			sb.WriteString(fmt.Sprintf("  %s %s\n", yellow("~"), cyan(node.Name)))
			for _, change := range plan.Changes {
				if change.ResourceName == node.Name && change.Diff != nil {
					keys := make([]string, 0, len(change.Diff))
					for k := range change.Diff {
						keys = append(keys, k)
					}
					sort.Strings(keys)
					for _, k := range keys {
						d := change.Diff[k]
						sb.WriteString(f.formatDiffItem(d, green, red, yellow))
					}
				}
			}
		}
		sb.WriteString("\n")
	}

	if len(plan.Delete) > 0 {
		sb.WriteString(bold(red("Resources to destroy:\n")))
		sb.WriteString(strings.Repeat("-", 60) + "\n")
		for _, node := range plan.Delete {
			sb.WriteString(fmt.Sprintf("  %s %s\n", red("-"), cyan(node.Name)))
		}
		sb.WriteString("\n")
	}

	if len(plan.Noop) > 0 {
		sb.WriteString(fmt.Sprintf("No changes: %d resource(s) already up-to-date.\n\n", len(plan.Noop)))
	}

	if len(plan.ParallelGroups) > 0 {
		sb.WriteString(bold("Deployment order:\n"))
		sb.WriteString(strings.Repeat("-", 60) + "\n")
		for i, group := range plan.ParallelGroups {
			names := make([]string, len(group))
			for j, node := range group {
				names[j] = node.Name
			}
			sb.WriteString(fmt.Sprintf("  Stage %d: %s\n", i+1, strings.Join(names, ", ")))
		}
		sb.WriteString("\n")
	}

	return sb.String()
}

func (f *DiffFormatter) formatDiffItem(d common.DiffItem, green, red, yellow func(...interface{}) string) string {
	var sb strings.Builder

	switch d.ChangeType {
	case "add":
		sb.WriteString(fmt.Sprintf("      %s %s = %s\n",
			green("+"), d.Path, f.formatValue(d.New, green)))
	case "remove":
		sb.WriteString(fmt.Sprintf("      %s %s = %s\n",
			red("-"), d.Path, f.formatValue(d.Old, red)))
	case "update":
		sb.WriteString(fmt.Sprintf("      %s %s: %s -> %s\n",
			yellow("~"), d.Path, f.formatValue(d.Old, red), f.formatValue(d.New, green)))
	}

	return sb.String()
}

func (f *DiffFormatter) formatValue(v interface{}, colorFunc func(...interface{}) string) string {
	if v == nil {
		return colorFunc("null")
	}
	switch val := v.(type) {
	case string:
		return colorFunc(fmt.Sprintf("%q", val))
	case bool:
		return colorFunc(fmt.Sprintf("%t", val))
	default:
		return colorFunc(fmt.Sprintf("%v", val))
	}
}

func (f *DiffFormatter) PrintPlan(plan *planner.Plan) {
	fmt.Fprint(f.writer, f.FormatPlan(plan))
}

func (f *DiffFormatter) FormatChanges(changes []*common.Change) string {
	var sb strings.Builder

	green := color.New(color.FgGreen).SprintFunc()
	red := color.New(color.FgRed).SprintFunc()
	yellow := color.New(color.FgYellow).SprintFunc()
	cyan := color.New(color.FgCyan).SprintFunc()
	bold := color.New(color.Bold).SprintFunc()

	if !f.useColor {
		green = func(a ...interface{}) string { return fmt.Sprint(a...) }
		red = func(a ...interface{}) string { return fmt.Sprint(a...) }
		yellow = func(a ...interface{}) string { return fmt.Sprint(a...) }
		cyan = func(a ...interface{}) string { return fmt.Sprint(a...) }
		bold = func(a ...interface{}) string { return fmt.Sprint(a...) }
	}

	sb.WriteString(bold("\nDetailed Changes\n"))
	sb.WriteString(strings.Repeat("=", 60) + "\n\n")

	for _, change := range changes {
		switch change.Action {
		case common.ActionCreate:
			sb.WriteString(fmt.Sprintf("%s %s\n", green("+"), bold(cyan(change.ResourceName))))
			sb.WriteString(fmt.Sprintf("  Action: %s\n", green("create")))
			if change.New != nil {
				sb.WriteString(fmt.Sprintf("  Provider: %s\n", change.New.Provider))
				sb.WriteString(fmt.Sprintf("  Type: %s\n", change.New.Type))
				sb.WriteString(fmt.Sprintf("  Region: %s\n", change.New.Region))
				if len(change.New.Properties) > 0 {
					sb.WriteString("  Properties:\n")
					keys := make([]string, 0, len(change.New.Properties))
					for k := range change.New.Properties {
						keys = append(keys, k)
					}
					sort.Strings(keys)
					for _, k := range keys {
						sb.WriteString(fmt.Sprintf("    %s: %v\n", k, change.New.Properties[k]))
					}
				}
			}
		case common.ActionDelete:
			sb.WriteString(fmt.Sprintf("%s %s\n", red("-"), bold(cyan(change.ResourceName))))
			sb.WriteString(fmt.Sprintf("  Action: %s\n", red("delete")))
			if change.Old != nil {
				sb.WriteString(fmt.Sprintf("  ID: %s\n", change.Old.ID))
				sb.WriteString(fmt.Sprintf("  Provider: %s\n", change.Old.Provider))
			}
		case common.ActionUpdate:
			sb.WriteString(fmt.Sprintf("%s %s\n", yellow("~"), bold(cyan(change.ResourceName))))
			sb.WriteString(fmt.Sprintf("  Action: %s\n", yellow("update")))
			if change.Diff != nil && len(change.Diff) > 0 {
				sb.WriteString("  Changes:\n")
				keys := make([]string, 0, len(change.Diff))
				for k := range change.Diff {
					keys = append(keys, k)
				}
				sort.Strings(keys)
				for _, k := range keys {
					d := change.Diff[k]
					sb.WriteString(f.formatDiffItem(d, green, red, yellow))
				}
			}
		case common.ActionNoop:
			sb.WriteString(fmt.Sprintf("= %s\n", bold(cyan(change.ResourceName))))
			sb.WriteString("  Action: no changes\n")
		}
		sb.WriteString("\n")
	}

	return sb.String()
}

func (f *DiffFormatter) PrintChanges(changes []*common.Change) {
	fmt.Fprint(f.writer, f.FormatChanges(changes))
}

func (f *DiffFormatter) FormatResourceDiff(old *common.Resource, new *common.ResourceConfig) string {
	diff := make(map[string]common.DiffItem)

	if old == nil {
		return fmt.Sprintf("New resource: %s\n", new.Name)
	}

	green := color.New(color.FgGreen).SprintFunc()
	red := color.New(color.FgRed).SprintFunc()
	yellow := color.New(color.FgYellow).SprintFunc()

	if !f.useColor {
		green = func(a ...interface{}) string { return fmt.Sprint(a...) }
		red = func(a ...interface{}) string { return fmt.Sprint(a...) }
		yellow = func(a ...interface{}) string { return fmt.Sprint(a...) }
	}

	if old.Type != new.Type {
		diff["type"] = common.DiffItem{
			Old:        old.Type,
			New:        new.Type,
			Path:       "type",
			ChangeType: "update",
		}
	}

	for k, v := range new.Properties {
		if oldV, ok := old.Properties[k]; !ok {
			diff["properties."+k] = common.DiffItem{
				Old:        nil,
				New:        v,
				Path:       "properties." + k,
				ChangeType: "add",
			}
		} else if oldV != v {
			diff["properties."+k] = common.DiffItem{
				Old:        oldV,
				New:        v,
				Path:       "properties." + k,
				ChangeType: "update",
			}
		}
	}

	for k, v := range old.Properties {
		if _, ok := new.Properties[k]; !ok {
			diff["properties."+k] = common.DiffItem{
				Old:        v,
				New:        nil,
				Path:       "properties." + k,
				ChangeType: "remove",
			}
		}
	}

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("Resource: %s\n", old.Name))
	keys := make([]string, 0, len(diff))
	for k := range diff {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		d := diff[k]
		sb.WriteString(f.formatDiffItem(d, green, red, yellow))
	}

	return sb.String()
}

func (f *DiffFormatter) Colorize(text string, colorName string) string {
	if !f.useColor {
		return text
	}

	switch strings.ToLower(colorName) {
	case "green":
		return color.New(color.FgGreen).Sprint(text)
	case "red":
		return color.New(color.FgRed).Sprint(text)
	case "yellow":
		return color.New(color.FgYellow).Sprint(text)
	case "blue":
		return color.New(color.FgBlue).Sprint(text)
	case "cyan":
		return color.New(color.FgCyan).Sprint(text)
	case "magenta":
		return color.New(color.FgMagenta).Sprint(text)
	case "bold":
		return color.New(color.Bold).Sprint(text)
	default:
		return text
	}
}
