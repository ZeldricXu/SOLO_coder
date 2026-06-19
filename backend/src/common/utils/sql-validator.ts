const FORBIDDEN_PATTERNS: RegExp[] = [
  /\bDROP\s+TABLE\b/gi,
  /\bDELETE\s+FROM\b/gi,
  /\bINSERT\s+INTO\b/gi,
  /\bUPDATE\s+\w+\s+SET\b/gi,
  /\bALTER\s+TABLE\b/gi,
  /\bTRUNCATE\b/gi,
  /\bCREATE\s+(TABLE|INDEX|VIEW|DATABASE|SCHEMA|PROCEDURE|FUNCTION|TRIGGER)\b/gi,
  /\bGRANT\b/gi,
  /\bREVOKE\b/gi,
];

function stripComments(sql: string): string {
  let result = sql;
  result = result.replace(/\/\*[\s\S]*?\*\//g, ' ');
  result = result.replace(/--[^\n]*/g, ' ');
  result = result.replace(/#[^\n]*/g, ' ');
  return result;
}

export class SqlValidator {
  static validate(sql: string): { safe: boolean; reason?: string } {
    const normalized = stripComments(sql.trim());

    for (const pattern of FORBIDDEN_PATTERNS) {
      pattern.lastIndex = 0;
      if (pattern.test(normalized)) {
        const match = normalized.match(pattern);
        return {
          safe: false,
          reason: `Forbidden SQL operation detected: ${match?.[0] ?? 'unknown'}`,
        };
      }
    }

    return { safe: true };
  }

  static isSelectOnly(sql: string): boolean {
    const normalized = sql.trim().toUpperCase();
    const firstKeyword = normalized.split(/\s+/)[0];
    return firstKeyword === 'SELECT' || firstKeyword === 'WITH';
  }
}
