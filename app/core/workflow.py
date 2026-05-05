import os
import json
import uuid
import numpy as np
from datetime import datetime
from typing import Dict, Any, Optional, List, Iterator
from dataclasses import dataclass, asdict

from app.config import RESULTS_DATA_DIR


@dataclass
class ProcessResult:
    result_id: str
    signal_id: str
    filter_config: Optional[Dict[str, Any]]
    filtered_data: Optional[List[float]]
    spectrum: Optional[List[Dict[str, float]]]
    features: Optional[Dict[str, float]]
    processed_at: str

    def to_dict(self) -> Dict[str, Any]:
        return {
            "result_id": self.result_id,
            "signal_id": self.signal_id,
            "filter_config": self.filter_config,
            "spectrum": self.spectrum,
            "features": self.features,
            "processed_at": self.processed_at,
        }

    def save_to_file(self, data_points: Optional[np.ndarray] = None) -> None:
        result_dir = os.path.join(RESULTS_DATA_DIR, self.result_id)
        os.makedirs(result_dir, exist_ok=True)

        meta_file = os.path.join(result_dir, "meta.json")
        meta = self.to_dict()
        if "filtered_data" in meta:
            del meta["filtered_data"]

        with open(meta_file, "w") as f:
            json.dump(meta, f, indent=2)

        if data_points is not None:
            data_file = os.path.join(result_dir, "filtered_data.npy")
            np.save(data_file, data_points)

    @classmethod
    def load_from_file(cls, result_id: str, load_data: bool = False) -> Optional["ProcessResult"]:
        result_dir = os.path.join(RESULTS_DATA_DIR, result_id)
        meta_file = os.path.join(result_dir, "meta.json")
        data_file = os.path.join(result_dir, "filtered_data.npy")

        if not os.path.exists(meta_file):
            return None

        with open(meta_file, "r") as f:
            meta = json.load(f)

        filtered_data = None
        if load_data and os.path.exists(data_file):
            data_points = np.load(data_file)
            filtered_data = data_points.tolist()

        return cls(
            result_id=meta["result_id"],
            signal_id=meta["signal_id"],
            filter_config=meta.get("filter_config"),
            filtered_data=filtered_data,
            spectrum=meta.get("spectrum"),
            features=meta.get("features"),
            processed_at=meta["processed_at"],
        )


class WorkflowManager:
    def __init__(self):
        self._history: List[Dict[str, Any]] = []
        self._load_history()

    def _load_history(self) -> None:
        history_file = os.path.join(RESULTS_DATA_DIR, "history.json")
        if os.path.exists(history_file):
            with open(history_file, "r") as f:
                self._history = json.load(f)

    def _save_history(self) -> None:
        history_file = os.path.join(RESULTS_DATA_DIR, "history.json")
        with open(history_file, "w") as f:
            json.dump(self._history, f, indent=2)

    @staticmethod
    def generate_result_id() -> str:
        return f"result_{uuid.uuid4().hex[:8]}"

    def create_result(
        self,
        signal_id: str,
        filter_config: Optional[Dict[str, Any]] = None,
        filtered_data: Optional[np.ndarray] = None,
        spectrum_result: Optional[Dict[str, Any]] = None,
        features: Optional[Dict[str, float]] = None,
    ) -> ProcessResult:
        result_id = WorkflowManager.generate_result_id()

        spectrum = None
        if spectrum_result is not None:
            spectrum = []
            freqs = spectrum_result.get("frequencies", [])
            amps = spectrum_result.get("amplitudes", [])
            for freq, amp in zip(freqs, amps):
                spectrum.append({"freq": float(freq), "amplitude": float(amp)})

        result = ProcessResult(
            result_id=result_id,
            signal_id=signal_id,
            filter_config=filter_config,
            filtered_data=filtered_data.tolist() if filtered_data is not None else None,
            spectrum=spectrum,
            features=features,
            processed_at=datetime.now().isoformat(),
        )

        result.save_to_file(filtered_data)

        history_entry = {
            "result_id": result_id,
            "signal_id": signal_id,
            "filter_type": filter_config.get("filter_type") if filter_config else None,
            "filter_id": filter_config.get("filter_id") if filter_config else None,
            "processed_at": result.processed_at,
        }
        self._history.insert(0, history_entry)
        self._save_history()

        return result

    def get_result(self, result_id: str, load_data: bool = False) -> Optional[ProcessResult]:
        return ProcessResult.load_from_file(result_id, load_data)

    def list_results(
        self,
        signal_id: Optional[str] = None,
        limit: int = 50,
        offset: int = 0,
    ) -> List[Dict[str, Any]]:
        results = []
        for entry in self._history:
            if signal_id and entry.get("signal_id") != signal_id:
                continue
            results.append(entry)

        return results[offset:offset + limit]

    def get_full_history(self) -> List[Dict[str, Any]]:
        return list(self._history)

    def delete_result(self, result_id: str) -> bool:
        import shutil
        result_dir = os.path.join(RESULTS_DATA_DIR, result_id)

        if os.path.exists(result_dir):
            shutil.rmtree(result_dir)

        original_length = len(self._history)
        self._history = [h for h in self._history if h.get("result_id") != result_id]

        if len(self._history) != original_length:
            self._save_history()
            return True

        return False

    def search_results(
        self,
        signal_id: Optional[str] = None,
        filter_type: Optional[str] = None,
        start_time: Optional[str] = None,
        end_time: Optional[str] = None,
        limit: int = 50,
    ) -> List[Dict[str, Any]]:
        results = []
        for entry in self._history:
            if signal_id and entry.get("signal_id") != signal_id:
                continue
            if filter_type and entry.get("filter_type") != filter_type:
                continue

            processed_at = entry.get("processed_at", "")
            if start_time and processed_at < start_time:
                continue
            if end_time and processed_at > end_time:
                continue

            results.append(entry)
            if len(results) >= limit:
                break

        return results

    def get_statistics(self) -> Dict[str, Any]:
        total_results = len(self._history)
        filter_types: Dict[str, int] = {}

        for entry in self._history:
            ft = entry.get("filter_type")
            if ft:
                filter_types[ft] = filter_types.get(ft, 0) + 1

        unique_signals = len(set(h.get("signal_id") for h in self._history))

        return {
            "total_processed": total_results,
            "unique_signals": unique_signals,
            "filter_type_counts": filter_types,
            "last_processed": self._history[0].get("processed_at") if self._history else None,
        }


class ProcessPipeline:
    def __init__(self, workflow_manager: Optional[WorkflowManager] = None):
        self.workflow_manager = workflow_manager or WorkflowManager()

    def execute_filter(
        self,
        signal_data: np.ndarray,
        sample_rate: float,
        filter_config: Dict[str, Any],
    ) -> np.ndarray:
        from app.core.filtering import FilterProcessor, FilterConfig

        config = FilterConfig.from_dict(filter_config)
        filtered_data = FilterProcessor.filter(signal_data, config, sample_rate)

        return filtered_data

    def execute_spectrum(
        self,
        data: np.ndarray,
        sample_rate: float,
        include_phase: bool = False,
    ) -> Dict[str, Any]:
        from app.core.spectrum import SpectrumAnalyzer

        result = SpectrumAnalyzer.compute_fft(
            data, sample_rate, include_phase=include_phase
        )
        return result.to_dict()

    def execute_features(
        self,
        data: np.ndarray,
        sample_rate: Optional[float] = None,
    ) -> Dict[str, float]:
        from app.core.features import FeatureExtractor

        features = FeatureExtractor.extract_all_features(data, sample_rate)
        return features.to_dict()

    def run_full_pipeline(
        self,
        signal_id: str,
        original_data: np.ndarray,
        sample_rate: float,
        filter_config: Dict[str, Any],
        include_phase: bool = False,
    ) -> ProcessResult:
        if len(original_data) == 0:
            raise ValueError("Signal data is empty")

        filtered_data = self.execute_filter(original_data, sample_rate, filter_config)

        spectrum = self.execute_spectrum(filtered_data, sample_rate, include_phase)

        features = self.execute_features(filtered_data, sample_rate)

        result = self.workflow_manager.create_result(
            signal_id=signal_id,
            filter_config=filter_config,
            filtered_data=filtered_data,
            spectrum_result=spectrum,
            features=features,
        )

        return result

    def run_analysis_only(
        self,
        signal_id: str,
        data: np.ndarray,
        sample_rate: float,
        include_phase: bool = False,
    ) -> ProcessResult:
        if len(data) == 0:
            raise ValueError("Signal data is empty")

        spectrum = self.execute_spectrum(data, sample_rate, include_phase)
        features = self.execute_features(data, sample_rate)

        result = self.workflow_manager.create_result(
            signal_id=signal_id,
            filter_config=None,
            filtered_data=data,
            spectrum_result=spectrum,
            features=features,
        )

        return result
