import { DocumentType, ClassificationPattern } from './types';

export const TECH_PROPOSAL_KEYWORDS = [
  '技术方案', '架构设计', '系统设计', '技术选型', 'API设计',
  '接口设计', '数据库设计', '技术架构', '微服务', '分布式',
  '高可用', '性能优化', '技术评审', '方案设计', '系统架构',
  'technical', 'architecture', 'design', 'proposal', 'api',
  'restful', 'grpc', 'microservice', 'database', 'schema',
  '架构', '模块划分', '技术栈', '框架选型', '中间件',
  '时序图', '流程图', '架构图', 'ER图', 'UML',
];

export const MEETING_NOTES_KEYWORDS = [
  '会议纪要', '参会人', '会议时间', '会议议程', '决议',
  '待办', '行动项', 'TODO', '会议主题', '主持人',
  '记录人', '会议结论', '下次会议', '议题', '讨论',
  'meeting', 'notes', 'minutes', 'attendees', 'action items',
  '参会人员', '会议地点', '会议日期', '跟进', '落实',
  '达成共识', '待跟进', '待确认', '待讨论', '会议纪要模板',
];

export const WEEKLY_REPORT_KEYWORDS = [
  '周报', '本周工作', '下周计划', '风险问题', '工作进展',
  '本周完成', '下周安排', '问题反馈', '需要支持', '工作总结',
  'weekly', 'report', 'progress', 'plan', 'risks',
  '本周总结', '下周重点', '本周重点', '完成情况', '进度',
  '里程碑', '交付物', '阻塞问题', '依赖项', '风险预警',
  '周报模板', '周汇报', '周进度', '周总结', '周计划',
];

export const POST_MORTEM_KEYWORDS = [
  '复盘', '项目复盘', '根因分析', '经验教训', '改进措施',
  '问题回顾', '事故复盘', '问题分析', '原因分析', '改进方案',
  'postmortem', 'retrospective', 'root cause', 'lessons learned',
  'action plan', 'improvement',
  '背景', '问题描述', '影响范围', '根本原因', '临时方案',
  '长期方案', '预防措施', '责任人', '完成时间', '复盘总结',
  '5Why分析', '鱼骨图', '故障分析', '事故报告', '经验总结',
];

export const PRODUCT_REQUIREMENT_KEYWORDS = [
  '产品需求', '需求文档', '用户故事', '功能描述', '验收标准',
  'PRD', '需求背景', '用户痛点', '功能需求', '非功能需求',
  'product', 'requirement', 'user story', 'acceptance criteria',
  'PRD文档', '需求分析', '产品方案', '交互设计', '原型图',
  '用户场景', '业务流程', '数据需求', '接口需求', '权限需求',
  '需求优先级', '迭代计划', '版本规划', '用户画像', '竞品分析',
  'As a', 'I want', 'So that', 'Given', 'When', 'Then',
];

export const DOCUMENT_TYPE_KEYWORDS: Record<DocumentType, string[]> = {
  [DocumentType.TECH_PROPOSAL]: TECH_PROPOSAL_KEYWORDS,
  [DocumentType.MEETING_NOTES]: MEETING_NOTES_KEYWORDS,
  [DocumentType.WEEKLY_REPORT]: WEEKLY_REPORT_KEYWORDS,
  [DocumentType.POST_MORTEM]: POST_MORTEM_KEYWORDS,
  [DocumentType.PRODUCT_REQUIREMENT]: PRODUCT_REQUIREMENT_KEYWORDS,
  [DocumentType.OTHER]: [],
};

export const TECH_PROPOSAL_REGEX = [
  /技术方案|架构设计|系统设计|技术选型|API设计/i,
  /接口设计|数据库设计|技术架构|微服务|分布式/i,
  /\*\*技术方案\*\*|## 技术方案|### 架构设计/i,
  /技术架构图|系统架构图|部署架构图/i,
  /RESTful|GraphQL|gRPC|HTTP.*API/i,
  /MySQL|PostgreSQL|MongoDB|Redis|Elasticsearch/i,
  /Kubernetes|Docker|微服务|服务治理/i,
];

export const MEETING_NOTES_REGEX = [
  /会议纪要|参会人|会议时间|会议议程|会议结论/i,
  /待办|行动项|TODO|Action Item/i,
  /## 会议纪要|### 参会人员|#### 会议时间/i,
  /时间：|地点：|主持人：|记录人：/i,
  /决议.*：|结论.*：|待跟进.*：/i,
  /下次会议|会议跟进|落实情况/i,
];

export const WEEKLY_REPORT_REGEX = [
  /周报|本周工作|下周计划|风险问题|工作进展/i,
  /本周完成|下周安排|问题反馈|需要支持/i,
  /## 本周工作|### 下周计划|#### 风险问题/i,
  /完成.*：|进展.*：|计划.*：/i,
  /进度.*%|里程碑.*完成|交付物.*就绪/i,
  /阻塞问题|依赖项.*等待|风险.*预警/i,
];

export const POST_MORTEM_REGEX = [
  /复盘|项目复盘|根因分析|经验教训|改进措施/i,
  /问题回顾|事故复盘|问题分析|原因分析/i,
  /## 问题描述|### 根本原因|#### 改进措施/i,
  /背景.*：|影响.*：|原因.*：/i,
  /5Why|鱼骨图|根本原因分析|RCA/i,
  /临时方案|长期方案|预防措施/i,
];

export const PRODUCT_REQUIREMENT_REGEX = [
  /产品需求|需求文档|用户故事|功能描述|验收标准/i,
  /PRD|需求背景|用户痛点|功能需求/i,
  /## 需求背景|### 用户故事|#### 验收标准/i,
  /As a.*I want.*So that|Given.*When.*Then/i,
  /用户场景|业务流程|数据需求|接口需求/i,
  /需求优先级|P0|P1|P2|高优先级|中优先级|低优先级/i,
];

export const DOCUMENT_TYPE_REGEX: Record<DocumentType, RegExp[]> = {
  [DocumentType.TECH_PROPOSAL]: TECH_PROPOSAL_REGEX,
  [DocumentType.MEETING_NOTES]: MEETING_NOTES_REGEX,
  [DocumentType.WEEKLY_REPORT]: WEEKLY_REPORT_REGEX,
  [DocumentType.POST_MORTEM]: POST_MORTEM_REGEX,
  [DocumentType.PRODUCT_REQUIREMENT]: PRODUCT_REQUIREMENT_REGEX,
  [DocumentType.OTHER]: [],
};

export const TECH_PROPOSAL_TITLE_PATTERNS = [
  /技术方案|技术设计|架构设计|系统设计/i,
  /关于.*的技术方案|.*技术方案设计/i,
  /API设计|接口设计|数据库设计/i,
  /.*系统.*架构|.*服务.*设计/i,
  /技术评审|方案评审|设计评审/i,
  /Technical Proposal|Architecture Design|System Design/i,
];

export const MEETING_NOTES_TITLE_PATTERNS = [
  /会议纪要|会议记录|.*会议.*纪要/i,
  /.*讨论纪要|.*评审会议|.*同步会议/i,
  /周会纪要|站会纪要|复盘会议/i,
  /Meeting Notes|Meeting Minutes|Discussion Notes/i,
  /.*项目.*会议|.*需求.*会议|.*技术.*会议/i,
];

export const WEEKLY_REPORT_TITLE_PATTERNS = [
  /周报|周汇报|周总结|周计划/i,
  /第.*周.*周报|.*年第.*周/i,
  /本周总结|下周计划|.*周工作/i,
  /Weekly Report|Weekly Summary|Weekly Plan/i,
  /.*项目.*周报|.*部门.*周报/i,
];

export const POST_MORTEM_TITLE_PATTERNS = [
  /复盘|项目复盘|事故复盘|问题复盘/i,
  /.*复盘报告|.*复盘总结|.*问题分析/i,
  /根因分析|经验教训|改进措施/i,
  /Postmortem|Retrospective|Root Cause Analysis/i,
  /.*故障分析|.*事故报告|.*问题回顾/i,
];

export const PRODUCT_REQUIREMENT_TITLE_PATTERNS = [
  /产品需求|PRD|需求文档|需求说明/i,
  /.*需求.*文档|.*功能.*需求|.*产品.*设计/i,
  /用户故事|功能规格|产品规格/i,
  /Product Requirement|PRD Document|User Story/i,
  /.*功能.*设计|.*模块.*需求|.*系统.*需求/i,
];

export const DOCUMENT_TYPE_TITLE_PATTERNS: Record<DocumentType, RegExp[]> = {
  [DocumentType.TECH_PROPOSAL]: TECH_PROPOSAL_TITLE_PATTERNS,
  [DocumentType.MEETING_NOTES]: MEETING_NOTES_TITLE_PATTERNS,
  [DocumentType.WEEKLY_REPORT]: WEEKLY_REPORT_TITLE_PATTERNS,
  [DocumentType.POST_MORTEM]: POST_MORTEM_TITLE_PATTERNS,
  [DocumentType.PRODUCT_REQUIREMENT]: PRODUCT_REQUIREMENT_TITLE_PATTERNS,
  [DocumentType.OTHER]: [],
};

export const REQUIRED_SECTIONS: Record<DocumentType, string[]> = {
  [DocumentType.TECH_PROPOSAL]: [
    '背景', '技术方案', '架构设计', '接口设计', '数据库设计',
  ],
  [DocumentType.MEETING_NOTES]: [
    '会议时间', '参会人', '会议议程', '决议', '待办',
  ],
  [DocumentType.WEEKLY_REPORT]: [
    '本周工作', '下周计划', '风险问题',
  ],
  [DocumentType.POST_MORTEM]: [
    '背景', '问题描述', '根本原因', '改进措施',
  ],
  [DocumentType.PRODUCT_REQUIREMENT]: [
    '需求背景', '用户故事', '功能描述', '验收标准',
  ],
  [DocumentType.OTHER]: [],
};

export const CLASSIFICATION_PATTERNS: ClassificationPattern[] = [
  {
    type: DocumentType.TECH_PROPOSAL,
    keywords: TECH_PROPOSAL_KEYWORDS,
    regexPatterns: TECH_PROPOSAL_REGEX,
    titlePatterns: TECH_PROPOSAL_TITLE_PATTERNS,
    structurePatterns: {
      minHeadings: 3,
      requiredSections: REQUIRED_SECTIONS[DocumentType.TECH_PROPOSAL],
    },
    weight: 1.0,
  },
  {
    type: DocumentType.MEETING_NOTES,
    keywords: MEETING_NOTES_KEYWORDS,
    regexPatterns: MEETING_NOTES_REGEX,
    titlePatterns: MEETING_NOTES_TITLE_PATTERNS,
    structurePatterns: {
      minHeadings: 2,
      requiredSections: REQUIRED_SECTIONS[DocumentType.MEETING_NOTES],
    },
    weight: 1.0,
  },
  {
    type: DocumentType.WEEKLY_REPORT,
    keywords: WEEKLY_REPORT_KEYWORDS,
    regexPatterns: WEEKLY_REPORT_REGEX,
    titlePatterns: WEEKLY_REPORT_TITLE_PATTERNS,
    structurePatterns: {
      minHeadings: 2,
      requiredSections: REQUIRED_SECTIONS[DocumentType.WEEKLY_REPORT],
    },
    weight: 1.0,
  },
  {
    type: DocumentType.POST_MORTEM,
    keywords: POST_MORTEM_KEYWORDS,
    regexPatterns: POST_MORTEM_REGEX,
    titlePatterns: POST_MORTEM_TITLE_PATTERNS,
    structurePatterns: {
      minHeadings: 3,
      requiredSections: REQUIRED_SECTIONS[DocumentType.POST_MORTEM],
    },
    weight: 1.0,
  },
  {
    type: DocumentType.PRODUCT_REQUIREMENT,
    keywords: PRODUCT_REQUIREMENT_KEYWORDS,
    regexPatterns: PRODUCT_REQUIREMENT_REGEX,
    titlePatterns: PRODUCT_REQUIREMENT_TITLE_PATTERNS,
    structurePatterns: {
      minHeadings: 3,
      requiredSections: REQUIRED_SECTIONS[DocumentType.PRODUCT_REQUIREMENT],
    },
    weight: 1.0,
  },
];

export const SECTION_HEADING_PATTERN = /^#{1,6}\s+(.+)$/gm;

export const BOLD_TEXT_PATTERN = /\*\*(.+?)\*\*|__(.+?)__/g;

export const LIST_ITEM_PATTERN = /^[-*+]\s+(.+)$/gm;

export const TABLE_PATTERN = /\|.+\|/g;

export const CODE_BLOCK_PATTERN = /```[\s\S]*?```/g;

export const DATE_PATTERNS = [
  /\d{4}[-/年]\d{1,2}[-/月]\d{1,2}[日号]?/g,
  /\d{1,2}[-/月]\d{1,2}[日号]?/g,
  /今天|昨天|前天|明天|后天/g,
  /本周|上周|下周|本月|上月|下月/g,
  /\d{4}年第\d{1,2}周/g,
];

export const EMAIL_PATTERN = /[\w.-]+@[\w.-]+\.\w+/g;

export const URL_PATTERN = /https?:\/\/[^\s]+/g;

export const PEOPLE_NAME_PATTERNS = [
  /[\u4e00-\u9fa5]{2,4}(?:同学|老师|先生|女士|总|经理|总监|CEO|CTO|COO)/g,
  /@[\w-]+/g,
];

export const PROJECT_NAME_PATTERNS = [
  /[\u4e00-\u9fa5A-Za-z][\u4e00-\u9fa5A-Za-z0-9_-]{2,}(?:项目|系统|平台|服务|模块|产品)/g,
  /[A-Z][A-Z0-9_]+-\d+/g,
];
