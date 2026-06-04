import { z } from 'zod';
import { TRPCError } from '@trpc/server';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import cookie from 'cookie';
import { router, publicProcedure, protectedProcedure } from '../trpc';

export const authRouter = router({
  register: publicProcedure
    .input(
      z.object({
        name: z.string().min(2, '姓名至少2个字符').max(50, '姓名最多50个字符'),
        email: z.string().email('邮箱格式不正确'),
        password: z.string().min(6, '密码至少6个字符').max(100, '密码最多100个字符'),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const existingUser = await ctx.prisma.user.findUnique({
        where: { email: input.email },
      });

      if (existingUser) {
        throw new TRPCError({
          code: 'CONFLICT',
          message: '该邮箱已被注册',
        });
      }

      const hashedPassword = await bcrypt.hash(input.password, 12);

      const user = await ctx.prisma.user.create({
        data: {
          name: input.name,
          email: input.email,
          password: hashedPassword,
          role: 'USER',
        },
        select: {
          id: true,
          name: true,
          email: true,
          avatar: true,
          role: true,
          createdAt: true,
        },
      });

      const token = jwt.sign(
        { userId: user.id, email: user.email },
        process.env.JWT_SECRET!,
        { expiresIn: '7d' }
      );

      ctx.resHeaders.set(
        'Set-Cookie',
        cookie.serialize('auth-token', token, {
          httpOnly: true,
          secure: process.env.NODE_ENV === 'production',
          sameSite: 'lax',
          maxAge: 7 * 24 * 60 * 60,
          path: '/',
        })
      );

      return user;
    }),

  login: publicProcedure
    .input(
      z.object({
        email: z.string().email('邮箱格式不正确'),
        password: z.string().min(6, '密码至少6个字符'),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const user = await ctx.prisma.user.findUnique({
        where: { email: input.email },
      });

      if (!user) {
        throw new TRPCError({
          code: 'NOT_FOUND',
          message: '用户不存在',
        });
      }

      const isPasswordValid = await bcrypt.compare(input.password, user.password);

      if (!isPasswordValid) {
        throw new TRPCError({
          code: 'UNAUTHORIZED',
          message: '密码错误',
        });
      }

      const token = jwt.sign(
        { userId: user.id, email: user.email },
        process.env.JWT_SECRET!,
        { expiresIn: '7d' }
      );

      ctx.resHeaders.set(
        'Set-Cookie',
        cookie.serialize('auth-token', token, {
          httpOnly: true,
          secure: process.env.NODE_ENV === 'production',
          sameSite: 'lax',
          maxAge: 7 * 24 * 60 * 60,
          path: '/',
        })
      );

      return {
        id: user.id,
        name: user.name,
        email: user.email,
        avatar: user.avatar,
        role: user.role,
        createdAt: user.createdAt,
      };
    }),

  logout: protectedProcedure.mutation(async ({ ctx }) => {
    ctx.resHeaders.set(
      'Set-Cookie',
      cookie.serialize('auth-token', '', {
        httpOnly: true,
        secure: process.env.NODE_ENV === 'production',
        sameSite: 'lax',
        maxAge: 0,
        path: '/',
      })
    );

    return { success: true };
  }),

  getCurrentUser: protectedProcedure.query(async ({ ctx }) => {
    return ctx.user;
  }),
});
