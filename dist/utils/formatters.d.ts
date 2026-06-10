import { ValidationReport, ValidationError, DiffReport, DiffItem } from '../types';
export declare function formatValidationReport(report: ValidationReport, useColors?: boolean): string;
export declare function formatValidationErrors(errors: ValidationError[], useColors?: boolean): string;
export declare function formatDiffReport(report: DiffReport, useColors?: boolean): string;
export declare function formatDiffItem(diff: DiffItem, useColors?: boolean): string;
export declare function formatValue(v: unknown): string;
export declare function formatByteSize(bytes: number): string;
export declare function formatDuration(ms: number): string;
export declare function formatTimestamp(ts: number): string;
export declare function truncate(str: string, maxLen: number): string;
export declare function formatKeyValueTable(rows: {
    key: string;
    value: string;
}[], useColors?: boolean): string;
