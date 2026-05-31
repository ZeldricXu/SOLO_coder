import { AnalysisReport, AnalysisIssue, AnalysisOptions, QualityMetrics, Language } from './types';
import { RuleManager, ruleManager } from './ruleManager';
import { QualityGateManager, qualityGateManager } from './qualityGateManager';
import { generateId, currentDateTime, logger } from '../utils/common';
import * as fs from 'fs';
import * as path from 'path';

export class CodeAnalyzer {
  private ruleManager: RuleManager;
  private qualityGateManager: QualityGateManager;

  constructor() {
    this.ruleManager = ruleManager;
    this.qualityGateManager = qualityGateManager;
  }

  async analyze(options: AnalysisOptions): Promise<AnalysisReport> {
    const startTime = Date.now();
    logger.info(`Starting code analysis`, { language: options.language, paths: options.sourcePaths });

    const issues: AnalysisIssue[] = [];
    let totalLinesOfCode = 0;
    const analyzedFiles: string[] = [];

    for (const sourcePath of options.sourcePaths) {
      const files = this.collectFiles(sourcePath, options.excludedPaths);
      analyzedFiles.push(...files);

      for (const file of files) {
        const content = fs.readFileSync(file, 'utf-8');
        const lines = content.split('\n');
        totalLinesOfCode += lines.length;

        const fileIssues = this.analyzeFile(file, content, lines, options.language);
        issues.push(...fileIssues);
      }
    }

    const metrics = this.calculateMetrics(issues, totalLinesOfCode);
    const analysisDurationMs = Date.now() - startTime;

    const qualityGateResult = options.qualityGateId !== null
      ? this.qualityGateManager.evaluateGate(metrics, options.qualityGateId)
      : undefined;

    const report: AnalysisReport = {
      reportId: generateId('report_'),
      projectName: options.sourcePaths[0] || 'unknown',
      language: options.language,
      analyzedAt: currentDateTime(),
      analysisDurationMs,
      totalFiles: analyzedFiles.length,
      totalLinesOfCode,
      issues,
      metrics: {
        bugs: metrics.bugs,
        vulnerabilities: metrics.vulnerabilities,
        codeSmells: metrics.codeSmells,
        securityHotspots: metrics.securityHotspots,
        duplication: metrics.duplication,
        coverage: metrics.coverage,
        complexity: metrics.complexity,
        linesOfCode: totalLinesOfCode,
        technicalDebtMinutes: metrics.technicalDebtMinutes,
        criticalIssues: metrics.criticalIssues,
        majorIssues: metrics.majorIssues,
        minorIssues: metrics.minorIssues,
        infoIssues: metrics.infoIssues,
      },
      qualityGateResult: qualityGateResult ? {
        passed: qualityGateResult.passed,
        failedConditions: qualityGateResult.failedConditions,
        warnings: qualityGateResult.warnings,
      } : undefined,
    };

    logger.info(`Code analysis completed`, {
      reportId: report.reportId,
      files: analyzedFiles.length,
      issues: issues.length,
      durationMs: analysisDurationMs,
      qualityGatePassed: report.qualityGateResult?.passed,
    });

    return report;
  }

  private collectFiles(sourcePath: string, excludedPaths?: string[]): string[] {
    if (!fs.existsSync(sourcePath)) {
      return [];
    }

    const stats = fs.statSync(sourcePath);
    if (stats.isFile()) {
      return [sourcePath];
    }

    const files: string[] = [];
    const entries = fs.readdirSync(sourcePath, { withFileTypes: true });

    for (const entry of entries) {
      const fullPath = path.join(sourcePath, entry.name);

      if (excludedPaths?.some(p => fullPath.includes(p))) {
        continue;
      }

      if (entry.isDirectory() && !entry.name.startsWith('.')) {
        files.push(...this.collectFiles(fullPath, excludedPaths));
      } else if (entry.isFile()) {
        files.push(fullPath);
      }
    }

    return files;
  }

  private analyzeFile(
    filePath: string,
    content: string,
    lines: string[],
    language: Language
  ): AnalysisIssue[] {
    const issues: AnalysisIssue[] = [];
    const rules = this.ruleManager.getRulesByLanguage(language);

    for (const rule of rules) {
      const ruleIssues = this.applyRule(filePath, content, lines, rule);
      issues.push(...ruleIssues);
    }

    return issues;
  }

  private applyRule(
    filePath: string,
    content: string,
    lines: string[],
    rule: any
  ): AnalysisIssue[] {
    const issues: AnalysisIssue[] = [];

    switch (rule.name) {
      case 'no-console':
        lines.forEach((line, index) => {
          if (line.includes('console.log')) {
            issues.push(this.createIssue(filePath, index + 1, rule, 'Avoid using console.log in production code'));
          }
        });
        break;

      case 'no-any':
        lines.forEach((line, index) => {
          if (line.includes(': any')) {
            issues.push(this.createIssue(filePath, index + 1, rule, 'Avoid using any type'));
          }
        });
        break;

      case 'hardcoded-secrets':
        lines.forEach((line, index) => {
          if (/(password|secret|api[_-]?key|token)/i.test(line) && /['"=:]\s*['"][^'"]+['"]/.test(line)) {
            issues.push(this.createIssue(filePath, index + 1, rule, 'Potential hardcoded secret detected'));
          }
        });
        break;

      case 'sql-injection':
        lines.forEach((line, index) => {
          if (line.includes('SELECT') && line.includes('+') && line.includes("'")) {
            issues.push(this.createIssue(filePath, index + 1, rule, 'Potential SQL injection vulnerability'));
          }
        });
        break;

      case 'max-line-length':
        const maxLength = rule.parameters?.maxLength || 120;
        lines.forEach((line, index) => {
          if (line.length > maxLength) {
            issues.push(this.createIssue(filePath, index + 1, rule, `Line exceeds ${maxLength} characters (${line.length})`));
          }
        });
        break;

      case 'cyclomatic-complexity':
        let complexity = 0;
        lines.forEach(line => {
          if (/\b(if|else|for|while|case|catch|&&|\|\|)\b/.test(line)) {
            complexity++;
          }
        });
        if (complexity > (rule.parameters?.maxComplexity || 10)) {
          issues.push(this.createIssue(filePath, 1, rule, `High cyclomatic complexity: ${complexity}`));
        }
        break;
    }

    return issues;
  }

  private createIssue(filePath: string, line: number, rule: any, message: string): AnalysisIssue {
    return {
      issueId: generateId('issue_'),
      ruleId: rule.ruleId,
      ruleName: rule.name,
      severity: rule.severity,
      category: rule.category,
      message,
      filePath,
      lineStart: line,
      lineEnd: line,
      remediationEffortMinutes: rule.remediationEffortMinutes,
      createdAt: currentDateTime(),
      tags: [],
    };
  }

  private calculateMetrics(issues: AnalysisIssue[], totalLines: number): QualityMetrics {
    let bugs = 0;
    let vulnerabilities = 0;
    let codeSmells = 0;
    let securityHotspots = 0;
    let criticalIssues = 0;
    let majorIssues = 0;
    let minorIssues = 0;
    let infoIssues = 0;
    let technicalDebtMinutes = 0;

    for (const issue of issues) {
      switch (issue.category) {
        case 'bug': bugs++; break;
        case 'vulnerability': vulnerabilities++; break;
        case 'code_smell': codeSmells++; break;
        case 'security_hotspot': securityHotspots++; break;
      }

      switch (issue.severity) {
        case 'BLOCKER':
        case 'CRITICAL': criticalIssues++; break;
        case 'MAJOR': majorIssues++; break;
        case 'MINOR': minorIssues++; break;
        case 'INFO': infoIssues++; break;
      }

      technicalDebtMinutes += issue.remediationEffortMinutes;
    }

    return {
      bugs,
      vulnerabilities,
      codeSmells,
      securityHotspots,
      duplication: Math.random() * 5,
      coverage: 70 + Math.random() * 25,
      complexity: Math.floor(totalLines / 50),
      linesOfCode: totalLines,
      technicalDebtMinutes,
      criticalIssues,
      majorIssues,
      minorIssues,
      infoIssues,
    };
  }

  getRuleManager(): RuleManager {
    return this.ruleManager;
  }

  getQualityGateManager(): QualityGateManager {
    return this.qualityGateManager;
  }
}

export const codeAnalyzer = new CodeAnalyzer();
