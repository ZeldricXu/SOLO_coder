export interface DocumentTemplate {
  id: string;
  name: string;
  description: string;
  icon: string;
  category: 'daily' | 'meeting' | 'project' | 'technical' | 'other';
  content: string;
  tags: string[];
}

export const BUILTIN_TEMPLATES: DocumentTemplate[] = [
  {
    id: 'daily-note',
    name: '每日笔记',
    description: '记录每日工作内容、待办事项和思考',
    icon: 'Calendar',
    category: 'daily',
    tags: ['daily', 'note'],
    content: `# {{date}} 每日笔记

## 📅 日期
{{date}} ({{weekday}})

## 🔗 日记导航
{{yesterday}} ← **今日** → {{tomorrow}}

## ✅ 今日待办
- [ ] 待办事项1
- [ ] 待办事项2
- [ ] 待办事项3

## 📝 工作记录
### 上午

### 下午

## 💡 思考与灵感

## 📚 学习内容

## 🔗 相关文档
- [[相关文档]]

## 📊 今日总结
`,
  },
  {
    id: 'meeting-notes',
    name: '会议记录',
    description: '记录会议议题、讨论内容和行动项',
    icon: 'Users',
    category: 'meeting',
    tags: ['meeting'],
    content: `# {{title}}

## 📋 会议信息
- **日期**: {{date}}
- **时间**: {{time}}
- **地点**: {{location}}
- **参会人**: {{attendees}}
- **主持人**: {{host}}
- **记录人**: {{recorder}}

## 🎯 会议议题
1. 议题1
2. 议题2

## 💬 讨论内容

### 议题1
**讨论**:
**结论**:

### 议题2
**讨论**:
**结论**:

## ✅ 行动项
| 任务 | 负责人 | 截止日期 | 状态 |
|------|--------|----------|------|
| 任务1 | 负责人 | 日期 | ⏳ 待办 |
| 任务2 | 负责人 | 日期 | ⏳ 待办 |

## 📎 附件
- [附件名称](附件路径)

## 🔗 相关文档
- [[相关文档]]
`,
  },
  {
    id: 'project-review',
    name: '项目复盘',
    description: '项目结束后的复盘总结和经验沉淀',
    icon: 'Target',
    category: 'project',
    tags: ['project', 'review'],
    content: `# {{projectName}} 项目复盘

## 📊 项目概览
- **项目名称**: {{projectName}}
- **开始日期**: {{startDate}}
- **结束日期**: {{endDate}}
- **项目负责人**: {{owner}}
- **团队成员**: {{team}}

## 🎯 项目目标
### 原定目标
1. 目标1
2. 目标2

### 实际达成
1. 达成情况1
2. 达成情况2

## ✅ 做得好的地方
1. 
2. 
3. 

## ❌ 需要改进的地方
1. 
2. 
3. 

## 🔍 问题与风险
| 问题 | 影响 | 解决方案 |
|------|------|----------|
| 问题1 | 影响 | 方案 |

## 📈 数据指标
- 指标1:
- 指标2:

## 💡 经验教训
1. 
2. 
3. 

## 🎁 可复用资产
- [文档链接]()
- [代码仓库]()
- [设计稿]()

## 🔗 相关文档
- [[相关文档]]
`,
  },
  {
    id: 'api-document',
    name: 'API文档',
    description: 'RESTful API接口设计文档模板',
    icon: 'Code2',
    category: 'technical',
    tags: ['api', 'technical'],
    content: `# {{apiName}} API 文档

## 📖 概述
- **版本**: {{version}}
- **基础地址**: {{baseUrl}}
- **认证方式**: {{auth}}

## 🔐 认证
\`\`\`
Authorization: Bearer <token>
\`\`\`

## 📋 通用响应
\`\`\`json
{
  "code": 200,
  "message": "success",
  "data": {}
}
\`\`\`

## 🔌 接口列表

### 1. {{endpointName}}
- **接口**: \`{{method}} {{path}}\`
- **描述**: {{description}}

**请求参数**:
| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| param | string | 是 | 参数说明 |

**请求示例**:
\`\`\`json
{}
\`\`\`

**响应示例**:
\`\`\`json
{}
\`\`\`

**错误码**:
| 错误码 | 描述 |
|--------|------|
| 400 | 参数错误 |
| 401 | 未授权 |

## 📌 附录
- 数据字典
- 错误码列表
- 变更记录
`,
  },
  {
    id: 'tech-design',
    name: '技术方案',
    description: '技术方案设计文档模板',
    icon: 'FileCode',
    category: 'technical',
    tags: ['design', 'technical'],
    content: `# {{title}} 技术方案

## 📖 背景与目标
### 背景

### 目标
1. 目标1
2. 目标2

### 非目标
- 非目标1
- 非目标2

## 🏗️ 方案设计
### 整体架构

### 核心模块设计
#### 模块1

#### 模块2

### 数据结构设计

### 接口设计

## 🚨 风险与挑战
| 风险 | 可能性 | 影响 | 应对方案 |
|------|--------|------|----------|
| 风险1 | 高 | 中 | 方案 |

## 📊 性能考量
- QPS预估:
- 响应时间:
- 数据量预估:

## 🔐 安全考量
- 认证授权:
- 数据安全:
- 防攻击:

## 🔄 迁移方案

## 🧪 测试方案
- 单元测试:
- 集成测试:
- 压测:

## 📅 排期计划
| 阶段 | 内容 | 时间 | 负责人 |
|------|------|------|--------|
| 阶段1 | 内容 | 时间 | 负责人 |

## 🔗 参考资料
- [参考1]()
- [[相关文档]]
`,
  },
  {
    id: 'blank',
    name: '空白文档',
    description: '创建一个空白的Markdown文档',
    icon: 'File',
    category: 'other',
    tags: [],
    content: `# {{title}}

`,
  },
];

export const TEMPLATE_CATEGORIES = [
  { id: 'daily', name: '日常', icon: 'Calendar' },
  { id: 'meeting', name: '会议', icon: 'Users' },
  { id: 'project', name: '项目', icon: 'Target' },
  { id: 'technical', name: '技术', icon: 'Code2' },
  { id: 'other', name: '其他', icon: 'Folder' },
];
