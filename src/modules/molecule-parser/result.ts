export enum ParseErrorCode {
  EMPTY_CONTENT = 'EMPTY_CONTENT',
  INVALID_FORMAT = 'INVALID_FORMAT',
  MISSING_REQUIRED_FIELD = 'MISSING_REQUIRED_FIELD',
  INVALID_COORDINATE = 'INVALID_COORDINATE',
  INVALID_ATOM_INDEX = 'INVALID_ATOM_INDEX',
  MALFORMED_LINE = 'MALFORMED_LINE',
  UNRECOGNIZED_RECORD = 'UNRECOGNIZED_RECORD',
  PARSING_FAILED = 'PARSING_FAILED',
}

export interface ParseError {
  code: ParseErrorCode;
  message: string;
  lineNumber: number | null;
  columnOffset: number | null;
  recordType: string | null;
}

export type Result<T> =
  | { ok: true; value: T; errors: ParseError[] }
  | { ok: false; errors: ParseError[] };

export function ok<T>(value: T, errors: ParseError[] = []): Result<T> {
  return { ok: true, value, errors };
}

export function err<T>(errors: ParseError[]): Result<T> {
  return { ok: false, errors };
}

export function singleErr<T>(
  code: ParseErrorCode,
  message: string,
  lineNumber: number | null = null,
  columnOffset: number | null = null,
  recordType: string | null = null
): Result<T> {
  return err([{ code, message, lineNumber, columnOffset, recordType }]);
}

export function createError(
  code: ParseErrorCode,
  message: string,
  lineNumber: number | null = null,
  columnOffset: number | null = null,
  recordType: string | null = null
): ParseError {
  return { code, message, lineNumber, columnOffset, recordType };
}
