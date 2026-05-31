#!/usr/bin/env python3
"""
完全修复所有 Python 3.9 不兼容的类型注解
1. `X | Y` -> `Union[X, Y]`
2. `X | None` -> `Optional[X]`
3. `Optional[Mapped[X]]` -> `Mapped[Optional[X]]`
"""
import re
from pathlib import Path

PROJECT_DIR = Path(__file__).parent.parent

# 匹配 `X | None` 或 `X | Y` 模式
pattern_pipe = re.compile(r'(\w+(?:\[[^\]]+\])?)\s*\|\s*(\w+(?:\[[^\]]+\])?)')
# 匹配 `Optional[Mapped[X]]` 模式
pattern_optional_mapped = re.compile(r'Optional\[Mapped\[([^\]]+)\]\]')

def fix_file(file_path: Path) -> bool:
    try:
        content = file_path.read_text(encoding='utf-8')
        original = content
        changed = False
        
        # 1. 先修复 `Optional[Mapped[X]]` -> `Mapped[Optional[X]]`
        def replace_optional_mapped(match):
            inner = match.group(1).strip()
            return f'Mapped[Optional[{inner}]]'
        
        new_content = pattern_optional_mapped.sub(replace_optional_mapped, content)
        if new_content != content:
            changed = True
            content = new_content
        
        # 2. 修复 `X | None` 和 `X | Y`
        def replace_pipe(match):
            type1 = match.group(1).strip()
            type2 = match.group(2).strip()
            
            # 跳过已经在 Union 或 Optional 中的情况
            if 'Union[' in type1 or 'Union[' in type2:
                return match.group(0)
            if 'Optional[' in type1 or 'Optional[' in type2:
                return match.group(0)
            
            # 如果右边是 None，使用 Optional
            if type2 == 'None':
                return f'Optional[{type1}]'
            if type1 == 'None':
                return f'Optional[{type2}]'
            
            # 否则使用 Union
            return f'Union[{type1}, {type2}]'
        
        new_content = pattern_pipe.sub(replace_pipe, content)
        if new_content != content:
            changed = True
            content = new_content
        
        # 检查是否需要添加导入
        if changed:
            has_optional = 'Optional[' in content
            has_union = 'Union[' in content
            
            if has_optional or has_union:
                # 检查是否有 typing 导入
                lines = content.split('\n')
                typing_import_idx = -1
                existing_imports = []
                
                for i, line in enumerate(lines):
                    if line.startswith('from typing import'):
                        typing_import_idx = i
                        # 提取现有导入
                        import_match = re.search(r'from typing import (.+)', line)
                        if import_match:
                            existing_imports = [
                                imp.strip() for imp in import_match.group(1).split(',')
                            ]
                        break
                
                needed_imports = []
                if has_optional and 'Optional' not in existing_imports:
                    needed_imports.append('Optional')
                if has_union and 'Union' not in existing_imports:
                    needed_imports.append('Union')
                
                if needed_imports:
                    if typing_import_idx >= 0:
                        # 追加到现有导入
                        new_imports = existing_imports + needed_imports
                        lines[typing_import_idx] = f"from typing import {', '.join(new_imports)}"
                    else:
                        # 在文件开头添加导入
                        insert_pos = 0
                        for i, line in enumerate(lines):
                            if line.startswith('import ') or line.startswith('from '):
                                insert_pos = i
                                break
                        lines.insert(insert_pos, f"from typing import {', '.join(needed_imports)}")
                    
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
