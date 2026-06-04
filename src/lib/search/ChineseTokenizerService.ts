import { tokenize, extractKeywords } from '../knowledge-graph/tokenizer';

export interface TokenizeResult {
  tokens: string;
  tokenVectorSql: string;
  tokenVectorParams: string[];
}

export class ChineseTokenizerService {
  private static instance: ChineseTokenizerService;

  private constructor() {}

  public static getInstance(): ChineseTokenizerService {
    if (!ChineseTokenizerService.instance) {
      ChineseTokenizerService.instance = new ChineseTokenizerService();
    }
    return ChineseTokenizerService.instance;
  }

  public tokenizeForSearch(title: string, content: string, ocrText?: string | null): TokenizeResult {
    const fullText = `${title} ${content || ''} ${ocrText || ''}`;
    const tokenized = tokenize(fullText);
    
    const uniqueTokens = Array.from(new Set([
      ...tokenized.filteredTokens,
      ...tokenized.keywords,
    ])).filter(t => t.length >= 2);
    
    const tokens = uniqueTokens.join(' ');
    
    const weightedTokens = this.buildWeightedTokens(title, content, ocrText || undefined);
    
    return {
      tokens,
      tokenVectorSql: weightedTokens.sql,
      tokenVectorParams: weightedTokens.params,
    };
  }

  public tokenizeQuery(query: string): {
    tokens: string[];
    tsQuery: string;
    hasChinese: boolean;
  } {
    const hasChinese = /[\u4e00-\u9fa5]/.test(query);
    
    if (!hasChinese) {
      return {
        tokens: [query.toLowerCase()],
        tsQuery: this.buildEnglishTsQuery(query),
        hasChinese: false,
      };
    }
    
    const tokenized = tokenize(query);
    const searchTokens = [
      ...tokenized.filteredTokens,
      ...tokenized.keywords,
      ...this.extractBigrams(query),
    ].filter(t => t.length >= 2);
    
    const uniqueTokens = Array.from(new Set(searchTokens));
    
    const tsQuery = uniqueTokens
      .map(t => this.escapeTsQueryTerm(t))
      .join(' | ');
    
    return {
      tokens: uniqueTokens,
      tsQuery,
      hasChinese: true,
    };
  }

  private extractBigrams(text: string): string[] {
    const bigrams: string[] = [];
    const cleaned = text.replace(/[^\u4e00-\u9fa5a-zA-Z0-9]/g, '');
    
    for (let i = 0; i < cleaned.length - 1; i++) {
      bigrams.push(cleaned.substring(i, i + 2));
    }
    
    return bigrams;
  }

  private buildWeightedTokens(
    title: string,
    content: string,
    ocrText?: string
  ): { sql: string; params: string[] } {
    const titleTokens = tokenize(title).filteredTokens.filter(t => t.length >= 2);
    const contentTokens = tokenize(content || '').filteredTokens.filter(t => t.length >= 2);
    const ocrTokens = ocrText ? tokenize(ocrText).filteredTokens.filter(t => t.length >= 2) : [];
    
    const titleWeight = 'A';
    const contentWeight = 'B';
    const ocrWeight = 'D';
    
    const params: string[] = [
      titleTokens.join(' '),
      contentTokens.join(' '),
      ocrTokens.join(' '),
    ];
    
    const sql = `
      setweight(to_tsvector('simple', coalesce($1, '')), '${titleWeight}') ||
      setweight(to_tsvector('simple', coalesce($2, '')), '${contentWeight}') ||
      setweight(to_tsvector('simple', coalesce($3, '')), '${ocrWeight}')
    `;
    
    return { sql, params };
  }

  private buildEnglishTsQuery(query: string): string {
    const terms = query.toLowerCase().split(/\s+/).filter(t => t.length > 0);
    return terms
      .map(t => `${this.escapeTsQueryTerm(t)}:*`)
      .join(' & ');
  }

  private escapeTsQueryTerm(term: string): string {
    return term
      .replace(/'/g, "''")
      .replace(/\\/g, '\\\\')
      .replace(/[&|!:*]/g, ' ');
  }

  public buildFallbackQuery(query: string): { sql: string; params: unknown[] } {
    const params: unknown[] = [];
    const searchPattern = `%${query}%`;
    params.push(searchPattern, searchPattern, searchPattern);
    
    const sql = `
      (title ILIKE $1 OR content ILIKE $2 OR "ocrText" ILIKE $3)
    `;
    
    return { sql, params };
  }
}

export function tokenizeDocumentForSearch(
  title: string,
  content: string,
  ocrText?: string | null
): TokenizeResult {
  return ChineseTokenizerService.getInstance().tokenizeForSearch(title, content, ocrText);
}

export function tokenizeQueryForSearch(query: string): {
  tokens: string[];
  tsQuery: string;
  hasChinese: boolean;
} {
  return ChineseTokenizerService.getInstance().tokenizeQuery(query);
}
