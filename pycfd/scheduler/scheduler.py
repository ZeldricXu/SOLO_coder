import os
import sys
import time
import queue
import threading
import multiprocessing
from concurrent.futures import ThreadPoolExecutor, ProcessPoolExecutor, as_completed
from typing import Callable, Dict, Any, Optional, List, Tuple
from collections import defaultdict
from .task import Task, TaskResult, TaskStatus, TaskPriority
from .checkpoint import CheckpointManager
import json
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class TaskScheduler:
    def __init__(self, max_workers: int = None, use_processes: bool = False,
                 checkpoint_dir: str = None):
        self.max_workers = max_workers or multiprocessing.cpu_count()
        self.use_processes = use_processes
        self.task_queue: queue.PriorityQueue = queue.PriorityQueue()
        self.running_tasks: Dict[str, Task] = {}
        self.completed_tasks: Dict[str, TaskResult] = {}
        self.task_dependencies: Dict[str, List[str]] = {}
        self.dependents: Dict[str, List[str]] = defaultdict(list)
        self._lock = threading.RLock()
        self._stop_event = threading.Event()
        self.checkpoint_manager = CheckpointManager(checkpoint_dir) if checkpoint_dir else None
        self.callbacks = {
            'on_task_start': [],
            'on_task_complete': [],
            'on_task_fail': [],
            'on_schedule_complete': []
        }

    def add_task(self, task: Task) -> str:
        with self._lock:
            self.task_queue.put((-task.priority.value, task.task_id, task))
            self.task_dependencies[task.task_id] = task.dependencies
            for dep in task.dependencies:
                self.dependents[dep].append(task.task_id)
            logger.info(f"Added task: {task.name} ({task.task_id[:8]})")
            return task.task_id

    def add_tasks(self, tasks: List[Task]) -> List[str]:
        return [self.add_task(task) for task in tasks]

    def _get_ready_tasks(self) -> List[Task]:
        ready = []
        with self._lock:
            remaining = []
            while not self.task_queue.empty():
                try:
                    priority, tid, task = self.task_queue.get_nowait()
                    if self._task_ready(task):
                        ready.append(task)
                    else:
                        remaining.append((priority, tid, task))
                except queue.Empty:
                    break
            for item in remaining:
                self.task_queue.put(item)
        ready.sort(key=lambda t: -t.priority.value)
        return ready

    def _task_ready(self, task: Task) -> bool:
        if not task.dependencies:
            return True
        with self._lock:
            return all(dep in self.completed_tasks for dep in task.dependencies)

    def _execute_task(self, task: Task) -> TaskResult:
        task.status = TaskStatus.RUNNING
        for callback in self.callbacks['on_task_start']:
            try:
                callback(task)
            except Exception as e:
                logger.error(f"Callback error in on_task_start: {e}")
        logger.info(f"Starting task: {task.name}")
        result = task.execute()
        with self._lock:
            if result.is_success:
                self.completed_tasks[task.task_id] = result
                for callback in self.callbacks['on_task_complete']:
                    try:
                        callback(result)
                    except Exception as e:
                        logger.error(f"Callback error in on_task_complete: {e}")
                logger.info(f"Completed task: {task.name}")
            else:
                self.completed_tasks[task.task_id] = result
                for callback in self.callbacks['on_task_fail']:
                    try:
                        callback(result)
                    except Exception as e:
                        logger.error(f"Callback error in on_task_fail: {e}")
                logger.error(f"Failed task: {task.name}, error: {result.error}")
        return result

    def run(self, timeout: float = None) -> Dict[str, TaskResult]:
        start_time = time.time()
        executor_class = ProcessPoolExecutor if self.use_processes else ThreadPoolExecutor
        with executor_class(max_workers=self.max_workers) as executor:
            futures = {}
            while not self._stop_event.is_set():
                ready_tasks = self._get_ready_tasks()
                for task in ready_tasks:
                    if task.task_id not in self.running_tasks:
                        self.running_tasks[task.task_id] = task
                        future = executor.submit(self._execute_task, task)
                        futures[future] = task
                if futures:
                    done, _ = wait(futures.keys(), timeout=0.1)
                    for future in done:
                        task = futures.pop(future)
                        del self.running_tasks[task.task_id]
                        try:
                            future.result()
                        except Exception as e:
                            logger.error(f"Task execution error: {e}")
                if (self.task_queue.empty() and not self.running_tasks 
                    and not futures):
                    break
                if timeout is not None and time.time() - start_time > timeout:
                    logger.warning("Scheduler timed out")
                    break
        for callback in self.callbacks['on_schedule_complete']:
            try:
                callback(self.completed_tasks)
            except Exception as e:
                logger.error(f"Callback error in on_schedule_complete: {e}")
        return self.completed_tasks

    def run_sequential(self) -> Dict[str, TaskResult]:
        while True:
            ready_tasks = self._get_ready_tasks()
            if not ready_tasks:
                if self.task_queue.empty():
                    break
                time.sleep(0.1)
                continue
            task = ready_tasks[0]
            self._execute_task(task)
        return self.completed_tasks

    def on(self, event: str, callback: Callable):
        if event in self.callbacks:
            self.callbacks[event].append(callback)

    def stop(self):
        self._stop_event.set()

    def get_status(self) -> Dict[str, Any]:
        with self._lock:
            return {
                'pending': self.task_queue.qsize(),
                'running': len(self.running_tasks),
                'completed': len([t for t in self.completed_tasks.values() if t.is_success]),
                'failed': len([t for t in self.completed_tasks.values() if not t.is_success]),
                'total': self.task_queue.qsize() + len(self.running_tasks) + len(self.completed_tasks)
            }

    def wait_all(self, timeout: float = None) -> bool:
        start = time.time()
        while True:
            status = self.get_status()
            if status['pending'] == 0 and status['running'] == 0:
                return True
            if timeout is not None and time.time() - start > timeout:
                return False
            time.sleep(0.1)

class BatchProcessor:
    def __init__(self, base_case_function: Callable, max_workers: int = None,
                 checkpoint_dir: str = './checkpoints'):
        self.base_function = base_case_function
        self.scheduler = TaskScheduler(max_workers=max_workers, checkpoint_dir=checkpoint_dir)
        self.cases: List[Dict[str, Any]] = []
        self.results: List[Dict[str, Any]] = []

    def add_case(self, params: Dict[str, Any], name: str = None, 
                 priority: TaskPriority = TaskPriority.NORMAL) -> str:
        case_name = name or f"case_{len(self.cases)}"
        task = Task(
            func=self._run_case,
            args=(params,),
            name=case_name,
            priority=priority,
            kwargs={'case_name': case_name}
        )
        task_id = self.scheduler.add_task(task)
        self.cases.append({
            'task_id': task_id,
            'name': case_name,
            'params': params
        })
        return task_id

    def add_parameter_sweep(self, param_grid: Dict[str, List[Any]], 
                            base_params: Dict[str, Any] = None,
                            priority: TaskPriority = TaskPriority.NORMAL) -> List[str]:
        from itertools import product
        base_params = base_params or {}
        param_names = list(param_grid.keys())
        param_values = list(param_grid.values())
        task_ids = []
        for i, combo in enumerate(product(*param_values)):
            params = dict(zip(param_names, combo))
            params.update(base_params)
            tid = self.add_case(params, name=f"sweep_{i}", priority=priority)
            task_ids.append(tid)
        return task_ids

    def _run_case(self, params: Dict[str, Any], case_name: str = None) -> Any:
        logger.info(f"Running case: {case_name}")
        result = self.base_function(**params)
        return {
            'params': params,
            'result': result,
            'case_name': case_name
        }

    def run(self) -> List[Dict[str, Any]]:
        results_dict = self.scheduler.run()
        self.results = []
        for case in self.cases:
            task_result = results_dict.get(case['task_id'])
            if task_result and task_result.is_success:
                self.results.append(task_result.result)
            else:
                self.results.append({
                    'params': case['params'],
                    'result': None,
                    'error': task_result.error if task_result else 'Unknown',
                    'case_name': case['name']
                })
        return self.results

    def get_results(self) -> List[Dict[str, Any]]:
        return self.results

    def save_results(self, filename: str):
        with open(filename, 'w') as f:
            json.dump([{
                'params': r['params'],
                'result': r['result'],
                'case_name': r.get('case_name'),
                'error': r.get('error')
            } for r in self.results], f, indent=2, default=str)

    def load_results(self, filename: str):
        with open(filename, 'r') as f:
            self.results = json.load(f)
        return self.results

def wait(fs, timeout=None):
    done = set()
    not_done = set(fs)
    start = time.time()
    while not_done:
        for f in list(not_done):
            if f.done():
                done.add(f)
                not_done.remove(f)
        if timeout is not None:
            if time.time() - start > timeout:
                break
        time.sleep(0.01)
    return done, not_done

class ResourceManager:
    def __init__(self, num_threads: int = None, memory_limit_gb: float = None):
        self.num_threads = num_threads or multiprocessing.cpu_count()
        self.memory_limit_gb = memory_limit_gb
        self.current_threads = 0
        self.current_memory_gb = 0.0
        self._lock = threading.Lock()

    def acquire_resources(self, required_threads: int = 1, required_memory_gb: float = 0.0) -> bool:
        with self._lock:
            if (self.current_threads + required_threads <= self.num_threads and
                (self.memory_limit_gb is None or 
                 self.current_memory_gb + required_memory_gb <= self.memory_limit_gb)):
                self.current_threads += required_threads
                self.current_memory_gb += required_memory_gb
                return True
            return False

    def release_resources(self, released_threads: int = 1, released_memory_gb: float = 0.0):
        with self._lock:
            self.current_threads -= released_threads
            self.current_memory_gb -= released_memory_gb

    def get_available(self) -> Tuple[int, float]:
        with self._lock:
            available_memory = (self.memory_limit_gb - self.current_memory_gb 
                              if self.memory_limit_gb else float('inf'))
            return (self.num_threads - self.current_threads, available_memory)
