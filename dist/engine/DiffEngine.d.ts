import { ConfigData, DiffItem, DiffReport, DiffType } from '../types';
export declare class DiffEngine {
    compare(dataA: ConfigData, dataB: ConfigData, environmentA: string, environmentB: string): DiffReport;
    private traverseAndCompare;
    private compareObjects;
    private compareArrays;
    private createDiff;
    private calculateChangePercent;
    formatDiff(report: DiffReport, useColors?: boolean): string;
    private formatValue;
    filterDiffs(diffs: DiffItem[], options: {
        type?: DiffType;
        keyPattern?: string;
    }): DiffItem[];
    hasDrift(report: DiffReport, ignoreKeys?: string[]): boolean;
    generateDriftReport(report: DiffReport, ignoreKeys?: string[]): {
        drift: boolean;
        criticalDiffs: DiffItem[];
        ignoredDiffs: DiffItem[];
    };
}
