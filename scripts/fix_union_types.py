#!/usr/bin/env python3
"""
批量修复所有 `X | Y` 联合类型为 `Union[X, Y]
"""
import re
from pathlib import Path

PROJECT_DIR = Path(__file__).parent.parent

# 匹配 `X | Y` 模式，但排除在字符串内部的情况
pattern_union = re.compile(r'(\w+(?:\[[^\]]+\]?)\s*\|\s*(\w+(?:\[[^\]]+\]?)')

def fix_file(file_path: Path) -> bool:
    try:
        content = file_path.read_text(encoding='utf-8')
        original = content
        
        # 修复联合类型
        def replace_union(match):
            type1 = match.group(1).strip()
            type2 = match.group(2).strip()
            return f'Union[{type1}, {type2}]'
        
        content = pattern_union.sub(replace_union, content)
        
        # 检查是否需要添加 Union 导入
        if content != original and 'from typing import' in content:
            if 'Union' not in content:
                content = re.sub(
                    r'from typing import (\w+(?:, \w+)*)',
                    lambda m: f'from typing import {m.group(1)}, Union',
                    content,
                    count=1
                )
        
        if content != original:
            file_path.write_text(content, encoding='utf-8')
            print(f"✓ Fixed union types: {file_path}")
            return True
        return False
    except Exception as e:
        print(f"✗ Error fixing {file_path}: {e}")
        return False

def main():
    print("Scanning Python files for union type issues...\n")
    
    py_files = list(PROJECT_DIR.rglob('*.py'))
    exclude_dirs = {'__pycache__', '.pytest_cache', '.mypy_cache', '.ruff_cache', 'venv', '.venv'}
    
    fixed_count = 0
    for file_path in py_files:
        if any(exclude in str(file_path) for exclude in exclude_dirs:
            continue
        if fix_file(file_path):
            fixed_count += 1
    
    print(f"\nFixed {fixed_count} files")

if __name__ == '__main__':
    main()
