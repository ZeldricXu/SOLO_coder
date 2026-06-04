import { PrismaClient, Role, SpaceVisibility } from '@prisma/client';
import bcrypt from 'bcryptjs';

const prisma = new PrismaClient();

async function main() {
  const defaultEmail = 'admin@example.com';
  const defaultPassword = 'password123';
  const hashedPassword = await bcrypt.hash(defaultPassword, 12);

  const existingUser = await prisma.user.findUnique({
    where: { email: defaultEmail },
  });

  if (existingUser) {
    console.log('Default user already exists, skipping seed.');
    return;
  }

  const user = await prisma.user.create({
    data: {
      name: 'Admin User',
      email: defaultEmail,
      password: hashedPassword,
      avatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=admin',
    },
  });

  console.log(`Created default user: ${user.email}`);

  const space = await prisma.space.create({
    data: {
      name: '测试空间',
      description: '这是一个用于测试的默认空间',
      icon: '📚',
      color: '#3b82f6',
      visibility: SpaceVisibility.PRIVATE,
      createdById: user.id,
    },
  });

  console.log(`Created test space: ${space.name}`);

  await prisma.spaceMember.create({
    data: {
      spaceId: space.id,
      userId: user.id,
      role: Role.ADMIN,
    },
  });

  console.log('Added user as ADMIN to test space');

  const welcomeDoc = await prisma.document.create({
    data: {
      spaceId: space.id,
      title: '欢迎使用 Knowledge Hub',
      content: `# 欢迎使用 Knowledge Hub

这是一个功能强大的知识库管理系统，支持：

- **Markdown 编辑** - 支持完整的 Markdown 语法
- **实时协作** - 多人同时编辑文档
- **版本控制** - 完整的历史版本记录
- **标签管理** - 灵活的文档分类
- **智能搜索** - 基于向量的语义搜索
- **外部同步** - 支持飞书、Notion、Confluence 等

## 快速开始

1. 创建新文档
2. 添加标签进行分类
3. 邀请团队成员协作
4. 享受高效的知识管理体验！
`,
      contentHtml: `<h1>欢迎使用 Knowledge Hub</h1>
<p>这是一个功能强大的知识库管理系统，支持：</p>
<ul>
<li><strong>Markdown 编辑</strong> - 支持完整的 Markdown 语法</li>
<li><strong>实时协作</strong> - 多人同时编辑文档</li>
<li><strong>版本控制</strong> - 完整的历史版本记录</li>
<li><strong>标签管理</strong> - 灵活的文档分类</li>
<li><strong>智能搜索</strong> - 基于向量的语义搜索</li>
<li><strong>外部同步</strong> - 支持飞书、Notion、Confluence 等</li>
</ul>
<h2>快速开始</h2>
<ol>
<li>创建新文档</li>
<li>添加标签进行分类</li>
<li>邀请团队成员协作</li>
<li>享受高效的知识管理体验！</li>
</ol>`,
      wordCount: 120,
      createdById: user.id,
      path: '/welcome',
    },
  });

  console.log(`Created welcome document: ${welcomeDoc.title}`);

  await prisma.documentVersion.create({
    data: {
      documentId: welcomeDoc.id,
      title: welcomeDoc.title,
      content: welcomeDoc.content || '',
      contentHtml: welcomeDoc.contentHtml,
      version: 1,
      createdById: user.id,
      changeSummary: '初始版本',
    },
  });

  console.log('Created initial document version');

  const tags = [
    { name: '入门指南', color: '#10b981' },
    { name: '文档', color: '#3b82f6' },
  ];

  for (const tagData of tags) {
    const tag = await prisma.tag.create({
      data: {
        ...tagData,
        spaceId: space.id,
      },
    });

    await prisma.documentTag.create({
      data: {
        documentId: welcomeDoc.id,
        tagId: tag.id,
        assignedById: user.id,
      },
    });

    console.log(`Created tag: ${tag.name}`);
  }

  console.log('\nSeed completed successfully!');
  console.log(`\nDefault credentials:`);
  console.log(`  Email: ${defaultEmail}`);
  console.log(`  Password: ${defaultPassword}`);
}

main()
  .catch((e) => {
    console.error('Error during seed:', e);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
