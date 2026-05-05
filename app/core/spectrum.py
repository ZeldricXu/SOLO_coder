import numpy as np
from typing import Dict, Any, List, Optional, Tuple
from dataclasses import dataclass
from enum import Enum


class NormalizationMode(Enum):
    NONE = "none"
    LENGTH = "length"
    ENERGY = "energy"
    PEAK = "peak"
    STANDARD = "standard"
    PSD = "psd"
    DB = "db"


class FFTAlgorithm(Enum):
    COOLEY_TUKEY = "cooley_tukey"
    BLUESTEIN = "bluestein"


@dataclass
class NormalizationCoefficients:
    amplitude_scale: float
    onesided_correction: bool
    nyquist_correction: bool
    window_correction: float
    n_fft: int
    n_samples: int
    is_onesided: bool

    def to_dict(self) -> Dict[str, Any]:
        return {
            "amplitude_scale": float(self.amplitude_scale),
            "onesided_correction": self.onesided_correction,
            "nyquist_correction": self.nyquist_correction,
            "window_correction": float(self.window_correction),
            "n_fft": self.n_fft,
            "n_samples": self.n_samples,
            "is_onesided": self.is_onesided,
        }


@dataclass
class SpectrumResult:
    frequencies: np.ndarray
    amplitudes: np.ndarray
    phases: Optional[np.ndarray]
    sample_rate: float
    n_samples: int
    n_fft: int
    normalization_mode: NormalizationMode
    is_onesided: bool
    normalization_coefficients: NormalizationCoefficients

    def to_dict(self) -> Dict[str, Any]:
        data = {
            "frequencies": self.frequencies.tolist(),
            "amplitudes": self.amplitudes.tolist(),
            "sample_rate": self.sample_rate,
            "n_samples": self.n_samples,
            "n_fft": self.n_fft,
            "normalization_mode": self.normalization_mode.value,
            "is_onesided": self.is_onesided,
            "normalization_coefficients": self.normalization_coefficients.to_dict(),
        }
        if self.phases is not None:
            data["phases"] = self.phases.tolist()
        return data

    def get_peak_frequency(self, min_freq: float = 0.0, max_freq: Optional[float] = None) -> Tuple[float, float]:
        mask = self.frequencies >= min_freq
        if max_freq is not None:
            mask = mask & (self.frequencies <= max_freq)
        
        filtered_amps = self.amplitudes[mask]
        filtered_freqs = self.frequencies[mask]
        
        if len(filtered_amps) == 0:
            return 0.0, 0.0
        
        peak_idx = np.argmax(filtered_amps)
        return float(filtered_freqs[peak_idx]), float(filtered_amps[peak_idx])

    def get_power_spectral_density(self) -> np.ndarray:
        if self.normalization_mode == NormalizationMode.PSD:
            return self.amplitudes ** 2
        else:
            original_amps = self._get_raw_amplitudes()
            freq_resolution = self.sample_rate / self.n_fft
            return (original_amps ** 2) / freq_resolution

    def get_total_power(self) -> float:
        if self.is_onesided:
            original_amps = self._get_raw_amplitudes()
            if len(original_amps) > 1:
                if self.frequencies[-1] == self.sample_rate / 2:
                    power = np.sum(original_amps[1:-1] ** 2) * 2 + original_amps[0] ** 2 + original_amps[-1] ** 2
                else:
                    power = np.sum(original_amps[1:] ** 2) * 2 + original_amps[0] ** 2
            else:
                power = np.sum(original_amps ** 2)
        else:
            original_amps = self._get_raw_amplitudes()
            power = np.sum(original_amps ** 2)
        
        return float(power / self.n_samples)

    def _get_raw_amplitudes(self) -> np.ndarray:
        coeff = self.normalization_coefficients
        
        amps = self.amplitudes.copy()
        
        if coeff.window_correction != 1.0:
            amps = amps / coeff.window_correction
        
        if self.normalization_mode == NormalizationMode.NONE:
            return amps
        elif self.normalization_mode == NormalizationMode.PEAK:
            max_val = np.max(np.abs(amps))
            if max_val > 0:
                amps = amps * max_val
            return amps
        
        if self.normalization_mode in [NormalizationMode.LENGTH, NormalizationMode.STANDARD, NormalizationMode.PSD]:
            amps = amps * coeff.n_fft
        
        return amps

    def renormalize(self, new_mode: NormalizationMode) -> "SpectrumResult":
        if new_mode == self.normalization_mode:
            return self

        original_amps = self._get_raw_amplitudes()

        new_amps, new_coeff = SpectrumNormalizer.normalize_amplitudes(
            amplitudes=original_amps,
            frequencies=self.frequencies,
            sample_rate=self.sample_rate,
            n_fft=self.n_fft,
            n_samples=self.n_samples,
            normalization_mode=new_mode,
            is_onesided=self.is_onesided,
        )

        return SpectrumResult(
            frequencies=self.frequencies.copy(),
            amplitudes=new_amps,
            phases=self.phases.copy() if self.phases is not None else None,
            sample_rate=self.sample_rate,
            n_samples=self.n_samples,
            n_fft=self.n_fft,
            normalization_mode=new_mode,
            is_onesided=self.is_onesided,
            normalization_coefficients=new_coeff,
        )


@dataclass
class NormalizationInfo:
    mode: NormalizationMode
    description: str
    use_case: str
    units: str
    formula: str


NORMALIZATION_MODES_INFO = {
    NormalizationMode.NONE: NormalizationInfo(
        mode=NormalizationMode.NONE,
        description="No normalization applied. Raw FFT amplitudes.",
        use_case="Direct comparison with raw FFT output, internal computations",
        units="Arbitrary amplitude units",
        formula="A = |FFT(x)|",
    ),
    NormalizationMode.LENGTH: NormalizationInfo(
        mode=NormalizationMode.LENGTH,
        description="Amplitudes divided by FFT length N. Standard for spectral analysis.",
        use_case="Comparing spectra of signals with different lengths",
        units="Amplitude per sample",
        formula="A = |FFT(x)| / N",
    ),
    NormalizationMode.STANDARD: NormalizationInfo(
        mode=NormalizationMode.STANDARD,
        description="Standard signal processing normalization (Length + onesided correction).",
        use_case="Default for most signal processing applications",
        units="Physical amplitude units (consistent with time domain)",
        formula="A = |FFT(x)| / N, onesided: A[k>0] *= 2",
    ),
    NormalizationMode.ENERGY: NormalizationInfo(
        mode=NormalizationMode.ENERGY,
        description="Normalized to unit energy (sum of squares = N).",
        use_case="Energy-preserving comparisons, power spectral density",
        units="Normalized energy units",
        formula="A = |FFT(x)| / sqrt(E), E = sum(|FFT(x)|^2)",
    ),
    NormalizationMode.PEAK: NormalizationInfo(
        mode=NormalizationMode.PEAK,
        description="Normalized to peak amplitude = 1.",
        use_case="Shape comparison, relative amplitude analysis",
        units="Normalized amplitude (0 to 1)",
        formula="A = |FFT(x)| / max(|FFT(x)|)",
    ),
    NormalizationMode.PSD: NormalizationInfo(
        mode=NormalizationMode.PSD,
        description="Power Spectral Density normalization. Divides by frequency resolution.",
        use_case="Power spectral density estimation, noise floor analysis",
        units="Power / Hz",
        formula="PSD = |FFT(x)|^2 / (N * fs)",
    ),
    NormalizationMode.DB: NormalizationInfo(
        mode=NormalizationMode.DB,
        description="Decibel scale relative to peak amplitude.",
        use_case="Dynamic range visualization, frequency response",
        units="dB (relative to peak)",
        formula="dB = 20 * log10(|FFT(x)| / max(|FFT(x)|))",
    ),
}


class SpectrumNormalizer:
    @staticmethod
    def calculate_onesided_factors(
        frequencies: np.ndarray,
        sample_rate: float,
        n_fft: int,
    ) -> np.ndarray:
        nyquist = sample_rate / 2
        factors = np.ones_like(frequencies)
        
        if len(frequencies) <= 1:
            return factors

        if frequencies[-1] == nyquist:
            factors[1:-1] = 2.0
            factors[0] = 1.0
            factors[-1] = 1.0
        else:
            factors[1:] = 2.0
            factors[0] = 1.0

        return factors

    @staticmethod
    def normalize_amplitudes(
        amplitudes: np.ndarray,
        frequencies: np.ndarray,
        sample_rate: float,
        n_fft: int,
        n_samples: int,
        normalization_mode: NormalizationMode,
        is_onesided: bool = True,
        window_correction: float = 1.0,
    ) -> Tuple[np.ndarray, NormalizationCoefficients]:
        amps = amplitudes.copy()
        amplitude_scale = 1.0
        onesided_correction = False
        nyquist_correction = False

        if normalization_mode == NormalizationMode.NONE:
            amplitude_scale = 1.0
        elif normalization_mode in [NormalizationMode.LENGTH, NormalizationMode.STANDARD]:
            amplitude_scale = 1.0 / n_fft
            amps = amps * amplitude_scale
        elif normalization_mode == NormalizationMode.PSD:
            freq_resolution = sample_rate / n_fft
            amplitude_scale = 1.0 / (n_fft * freq_resolution)
            amps = amps * amplitude_scale
        elif normalization_mode == NormalizationMode.ENERGY:
            total_power = np.sum(amps ** 2)
            if total_power > 0:
                energy_scale = np.sqrt(n_samples / total_power)
                amps = amps * energy_scale
                amplitude_scale = energy_scale
        elif normalization_mode == NormalizationMode.PEAK:
            max_val = np.max(np.abs(amps))
            if max_val > 0:
                amplitude_scale = 1.0 / max_val
                amps = amps * amplitude_scale
        elif normalization_mode == NormalizationMode.DB:
            max_val = np.max(np.abs(amps))
            if max_val > 0:
                ref_value = max_val
                eps = 1e-10
                amps = 20 * np.log10(np.maximum(amps / ref_value, eps))
                amplitude_scale = 1.0 / ref_value
            else:
                amps = np.zeros_like(amps)
                amplitude_scale = 1.0

        if is_onesided and normalization_mode in [NormalizationMode.STANDARD, NormalizationMode.PSD]:
            onesided_factors = SpectrumNormalizer.calculate_onesided_factors(
                frequencies, sample_rate, n_fft
            )
            amps = amps * onesided_factors
            onesided_correction = True
            nyquist_correction = frequencies[-1] == sample_rate / 2 if len(frequencies) > 0 else False

        coefficients = NormalizationCoefficients(
            amplitude_scale=amplitude_scale,
            onesided_correction=onesided_correction,
            nyquist_correction=nyquist_correction,
            window_correction=window_correction,
            n_fft=n_fft,
            n_samples=n_samples,
            is_onesided=is_onesided,
        )

        return amps, coefficients


class WindowManager:
    WINDOW_TYPES = {
        "hann": np.hanning,
        "hamming": np.hamming,
        "blackman": np.blackman,
        "rectangular": np.ones,
        "bartlett": np.bartlett,
    }

    @staticmethod
    def get_window(window_type: str, size: int) -> Tuple[np.ndarray, float, float]:
        if window_type not in WindowManager.WINDOW_TYPES:
            raise ValueError(f"Unknown window type: {window_type}. Valid types: {list(WindowManager.WINDOW_TYPES.keys())}")

        window_func = WindowManager.WINDOW_TYPES[window_type]
        window = window_func(size)

        coherent_gain = np.mean(window)
        power_gain = np.mean(window ** 2)
        amplitude_correction = 1.0 / coherent_gain if coherent_gain > 0 else 1.0
        power_correction = 1.0 / power_gain if power_gain > 0 else 1.0

        return window, amplitude_correction, power_correction

    @staticmethod
    def list_window_types() -> List[str]:
        return list(WindowManager.WINDOW_TYPES.keys())


class SpectrumAnalyzer:
    def __init__(self):
        self.normalizer = SpectrumNormalizer()
        self.window_manager = WindowManager()

    @staticmethod
    def get_normalization_info(mode: NormalizationMode) -> NormalizationInfo:
        return NORMALIZATION_MODES_INFO.get(mode, NORMALIZATION_MODES_INFO[NormalizationMode.STANDARD])

    @staticmethod
    def list_normalization_modes() -> List[Dict[str, str]]:
        return [
            {
                "mode": mode.value,
                "description": info.description,
                "use_case": info.use_case,
                "units": info.units,
                "formula": info.formula,
            }
            for mode, info in NORMALIZATION_MODES_INFO.items()
        ]

    @staticmethod
    def list_window_types() -> List[str]:
        return WindowManager.list_window_types()

    @staticmethod
    def compute_fft(
        data: np.ndarray,
        sample_rate: float,
        n_fft: Optional[int] = None,
        include_phase: bool = False,
        normalization: str = "standard",
        return_onesided: bool = True,
        window_type: Optional[str] = None,
        apply_window_correction: bool = True,
    ) -> SpectrumResult:
        if len(data) == 0:
            raise ValueError("Signal data is empty")

        try:
            norm_mode = NormalizationMode(normalization.lower())
        except ValueError:
            raise ValueError(
                f"Invalid normalization mode: {normalization}. "
                f"Valid modes: {[m.value for m in NormalizationMode]}"
            )

        n_samples = len(data)
        
        if n_fft is None:
            n_fft = n_samples
        elif n_fft < n_samples:
            n_fft = n_samples

        window_correction = 1.0
        windowed_data = data.copy()

        if window_type is not None:
            window, amplitude_corr, power_corr = WindowManager.get_window(window_type, n_samples)
            windowed_data = data * window
            
            if apply_window_correction:
                if norm_mode in [NormalizationMode.STANDARD, NormalizationMode.LENGTH]:
                    window_correction = amplitude_corr
                elif norm_mode in [NormalizationMode.PSD]:
                    window_correction = power_corr

        fft_result = np.fft.fft(windowed_data, n_fft)
        
        frequencies = np.fft.fftfreq(n_fft, 1.0 / sample_rate)

        amplitudes = np.abs(fft_result)
        phases = None
        if include_phase:
            phases = np.angle(fft_result)

        if return_onesided:
            positive_mask = frequencies >= 0
            frequencies = frequencies[positive_mask]
            amplitudes = amplitudes[positive_mask]
            if phases is not None:
                phases = phases[positive_mask]

        amplitudes, coefficients = SpectrumNormalizer.normalize_amplitudes(
            amplitudes=amplitudes,
            frequencies=frequencies,
            sample_rate=sample_rate,
            n_fft=n_fft,
            n_samples=n_samples,
            normalization_mode=norm_mode,
            is_onesided=return_onesided,
            window_correction=window_correction,
        )

        return SpectrumResult(
            frequencies=frequencies,
            amplitudes=amplitudes,
            phases=phases,
            sample_rate=sample_rate,
            n_samples=n_samples,
            n_fft=n_fft,
            normalization_mode=norm_mode,
            is_onesided=return_onesided,
            normalization_coefficients=coefficients,
        )

    @staticmethod
    def compute_stft(
        data: np.ndarray,
        sample_rate: float,
        window_size: int = 1024,
        overlap: int = 512,
        n_fft: Optional[int] = None,
        normalization: str = "standard",
        window_type: str = "hann",
    ) -> Dict[str, Any]:
        if len(data) == 0:
            raise ValueError("Signal data is empty")

        try:
            norm_mode = NormalizationMode(normalization.lower())
        except ValueError:
            raise ValueError(
                f"Invalid normalization mode: {normalization}"
            )

        n_samples = len(data)
        hop_size = window_size - overlap

        n_frames = (n_samples - window_size) // hop_size + 1

        if n_frames <= 0:
            raise ValueError("Signal too short for STFT with given window size")

        if n_fft is None:
            n_fft = window_size

        window, amplitude_corr, power_corr = WindowManager.get_window(window_type, window_size)
        window_energy = np.sum(window ** 2)

        stft_matrix = []
        times = []
        reference_freqs = None

        freq_resolution = sample_rate / n_fft
        nyquist = sample_rate / 2

        for i in range(n_frames):
            start = i * hop_size
            end = start + window_size

            frame = data[start:end]
            if len(frame) < window_size:
                break

            windowed = frame * window

            fft_result = np.fft.fft(windowed, n_fft)
            frequencies = np.fft.fftfreq(n_fft, 1.0 / sample_rate)

            positive_mask = frequencies >= 0
            positive_freqs = frequencies[positive_mask]
            positive_fft = fft_result[positive_mask]

            amplitudes = np.abs(positive_fft)

            if norm_mode in [NormalizationMode.STANDARD, NormalizationMode.LENGTH]:
                amplitudes = amplitudes / window_size
            elif norm_mode == NormalizationMode.PSD:
                amplitudes = amplitudes ** 2 / (window_energy * freq_resolution)
            elif norm_mode == NormalizationMode.ENERGY:
                frame_energy = np.sum(amplitudes ** 2)
                if frame_energy > 0:
                    amplitudes = amplitudes / np.sqrt(frame_energy)
            elif norm_mode == NormalizationMode.PEAK:
                max_val = np.max(amplitudes)
                if max_val > 0:
                    amplitudes = amplitudes / max_val

            if norm_mode in [NormalizationMode.STANDARD, NormalizationMode.PSD]:
                if len(amplitudes) > 1:
                    if positive_freqs[-1] == nyquist:
                        amplitudes[1:-1] *= 2
                    else:
                        amplitudes[1:] *= 2

            if reference_freqs is None:
                reference_freqs = positive_freqs

            stft_matrix.append(amplitudes)
            times.append(start / sample_rate)

        return {
            "frequencies": reference_freqs.tolist() if reference_freqs is not None else [],
            "times": times,
            "amplitudes": np.array(stft_matrix).tolist(),
            "sample_rate": sample_rate,
            "window_size": window_size,
            "hop_size": hop_size,
            "n_fft": n_fft,
            "normalization_mode": norm_mode.value,
            "window_type": window_type,
        }

    @staticmethod
    def find_peaks(
        spectrum: SpectrumResult,
        height_threshold: Optional[float] = None,
        min_distance: float = 0.0,
        min_freq: float = 0.0,
        max_freq: Optional[float] = None,
    ) -> List[Dict[str, float]]:
        amplitudes = spectrum.amplitudes
        frequencies = spectrum.frequencies

        freq_mask = frequencies >= min_freq
        if max_freq is not None:
            freq_mask = freq_mask & (frequencies <= max_freq)

        filtered_freqs = frequencies[freq_mask]
        filtered_amps = amplitudes[freq_mask]

        if len(filtered_amps) < 3:
            return []

        if height_threshold is None:
            height_threshold = np.mean(filtered_amps) + np.std(filtered_amps)

        peaks = []
        for i in range(1, len(filtered_amps) - 1):
            if filtered_amps[i] > filtered_amps[i-1] and filtered_amps[i] > filtered_amps[i+1]:
                if filtered_amps[i] >= height_threshold:
                    if min_distance > 0 and len(peaks) > 0:
                        last_freq = peaks[-1]["frequency"]
                        if abs(filtered_freqs[i] - last_freq) < min_distance:
                            if filtered_amps[i] > peaks[-1]["amplitude"]:
                                peaks[-1] = {
                                    "frequency": float(filtered_freqs[i]),
                                    "amplitude": float(filtered_amps[i]),
                                }
                            continue
                    
                    peaks.append({
                        "frequency": float(filtered_freqs[i]),
                        "amplitude": float(filtered_amps[i]),
                    })

        peaks.sort(key=lambda x: x["amplitude"], reverse=True)
        return peaks

    @staticmethod
    def compute_band_power(
        spectrum: SpectrumResult,
        low_freq: float,
        high_freq: float,
    ) -> float:
        mask = (spectrum.frequencies >= low_freq) & (spectrum.frequencies <= high_freq)
        
        if not np.any(mask):
            return 0.0

        band_amplitudes = spectrum.amplitudes[mask]
        
        if spectrum.normalization_mode == NormalizationMode.PSD:
            freq_resolution = spectrum.sample_rate / spectrum.n_fft
            power = np.sum(band_amplitudes) * freq_resolution
        else:
            power = np.sum(band_amplitudes ** 2)
        
        return float(power)

    @staticmethod
    def compute_snr(
        spectrum: SpectrumResult,
        signal_freq: float,
        bandwidth: float = 10.0,
    ) -> float:
        low_signal = signal_freq - bandwidth / 2
        high_signal = signal_freq + bandwidth / 2

        signal_mask = (spectrum.frequencies >= low_signal) & (spectrum.frequencies <= high_signal)
        noise_mask = ~signal_mask

        if not np.any(signal_mask) or not np.any(noise_mask):
            return 0.0

        if spectrum.normalization_mode == NormalizationMode.PSD:
            signal_power = np.sum(spectrum.amplitudes[signal_mask])
            noise_power = np.sum(spectrum.amplitudes[noise_mask])
        else:
            signal_power = np.sum(spectrum.amplitudes[signal_mask] ** 2)
            noise_power = np.sum(spectrum.amplitudes[noise_mask] ** 2)

        if noise_power == 0:
            return float('inf')

        snr = 10 * np.log10(signal_power / noise_power)
        return float(snr)

    @staticmethod
    def compare_spectra(
        spectrum1: SpectrumResult,
        spectrum2: SpectrumResult,
    ) -> Dict[str, float]:
        if spectrum1.sample_rate != spectrum2.sample_rate:
            raise ValueError("Spectra must have same sample rate for comparison")

        freqs1 = spectrum1.frequencies
        freqs2 = spectrum2.frequencies

        if not np.array_equal(freqs1, freqs2):
            from scipy.interpolate import interp1d
            interp_func = interp1d(freqs2, spectrum2.amplitudes, 
                                   bounds_error=False, fill_value=0.0)
            amps2_interp = interp_func(freqs1)
        else:
            amps2_interp = spectrum2.amplitudes

        amps1 = spectrum1.amplitudes

        correlation = np.corrcoef(amps1, amps2_interp)[0, 1]
        if np.isnan(correlation):
            correlation = 0.0

        mse = np.mean((amps1 - amps2_interp) ** 2)
        rmse = np.sqrt(mse)

        total_power1 = np.sum(amps1 ** 2)
        total_power2 = np.sum(amps2_interp ** 2)
        power_ratio = total_power1 / total_power2 if total_power2 > 0 else float('inf')

        max_diff = np.max(np.abs(amps1 - amps2_interp))

        return {
            "correlation": float(correlation),
            "mse": float(mse),
            "rmse": float(rmse),
            "power_ratio": float(power_ratio),
            "max_amplitude_difference": float(max_diff),
        }
