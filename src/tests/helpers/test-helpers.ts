import { PrismaClient, User, Space, Document, SpaceMember } from '@prisma/client';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';

export class TestHelper {
  private prisma: PrismaClient;

  constructor(prisma: PrismaClient) {
    this.prisma = prisma;
  }

  async createUser(overrides: Partial<User> = {}): Promise<User & { token: string }> {
    const passwordHash = await bcrypt.hash('password123', 10);
    
    const user = await this.prisma.user.create({
      data: {
        name: 'Test User',
        email: `test-${Date.now()}-${Math.random()}@example.com`,
        password: passwordHash,
        role: 'USER',
        ...overrides,
      },
    });

    const token = jwt.sign(
      { userId: user.id, email: user.email },
      process.env.JWT_SECRET || 'test-secret',
      { expiresIn: '7d' }
    );

    return { ...user, token };
  }

  async createSpace(userId: string, overrides: Partial<Space> = {}): Promise<Space> {
    const space = await this.prisma.space.create({
      data: {
        name: 'Test Space',
        description: 'Test space for integration tests',
        visibility: 'PRIVATE',
        createdById: userId,
        ...overrides,
      },
    });

    await this.prisma.spaceMember.create({
      data: {
        spaceId: space.id,
        userId,
        role: 'OWNER',
      },
    });

    return space;
  }

  async addSpaceMember(
    spaceId: string,
    userId: string,
    role: 'OWNER' | 'ADMIN' | 'EDITOR' | 'VIEWER' = 'VIEWER'
  ): Promise<SpaceMember> {
    return this.prisma.spaceMember.create({
      data: {
        spaceId,
        userId,
        role,
      },
    });
  }

  async createDocument(
    spaceId: string,
    userId: string,
    overrides: Partial<Document> = {}
  ): Promise<Document> {
    return this.prisma.document.create({
      data: {
        spaceId,
        title: 'Test Document',
        content: '# Test Document\n\nThis is a test document with some content.',
        contentHtml: '<h1>Test Document</h1><p>This is a test document with some content.</p>',
        wordCount: 15,
        source: 'MANUAL',
        status: 'DRAFT',
        createdById: userId,
        updatedById: userId,
        path: `/test-doc-${Date.now()}`,
        version: 1,
        isArchived: false,
        ...overrides,
      },
    });
  }

  async createShareLink(
    spaceId: string,
    createdById: string,
    options: {
      password?: string;
      expiresAt?: Date;
      role?: 'VIEWER' | 'EDITOR';
    } = {}
  ) {
    const token = `share-${Date.now()}-${Math.random().toString(36).substring(7)}`;
    const hashedPassword = options.password
      ? await bcrypt.hash(options.password, 10)
      : null;

    return this.prisma.spaceShareLink.create({
      data: {
        spaceId,
        token,
        password: hashedPassword,
        expiresAt: options.expiresAt,
        role: options.role || 'VIEWER',
        createdById,
      },
    });
  }

  async createReview(
    documentId: string,
    createdById: string,
    reviewerIds: string[],
    overrides = {}
  ) {
    const review = await this.prisma.review.create({
      data: {
        documentId,
        title: 'Test Review',
        description: 'Please review this document',
        status: 'PENDING',
        createdById,
        ...overrides,
      },
    });

    await this.prisma.reviewReviewer.createMany({
      data: reviewerIds.map((userId) => ({
        reviewId: review.id,
        userId,
        status: 'PENDING',
      })),
    });

    return this.prisma.review.findUnique({
      where: { id: review.id },
      include: {
        reviewers: {
          include: {
            user: {
              select: { id: true, name: true, email: true },
            },
          },
        },
      },
    });
  }

  async createComment(
    documentId: string,
    createdById: string,
    reviewId?: string,
    overrides = {}
  ) {
    return this.prisma.comment.create({
      data: {
        documentId,
        reviewId,
        content: 'This is a test comment',
        createdById,
        position: JSON.stringify({ from: 0, to: 10 }),
        ...overrides,
      },
    });
  }

  async cleanup() {
    const tables = [
      'SyncLog',
      'SyncConfig',
      'Reviewer',
      'Review',
      'Comment',
      'KnowledgeNode',
      'DocumentReference',
      'DocumentTag',
      'Tag',
      'DocumentVersion',
      'Document',
      'SpaceShareLink',
      'ShareLink',
      'SpaceMember',
      'Space',
      'User',
    ];

    for (const table of tables) {
      try {
        await this.prisma.$executeRawUnsafe(`TRUNCATE TABLE "${table}" CASCADE;`);
      } catch (error) {
        console.warn(`Failed to truncate ${table}:`, error);
      }
    }
  }

  get prismaClient() {
    return this.prisma;
  }
}

export function createTestContext(prisma: PrismaClient, user?: User & { token: string }) {
  return {
    prisma,
    user: user
      ? {
          id: user.id,
          email: user.email,
          name: user.name,
          avatar: user.avatar,
          role: user.role,
          createdAt: user.createdAt,
        }
      : null,
    req: {
      headers: {
        get: (key: string) => {
          if (key === 'cookie' && user) {
            return `auth-token=${user.token}`;
          }
          return null;
        },
      },
    } as any,
    resHeaders: new Headers(),
  };
}
