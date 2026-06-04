#!/usr/bin/env python3
import subprocess
import sys
import platform
from pathlib import Path


def build_client():
    project_root = Path(__file__).parent
    spec_file = project_root / "client.spec"

    if not spec_file.exists():
        print("Error: client.spec not found")
        sys.exit(1)

    system = platform.system().lower()
    print(f"Building for {system}...")

    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--clean",
        "--noconfirm",
        str(spec_file),
    ]

    if system == "darwin":
        cmd.extend(["--osx-bundle-identifier", "com.dungeon.client"])
    elif system == "windows":
        cmd.extend(["--windowed"])

    subprocess.run(cmd, cwd=str(project_root), check=True)

    dist_dir = project_root / "dist"
    if dist_dir.exists():
        print(f"\nBuild complete! Output in: {dist_dir}")
        for item in dist_dir.iterdir():
            print(f"  {item.name}")
    else:
        print("Build failed - no dist directory found")
        sys.exit(1)


if __name__ == "__main__":
    build_client()
