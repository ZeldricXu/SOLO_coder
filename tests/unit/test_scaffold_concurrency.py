from __future__ import annotations

import concurrent.futures
import copy
import os
import pathlib
import threading
import time
from pathlib import Path
from typing import Dict, List
from unittest.mock import MagicMock, mock_open, patch

import pytest

from tests.builders import ScaffoldBuilder


class TestScaffoldConcurrencyIsolation:
    @pytest.fixture
    def mock_file_system(self):
        with patch("builtins.open", mock_open()) as mock_file:
            with patch("os.makedirs") as mock_mkdirs:
                with patch("pathlib.Path.mkdir") as mock_path_mkdir:
                    yield {
                        "open": mock_file,
                        "makedirs": mock_mkdirs,
                        "path_mkdir": mock_path_mkdir,
                    }

    @pytest.fixture
    def temp_output_dir(self, tmp_path):
        output_dir = tmp_path / "scaffold_output"
        output_dir.mkdir()
        return str(output_dir)

    def test_concurrent_generation_isolated_output_dirs(self):
        concurrent_count = 5
        requests = ScaffoldBuilder.create_concurrent_requests(concurrent_count)

        output_dirs = set()
        for req in requests:
            output_dirs.add(req["output_dir"])

        assert len(output_dirs) == concurrent_count, "Each request should have unique output directory"
        assert len({r["params"]["module_name"] for r in requests}) == concurrent_count
        assert len({r["params"]["service_name"] for r in requests}) == concurrent_count

    def test_concurrent_template_rendering_no_interference(self):
        template_def = ScaffoldBuilder().build_template_definition()

        def render_template(content: str, params: Dict) -> str:
            for key, value in params.items():
                content = content.replace("{{." + key + "}}", str(value))
            return content

        results = {}
        errors = []

        def worker(worker_id: int):
            try:
                builder = ScaffoldBuilder()
                builder._service_name = f"WorkerApp{worker_id}"
                builder._module_name = f"github.com/worker/{worker_id}"
                params = builder.build_params()

                rendered_files = []
                for file_def in template_def["files"]:
                    rendered = render_template(file_def["content"], params)
                    rendered_files.append({"path": file_def["path"], "content": rendered})

                results[worker_id] = {
                    "params": params,
                    "files": rendered_files,
                }
            except Exception as e:
                errors.append({"worker_id": worker_id, "error": str(e)})

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0, f"Concurrent rendering failed: {errors}"
        assert len(results) == 10

        for worker_id, result in results.items():
            service_name = f"WorkerApp{worker_id}"
            module_name = f"github.com/worker/{worker_id}"

            for file_data in result["files"]:
                if file_data["path"] == "go.mod":
                    assert module_name in file_data["content"]
                elif file_data["path"] == "main.go":
                    assert service_name in file_data["content"]
                elif file_data["path"] == "README.md":
                    assert service_name in file_data["content"]

    def test_thread_safe_builder_usage(self):
        def build_requests(worker_id: int, count: int) -> List[Dict]:
            return [
                ScaffoldBuilder()
                .with_service_name(f"ThreadSafe-{worker_id}-{i}")
                .with_module_name(f"github.com/ts/{worker_id}/{i}")
                .build_request()
                for i in range(count)
            ]

        all_results = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(build_requests, i, 20) for i in range(5)]
            for future in concurrent.futures.as_completed(futures):
                all_results.extend(future.result())

        assert len(all_results) == 100

        service_names = [r["params"]["service_name"] for r in all_results]
        assert len(service_names) == len(set(service_names)), "Duplicate service names detected"

        module_names = [r["params"]["module_name"] for r in all_results]
        assert len(module_names) == len(set(module_names)), "Duplicate module names detected"

    def test_output_dir_isolation_when_shared(self, mock_file_system):
        shared_dir = "/tmp/shared_scaffold_test"
        requests = ScaffoldBuilder.create_with_conflicting_output_dir()

        written_files = []
        file_write_lock = threading.Lock()

        def simulate_generation(request: Dict):
            output_dir = request["output_dir"]
            params = request["params"]

            time.sleep(0.01)

            for file_def in [
                {"path": "go.mod", "content": f"module {params['module_name']}"},
                {"path": "main.go", "content": f"package main\n// {params['service_name']}"},
                {"path": "README.md", "content": f"# {params['service_name']}"},
            ]:
                file_path = os.path.join(output_dir, file_def["path"])
                with file_write_lock:
                    written_files.append({
                        "path": file_path,
                        "content": file_def["content"],
                        "module": params["module_name"],
                        "service": params["service_name"],
                    })

        threads = [threading.Thread(target=simulate_generation, args=(req,)) for req in requests]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(written_files) == 9

        for file_entry in written_files:
            assert file_entry["module"] in file_entry["content"] or file_entry["service"] in file_entry["content"]

    def test_concurrent_generation_with_same_template(self):
        template_name = "go-service"
        generation_count = 20

        results = []
        errors = []
        lock = threading.Lock()

        def generate(index: int):
            try:
                builder = ScaffoldBuilder().with_template(template_name)
                builder._service_name = f"SameTemplateApp{index}"
                builder._module_name = f"github.com/same/{index}"
                request = builder.build_request()

                time.sleep(0.005)

                with lock:
                    results.append({
                        "index": index,
                        "request": request,
                    })
            except Exception as e:
                with lock:
                    errors.append({"index": index, "error": str(e)})

        threads = [threading.Thread(target=generate, args=(i,)) for i in range(generation_count)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0
        assert len(results) == generation_count
        assert all(r["request"]["template_name"] == template_name for r in results)

    def test_builder_immutability_across_threads(self):
        base_builder = ScaffoldBuilder()
        base_builder._service_name = "BaseApp"
        base_builder._module_name = "github.com/base/app"

        modifications = []
        lock = threading.Lock()

        def modify_builder(worker_id: int):
            builder_copy = copy.deepcopy(base_builder)
            builder_copy._service_name = f"Modified{worker_id}"
            builder_copy._module_name = f"github.com/modified/{worker_id}"

            time.sleep(0.01)

            result = builder_copy.build_request()
            with lock:
                modifications.append(result)

        threads = [threading.Thread(target=modify_builder, args=(i,)) for i in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert base_builder._service_name == "BaseApp"
        assert base_builder._module_name == "github.com/base/app"
        assert len(modifications) == 10

        for mod in modifications:
            assert mod["params"]["service_name"].startswith("Modified")
            assert mod["params"]["module_name"].startswith("github.com/modified/")

    def test_concurrent_file_generation_no_corruption(self, temp_output_dir):
        num_workers = 5
        files_per_worker = 10

        written_contents = {}
        lock = threading.Lock()

        def worker(worker_id: int):
            worker_dir = os.path.join(temp_output_dir, f"worker_{worker_id}")
            os.makedirs(worker_dir, exist_ok=True)

            for i in range(files_per_worker):
                file_path = os.path.join(worker_dir, f"file_{i}.txt")
                content = f"Worker {worker_id} - File {i} - {time.time()}"

                with open(file_path, "w") as f:
                    f.write(content)

                with lock:
                    written_contents[file_path] = content

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(num_workers)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(written_contents) == num_workers * files_per_worker

        for file_path, expected_content in written_contents.items():
            assert os.path.exists(file_path)
            with open(file_path, "r") as f:
                actual_content = f.read()
            assert actual_content == expected_content, f"File corruption detected: {file_path}"

    def test_concurrent_request_building_with_custom_params(self):
        def build_custom_request(worker_id: int):
            builder = ScaffoldBuilder()
            builder.with_custom_param("worker_id", worker_id)
            builder.with_custom_param("timestamp", time.time())
            builder.with_custom_param("features", ["feat1", "feat2"])
            return builder.build_request()

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            futures = [executor.submit(build_custom_request, i) for i in range(50)]
            results = [f.result() for f in concurrent.futures.as_completed(futures)]

        assert len(results) == 50

        worker_ids = [r["params"]["worker_id"] for r in results]
        assert len(worker_ids) == len(set(worker_ids))

        for r in results:
            assert "features" in r["params"]
            assert r["params"]["features"] == ["feat1", "feat2"]

    def test_template_definition_access_thread_safe(self):
        access_count = 100
        results = []
        errors = []

        def access_template(index: int):
            try:
                builder = ScaffoldBuilder()
                builder._template_name = "go-service"
                template_def = builder.build_template_definition()

                time.sleep(0.001)

                results.append({
                    "index": index,
                    "template_name": template_def["name"],
                    "param_count": len(template_def["params"]),
                })
            except Exception as e:
                errors.append({"index": index, "error": str(e)})

        threads = [threading.Thread(target=access_template, args=(i,)) for i in range(access_count)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0
        assert len(results) == access_count
        assert all(r["template_name"] == "go-service" for r in results)
        assert all(r["param_count"] == 5 for r in results)

    def test_cleanup_concurrent_temp_dirs(self):
        created_dirs = []
        lock = threading.Lock()

        def create_and_cleanup(worker_id: int):
            builder = ScaffoldBuilder()
            builder.with_temp_output_dir()
            output_dir = builder._output_dir

            with lock:
                created_dirs.append(output_dir)

            pathlib.Path(output_dir).mkdir(parents=True, exist_ok=True)
            test_file = os.path.join(output_dir, "test.txt")
            with open(test_file, "w") as f:
                f.write("test")

            ScaffoldBuilder.cleanup_temp_dir(output_dir)

        threads = [threading.Thread(target=create_and_cleanup, args=(i,)) for i in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        for d in created_dirs:
            assert not os.path.exists(d), f"Directory {d} was not cleaned up"

    def test_concurrent_different_template_types(self):
        template_types = ["go-service", "python-api", "react-app"]
        results = []
        errors = []

        def generate_for_template(template_type: str, index: int):
            try:
                if template_type == "go-service":
                    request = ScaffoldBuilder.create_go_service_request()
                elif template_type == "python-api":
                    request = ScaffoldBuilder.create_python_api_request()
                elif template_type == "react-app":
                    request = ScaffoldBuilder.create_react_app_request()

                request["params"]["index"] = index
                results.append({"type": template_type, "request": request})
            except Exception as e:
                errors.append({"type": template_type, "index": index, "error": str(e)})

        threads = []
        for i in range(5):
            for t_type in template_types:
                threads.append(threading.Thread(target=generate_for_template, args=(t_type, i)))

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0
        assert len(results) == 15

        type_counts = {}
        for r in results:
            type_counts[r["type"]] = type_counts.get(r["type"], 0) + 1

        assert type_counts == {"go-service": 5, "python-api": 5, "react-app": 5}

    def test_mutex_protected_shared_resource(self):
        shared_counter = 0
        lock = threading.Lock()

        def generate_with_counter(worker_id: int):
            nonlocal shared_counter

            builder = ScaffoldBuilder()
            builder._service_name = f"CounterApp{worker_id}"

            with lock:
                current = shared_counter
                time.sleep(0.001)
                shared_counter = current + 1

            builder.with_custom_param("counter_value", shared_counter)
            return builder.build_request()

        threads = [threading.Thread(target=generate_with_counter, args=(i,)) for i in range(20)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert shared_counter == 20

    @pytest.mark.parametrize("concurrent_level", [2, 5, 10, 20])
    def test_scalability_concurrent_levels(self, concurrent_level):
        results = []
        errors = []
        lock = threading.Lock()

        def worker(index: int):
            try:
                request = ScaffoldBuilder.create_default_request()
                time.sleep(0.01)
                with lock:
                    results.append(index)
            except Exception as e:
                with lock:
                    errors.append(str(e))

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(concurrent_level)]
        start_time = time.time()

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        duration = time.time() - start_time

        assert len(errors) == 0
        assert len(results) == concurrent_level
        assert duration < 5.0, f"Performance degradation: {duration}s for {concurrent_level} requests"

    def test_concurrent_generation_file_permissions(self, temp_output_dir):
        def generate_files(worker_id: int):
            worker_dir = os.path.join(temp_output_dir, f"perm_test_{worker_id}")
            os.makedirs(worker_dir, exist_ok=True)

            for i in range(5):
                file_path = os.path.join(worker_dir, f"generated_{i}.txt")
                with open(file_path, "w") as f:
                    f.write(f"Generated by worker {worker_id}")

                os.chmod(file_path, 0o644)

        threads = [threading.Thread(target=generate_files, args=(i,)) for i in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        for root, dirs, files in os.walk(temp_output_dir):
            for file in files:
                file_path = os.path.join(root, file)
                assert os.access(file_path, os.R_OK), f"File {file_path} is not readable"
                assert os.access(file_path, os.W_OK), f"File {file_path} is not writable"
