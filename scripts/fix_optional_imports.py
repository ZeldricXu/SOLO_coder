#!/usr/bin/env python3
"""
批量添加缺失的 Optional 导入
"""
import re
from pathlib import Path

PROJECT_DIR = Path(__file__).parent.parent

pattern_optional_used = re.compile(r'Optional\[')

def fix_imports(file_path: Path) -> bool:
    try:
        content = file_path.read_text(encoding='utf-8')
        
        # 检查是否使用了 Optional
        if not pattern_optional_used.search(content):
            return False
        
        # 检查是否已经导入了 Optional
        if re.search(r'from typing import.*Optional', content):
            return False
        
        original = content
        lines = content.split('\n')
        
        # 查找 typing 导入行
        typing_import_idx = -1
        import_lines = []
        
        for i, line in enumerate(lines):
            if line.startswith('from typing import'):
                typing_import_idx = i
                break
            elif line.startswith('import ') or line.startswith('from '):
                import_lines.append(i)
        
        if typing_import_idx >= 0:
            # 追加 Optional 到现有导入
            lines[typing_import_idx] = lines[typing_import_idx].rstrip() + ', Optional'
        else:
            # 在第一个导入行前添加新的导入
            insert_pos = import_lines[0] if import_lines else 0
            lines.insert(insert_pos, 'from typing import Optional')
        
        content = '\n'.join(lines)
        
        if content != original:
            file_path.write_text(content, encoding='utf-8')
            print(f"✓ Added Optional import: {file_path}")
            return True
        return False
    except Exception as e:
        print(f"✗ Error fixing {file_path}: {e}")
        return False

def main():
    print("Scanning Python files for missing Optional imports...\n")
    
    py_files = list(PROJECT_DIR.rglob('*.py'))
    exclude_dirs = {'__pycache__', '.pytest_cache', '.mypy_cache', '.ruff_cache', 'venv', '.venv'}
    
    fixed_count = 0
    for file_path in py_files:
        if any(exclude in str(file_path) for exclude in exclude_dirs):
            continue
        if fix_imports(file_path):
            fixed_count += 1
    
    print(f"\nFixed {fixed_count} files")

if __name__ == '__main__':
    main()
