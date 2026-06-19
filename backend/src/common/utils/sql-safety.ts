export interface SqlSafetyCheckResult {
  safe: boolean;
  reason?: string;
  matchedRule?: string;
}

const DANGEROUS_PATTERNS: Array<{ name: string; regex: RegExp }> = [
  {
    name: 'DROP_TABLE',
    regex: /\bDROP\s+(TABLE|DATABASE|SCHEMA|INDEX|VIEW|FUNCTION|PROCEDURE|TRIGGER)\b/i,
  },
  {
    name: 'DELETE_FROM',
    regex: /\bDELETE\s+FROM\b/i,
  },
  {
    name: 'INSERT_INTO',
    regex: /\bINSERT\s+INTO\b/i,
  },
  {
    name: 'UPDATE_SET',
    regex: /\bUPDATE\s+[\w.]+(\s+.*)?\s+SET\b/i,
  },
  {
    name: 'ALTER_TABLE',
    regex: /\bALTER\s+(TABLE|DATABASE|SCHEMA|VIEW|FUNCTION|PROCEDURE|TRIGGER|USER|ROLE)\b/i,
  },
  {
    name: 'TRUNCATE',
    regex: /\bTRUNCATE\s+(TABLE\s+)?[\w.]+\b/i,
  },
  {
    name: 'CREATE',
    regex: /\bCREATE\s+(TABLE|DATABASE|SCHEMA|USER|ROLE|INDEX|VIEW|FUNCTION|PROCEDURE|TRIGGER|FUNCTION|VIEW)\b/i,
  },
  {
    name: 'GRANT',
    regex: /\bGRANT\s+.*?\s+ON\b/i,
  },
  {
    name: 'REVOKE',
    regex: /\bREVOKE\s+.*?\s+ON\b/i,
  },
];

const MULTI_STATEMENT_PATTERN = /;\s*\w/i;
const COMMENT_PATTERN = /--.*$/gm;
const BLOCK_COMMENT_PATTERN = /\/\*[\s\S]*?\*\//g;

function stripComments(sql: string): string {
  return sql.replace(BLOCK_COMMENT_PATTERN, '').replace(COMMENT_PATTERN, '');
}

export function checkSqlSafety(sql: string): SqlSafetyCheckResult {
  const stripped = stripComments(sql);
  const trimmed = stripped.trim();

  if (!trimmed) return { safe: false, reason: 'Empty SQL' };

  if (MULTI_STATEMENT_PATTERN.test(trimmed)) {
    return { safe: false, reason: 'Multiple statements not allowed' };
  }

  for (const pattern of DANGEROUS_PATTERNS) {
    if (pattern.regex.test(trimmed)) {
      return {
        safe: false,
        reason: `Dangerous statement detected: ${pattern.name}`,
        matchedRule: pattern.name,
      };
    }
  }

  const firstWord = trimmed.replace(/^[(WITH\s/i, 'WITH ').trim().split(/\s+/)[0].toUpperCase();
  const allowedStartsWith = ['SELECT', 'WITH', 'EXPLAIN', 'DESCRIBE', 'SHOW'];
  if (!allowedStartsWith.some((w) firstWord.startsWith(w)) {
    return { safe: false, reason: 'Only SELECT/WITH/EXPLAIN/DESCRIBE/SHOW statements are allowed' };
  }

  return { safe: true };
}

export const dangerousPatternNames = DANGEROUS_PATTERNS.map((p) => p.name);
