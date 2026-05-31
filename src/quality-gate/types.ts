import { z } from 'zod';

export const LanguageSchema = z.enum(['typescript', 'javascript', 'python', 'java', 'go', 'rust', 'csharp', 'ruby', 'php', 'kotlin', 'swift']);
export type Language = z.infer<typeof LanguageSchema>;

export const SeveritySchema = z.enum(['INFO', 'MINOR', 'MAJOR', 'CRITICAL', 'BLOCKER']);
export type Severity = z.infer<typeof SeveritySchema>;

export const AnalysisRuleSchema = z.object({
  ruleId: z.string(),
  name: z.string(),
  description: z.string(),
  language: LanguageSchema,
  severity: SeveritySchema,
  category: z.enum(['bug', 'vulnerability', 'code_smell', 'security_hotspot', 'duplication']),
  enabled: z.boolean().default(true),
  parameters: z.record(z.unknown()).default({}),
  remediationEffortMinutes: z.number().default(5),
});

export type AnalysisRule = z.infer<typeof AnalysisRuleSchema>;

export const QualityGateConditionSchema = z.object({
  metric: z.string(),
  operator: z.enum(['LT', 'GT', 'LTE', 'GTE', 'EQ', 'NE']),
  threshold: z.union([z.number(), z.string()]),
  onFail: z.enum(['WARN', 'ERROR']).default('ERROR'),
});

export type QualityGateCondition = z.infer<typeof QualityGateConditionSchema>;

export const QualityGateSchema = z.object({
  gateId: z.string(),
  name: z.string(),
  conditions: z.array(QualityGateConditionSchema),
  isDefault: z.boolean().default(false),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});

export type QualityGate = z.infer<typeof QualityGateSchema>;

export const AnalysisIssueSchema = z.object({
  issueId: z.string(),
  ruleId: z.string(),
  ruleName: z.string(),
  severity: SeveritySchema,
  category: z.string(),
  message: z.string(),
  filePath: z.string(),
  lineStart: z.number(),
  lineEnd: z.number(),
  columnStart: z.number().optional(),
  columnEnd: z.number().optional(),
  codeSnippet: z.string().optional(),
  remediationEffortMinutes: z.number().default(5),
  tags: z.array(z.string()).default([]),
  createdAt: z.string().datetime(),
});

export type AnalysisIssue = z.infer<typeof AnalysisIssueSchema>;

export const AnalysisReportSchema = z.object({
  reportId: z.string(),
  projectName: z.string(),
  language: LanguageSchema,
  analyzedAt: z.string().datetime(),
  analysisDurationMs: z.number(),
  totalFiles: z.number(),
  totalLinesOfCode: z.number(),
  issues: z.array(AnalysisIssueSchema),
  metrics: z.record(z.number()),
  qualityGateResult: z.object({
    passed: z.boolean(),
    failedConditions: z.array(QualityGateConditionSchema),
    warnings: z.array(QualityGateConditionSchema),
  }).optional(),
});

export type AnalysisReport = z.infer<typeof AnalysisReportSchema>;

export interface AnalysisOptions {
  language: Language;
  sourcePaths: string[];
  excludedPaths?: string[];
  rules?: string[];
  qualityGateId?: string;
  failOnQualityGate?: boolean;
}

export interface QualityMetrics {
  bugs: number;
  vulnerabilities: number;
  codeSmells: number;
  securityHotspots: number;
  duplication: number;
  coverage: number;
  complexity: number;
  linesOfCode: number;
  technicalDebtMinutes: number;
  criticalIssues: number;
  majorIssues: number;
  minorIssues: number;
  infoIssues: number;
}
