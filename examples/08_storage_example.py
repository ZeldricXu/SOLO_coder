"""
示例8: 存储管理模块
"""

import asyncio
import sys
import os
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from src.core import LogLevel
from src.infrastructure.logging import StructuredLogger, ConsoleHandler, TextFormatter
from src.modules.storage_manager import StorageManager


async def main():
    print("=== 存储管理示例 ===\n")

    logger = StructuredLogger(
        service_name="storage-manager",
        handlers=[ConsoleHandler(level=LogLevel.INFO, formatter=TextFormatter())],
    )

    print("1. 内存存储 (用于测试):")
    mem_storage = StorageManager.create_memory_storage(logger=logger)

    data1 = b"Hello, World!"
    obj1 = await mem_storage.upload(
        "documents",
        "hello.txt",
        data1,
        metadata={"author": "alice", "tags": ["text", "demo"], "category": "examples"},
    )
    print(f"   上传: {obj1.bucket}/{obj1.key} (size={obj1.size}, etag={obj1.etag[:8]}...)")

    data2 = b'{"name": "test", "value": 42}'
    obj2 = await mem_storage.upload(
        "documents",
        "data.json",
        data2,
        metadata={"author": "bob", "tags": ["json", "data"], "category": "examples"},
    )
    print(f"   上传: {obj2.bucket}/{obj2.key}")

    print("\n2. 下载文件:")
    downloaded_data, meta = await mem_storage.download("documents", "hello.txt")
    print(f"   内容: {downloaded_data.decode()}")

    print("\n3. 列出文件:")
    files = await mem_storage.list("documents")
    for f in files:
        print(f"   - {f.key} ({f.size} bytes)")

    print("\n4. 元数据搜索:")
    results = mem_storage.search_by_tag("text")
    print(f"   标签 'text': {len(results)} 个结果")
    for r in results:
        print(f"   - {r['bucket']}/{r['key']} (author: {r.get('author')})")

    results = mem_storage.search_metadata(author="bob")
    print(f"\n   author='bob': {len(results)} 个结果")
    for r in results:
        print(f"   - {r['bucket']}/{r['key']}")

    print("\n5. 本地文件存储:")
    with tempfile.TemporaryDirectory() as tmpdir:
        local_storage = StorageManager.create_local_storage(tmpdir, logger=logger)

        data = b"Local file content"
        obj = await local_storage.upload(
            "uploads",
            "test.txt",
            data,
            metadata={"source": "example"},
        )
        print(f"   上传到: {tmpdir}/uploads/test.txt")

        exists = await local_storage.exists("uploads", "test.txt")
        print(f"   文件存在: {exists}")

        files = await local_storage.list("uploads")
        for f in files:
            print(f"   - {f.key}")

        print(f"\n6. 复制文件:")
        await local_storage.copy("uploads", "test.txt", "backups", "test-backup.txt")
        files = await local_storage.list("backups")
        print(f"   backups 目录文件: {[f.key for f in files]}")

        print(f"\n7. 删除文件:")
        await local_storage.delete("uploads", "test.txt")
        exists = await local_storage.exists("uploads", "test.txt")
        print(f"   删除后存在: {exists}")


if __name__ == "__main__":
    asyncio.run(main())
