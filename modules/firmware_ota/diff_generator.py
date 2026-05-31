import hashlib
import os
import subprocess
import tempfile
from typing import Dict, Optional, Tuple


class DeltaGenerator:
    def __init__(self, storage_path: str):
        self.storage_path = storage_path
        os.makedirs(storage_path, exist_ok=True)

    @staticmethod
    def _calculate_checksum(file_path: str) -> str:
        sha256_hash = hashlib.sha256()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(4096), b""):
                sha256_hash.update(chunk)
        return sha256_hash.hexdigest()

    @staticmethod
    def _get_file_size(file_path: str) -> int:
        return os.path.getsize(file_path) if os.path.exists(file_path) else 0

    def generate_delta_bsdiff(
        self,
        old_file_path: str,
        new_file_path: str,
        output_file_name: Optional[str] = None,
    ) -> Dict[str, any]:
        if not os.path.exists(old_file_path):
            raise FileNotFoundError(f"Old file not found: {old_file_path}")
        if not os.path.exists(new_file_path):
            raise FileNotFoundError(f"New file not found: {new_file_path}")

        if output_file_name is None:
            old_hash = self._calculate_checksum(old_file_path)[:8]
            new_hash = self._calculate_checksum(new_file_path)[:8]
            output_file_name = f"delta_{old_hash}_{new_hash}.bin"

        output_path = os.path.join(self.storage_path, output_file_name)

        try:
            subprocess.run(
                ["bsdiff", old_file_path, new_file_path, output_path],
                check=True,
                capture_output=True,
            )
        except (subprocess.CalledProcessError, FileNotFoundError):
            self._generate_simple_delta(old_file_path, new_file_path, output_path)

        checksum = self._calculate_checksum(output_path)
        file_size = self._get_file_size(output_path)

        return {
            "file_path": output_path,
            "file_size": file_size,
            "checksum": checksum,
            "compression_method": "bsdiff",
        }

    def _generate_simple_delta(
        self,
        old_file_path: str,
        new_file_path: str,
        output_path: str,
    ) -> None:
        with open(old_file_path, "rb") as f_old, open(new_file_path, "rb") as f_new:
            old_data = f_old.read()
            new_data = f_new.read()

        if old_data == new_data:
            with open(output_path, "wb") as f:
                f.write(b"")
            return

        with open(output_path, "wb") as f:
            f.write(new_data)

    def apply_delta_bsdiff(
        self,
        old_file_path: str,
        delta_file_path: str,
        output_path: str,
    ) -> bool:
        try:
            subprocess.run(
                ["bspatch", old_file_path, output_path, delta_file_path],
                check=True,
                capture_output=True,
            )
            return True
        except (subprocess.CalledProcessError, FileNotFoundError):
            with open(delta_file_path, "rb") as f_delta:
                delta_data = f_delta.read()
            if delta_data:
                with open(output_path, "wb") as f:
                    f.write(delta_data)
                return True
            return False

    def verify_integrity(
        self,
        file_path: str,
        expected_checksum: str,
    ) -> bool:
        if not os.path.exists(file_path):
            return False
        actual_checksum = self._calculate_checksum(file_path)
        return actual_checksum == expected_checksum

    def compare_versions(
        self,
        version1: str,
        version2: str,
    ) -> int:
        v1_parts = [int(x) for x in version1.split(".")]
        v2_parts = [int(x) for x in version2.split(".")]

        max_len = max(len(v1_parts), len(v2_parts))
        v1_parts.extend([0] * (max_len - len(v1_parts)))
        v2_parts.extend([0] * (max_len - len(v2_parts)))

        for v1, v2 in zip(v1_parts, v2_parts):
            if v1 < v2:
                return -1
            elif v1 > v2:
                return 1
        return 0

    def can_upgrade_directly(
        self,
        current_version: str,
        target_version: str,
        min_version: Optional[str] = None,
    ) -> bool:
        if self.compare_versions(current_version, target_version) >= 0:
            return False

        if min_version and self.compare_versions(current_version, min_version) < 0:
            return False

        return True
