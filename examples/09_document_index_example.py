"""
示例9: 内部文档索引模块
"""

import asyncio
import sys
import os
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from src.core import LogLevel
from src.infrastructure.logging import StructuredLogger, ConsoleHandler, TextFormatter
from src.modules.document_index import DocumentIndex


async def main():
    print("=== 文档索引示例 ===\n")

    logger = StructuredLogger(
        service_name="document-index",
        handlers=[ConsoleHandler(level=LogLevel.INFO, formatter=TextFormatter())],
    )

    doc_index = DocumentIndex(logger=logger)

    print("1. 创建测试文档源:")
    with tempfile.TemporaryDirectory() as tmpdir:
        docs = [
            ("README.md", "# 用户服务\n\n用户服务提供用户管理功能。API使用RESTful风格。\n\n## 认证\n使用JWT Token进行认证。"),
            ("API_GUIDE.md", "# API指南\n\n## 端点\n\n### GET /api/users\n获取用户列表。需要JWT认证。\n\n### POST /api/users\n创建新用户。"),
            ("DEPLOYMENT.md", "# 部署指南\n\n使用Docker部署用户服务。\n\n## 配置\n需要配置数据库连接和JWT密钥。"),
            ("internal/ARCHITECTURE.md", "# 架构设计\n\n用户服务采用分层架构：Controller -> Service -> Repository。\n\n依赖认证服务进行JWT验证。"),
        ]

        for filename, content in docs:
            filepath = os.path.join(tmpdir, filename)
            os.makedirs(os.path.dirname(filepath), exist_ok=True)
            with open(filepath, "w", encoding="utf-8") as f:
                f.write(content)
            print(f"   - {filename}")

        print("\n2. 添加文档源并索引:")
        doc_index.add_source(
            "user-service-docs",
            {
                "type": "filesystem",
                "path": tmpdir,
                "permissions": ["docs:user", "docs:read"],
                "tags": ["user-service", "backend"],
            },
        )

        doc_index.add_source(
            "internal-docs",
            {
                "type": "filesystem",
                "path": os.path.join(tmpdir, "internal"),
                "permissions": ["docs:internal"],
                "tags": ["internal", "architecture"],
            },
        )

        count = await doc_index.crawl_and_index()
        print(f"   索引完成，共 {count} 个文档")

        print("\n3. 配置权限:")
        perm_filter = doc_index.get_permission_filter()
        perm_filter.add_user_permission("alice", "docs:user")
        perm_filter.add_user_permission("alice", "docs:read")
        perm_filter.add_user_to_group("bob", "engineering")
        perm_filter.add_group_permission("engineering", "docs:internal")
        perm_filter.add_group_permission("engineering", "docs:user")
        perm_filter.add_group_permission("engineering", "docs:read")

        print("   - alice 有权限: docs:user, docs:read")
        print("   - bob 属于 engineering 组，有权限: docs:internal, docs:user, docs:read")
        print("   - charlie 无任何权限")

        print("\n4. 全文搜索:")
        results = doc_index.search("JWT 认证", user_id="alice")
        print(f"   alice 搜索 'JWT 认证': {len(results)} 个结果")
        for r in results:
            print(f"   - {r['title']} (score: {r['score']:.3f})")
            print(f"     {r['snippet']}")

        print("\n5. 按标签过滤:")
        results = doc_index.search("服务", tags=["backend"])
        print(f"   搜索 '服务' 且标签 backend: {len(results)} 个结果")
        for r in results:
            print(f"   - {r['title']} (tags: {r['tags']})")

        print("\n6. 权限过滤:")
        results_all = doc_index.search("架构", user_id=None)
        results_alice = doc_index.search("架构", user_id="alice")
        results_bob = doc_index.search("架构", user_id="bob")
        results_charlie = doc_index.search("架构", user_id="charlie")

        print(f"   搜索 '架构' (无权限过滤): {len(results_all)} 个结果")
        print(f"   alice (无internal权限): {len(results_alice)} 个结果")
        print(f"   bob (有internal权限): {len(results_bob)} 个结果")
        print(f"   charlie (无权限): {len(results_charlie)} 个结果")


if __name__ == "__main__":
    asyncio.run(main())
