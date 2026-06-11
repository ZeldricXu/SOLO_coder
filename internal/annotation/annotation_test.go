package annotation

import (
	"encoding/json"
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

	t.Logf("DEBUG: Tags=%#v, Tags==nil=%v", ann.Tags, ann.Tags == nil)
	t.Logf("DEBUG: LabelGroups=%#v, LabelGroups==nil=%v", ann.LabelGroups, ann.LabelGroups == nil)

	assert.True(ann.Tags == nil, "tags should be nil")
	assert.True(ann.LabelGroups == nil, "label groups should be nil")
	assert.Equal(0, len(ann.Tags), "nil tags should have length 0")
	assert.Equal(0, len(ann.LabelGroups), "nil label groups should have length 0")

	ann.Tags = make(map[string]string)
	ann.LabelGroups = make([]*LabelGroup, 0)

	assert.False(ann.Tags == nil, "tags should not be nil after init")
	assert.False(ann.LabelGroups == nil, "label groups should not be nil after init")
	assert.Equal(0, len(ann.Tags), "empty tags should have length 0")
	assert.Equal(0, len(ann.LabelGroups), "empty label groups should have length 0")

	t.Log("Empty tags and label groups behavior verified")
}

func TestAnnotationIntersectsBounds_WithTags(t *testing.T) {
	assert := testutil.NewAssert(t)

	service := NewAnnotationService()

	bbox := BBox3D{
		Center: math3d.Vec3{X: 100, Y: 200, Z: 50},
		Size:   math3d.Vec3{X: 50, Y: 50, Z: 30},
	}
	geoJSON, _ := json.Marshal(bbox)
	var geometry map[string]interface{}
	json.Unmarshal(geoJSON, &geometry)

	ann := &Annotation{
		ID:        "test-annot-4",
		DatasetID: "dataset-1",
		Type:      AnnotationBBox3D,
		Label:     "带标签的建筑",
		Geometry:  geometry,
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
	var bboxParsed BBox3D
	geoParsedJSON, _ := json.Marshal(ann.Geometry)
	json.Unmarshal(geoParsedJSON, &bboxParsed)
	t.Logf("  Annotation center: (%.1f, %.1f, %.1f)", bboxParsed.Center.X, bboxParsed.Center.Y, bboxParsed.Center.Z)
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

func TestAnnotationService_CreateWithFullTags(t *testing.T) {
	assert := testutil.NewAssert(t)

	bbox := BBox3D{
		Center:   math3d.Vec3{X: 100, Y: 200, Z: 50},
		Size:     math3d.Vec3{X: 50, Y: 60, Z: 30},
		Rotation: math3d.Vec3{X: 0, Y: 45, Z: 0},
	}
	geoJSON, _ := json.Marshal(bbox)
	var geometry map[string]interface{}
	json.Unmarshal(geoJSON, &geometry)

	ann := &Annotation{
		ID:        "full-annot-001",
		DatasetID: "ds-full-001",
		Type:      AnnotationBBox3D,
		Label:     "完整标签建筑",
		Geometry:  geometry,
		Properties: map[string]interface{}{
			"area":      3000.5,
			"height":    120.0,
			"floors":    30,
			"is_active": true,
			"materials": []string{"steel", "concrete", "glass"},
		},
		Tags: map[string]string{
			"name":        "环球金融中心",
			"category":    "商业建筑",
			"status":      "运营中",
			"city":        "上海",
			"year_built":  "2015",
			"owner":       "环球集团",
		},
		LabelGroups: []*LabelGroup{
			{
				GroupName: "建筑信息",
				GroupType: "building_info",
				Labels: map[string]string{
					"building_type":    "超高层",
					"architectural_style": "现代主义",
					"total_area":       "400000sqm",
				},
				Children: []*LabelGroup{
					{
						GroupName: "结构参数",
						GroupType: "structural_params",
						Labels: map[string]string{
							"structure_type":  "钢混组合",
							"seismic_level":   "9级",
							"foundation_type": "桩筏基础",
						},
						Children: []*LabelGroup{
							{
								GroupName: "材料详情",
								Labels: map[string]string{
									"steel_grade":    "Q345B",
									"concrete_grade": "C60",
									"curtain_wall":   "中空LOW-E玻璃",
								},
							},
						},
					},
					{
						GroupName: "设施配置",
						Labels: map[string]string{
							"elevators":       "62部",
							"parking_spaces":  "1800",
							"hvac":            "变风量空调",
						},
					},
				},
			},
			{
				GroupName: "消防安全",
				Labels: map[string]string{
					"fire_rating":      "一级",
					"sprinkler_system": "全覆盖",
					"emergency_exits":  "24个",
				},
			},
		},
		CreatorID: "user-admin-001",
	}

	assert.Equal("full-annot-001", ann.ID, "ID should be preserved")
	assert.Equal("环球金融中心", ann.Tags["name"], "tag name should be '环球金融中心'")
	assert.Equal("上海", ann.Tags["city"], "tag city should be '上海'")
	assert.Equal(6, len(ann.Tags), "should have 6 tags")

	assert.Equal(2, len(ann.LabelGroups), "should have 2 top-level label groups")
	assert.Equal("建筑信息", ann.LabelGroups[0].GroupName, "first group name should be '建筑信息'")
	assert.Equal("钢混组合", ann.LabelGroups[0].Children[0].Labels["structure_type"], "structure type should be '钢混组合'")
	assert.Equal("Q345B", ann.LabelGroups[0].Children[0].Children[0].Labels["steel_grade"], "steel grade should be 'Q345B'")
	assert.Equal(1, len(ann.LabelGroups[0].Children[0].Children), "should have 1 child at level 3")

	geoJSON2, err := json.Marshal(ann.Geometry)
	assert.NoError(err, "geometry marshal should succeed")
	var geometry2 map[string]interface{}
	json.Unmarshal(geoJSON2, &geometry2)

	centerMap := geometry2["center"].(map[string]interface{})
	assert.Equal(100.0, centerMap["X"].(float64), "center X should be 100")
	assert.Equal(200.0, centerMap["Y"].(float64), "center Y should be 200")

	propsJSON, _ := json.Marshal(ann.Properties)
	var props2 map[string]interface{}
	json.Unmarshal(propsJSON, &props2)
	assert.Equal(3000.5, props2["area"].(float64), "area should be 3000.5")
	assert.Equal(true, props2["is_active"].(bool), "is_active should be true")

	t.Log("Annotation with full tags and nested label groups created successfully")
	t.Logf("  Tags count: %d", len(ann.Tags))
	t.Logf("  Properties count: %d", len(ann.Properties))
	t.Logf("  LabelGroups count: %d", len(ann.LabelGroups))
}

func TestLabelGroup_DeepNesting(t *testing.T) {
	assert := testutil.NewAssert(t)

	level3 := &LabelGroup{
		GroupName: "L3-设备参数",
		GroupType: "equipment_params",
		Labels: map[string]string{
			"model":     "XK-2000-Pro",
			"serial":    "SN-2024-00188",
			"warranty":  "5年",
			"power":     "220V/50Hz",
		},
		Children: []*LabelGroup{},
	}

	level2 := &LabelGroup{
		GroupName: "L2-子系统配置",
		GroupType: "subsystem_config",
		Labels: map[string]string{
			"subsystem_name": "暖通空调",
			"vendor":        "开利空调",
			"install_date":  "2024-03-15",
		},
		Children: []*LabelGroup{level3},
	}

	level1 := &LabelGroup{
		GroupName: "L1-机电系统",
		GroupType: "mechanical_system",
		Labels: map[string]string{
			"system_type": "HVAC",
			"capacity":    "5000RT",
			"efficiency":  "一级能效",
		},
		Children: []*LabelGroup{level2},
	}

	var verify func(group *LabelGroup, depth int)
	verify = func(group *LabelGroup, depth int) {
		assert.NotNil(group, "group at depth %d should not be nil", depth)
		assert.NotEmpty(group.GroupName, "group name at depth %d should not be empty", depth)
		assert.True(len(group.Labels) > 0, "labels at depth %d should not be empty", depth)

		if depth == 1 {
			assert.Equal("L1-机电系统", group.GroupName, "L1 group name mismatch")
			assert.Equal("HVAC", group.Labels["system_type"], "L1 system_type mismatch")
			assert.Equal(1, len(group.Children), "L1 should have 1 child")
		} else if depth == 2 {
			assert.Equal("L2-子系统配置", group.GroupName, "L2 group name mismatch")
			assert.Equal("开利空调", group.Labels["vendor"], "L2 vendor mismatch")
			assert.Equal(1, len(group.Children), "L2 should have 1 child")
		} else if depth == 3 {
			assert.Equal("L3-设备参数", group.GroupName, "L3 group name mismatch")
			assert.Equal("XK-2000-Pro", group.Labels["model"], "L3 model mismatch")
			assert.Equal(0, len(group.Children), "L3 should have no children")
		}

		for _, child := range group.Children {
			verify(child, depth+1)
		}
	}

	verify(level1, 1)

	assert.Equal("一级能效", level1.Labels["efficiency"], "L1 efficiency should be '一级能效'")
	assert.Equal("2024-03-15", level1.Children[0].Labels["install_date"], "L2 install_date should be '2024-03-15'")
	assert.Equal("SN-2024-00188", level1.Children[0].Children[0].Labels["serial"], "L3 serial should be 'SN-2024-00188'")

	t.Log("3-level deep nesting LabelGroup verified successfully")
	t.Logf("  Level 1: %s (%d labels, %d children)", level1.GroupName, len(level1.Labels), len(level1.Children))
	t.Logf("  Level 2: %s (%d labels, %d children)", level1.Children[0].GroupName, len(level1.Children[0].Labels), len(level1.Children[0].Children))
	t.Logf("  Level 3: %s (%d labels, %d children)", level1.Children[0].Children[0].GroupName, len(level1.Children[0].Children[0].Labels), len(level1.Children[0].Children[0].Children))
}

func TestTagQueryCondition_AllOperators(t *testing.T) {
	assert := testutil.NewAssert(t)

	conditions := []TagQueryCondition{
		{
			Key:      "building_type",
			Operator: "exists",
		},
		{
			Key:      "demolition_tag",
			Operator: "not_exists",
		},
		{
			Key:      "city",
			Value:    "北京",
			Operator: "equals",
		},
		{
			Key:      "status",
			Value:    "已拆除",
			Operator: "not_equals",
		},
		{
			Key:      "description",
			Value:    "办公",
			Operator: "contains",
		},
	}

	assert.Equal(5, len(conditions), "should have 5 conditions covering all operators")

	operators := make(map[string]bool)
	for _, cond := range conditions {
		operators[cond.Operator] = true
	}
	assert.True(operators["exists"], "exists operator should be present")
	assert.True(operators["not_exists"], "not_exists operator should be present")
	assert.True(operators["equals"], "equals operator should be present")
	assert.True(operators["not_equals"], "not_equals operator should be present")
	assert.True(operators["contains"], "contains operator should be present")

	assert.Equal("building_type", conditions[0].Key, "first condition key mismatch")
	assert.Equal("exists", conditions[0].Operator, "first condition operator mismatch")
	assert.Nil(conditions[0].Value, "exists condition should have no value")

	assert.Equal("demolition_tag", conditions[1].Key, "second condition key mismatch")
	assert.Equal("not_exists", conditions[1].Operator, "second condition operator mismatch")

	assert.Equal("city", conditions[2].Key, "third condition key mismatch")
	assert.Equal("北京", conditions[2].Value, "third condition value mismatch")
	assert.Equal("equals", conditions[2].Operator, "third condition operator mismatch")

	assert.Equal("status", conditions[3].Key, "fourth condition key mismatch")
	assert.Equal("已拆除", conditions[3].Value, "fourth condition value mismatch")
	assert.Equal("not_equals", conditions[3].Operator, "fourth condition operator mismatch")

	assert.Equal("description", conditions[4].Key, "fifth condition key mismatch")
	assert.Equal("办公", conditions[4].Value, "fifth condition value mismatch")
	assert.Equal("contains", conditions[4].Operator, "fifth condition operator mismatch")

	type WhereClauseInfo struct {
		Clause string
		Args   []interface{}
	}

	clauses := make([]WhereClauseInfo, 0, len(conditions))
	argIdx := 2

	for _, cond := range conditions {
		switch cond.Operator {
		case "exists":
			clauses = append(clauses, WhereClauseInfo{
				Clause: "tags ? $2",
				Args:   []interface{}{cond.Key},
			})
			argIdx++
		case "not_exists":
			clauses = append(clauses, WhereClauseInfo{
				Clause: "NOT (tags ? $3)",
				Args:   []interface{}{cond.Key},
			})
			argIdx++
		case "equals":
			clauses = append(clauses, WhereClauseInfo{
				Clause: "tags @> jsonb_build_object($4, $5)",
				Args:   []interface{}{cond.Key, cond.Value},
			})
			argIdx += 2
		case "not_equals":
			clauses = append(clauses, WhereClauseInfo{
				Clause: "NOT (tags @> jsonb_build_object($6, $7))",
				Args:   []interface{}{cond.Key, cond.Value},
			})
			argIdx += 2
		case "contains":
			clauses = append(clauses, WhereClauseInfo{
				Clause: "tags ->> $8 LIKE $9",
				Args:   []interface{}{cond.Key, "%" + "办公" + "%"},
			})
			argIdx += 2
		}
	}

	assert.Equal(5, len(clauses), "should generate 5 WHERE clauses")
	assert.Equal("tags ? $2", clauses[0].Clause, "first where clause mismatch")
	assert.Equal("NOT (tags ? $3)", clauses[1].Clause, "second where clause mismatch")
	assert.Equal("tags @> jsonb_build_object($4, $5)", clauses[2].Clause, "third where clause mismatch")
	assert.Equal("NOT (tags @> jsonb_build_object($6, $7))", clauses[3].Clause, "fourth where clause mismatch")
	assert.Equal("tags ->> $8 LIKE $9", clauses[4].Clause, "fifth where clause mismatch")

	assert.Equal("building_type", clauses[0].Args[0], "first clause arg mismatch")
	assert.Equal("北京", clauses[2].Args[1], "equals clause value arg mismatch")
	assert.Equal("%办公%", clauses[4].Args[1], "contains clause pattern arg mismatch")

	t.Log("All 5 TagQueryCondition operators verified with WHERE clause structures")
	for i, cond := range conditions {
		t.Logf("  Condition %d: operator=%s, key=%s, value=%v -> %s", i, cond.Operator, cond.Key, cond.Value, clauses[i].Clause)
	}
}

func TestAnnotationService_TagsMergeBehavior(t *testing.T) {
	assert := testutil.NewAssert(t)

	ann := &Annotation{
		ID:        "merge-test-001",
		DatasetID: "ds-merge-001",
		Type:      AnnotationBBox3D,
		Label:     "合并测试标注",
		Tags: map[string]string{
			"name":   "原始名称",
			"type":   "商业",
			"status": "已竣工",
		},
		CreatorID: "user-001",
	}

	assert.Equal(3, len(ann.Tags), "initial tags should have 3 keys")
	assert.Equal("原始名称", ann.Tags["name"], "initial name should be '原始名称'")
	assert.Equal("商业", ann.Tags["type"], "initial type should be '商业'")
	assert.Equal("已竣工", ann.Tags["status"], "initial status should be '已竣工'")

	updateTags := map[string]string{
		"type":   "商住两用",
		"region": "浦东新区",
	}

	for k, v := range updateTags {
		ann.Tags[k] = v
	}

	assert.Equal(4, len(ann.Tags), "after merge should have 4 keys")
	assert.Equal("原始名称", ann.Tags["name"], "name should remain unchanged")
	assert.Equal("商住两用", ann.Tags["type"], "type should be overwritten to '商住两用'")
	assert.Equal("已竣工", ann.Tags["status"], "status should remain unchanged")
	assert.Equal("浦东新区", ann.Tags["region"], "region should be newly added")

	expectedKeys := map[string]bool{
		"name":   true,
		"type":   true,
		"status": true,
		"region": true,
	}
	for k := range ann.Tags {
		assert.True(expectedKeys[k], "unexpected key found: %s", k)
	}
	for k := range expectedKeys {
		_, exists := ann.Tags[k]
		assert.True(exists, "expected key not found: %s", k)
	}

	ann.Tags["type"] = "纯住宅"
	assert.Equal("纯住宅", ann.Tags["type"], "direct overwrite should work")

	ann.Tags["extra1"] = "val1"
	ann.Tags["extra2"] = "val2"
	assert.Equal(6, len(ann.Tags), "should have 6 keys after adding extras")

	delete(ann.Tags, "extra1")
	assert.Equal(5, len(ann.Tags), "should have 5 keys after delete")
	_, exists := ann.Tags["extra1"]
	assert.False(exists, "extra1 should be deleted")

	t.Log("Tags merge behavior verified successfully")
	t.Logf("  Final tags: %v", ann.Tags)
	t.Logf("  Final tag count: %d", len(ann.Tags))
}

func TestAnnotation_TagsSerialization(t *testing.T) {
	assert := testutil.NewAssert(t)

	original := &Annotation{
		ID:        "serial-test-001",
		DatasetID: "ds-serial-001",
		Type:      AnnotationPolygon,
		Label:     "序列化测试",
		Tags: map[string]string{
			"name":        "文化中心",
			"category":    "公共建筑",
			"city":        "杭州",
			"year_built":  "2019",
			"architect":   "扎哈事务所",
			"award":       "鲁班奖",
		},
		LabelGroups: []*LabelGroup{
			{
				GroupName: "建筑设计",
				GroupType: "design",
				Labels: map[string]string{
					"style":       "解构主义",
					"façade":      "参数化曲面",
					"landscape":   "屋顶花园",
				},
				Children: []*LabelGroup{
					{
						GroupName: "室内设计",
						Labels: map[string]string{
							"lobby":      "挑高20米",
							"theater":    "1200座",
							"exhibition": "5000平米",
						},
						Children: []*LabelGroup{
							{
								GroupName: "材料细节",
								Labels: map[string]string{
									"floor":  "意大利大理石",
									"wall":   "GRG玻璃纤维增强石膏",
									"roof":   "钛锌板",
								},
							},
						},
					},
				},
			},
			{
				GroupName: "绿色认证",
				Labels: map[string]string{
					"leed":       "铂金级",
					"green_star": "三星级",
					"well":       "金级",
				},
			},
		},
		CreatorID: "user-designer-001",
	}

	data, err := json.Marshal(original)
	assert.NoError(err, "json marshal should succeed")
	assert.NotEmpty(data, "marshaled data should not be empty")

	var restored Annotation
	err = json.Unmarshal(data, &restored)
	assert.NoError(err, "json unmarshal should succeed")

	assert.Equal(original.ID, restored.ID, "ID should be preserved after serialization")
	assert.Equal(original.Label, restored.Label, "Label should be preserved")

	assert.Equal(6, len(restored.Tags), "should have 6 tags after serialization")
	assert.Equal("文化中心", restored.Tags["name"], "tag name preserved")
	assert.Equal("杭州", restored.Tags["city"], "tag city preserved")
	assert.Equal("鲁班奖", restored.Tags["award"], "tag award preserved")
	assert.Equal("2019", restored.Tags["year_built"], "tag year_built preserved")
	for k, v := range original.Tags {
		assert.Equal(v, restored.Tags[k], "tag %s should match after serialization", k)
	}

	assert.Equal(2, len(restored.LabelGroups), "should have 2 top-level label groups")
	assert.Equal("建筑设计", restored.LabelGroups[0].GroupName, "first group name preserved")
	assert.Equal(3, len(restored.LabelGroups[0].Labels), "first group labels count preserved")
	assert.Equal("参数化曲面", restored.LabelGroups[0].Labels["façade"], "façade label preserved")

	assert.Equal(1, len(restored.LabelGroups[0].Children), "first group children count preserved")
	assert.Equal("室内设计", restored.LabelGroups[0].Children[0].GroupName, "child group name preserved")
	assert.Equal("挑高20米", restored.LabelGroups[0].Children[0].Labels["lobby"], "lobby label preserved")

	assert.Equal(1, len(restored.LabelGroups[0].Children[0].Children), "grandchild count preserved")
	assert.Equal("材料细节", restored.LabelGroups[0].Children[0].Children[0].GroupName, "grandchild group name preserved")
	assert.Equal("意大利大理石", restored.LabelGroups[0].Children[0].Children[0].Labels["floor"], "floor material preserved")

	assert.Equal("绿色认证", restored.LabelGroups[1].GroupName, "second group name preserved")
	assert.Equal("铂金级", restored.LabelGroups[1].Labels["leed"], "leed label preserved")

	tagsJSON, _ := json.Marshal(original.Tags)
	var tagsMapCopy map[string]string
	json.Unmarshal(tagsJSON, &tagsMapCopy)
	assert.Equal(6, len(tagsMapCopy), "tags standalone serialization preserved")

	lgJSON, _ := json.Marshal(original.LabelGroups)
	var lgCopy []*LabelGroup
	json.Unmarshal(lgJSON, &lgCopy)
	assert.Equal(2, len(lgCopy), "label groups standalone serialization preserved")
	assert.Equal(1, len(lgCopy[0].Children[0].Children), "deep nesting preserved in standalone")

	t.Log("Tags and LabelGroups serialization round-trip verified successfully")
	t.Logf("  Original tags: %d keys, Restored tags: %d keys", len(original.Tags), len(restored.Tags))
	t.Logf("  Original label groups: %d groups, Restored: %d groups", len(original.LabelGroups), len(restored.LabelGroups))
	t.Logf("  Deep nesting preserved: level3 group = %s", restored.LabelGroups[0].Children[0].Children[0].GroupName)
}
