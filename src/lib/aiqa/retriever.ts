import { PrismaClient } from '@prisma/client';
import { RetrievalResult, DocumentReference, RAGOptions } from '../types';
import { buildDocumentVectorSql } from '../search/indexer';

export class DocumentRetriever {
  private prisma: PrismaClient;
  private defaultOptions: RAGOptions = {
    topK: 5,
    maxContextLength: 4000,
    includeOcrText: true,
  };

  constructor(prisma: PrismaClient) {
    this.prisma = prisma;
  }

  async retrieve(
    query: string,
    spaceId: string,
    options: Partial<RAGOptions> = {}
  ): Promise<RetrievalResult[]> {
    const opts = { ...this.defaultOptions, ...options };

    const results = await this.searchWithRelevance(query, spaceId, opts);
    
    const snippets = this.extractSnippets(query, results);
    
    return snippets;
  }

  private async searchWithRelevance(
    query: string,
    spaceId: string,
    options: RAGOptions
  ): Promise<Array<{ documentId: string; title: string; content: string; ocrText?: string | null; relevance: number }>> {
    const useZhparser = process.env.SEARCH_USE_ZHPARSER === 'true';
    const vectorSql = buildDocumentVectorSql(useZhparser);
    const configName = useZhparser ? 'zhparser' : 'simple';

    const tsQuery = this.buildTsQuery(query, configName);

    const results = await this.prisma.$queryRawUnsafe<
      Array<{
        id: string;
        title: string;
        content: string;
        ocrText?: string | null;
        relevance: number;
      }>
    >(
      `
        SELECT 
          d.id,
          d.title,
          d.content,
          d."ocrText",
          ts_rank(
            d."searchVector",
            ${tsQuery}
          ) as relevance
        FROM "Document" d
        WHERE 
          d."spaceId" = $1
          AND d.status != 'DELETED'
          AND d."searchVector" @@ ${tsQuery}
        ORDER BY relevance DESC
        LIMIT $2
      `,
      spaceId,
      options.topK
    );

    if (results.length === 0) {
      return this.fuzzySearch(query, spaceId, options.topK);
    }

    return results.map(r => ({
      documentId: r.id,
      title: r.title,
      content: r.content,
      ocrText: r.ocrText,
      relevance: r.relevance,
    }));
  }

  private buildTsQuery(query: string, configName: string): string {
    const terms = query.split(/\s+/).filter(Boolean);
    const quotedTerms = terms.map(t => `'${t.replace(/'/g, "''")}'`).join(' & ');
    return `to_tsquery('${configName}', ${quotedTerms})`;
  }

  private async fuzzySearch(
    query: string,
    spaceId: string,
    limit: number
  ): Promise<Array<{ documentId: string; title: string; content: string; ocrText?: string | null; relevance: number }>> {
    const documents = await this.prisma.document.findMany({
      where: {
        spaceId,
        status: { not: 'DELETED' },
        OR: [
          { title: { contains: query, mode: 'insensitive' } },
          { content: { contains: query, mode: 'insensitive' } },
          { ocrText: { contains: query, mode: 'insensitive' } },
        ],
      },
      select: {
        id: true,
        title: true,
        content: true,
        ocrText: true,
      },
      take: limit,
      orderBy: { updatedAt: 'desc' },
    });

    return documents.map((doc, index) => ({
      documentId: doc.id,
      title: doc.title,
      content: doc.content,
      ocrText: doc.ocrText,
      relevance: 1 - index * 0.1,
    }));
  }

  private extractSnippets(
    query: string,
    documents: Array<{ documentId: string; title: string; content: string; ocrText?: string | null; relevance: number }>
  ): RetrievalResult[] {
    const queryTerms = query.toLowerCase().split(/\s+/).filter(Boolean);

    return documents.map(doc => {
      const searchContent = doc.content;
      let bestSnippet = this.findBestSnippet(searchContent, queryTerms, 200);

      if (!bestSnippet && doc.ocrText) {
        bestSnippet = this.findBestSnippet(doc.ocrText, queryTerms, 200);
      }

      if (!bestSnippet) {
        bestSnippet = searchContent.slice(0, 200) + (searchContent.length > 200 ? '...' : '');
      }

      return {
        documentId: doc.documentId,
        title: doc.title,
        spaceId: '',
        content: searchContent,
        snippet: bestSnippet,
        relevanceScore: doc.relevance,
      };
    });
  }

  private findBestSnippet(
    content: string,
    queryTerms: string[],
    windowSize: number = 200
  ): string | null {
    if (!content) return null;

    const lowerContent = content.toLowerCase();
    const positions: number[] = [];

    for (const term of queryTerms) {
      let pos = lowerContent.indexOf(term);
      while (pos !== -1) {
        positions.push(pos);
        pos = lowerContent.indexOf(term, pos + 1);
      }
    }

    if (positions.length === 0) return null;

    positions.sort((a, b) => a - b);

    let bestStart = 0;
    let bestCount = 0;

    for (let i = 0; i < positions.length; i++) {
      const windowStart = positions[i];
      const windowEnd = windowStart + windowSize;
      let count = 0;

      for (const pos of positions) {
        if (pos >= windowStart && pos < windowEnd) {
          count++;
        }
      }

      if (count > bestCount) {
        bestCount = count;
        bestStart = windowStart;
      }
    }

    const start = Math.max(0, bestStart - 50);
    const end = Math.min(content.length, start + windowSize);
    let snippet = content.slice(start, end);

    if (start > 0) snippet = '...' + snippet;
    if (end < content.length) snippet = snippet + '...';

    return snippet;
  }

  buildContext(retrievalResults: RetrievalResult[], maxLength: number = 4000): string {
    let context = '';
    const references: DocumentReference[] = [];

    for (let i = 0; i < retrievalResults.length; i++) {
      const result = retrievalResults[i];
      const docContext = `
[Document ${i + 1}]
Title: ${result.title}
Content: ${result.content.slice(0, 800)}
${result.snippet !== result.content.slice(0, 200) ? `Relevant snippet: ${result.snippet}` : ''}

`;

      if (context.length + docContext.length > maxLength) {
        break;
      }

      context += docContext;
      references.push({
        documentId: result.documentId,
        title: result.title,
        spaceId: result.spaceId,
        snippet: result.snippet,
        relevanceScore: result.relevanceScore,
      });
    }

    return context.trim();
  }

  formatReferences(references: DocumentReference[]): string {
    if (references.length === 0) return '';

    return '\n\n**References:**\n' + 
      references.map((ref, i) => 
        `[${i + 1}] ${ref.title} (Score: ${ref.relevanceScore.toFixed(2)})`
      ).join('\n');
  }
}
