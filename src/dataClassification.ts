import * as crypto from 'crypto';
import { DataClassificationRule, ClassificationResult } from './types';

export interface DataSource {
  id: string;
  name: string;
  type: 'database' | 'file' | 'api' | 'stream';
  connectionInfo: string;
  lastScanned?: number;
  scanStatus: 'idle' | 'scanning' | 'completed' | 'failed';
}

export interface ClassificationReport {
  dataSourceId: string;
  scanStartTime: number;
  scanEndTime: number;
  totalFields: number;
  classifiedFields: number;
  results: ClassificationResult[];
  riskSummary: {
    public: number;
    internal: number;
    confidential: number;
    restricted: number;
  };
}

export interface PolicyAction {
  type: 'mask' | 'encrypt' | 'block' | 'alert';
  targetSensitivity: ('public' | 'internal' | 'confidential' | 'restricted')[];
  conditions?: Record<string, unknown>;
}

export class DataClassificationModule {
  private rules: Map<string, DataClassificationRule> = new Map();
  private dataSources: Map<string, DataSource> = new Map();
  private reports: Map<string, ClassificationReport[]> = new Map();
  private policies: Map<string, PolicyAction[]> = new Map();

  constructor() {
    this.registerDefaultRules();
  }

  public registerRule(rule: Omit<DataClassificationRule, 'id'>): DataClassificationRule {
    const id = crypto.randomUUID();
    const newRule: DataClassificationRule = { ...rule, id };
    this.rules.set(id, newRule);
    return newRule;
  }

  public registerRules(rules: Omit<DataClassificationRule, 'id'>[]): DataClassificationRule[] {
    return rules.map(rule => this.registerRule(rule));
  }

  public getRule(id: string): DataClassificationRule | undefined {
    return this.rules.get(id);
  }

  public getAllRules(): DataClassificationRule[] {
    return Array.from(this.rules.values());
  }

  public updateRule(id: string, updates: Partial<DataClassificationRule>): boolean {
    const rule = this.rules.get(id);
    if (!rule) return false;

    Object.assign(rule, updates);
    this.rules.set(id, rule);
    return true;
  }

  public deleteRule(id: string): boolean {
    return this.rules.delete(id);
  }

  public addDataSource(dataSource: Omit<DataSource, 'id' | 'scanStatus'>): DataSource {
    const id = crypto.randomUUID();
    const newDataSource: DataSource = {
      ...dataSource,
      id,
      scanStatus: 'idle'
    };
    this.dataSources.set(id, newDataSource);
    return newDataSource;
  }

  public getDataSource(id: string): DataSource | undefined {
    return this.dataSources.get(id);
  }

  public getAllDataSources(): DataSource[] {
    return Array.from(this.dataSources.values());
  }

  public classifyData(data: Record<string, unknown>): ClassificationResult[] {
    const results: ClassificationResult[] = [];

    for (const [fieldName, value] of Object.entries(data)) {
      if (typeof value !== 'string') continue;

      const result = this.classifyField(fieldName, value);
      if (result) {
        results.push(result);
      }
    }

    return results;
  }

  public classifyField(fieldName: string, value: string): ClassificationResult | null {
    let bestMatch: ClassificationResult | null = null;
    let highestConfidence = 0;

    for (const rule of this.rules.values()) {
      const confidence = this.matchRule(rule, fieldName, value);
      if (confidence > highestConfidence) {
        highestConfidence = confidence;
        bestMatch = {
          fieldName,
          category: rule.category,
          sensitivityLevel: rule.sensitivityLevel,
          confidence,
          matchedRule: rule.id
        };
      }
    }

    return bestMatch;
  }

  public async scanDataSource(dataSourceId: string): Promise<ClassificationReport | null> {
    const dataSource = this.dataSources.get(dataSourceId);
    if (!dataSource || dataSource.scanStatus === 'scanning') return null;

    dataSource.scanStatus = 'scanning';
    this.dataSources.set(dataSourceId, dataSource);

    const startTime = Date.now();

    try {
      const sampleData = await this.fetchDataSourceSample(dataSource);
      const results: ClassificationResult[] = [];

      for (const data of sampleData) {
        const fieldResults = this.classifyData(data);
        results.push(...fieldResults);
      }

      const uniqueResults = this.deduplicateResults(results);
      const endTime = Date.now();

      const report: ClassificationReport = {
        dataSourceId,
        scanStartTime: startTime,
        scanEndTime: endTime,
        totalFields: Object.keys(sampleData[0] || {}).length * sampleData.length,
        classifiedFields: uniqueResults.length,
        results: uniqueResults,
        riskSummary: this.calculateRiskSummary(uniqueResults)
      };

      const existingReports = this.reports.get(dataSourceId) || [];
      existingReports.push(report);
      this.reports.set(dataSourceId, existingReports);

      dataSource.scanStatus = 'completed';
      dataSource.lastScanned = endTime;
      this.dataSources.set(dataSourceId, dataSource);

      return report;
    } catch {
      dataSource.scanStatus = 'failed';
      this.dataSources.set(dataSourceId, dataSource);
      return null;
    }
  }

  public getReports(dataSourceId: string): ClassificationReport[] | undefined {
    return this.reports.get(dataSourceId);
  }

  public getLatestReport(dataSourceId: string): ClassificationReport | undefined {
    const reports = this.reports.get(dataSourceId);
    if (!reports || reports.length === 0) return undefined;
    return reports[reports.length - 1];
  }

  public setPolicy(dataSourceId: string, policies: PolicyAction[]): void {
    this.policies.set(dataSourceId, policies);
  }

  public getPolicy(dataSourceId: string): PolicyAction[] | undefined {
    return this.policies.get(dataSourceId);
  }

  public applyPolicies(
    data: Record<string, unknown>,
    dataSourceId: string
  ): { data: Record<string, unknown>; actions: PolicyAction[] } {
    const policies = this.policies.get(dataSourceId) || [];
    const classifications = this.classifyData(data);
    const appliedActions: PolicyAction[] = [];

    const processedData = { ...data };

    for (const classification of classifications) {
      const applicablePolicies = policies.filter(p => 
        p.targetSensitivity.includes(classification.sensitivityLevel)
      );

      for (const policy of applicablePolicies) {
        appliedActions.push(policy);
        
        if (policy.type === 'mask') {
          const value = processedData[classification.fieldName];
          if (typeof value === 'string') {
            processedData[classification.fieldName] = this.maskValue(value, classification.sensitivityLevel);
          }
        } else if (policy.type === 'block') {
          delete processedData[classification.fieldName];
        }
      }
    }

    return { data: processedData, actions: appliedActions };
  }

  public getRiskAssessment(classifications: ClassificationResult[]): {
    overallRisk: 'low' | 'medium' | 'high' | 'critical';
    score: number;
    breakdown: Record<string, number>;
  } {
    const weights = {
      public: 0,
      internal: 1,
      confidential: 3,
      restricted: 10
    };

    const breakdown: Record<string, number> = {
      public: 0,
      internal: 0,
      confidential: 0,
      restricted: 0
    };

    let totalScore = 0;
    for (const classification of classifications) {
      const weight = weights[classification.sensitivityLevel];
      totalScore += weight * classification.confidence;
      breakdown[classification.sensitivityLevel]++;
    }

    const maxPossibleScore = classifications.length * weights.restricted;
    const normalizedScore = maxPossibleScore > 0 ? totalScore / maxPossibleScore : 0;

    let overallRisk: 'low' | 'medium' | 'high' | 'critical' = 'low';
    if (normalizedScore >= 0.75) overallRisk = 'critical';
    else if (normalizedScore >= 0.5) overallRisk = 'high';
    else if (normalizedScore >= 0.25) overallRisk = 'medium';

    return {
      overallRisk,
      score: normalizedScore,
      breakdown
    };
  }

  private registerDefaultRules(): void {
    const defaultRules: Omit<DataClassificationRule, 'id'>[] = [
      {
        name: '身份证号',
        description: '中国居民身份证号码',
        pattern: /^[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$/,
        sensitivityLevel: 'restricted',
        category: '个人身份信息'
      },
      {
        name: '手机号',
        description: '中国手机号码',
        pattern: /^1[3-9]\d{9}$/,
        sensitivityLevel: 'confidential',
        category: '个人联系方式'
      },
      {
        name: '邮箱地址',
        description: '电子邮件地址',
        pattern: /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/,
        sensitivityLevel: 'confidential',
        category: '个人联系方式'
      },
      {
        name: '银行卡号',
        description: '银行借记卡/信用卡号',
        pattern: /^\d{16,19}$/,
        sensitivityLevel: 'restricted',
        category: '金融信息'
      },
      {
        name: '密码',
        description: '用户密码字段',
        keywords: ['password', 'pwd', 'passwd', '密码'],
        sensitivityLevel: 'restricted',
        category: '认证信息'
      },
      {
        name: '住址',
        description: '居住地址信息',
        keywords: ['address', '住址', '地址', '居住地址'],
        sensitivityLevel: 'confidential',
        category: '位置信息'
      },
      {
        name: '薪资',
        description: '薪资收入信息',
        keywords: ['salary', '薪资', '工资', '收入'],
        sensitivityLevel: 'restricted',
        category: '财务信息'
      },
      {
        name: '健康信息',
        description: '个人健康医疗信息',
        keywords: ['health', 'medical', '健康', '医疗', '病史'],
        sensitivityLevel: 'restricted',
        category: '健康信息'
      }
    ];

    defaultRules.forEach(rule => this.registerRule(rule));
  }

  private matchRule(rule: DataClassificationRule, fieldName: string, value: string): number {
    let confidence = 0;

    if (rule.pattern && rule.pattern.test(value)) {
      confidence = 0.9;
    }

    if (rule.keywords) {
      const fieldNameLower = fieldName.toLowerCase();
      const keywordMatch = rule.keywords.some(keyword => 
        fieldNameLower.includes(keyword.toLowerCase())
      );
      if (keywordMatch) {
        confidence = Math.max(confidence, 0.7);
      }
    }

    return confidence;
  }

  private deduplicateResults(results: ClassificationResult[]): ClassificationResult[] {
    const seen = new Map<string, ClassificationResult>();

    for (const result of results) {
      const existing = seen.get(result.fieldName);
      if (!existing || result.confidence > existing.confidence) {
        seen.set(result.fieldName, result);
      }
    }

    return Array.from(seen.values());
  }

  private calculateRiskSummary(results: ClassificationResult[]) {
    const summary = {
      public: 0,
      internal: 0,
      confidential: 0,
      restricted: 0
    };

    for (const result of results) {
      summary[result.sensitivityLevel]++;
    }

    return summary;
  }

  private async fetchDataSourceSample(dataSource: DataSource): Promise<Record<string, unknown>[]> {
    await new Promise(resolve => setTimeout(resolve, 100));

    return [
      {
        name: '张三',
        idCard: '110101199001011234',
        phone: '13800138000',
        email: 'zhangsan@example.com',
        address: '北京市朝阳区建国路88号',
        bankCard: '6222021234567890123',
        salary: '50000',
        password: '********'
      }
    ];
  }

  private maskValue(value: string, sensitivityLevel: string): string {
    const maskLength = Math.ceil(value.length * 0.7);
    const visibleStart = Math.floor((value.length - maskLength) / 2);
    
    return value.split('').map((char, i) => 
      i >= visibleStart && i < visibleStart + maskLength ? '*' : char
    ).join('');
  }

  public getStats() {
    return {
      rules: this.rules.size,
      dataSources: this.dataSources.size,
      totalReports: Array.from(this.reports.values()).flat().length,
      policies: this.policies.size
    };
  }
}

export const createDataClassification = (): DataClassificationModule => {
  return new DataClassificationModule();
};
