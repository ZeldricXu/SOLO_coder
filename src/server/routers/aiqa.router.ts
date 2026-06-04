import { z } from 'zod';
import { router, protectedProcedure } from '../trpc';
import { AiQaService } from '../../lib/aiqa/AiQaService';
import { LlmProviderConfig } from '../../lib/aiqa/types';

function getLlmConfig(): LlmProviderConfig {
  const provider = process.env.LLM_PROVIDER as 'openai' | 'anthropic' || 'openai';
  const apiKey = process.env.LLM_API_KEY || '';
  const model = process.env.LLM_MODEL;
  const baseUrl = process.env.LLM_BASE_URL;

  if (!apiKey) {
    throw new Error('LLM_API_KEY environment variable is not set');
  }

  return {
    provider,
    apiKey,
    model,
    baseUrl,
  };
}

export const aiqaRouter = router({
  ask: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        question: z.string().min(1, '问题不能为空'),
        sessionId: z.string().cuid().optional(),
        topK: z.number().min(1).max(10).default(5),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const space = await ctx.prisma.space.findUnique({
        where: {
          id: input.spaceId,
          members: {
            some: {
              userId: ctx.user.id,
            },
          },
        },
        select: {
          id: true,
          aiQaEnabled: true,
        },
      });

      if (!space) {
        throw new Error('Space not found or access denied');
      }

      if (!space.aiQaEnabled) {
        throw new Error('AI问答功能未在此空间中启用，请联系空间管理员开启');
      }

      try {
        const llmConfig = getLlmConfig();
        const aiQaService = new AiQaService(ctx.prisma, llmConfig);

        const result = await aiQaService.askQuestion({
          spaceId: input.spaceId,
          question: input.question,
          sessionId: input.sessionId,
          topK: input.topK,
        });

        return {
          success: true,
          data: result,
        };
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : 'AI服务暂时不可用，请稍后重试';
        return {
          success: false,
          error: errorMessage,
          data: null,
        };
      }
    }),

  getSessions: protectedProcedure
    .input(
      z.object({
        spaceId: z.string().cuid(),
        limit: z.number().min(1).max(50).default(20),
      })
    )
    .query(async ({ ctx, input }) => {
      const space = await ctx.prisma.space.findUnique({
        where: {
          id: input.spaceId,
          members: {
            some: {
              userId: ctx.user.id,
            },
          },
        },
      });

      if (!space) {
        throw new Error('Space not found or access denied');
      }

      const llmConfig = getLlmConfig();
      const aiQaService = new AiQaService(ctx.prisma, llmConfig);

      const sessions = await aiQaService.getSessions(input.spaceId, input.limit);

      return {
        success: true,
        data: sessions,
      };
    }),

  getSessionMessages: protectedProcedure
    .input(
      z.object({
        sessionId: z.string().cuid(),
      })
    )
    .query(async ({ ctx, input }) => {
      const session = await ctx.prisma.aiQaSession.findUnique({
        where: {
          id: input.sessionId,
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
        },
      });

      if (!session) {
        throw new Error('Session not found or access denied');
      }

      const llmConfig = getLlmConfig();
      const aiQaService = new AiQaService(ctx.prisma, llmConfig);

      const messages = await aiQaService.getSessionMessages(input.sessionId);

      return {
        success: true,
        data: messages,
      };
    }),

  deleteSession: protectedProcedure
    .input(
      z.object({
        sessionId: z.string().cuid(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const session = await ctx.prisma.aiQaSession.findUnique({
        where: {
          id: input.sessionId,
          space: {
            members: {
              some: {
                userId: ctx.user.id,
              },
            },
          },
        },
      });

      if (!session) {
        throw new Error('Session not found or access denied');
      }

      const llmConfig = getLlmConfig();
      const aiQaService = new AiQaService(ctx.prisma, llmConfig);

      await aiQaService.deleteSession(input.sessionId);

      return {
        success: true,
      };
    }),
});
