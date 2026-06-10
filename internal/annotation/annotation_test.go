package annotation

import (
	"pointcloud-platform/internal/testutil"
	"pointcloud-platform/pkg/math3d"
	"testing"
)

func TestAnnotation_TagsAndLabelGroups_Structure(t *testing.T) {
	assert := testutil.NewAssert(t)

	ann := &Annotation{
		ID:         "test-annot-1",
		DatasetID:  "dataset-1",
		Type:       AnnotationBBox3D,
		Label:      "测试建筑",
		CreatorID:  "user-1",
		Tags: map[string]string{
			"name":      "建筑物A",
			"type":      "永久结构",
			"material":  "钢筋混凝土",
			"floor":     "10",
			"year_built": "2020",
		},
		LabelGroups: []*LabelGroup{
			{
				GroupName: "结构信息",
				GroupType: "structural",
				Labels: map[string]string{
					"beam_type":  "I型梁",
					"column_type": "方柱",
				},
				Children: []*LabelGroup{
					{
						GroupName: "基础信息",
						Labels: map[string]string{
							"foundation_type": "桩基础",
							"depth":           "15m",
						},
					},
				},
			},
			{
				GroupName: "使用信息",
				GroupType: "usage",
				Labels: map[string]string{
					"primary_use":   "办公楼",
					"occupancy_rate": "95%",
				},
			},
		},
	}

	assert.Equal("建筑物A", ann.Tags["name"], "tag name should be '建筑物A'")
	assert.Equal("永久结构", ann.Tags["type"], "tag type should be '永久结构'")
	assert.Equal(5, len(ann.Tags), "should have 5 tags")

	assert.Equal(2, len(ann.LabelGroups), "should have 2 label groups")
	assert.Equal("结构信息", ann.LabelGroups[0].GroupName, "first group name should be '结构信息'")
	assert.Equal("I型梁", ann.LabelGroups[0].Labels["beam_type"], "beam_type label should be 'I型梁'")
	assert.Equal(1, len(ann.LabelGroups[0].Children), "should have 1 child group")
	assert.Equal("桩基础", ann.LabelGroups[0].Children[0].Labels["foundation_type"], "nested label should be '桩基础'")

	t.Log("Annotation structure with tags and label groups verified")
	t.Logf("  Tags: %v", ann.Tags)
	for i, group := range ann.LabelGroups {
		t.Logf("  LabelGroup %d: %s (%s)", i, group.GroupName, group.GroupType)
		for k, v := range group.Labels {
			t.Logf("    %s: %s", k, v)
		}
		for j, child := range group.Children {
			t.Logf("    Child %d: %s", j, child.GroupName)
			for k, v := range child.Labels {
				t.Logf("      %s: %s", k, v)
			}
		}
	}
}

func TestTagQueryCondition_Basic(t *testing.T) {
	assert := testutil.NewAssert(t)

	conditions := []TagQueryCondition{
		{
			Key:      "type",
			Operator: "exists",
		},
		{
			Key:      "name",
			Value:    "建筑物A",
			Operator: "equals",
		},
		{
			Key:      "material",
			Value:    "混凝土",
			Operator: "contains",
		},
		{
			Key:      "temp_tag",
			Operator: "not_exists",
		},
	}

	assert.Equal(4, len(conditions), "should have 4 conditions")
	assert.Equal("type", conditions[0].Key, "first condition key should be 'type'")
	assert.Equal("exists", conditions[0].Operator, "first condition operator should be 'exists'")
	assert.Equal("建筑物A", conditions[1].Value, "second condition value should be '建筑物A'")
	assert.Equal("contains", conditions[2].Operator, "third condition operator should be 'contains'")

	t.Log("Tag query conditions verified")
	for i, cond := range conditions {
		t.Logf("  Condition %d: key=%s, operator=%s, value=%v", i, cond.Key, cond.Operator, cond.Value)
	}
}

func TestLabelGroup_NestedStructure(t *testing.T) {
	assert := testutil.NewAssert(t)

	rootGroup := &LabelGroup{
		GroupName: "建筑信息",
		GroupType: "building",
		Labels: map[string]string{
			"building_type": "商业综合体",
			"total_area":    "50000sqm",
		},
		Children: []*LabelGroup{
			{
				GroupName: "楼层信息",
				Labels: map[string]string{
					"above_ground": "25",
					"underground":  "3",
				},
				Children: []*LabelGroup{
					{
						GroupName: "电梯配置",
						Labels: map[string]string{
							"passenger": "8",
							"freight":   "2",
						},
					},
				},
			},
			{
				GroupName: "设施信息",
				Labels: map[string]string{
					"parking_spaces": "500",
					"hvac_type":      "中央空调",
				},
			},
		},
	}

	assert.Equal("建筑信息", rootGroup.GroupName, "root group name should be '建筑信息'")
	assert.Equal(2, len(rootGroup.Children), "root should have 2 children")
	assert.Equal("25", rootGroup.Children[0].Labels["above_ground"], "above_ground should be '25'")
	assert.Equal(1, len(rootGroup.Children[0].Children), "floor group should have 1 child")
	assert.Equal("8", rootGroup.Children[0].Children[0].Labels["passenger"], "passenger elevators should be '8'")
	assert.Equal("500", rootGroup.Children[1].Labels["parking_spaces"], "parking spaces should be '500'")

	t.Log("Nested label group structure verified")
	printLabelGroup(rootGroup, 0, t)
}

func printLabelGroup(group *LabelGroup, depth int, t *testing.T) {
	indent := ""
	for i := 0; i < depth; i++ {
		indent += "  "
	}
	t.Logf("%sGroup: %s (%s)", indent, group.GroupName, group.GroupType)
	for k, v := range group.Labels {
		t.Logf("%s  %s: %s", indent, k, v)
	}
	for _, child := range group.Children {
		printLabelGroup(child, depth+1, t)
	}
}

func TestAnnotation_UpdateTags_Merge(t *testing.T) {
	assert := testutil.NewAssert(t)

	ann := &Annotation{
		ID:        "test-annot-2",
		DatasetID: "dataset-1",
		Type:      AnnotationBBox3D,
		Tags: map[string]string{
			"name": "建筑物A",
			"type": "永久结构",
		},
		CreatorID: "user-1",
	}

	assert.Equal(2, len(ann.Tags), "should have 2 initial tags")
	assert.Equal("建筑物A", ann.Tags["name"], "initial name should be '建筑物A'")

	updatedTags := map[string]string{
		"type":   "临时结构",
		"status": "在建",
	}

	for k, v := range updatedTags {
		ann.Tags[k] = v
	}

	assert.Equal(3, len(ann.Tags), "should have 3 tags after merge")
	assert.Equal("建筑物A", ann.Tags["name"], "name should remain unchanged")
	assert.Equal("临时结构", ann.Tags["type"], "type should be updated")
	assert.Equal("在建", ann.Tags["status"], "status should be added")

	t.Log("Tag merge behavior verified")
	t.Logf("  Final tags: %v", ann.Tags)
}

func TestAnnotation_EmptyTagsAndLabelGroups(t *testing.T) {
	assert := testutil.NewAssert(t)

	ann := &Annotation{
		ID:         "test-annot-3",
		DatasetID:  "dataset-1",
		Type:       AnnotationPoint,
		Label:      "测试点",
		CreatorID:  "user-1",
		Tags:       nil,
		LabelGroups: nil,
	}

	assert.Nil(ann.Tags, "tags should be nil")
	assert.Nil(ann.LabelGroups, "label groups should be nil")
	assert.Equal(0, len(ann.Tags), "nil tags should have length 0")
	assert.Equal(0, len(ann.LabelGroups), "nil label groups should have length 0")

	ann.Tags = make(map[string]string)
	ann.LabelGroups = make([]*LabelGroup, 0)

	assert.NotNil(ann.Tags, "tags should not be nil after init")
	assert.NotNil(ann.LabelGroups, "label groups should not be nil after init")
	assert.Equal(0, len(ann.Tags), "empty tags should have length 0")
	assert.Equal(0, len(ann.LabelGroups), "empty label groups should have length 0")

	t.Log("Empty tags and label groups behavior verified")
}

func TestAnnotationIntersectsBounds_WithTags(t *testing.T) {
	assert := testutil.NewAssert(t)

	service := NewAnnotationService()

	ann := &Annotation{
		ID:        "test-annot-4",
		DatasetID: "dataset-1",
		Type:      AnnotationBBox3D,
		Label:     "带标签的建筑",
		Geometry: BBox3D{
			Center: math3d.Vec3{X: 100, Y: 200, Z: 50},
			Size:   math3d.Vec3{X: 50, Y: 50, Z: 30},
		},
		Tags: map[string]string{
			"name": "测试建筑",
			"type": "办公楼",
		},
		CreatorID: "user-1",
	}

	boundsContaining := math3d.AABB{
		Min: math3d.Vec3{X: 0, Y: 0, Z: 0},
		Max: math3d.Vec3{X: 200, Y: 300, Z: 100},
	}

	boundsOutside := math3d.AABB{
		Min: math3d.Vec3{X: 1000, Y: 1000, Z: 1000},
		Max: math3d.Vec3{X: 1100, Y: 1100, Z: 1100},
	}

	boundsPartial := math3d.AABB{
		Min: math3d.Vec3{X: 75, Y: 175, Z: 25},
		Max: math3d.Vec3{X: 125, Y: 225, Z: 75},
	}

	assert.True(service.AnnotationIntersectsBounds(ann, boundsContaining), "annotation should be inside containing bounds")
	assert.False(service.AnnotationIntersectsBounds(ann, boundsOutside), "annotation should be outside bounds")
	assert.True(service.AnnotationIntersectsBounds(ann, boundsPartial), "annotation should intersect partial bounds")

	assert.Equal("测试建筑", ann.Tags["name"], "tags should remain intact after intersection check")
	assert.Equal("办公楼", ann.Tags["type"], "tags should remain intact after intersection check")

	t.Log("Annotation intersection with tags verified")
	t.Logf("  Annotation center: (%.1f, %.1f, %.1f)", ann.Geometry.(BBox3D).Center.X, ann.Geometry.(BBox3D).Center.Y, ann.Geometry.(BBox3D).Center.Z)
	t.Logf("  Tags: %v", ann.Tags)
}

func TestTagQueryCondition_Validation(t *testing.T) {
	assert := testutil.NewAssert(t)

	testCases := []struct {
		name     string
		cond     TagQueryCondition
		validKey bool
	}{
		{
			name: "exists operator with key",
			cond: TagQueryCondition{
				Key:      "test_key",
				Operator: "exists",
			},
			validKey: true,
		},
		{
			name: "equals operator with key and value",
			cond: TagQueryCondition{
				Key:      "test_key",
				Value:    "test_value",
				Operator: "equals",
			},
			validKey: true,
		},
		{
			name: "contains operator with pattern",
			cond: TagQueryCondition{
				Key:      "description",
				Value:    "test",
				Operator: "contains",
			},
			validKey: true,
		},
		{
			name: "not_exists operator",
			cond: TagQueryCondition{
				Key:      "deprecated_tag",
				Operator: "not_exists",
			},
			validKey: true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			assert.Equal(true, tc.validKey, "condition should have valid key")
			assert.NotEmpty(tc.cond.Key, "condition key should not be empty")
			assert.NotEmpty(tc.cond.Operator, "condition operator should not be empty")
		})
	}

	t.Log("All tag query conditions validated")
}

func TestLabelGroupQuery_Basic(t *testing.T) {
	assert := testutil.NewAssert(t)

	query := LabelGroupQuery{
		GroupName: "结构信息",
		Labels: []TagQueryCondition{
			{
				Key:      "beam_type",
				Value:    "I型梁",
				Operator: "equals",
			},
			{
				Key:      "column_type",
				Operator: "exists",
			},
		},
	}

	assert.Equal("结构信息", query.GroupName, "group name should be '结构信息'")
	assert.Equal(2, len(query.Labels), "should have 2 label conditions")
	assert.Equal("beam_type", query.Labels[0].Key, "first label key should be 'beam_type'")
	assert.Equal("I型梁", query.Labels[0].Value, "first label value should be 'I型梁'")
	assert.Equal("exists", query.Labels[1].Operator, "second operator should be 'exists'")

	t.Log("Label group query verified")
	t.Logf("  Group: %s", query.GroupName)
	for i, label := range query.Labels {
		t.Logf("  Label %d: %s %s %v", i, label.Key, label.Operator, label.Value)
	}
}
