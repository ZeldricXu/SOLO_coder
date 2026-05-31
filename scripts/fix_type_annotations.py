#!/usr/bin/env python3
"""
批量修复 Ruff 自动修复导致的 Python 3.9 不兼容类型注解问题
将 `X | None` 改回 `Optional[X]`
"""
import re
import os
from pathlib import Path

PROJECT_DIR = Path(__file__).parent.parent

# 匹配 `X | None` 模式，但要排除已经在 Optional 中的情况
pattern_optional = re.compile(r'(\w+(?:\[[^\]]+\])?)\s*\|\s*None')

# 匹配 `Mapped[X | None]` 模式
pattern_mapped = re.compile(r'Mapped\[([^\]]+?)\s*\|\s*None\]')

def fix_file(file_path: Path) -> bool:
    try:
        content = file_path.read_text(encoding='utf-8')
        original = content
        
        # 先修复 Mapped[X | None] -> Mapped[Optional[X]]
        def replace_mapped(match):
            inner = match.group(1).strip()
            return f'Mapped[Optional[{inner}]]'
        
        content = pattern_mapped.sub(replace_mapped, content)
        
        # 再修复 X | None -> Optional[X]
        def replace_optional(match):
            type_str = match.group(1).strip()
            return f'Optional[{type_str}]'
        
        content = pattern_optional.sub(replace_optional, content)
        
        # 检查是否需要添加 Optional 导入
        if content != original and 'from typing import' in content:
            if 'Optional' not in content:
                # 添加 Optional 到导入中
                content = re.sub(
                    r'from typing import (\w+(?:, \w+)*)',
                    lambda m: f'from typing import {m.group(1)}, Optional',
                    content,
                    count=1
                )
        elif content != original and 'from typing import' not in content:
            # 检查是否有其他 typing 导入
            if 'typing' not in content or 'from typing' not in content:
                # 在合适的位置添加导入
                lines = content.split('\n')
                insert_pos = 0
                for i, line in enumerate(lines):
                    if line.startswith('from ') or line.startswith('import '):
                        insert_pos = i
                        break
                lines.insert(insert_pos, 'from typing import Optional')
                content = '\n'.join(lines)
        
        if content != original:
            file_path.write_text(content, encoding='utf-8')
            print(f"✓ Fixed: {file_path}")
            return True
        return False
    except Exception as e:
        print(f"✗ Error fixing {file_path}: {e}")
        return False

def main():
    print("Scanning Python files for type annotation issues...\n")
    
    py_files = list(PROJECT_DIR.rglob('*.py'))
    # 排除一些目录
    exclude_dirs = {'__pycache__', '.pytest_cache', '.mypy_cache', '.ruff_cache', 'venv', '.venv'}
    
    fixed_count = 0
    for file_path in py_files:
        if any(exclude in str(file_path) for exclude in exclude_dirs):
            continue
        if fix_file(file_path):
            fixed_count += 1
    
    print(f"\nFixed {fixed_count} files")

if __name__ == '__main__':
    main()
