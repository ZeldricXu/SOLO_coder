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

export class SqlValidator {
  static validate(sql: string): { safe: boolean; reason?: string } {
    const normalized = sql.trim();

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
