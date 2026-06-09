import os
import h5py
import numpy as np
import time
from dataclasses import dataclass
from typing import Any, Dict, Optional, Callable, List
from datetime import datetime
import json
import pickle

class CheckpointManager:
    def __init__(self, checkpoint_dir: str = './checkpoints', max_checkpoints: int = 10,
                 interval: int = 100):
        self.checkpoint_dir = checkpoint_dir
        self.max_checkpoints = max_checkpoints
        self.interval = interval
        self.last_saved_step = -1
        os.makedirs(checkpoint_dir, exist_ok=True)
        self.checkpoint_files: List[str] = []
        self._load_existing_checkpoints()

    def _load_existing_checkpoints(self):
        if os.path.exists(self.checkpoint_dir):
            files = sorted(
                [os.path.join(self.checkpoint_dir, f) 
                 for f in os.listdir(self.checkpoint_dir) 
                 if f.endswith('.h5') or f.endswith('.ckpt')],
                key=os.path.getmtime
            )
            self.checkpoint_files = files

    def _get_checkpoint_filename(self, step: int, time: float = None) -> str:
        timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
        if time is not None:
            return os.path.join(
                self.checkpoint_dir, 
                f'checkpoint_step{step:06d}_t{time:.4f}_{timestamp}.h5'
            )
        return os.path.join(
            self.checkpoint_dir, 
            f'checkpoint_step{step:06d}_{timestamp}.h5'
        )

    def save(self, data: Dict[str, Any], step: int, time: float = None,
             metadata: Dict[str, Any] = None) -> str:
        filename = self._get_checkpoint_filename(step, time)
        with h5py.File(filename, 'w') as f:
            f.attrs['step'] = step
            if time is not None:
                f.attrs['time'] = time
            f.attrs['timestamp'] = datetime.now().isoformat()
            if metadata:
                meta_grp = f.create_group('metadata')
                for key, value in metadata.items():
                    if isinstance(value, (int, float, str, bool)):
                        meta_grp.attrs[key] = value
                    else:
                        meta_grp.attrs[key] = json.dumps(value)
            data_grp = f.create_group('data')
            self._save_dict(data_grp, data)
        self.checkpoint_files.append(filename)
        self._prune_old_checkpoints()
        return filename

    def _save_dict(self, group: h5py.Group, data: Dict[str, Any]):
        for key, value in data.items():
            if isinstance(value, np.ndarray):
                group.create_dataset(key, data=value, compression='gzip')
            elif isinstance(value, dict):
                sub_group = group.create_group(key)
                self._save_dict(sub_group, value)
            elif isinstance(value, list):
                if all(isinstance(v, np.ndarray) for v in value):
                    group.create_dataset(key, data=np.array(value), compression='gzip')
                else:
                    group.attrs[key] = json.dumps(value)
            elif isinstance(value, (int, float, str, bool)):
                group.attrs[key] = value
            else:
                group.attrs[key] = pickle.dumps(value)

    def load(self, filename: str) -> Dict[str, Any]:
        if not os.path.exists(filename):
            raise FileNotFoundError(f"Checkpoint file not found: {filename}")
        with h5py.File(filename, 'r') as f:
            data = self._load_dict(f['data'])
            metadata = {}
            if 'metadata' in f:
                for key in f['metadata'].attrs:
                    value = f['metadata'].attrs[key]
                    try:
                        metadata[key] = json.loads(value)
                    except (json.JSONDecodeError, TypeError):
                        metadata[key] = value
            result = {
                'data': data,
                'step': f.attrs.get('step'),
                'time': f.attrs.get('time'),
                'timestamp': f.attrs.get('timestamp'),
                'metadata': metadata
            }
        return result

    def _load_dict(self, group: h5py.Group) -> Dict[str, Any]:
        data = {}
        for key in group.keys():
            if isinstance(group[key], h5py.Dataset):
                data[key] = group[key][()]
            elif isinstance(group[key], h5py.Group):
                data[key] = self._load_dict(group[key])
        for key in group.attrs:
            value = group.attrs[key]
            try:
                if isinstance(value, bytes) and value.startswith(b'\x80'):
                    data[key] = pickle.loads(value)
                else:
                    data[key] = json.loads(value)
            except (json.JSONDecodeError, TypeError, pickle.UnpicklingError):
                data[key] = value
        return data

    def load_latest(self) -> Optional[Dict[str, Any]]:
        if not self.checkpoint_files:
            return None
        latest = self.checkpoint_files[-1]
        return self.load(latest)

    def list_checkpoints(self) -> List[Dict[str, Any]]:
        checkpoints = []
        for fname in self.checkpoint_files:
            try:
                with h5py.File(fname, 'r') as f:
                    checkpoints.append({
                        'filename': fname,
                        'step': f.attrs.get('step'),
                        'time': f.attrs.get('time'),
                        'timestamp': f.attrs.get('timestamp'),
                        'size': os.path.getsize(fname)
                    })
            except:
                continue
        return checkpoints

    def _prune_old_checkpoints(self):
        while len(self.checkpoint_files) > self.max_checkpoints:
            oldest = self.checkpoint_files.pop(0)
            try:
                os.remove(oldest)
            except:
                pass

    def save_checkpoint(self, solver, step: int, time: float = None,
                        metadata: Dict[str, Any] = None) -> str:
        """Save solver state to a checkpoint file.
        
        Args:
            solver: The solver object to save
            step: Current simulation step
            time: Current simulation time
            metadata: Additional metadata to save
            
        Returns:
            Path to the saved checkpoint file
        """
        data = {
            'flow_u': solver.flow.u.copy(),
            'flow_v': solver.flow.v.copy(),
            'flow_p': solver.flow.p.copy(),
            'flow_u_prev': solver.flow.u_prev.copy(),
            'flow_p_prev': solver.flow.p_prev.copy(),
            'flow_ap': solver.flow.ap.copy(),
            'underrelaxation': solver.underrelaxation,
            'step': step,
            'time': time if time is not None else 0.0
        }
        if hasattr(solver, 'residual_history') and solver.residual_history:
            data['residual_history'] = np.array(solver.residual_history)
        self.last_saved_step = step
        return self.save(data, step, time, metadata)

    def load_checkpoint(self, solver, step: int = None, time: float = None) -> bool:
        """Load solver state from a checkpoint file.
        
        Args:
            solver: The solver object to restore
            step: Step number to load (or None for latest)
            time: Time to load (or None for latest)
            
        Returns:
            True if checkpoint was loaded successfully, False otherwise
        """
        if step is None and time is None:
            data = self.load_latest()
        else:
            filename = self.get_checkpoint_at(step=step, time=time)
            if filename is None:
                raise FileNotFoundError(f"No checkpoint found for step={step}, time={time}")
            data = self.load(filename)
        
        if data is None:
            return False
        
        solver.flow.u[:] = data['flow_u']
        solver.flow.v[:] = data['flow_v']
        solver.flow.p[:] = data['flow_p']
        solver.flow.u_prev[:] = data['flow_u_prev']
        solver.flow.p_prev[:] = data['flow_p_prev']
        solver.flow.ap[:] = data['flow_ap']
        if 'underrelaxation' in data:
            solver.underrelaxation = data['underrelaxation']
        if 'residual_history' in data and hasattr(solver, 'residual_history'):
            solver.residual_history = data['residual_history'].tolist()
        return True

    def save_if_needed(self, solver) -> Optional[str]:
        """Save checkpoint if the interval has elapsed.
        
        Args:
            solver: The solver object
            
        Returns:
            Path to saved checkpoint if saved, None otherwise
        """
        if not hasattr(solver, 'current_step'):
            return None
        step = solver.current_step
        if step - self.last_saved_step >= self.interval:
            return self.save_checkpoint(solver, step)
        return None

    def list_checkpoints(self) -> List[Dict[str, Any]]:
        """List all available checkpoints with metadata.
        
        Returns:
            List of dicts containing checkpoint info (filename, step, time, timestamp)
        """
        checkpoints = []
        for fname in self.checkpoint_files:
            try:
                with h5py.File(fname, 'r') as f:
                    info = {
                        'filename': fname,
                        'step': f.attrs.get('step'),
                        'time': f.attrs.get('time'),
                        'timestamp': f.attrs.get('timestamp')
                    }
                    checkpoints.append(info)
            except:
                continue
        return checkpoints

    def get_checkpoint_at(self, step: int = None, time: float = None) -> Optional[str]:
        if step is not None:
            for fname in reversed(self.checkpoint_files):
                try:
                    with h5py.File(fname, 'r') as f:
                        if f.attrs.get('step') == step:
                            return fname
                except:
                    continue
        if time is not None:
            best_match = None
            best_diff = float('inf')
            for fname in self.checkpoint_files:
                try:
                    with h5py.File(fname, 'r') as f:
                        ckpt_time = f.attrs.get('time')
                        if ckpt_time is not None:
                            diff = abs(ckpt_time - time)
                            if diff < best_diff:
                                best_diff = diff
                                best_match = fname
                except:
                    continue
            return best_match
        return None

class CheckpointSolver:
    def __init__(self, solver, checkpoint_manager: CheckpointManager, 
                 save_interval: int = 100):
        self.solver = solver
        self.checkpoint_manager = checkpoint_manager
        self.save_interval = save_interval
        self.step = 0

    def step_with_checkpoint(self, n_steps: int, callback: Callable = None):
        start_step = self.step
        for i in range(n_steps):
            self.step += 1
            result = self.solver.step()
            if self.step % self.save_interval == 0:
                self._save_current_state()
            if callback is not None:
                callback(self.step, result)
        return result

    def _save_current_state(self):
        state = {
            'step': self.step,
            'flow': {
                'u': self.solver.flow.u,
                'v': self.solver.flow.v if hasattr(self.solver.flow, 'v') else None,
                'w': self.solver.flow.w if hasattr(self.solver.flow, 'w') else None,
                'p': self.solver.flow.p,
                'k': self.solver.flow.k if hasattr(self.solver.flow, 'k') else None,
                'epsilon': self.solver.flow.epsilon if hasattr(self.solver.flow, 'epsilon') else None,
                'omega': self.solver.flow.omega if hasattr(self.solver.flow, 'omega') else None,
            },
            'time': getattr(self.solver, 'time', None),
            'residuals': {k: list(v) for k, v in self.solver.residuals.items() if hasattr(self.solver, 'residuals')}
        }
        self.checkpoint_manager.save(state, self.step, getattr(self.solver, 'time', None))

    def restore_from_checkpoint(self, checkpoint_file: str = None):
        if checkpoint_file is None:
            ckpt = self.checkpoint_manager.load_latest()
        else:
            ckpt = self.checkpoint_manager.load(checkpoint_file)
        if ckpt is None:
            raise ValueError("No checkpoint available")
        data = ckpt['data']
        self.step = data['step']
        flow = data['flow']
        if flow['u'] is not None:
            self.solver.flow.u = flow['u']
        if flow.get('v') is not None:
            self.solver.flow.v = flow['v']
        if flow.get('w') is not None:
            self.solver.flow.w = flow['w']
        if flow['p'] is not None:
            self.solver.flow.p = flow['p']
        if flow.get('k') is not None:
            self.solver.flow.k = flow['k']
        if flow.get('epsilon') is not None:
            self.solver.flow.epsilon = flow['epsilon']
        if flow.get('omega') is not None:
            self.solver.flow.omega = flow['omega']
        if hasattr(self.solver, 'time') and data.get('time') is not None:
            self.solver.time = data['time']
        return ckpt

def restart_from_checkpoint(solver_func: Callable, checkpoint_file: str, **kwargs):
    ckpt_mgr = CheckpointManager(os.path.dirname(checkpoint_file))
    ckpt = ckpt_mgr.load(checkpoint_file)
    solver = solver_func(**kwargs)
    if hasattr(solver, 'flow'):
        flow_data = ckpt['data']['flow']
        if flow_data['u'] is not None:
            solver.flow.u = flow_data['u']
        if flow_data.get('p') is not None:
            solver.flow.p = flow_data['p']
    return solver, ckpt
