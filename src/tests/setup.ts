import { afterAll, afterEach, beforeAll } from 'vitest';
import { PrismaClient } from '@prisma/client';
import { execSync } from 'child_process';
import * as fs from 'fs/promises';
import * as path from 'path';

declare global {
  var prisma: PrismaClient;
  var testUserId: string;
  var testSpaceId: string;
}

let prisma: PrismaClient;

beforeAll(async () => {
  process.env.DATABASE_URL = process.env.TEST_DATABASE_URL || 
    'postgresql://postgres:postgres@localhost:5432/knowledge_hub_test?schema=public';
  
  try {
    execSync('npx prisma db push --skip-generate', {
      stdio: 'inherit',
      cwd: path.resolve(__dirname, '../../'),
    });
  } catch (error) {
    console.warn('Database push failed, continuing anyway:', error);
  }

  prisma = new PrismaClient();
  globalThis.prisma = prisma;

  const sqlPath = path.resolve(__dirname, '../lib/search/migrations.sql');
  try {
    const sql = await fs.readFile(sqlPath, 'utf-8');
    await prisma.$executeRawUnsafe(sql);
  } catch (error) {
    console.warn('Search migrations failed, continuing anyway:', error);
  }
});

afterEach(async () => {
  if (prisma) {
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
        await prisma.$executeRawUnsafe(`TRUNCATE TABLE "${table}" CASCADE;`);
      } catch (error) {
        console.warn(`Failed to truncate ${table}:`, error);
      }
    }
  }
});

afterAll(async () => {
  if (prisma) {
    await prisma.$disconnect();
  }
});

export async function createTestUser(overrides = {}) {
  const bcrypt = await import('bcryptjs');
  const passwordHash = await bcrypt.hash('password123', 10);
  
  const user = await prisma.user.create({
    data: {
      name: 'Test User',
      email: `test-${Date.now()}@example.com`,
      password: passwordHash,
      role: 'USER',
      ...overrides,
    },
  });
  
  globalThis.testUserId = user.id;
  return user;
}

export async function createTestSpace(userId: string, overrides = {}) {
  const space = await prisma.space.create({
    data: {
      name: 'Test Space',
      description: 'Test space for integration tests',
      visibility: 'PRIVATE',
      createdById: userId,
      ...overrides,
    },
  });

  await prisma.spaceMember.create({
    data: {
      spaceId: space.id,
      userId,
      role: 'OWNER',
    },
  });

  globalThis.testSpaceId = space.id;
  return space;
}

export async function createTestDocument(spaceId: string, userId: string, overrides = {}) {
  return prisma.document.create({
    data: {
      spaceId,
      title: 'Test Document',
      content: '# Test Document\n\nThis is a test document.',
      contentHtml: '<h1>Test Document</h1><p>This is a test document.</p>',
      wordCount: 10,
      externalSource: 'INTERNAL',
      createdById: userId,
      path: '/test-document',
      version: 1,
      isArchived: false,
      ...overrides,
    },
  });
}
