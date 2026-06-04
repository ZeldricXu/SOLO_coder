import { PrismaClient } from '@prisma/client';
import {
  AskQuestionOptions,
  AskQuestionResult,
  DocumentReference,
  LlmProvider,
  LlmProviderConfig,
  Message,
  RAGOptions,
} from './types';
import { DocumentRetriever } from './retriever';
import { createLlmProvider } from './providers/llmProviders';

const SYSTEM_PROMPT = `你是一个知识库助手，基于提供的文档内容回答用户的问题。

重要规则：
1. 只使用提供的文档内容回答问题，不要编造信息
2. 如果文档中没有相关信息，请明确说明"我没有找到相关信息来回答这个问题"
3. 回答要准确、简洁、有帮助
4. 优先引用最新的文档内容
5. 如果有多个相关文档，综合它们的内容给出完整回答
6. 在回答的最后，用 [n] 格式标注引用来源，对应文档编号

文档将按以下格式提供：
[Document 1]
Title: 文档标题
Content: 文档内容
Relevant snippet: 相关片段

请基于这些文档内容回答用户的问题。`;

export class AiQaService {
  private prisma: PrismaClient;
  private retriever: DocumentRetriever;
  private llmProvider: LlmProvider;
  private defaultRagOptions: RAGOptions = {
    topK: 5,
    maxContextLength: 4000,
    includeOcrText: true,
  };

  constructor(
    prisma: PrismaClient,
    llmConfig: LlmProviderConfig
  ) {
    this.prisma = prisma;
    this.retriever = new DocumentRetriever(prisma);
    this.llmProvider = createLlmProvider(llmConfig);
  }

  async askQuestion(options: AskQuestionOptions): Promise<AskQuestionResult> {
    const { spaceId, question, sessionId, topK = 5 } = options;

    await this.validateSpaceAiEnabled(spaceId);

    const retrievalResults = await this.retriever.retrieve(
      question,
      spaceId,
      { ...this.defaultRagOptions, topK }
    );

    if (retrievalResults.length === 0) {
      return this.createNoResultsResponse(spaceId, question, sessionId);
    }

    const context = this.retriever.buildContext(
      retrievalResults,
      this.defaultRagOptions.maxContextLength
    );

    const references: DocumentReference[] = retrievalResults.map((result, index) => ({
      documentId: result.documentId,
      title: result.title,
      spaceId,
      snippet: result.snippet,
      relevanceScore: result.relevanceScore,
      url: `/spaces/${spaceId}/documents/${result.documentId}`,
    }));

    const session = await this.getOrCreateSession(spaceId, sessionId, question);

    const previousMessages = await this.getPreviousMessages(session.id);
    const llmMessages = this.buildLlmMessages(question, context, previousMessages);

    const llmResponse = await this.llmProvider.complete({
      messages: llmMessages,
      temperature: 0.3,
      maxTokens: 1500,
    });

    const answer = this.formatAnswerWithReferences(llmResponse.content, references);

    const message = await this.saveMessage(session.id, {
      role: 'user',
      content: question,
      createdAt: new Date(),
    });

    await this.saveMessage(session.id, {
      role: 'assistant',
      content: answer,
      references,
      createdAt: new Date(),
    });

    return {
      answer,
      references,
      sessionId: session.id,
      messageId: message.id,
    };
  }

  private async validateSpaceAiEnabled(spaceId: string): Promise<void> {
    const space = await this.prisma.space.findUnique({
      where: { id: spaceId },
      select: { aiQaEnabled: true },
    });

    if (!space) {
      throw new Error('Space not found');
    }

    if (!space.aiQaEnabled) {
      throw new Error('AI QA is not enabled for this space');
    }
  }

  private async getOrCreateSession(
    spaceId: string,
    sessionId: string | undefined,
    firstQuestion: string
  ) {
    if (sessionId) {
      const session = await this.prisma.aiQaSession.findUnique({
        where: { id: sessionId },
      });
      if (session) return session;
    }

    const title = firstQuestion.slice(0, 100) + (firstQuestion.length > 100 ? '...' : '');
    return this.prisma.aiQaSession.create({
      data: {
        spaceId,
        title,
      },
    });
  }

  private async getPreviousMessages(sessionId: string): Promise<Message[]> {
    const messages = await this.prisma.aiQaMessage.findMany({
      where: { sessionId },
      orderBy: { createdAt: 'asc' },
      take: 10,
      select: {
        id: true,
        role: true,
        content: true,
        createdAt: true,
      },
    });

    return messages.map(m => ({
      id: m.id,
      role: m.role as 'user' | 'assistant',
      content: m.content,
      createdAt: m.createdAt,
    }));
  }

  private buildLlmMessages(
    question: string,
    context: string,
    previousMessages: Message[]
  ): Array<{ role: 'system' | 'user' | 'assistant'; content: string }> {
    const messages: Array<{ role: 'system' | 'user' | 'assistant'; content: string }> = [
      {
        role: 'system',
        content: SYSTEM_PROMPT,
      },
    ];

    for (const msg of previousMessages.slice(-6)) {
      if (msg.role === 'user' || msg.role === 'assistant') {
        messages.push({
          role: msg.role,
          content: msg.content,
        });
      }
    }

    const userPrompt = `
参考文档：
${context}

用户问题：${question}

请基于上述参考文档回答问题。如果文档中没有相关信息，请明确说明。
回答末尾请用 [n] 格式标注引用的文档编号。`;

    messages.push({
      role: 'user',
      content: userPrompt,
    });

    return messages;
  }

  private formatAnswerWithReferences(
    answer: string,
    references: DocumentReference[]
  ): string {
    let formattedAnswer = answer.trim();

    for (let i = 0; i < references.length; i++) {
      const docRef = `[${i + 1}]`;
      if (!formattedAnswer.includes(docRef)) {
        formattedAnswer = formattedAnswer + ` ${docRef}`;
      }
    }

    return formattedAnswer;
  }

  private createNoResultsResponse(
    spaceId: string,
    question: string,
    sessionId?: string
  ): AskQuestionResult {
    const answer = '我没有找到相关信息来回答这个问题。请尝试使用其他关键词搜索，或者检查知识库中是否有相关文档。';

    return {
      answer,
      references: [],
      sessionId: sessionId || '',
      messageId: '',
    };
  }

  private async saveMessage(sessionId: string, message: Omit<Message, 'id'>): Promise<Message> {
    const saved = await this.prisma.aiQaMessage.create({
      data: {
        sessionId,
        role: message.role,
        content: message.content,
        references: message.references ? JSON.stringify(message.references) : null,
      },
    });

    return {
      id: saved.id,
      role: saved.role as 'user' | 'assistant',
      content: saved.content,
      references: saved.references ? JSON.parse(saved.references) : undefined,
      createdAt: saved.createdAt,
    };
  }

  async getSessions(spaceId: string, limit: number = 20) {
    return this.prisma.aiQaSession.findMany({
      where: { spaceId },
      orderBy: { updatedAt: 'desc' },
      take: limit,
    });
  }

  async getSessionMessages(sessionId: string) {
    const messages = await this.prisma.aiQaMessage.findMany({
      where: { sessionId },
      orderBy: { createdAt: 'asc' },
    });

    return messages.map(m => ({
      id: m.id,
      role: m.role as 'user' | 'assistant',
      content: m.content,
      references: m.references ? JSON.parse(m.references) : undefined,
      createdAt: m.createdAt,
    }));
  }

  async deleteSession(sessionId: string) {
    await this.prisma.aiQaMessage.deleteMany({
      where: { sessionId },
    });
    await this.prisma.aiQaSession.delete({
      where: { id: sessionId },
    });
  }
}
