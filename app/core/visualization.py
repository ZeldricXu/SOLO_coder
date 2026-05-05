import os
import numpy as np
from typing import Dict, Any, Optional, List, Tuple
import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt
import plotly.graph_objects as go
from plotly.subplots import make_subplots

from app.config import STATIC_IMAGE_DIR


class Visualizer:
    def __init__(self):
        pass

    @staticmethod
    def get_output_path(filename: str) -> str:
        return os.path.join(STATIC_IMAGE_DIR, filename)

    @staticmethod
    def plot_waveform_matplotlib(
        data: np.ndarray,
        sample_rate: float,
        title: str = "Signal Waveform",
        filename: Optional[str] = None,
        show_grid: bool = True,
        figsize: Tuple[int, int] = (12, 4),
        dpi: int = 100,
    ) -> str:
        if len(data) == 0:
            raise ValueError("Signal data is empty")

        n_samples = len(data)
        duration = n_samples / sample_rate
        time_axis = np.linspace(0, duration, n_samples)

        fig, ax = plt.subplots(figsize=figsize, dpi=dpi)
        ax.plot(time_axis, data, linewidth=0.8, color='#1f77b4')
        ax.set_title(title, fontsize=12, fontweight='bold')
        ax.set_xlabel('Time (s)', fontsize=10)
        ax.set_ylabel('Amplitude', fontsize=10)
        ax.set_xlim(0, duration)

        if show_grid:
            ax.grid(True, alpha=0.3, linestyle='--')

        ax.axhline(y=0, color='#333333', linewidth=0.5, alpha=0.5)

        plt.tight_layout()

        if filename is None:
            import uuid
            filename = f"waveform_{uuid.uuid4().hex[:8]}.png"

        output_path = Visualizer.get_output_path(filename)
        plt.savefig(output_path, dpi=dpi, bbox_inches='tight')
        plt.close(fig)

        return filename

    @staticmethod
    def plot_spectrum_matplotlib(
        frequencies: np.ndarray,
        amplitudes: np.ndarray,
        title: str = "Frequency Spectrum",
        filename: Optional[str] = None,
        xscale: str = 'linear',
        yscale: str = 'linear',
        show_grid: bool = True,
        figsize: Tuple[int, int] = (12, 4),
        dpi: int = 100,
    ) -> str:
        if len(frequencies) == 0 or len(amplitudes) == 0:
            raise ValueError("Spectrum data is empty")

        fig, ax = plt.subplots(figsize=figsize, dpi=dpi)
        ax.plot(frequencies, amplitudes, linewidth=0.8, color='#ff7f0e')
        ax.fill_between(frequencies, amplitudes, alpha=0.2, color='#ff7f0e')
        ax.set_title(title, fontsize=12, fontweight='bold')
        ax.set_xlabel('Frequency (Hz)', fontsize=10)
        ax.set_ylabel('Amplitude', fontsize=10)
        ax.set_xscale(xscale)
        ax.set_yscale(yscale)

        if show_grid:
            ax.grid(True, alpha=0.3, linestyle='--')

        plt.tight_layout()

        if filename is None:
            import uuid
            filename = f"spectrum_{uuid.uuid4().hex[:8]}.png"

        output_path = Visualizer.get_output_path(filename)
        plt.savefig(output_path, dpi=dpi, bbox_inches='tight')
        plt.close(fig)

        return filename

    @staticmethod
    def plot_comparison_matplotlib(
        data1: np.ndarray,
        data2: np.ndarray,
        sample_rate: float,
        title: str = "Signal Comparison",
        label1: str = "Original",
        label2: str = "Processed",
        filename: Optional[str] = None,
        show_grid: bool = True,
        figsize: Tuple[int, int] = (12, 6),
        dpi: int = 100,
    ) -> str:
        if len(data1) == 0 or len(data2) == 0:
            raise ValueError("Signal data is empty")

        n_samples = min(len(data1), len(data2))
        duration = n_samples / sample_rate
        time_axis = np.linspace(0, duration, n_samples)

        fig, axes = plt.subplots(2, 1, figsize=figsize, dpi=dpi)

        axes[0].plot(time_axis, data1[:n_samples], linewidth=0.8, color='#1f77b4', label=label1)
        axes[0].plot(time_axis, data2[:n_samples], linewidth=0.8, color='#ff7f0e', label=label2, alpha=0.7)
        axes[0].set_title(f"{title} - Time Domain", fontsize=12, fontweight='bold')
        axes[0].set_ylabel('Amplitude', fontsize=10)
        axes[0].set_xlim(0, duration)
        axes[0].legend(loc='upper right')
        if show_grid:
            axes[0].grid(True, alpha=0.3, linestyle='--')
        axes[0].axhline(y=0, color='#333333', linewidth=0.5, alpha=0.5)

        diff = data1[:n_samples] - data2[:n_samples]
        axes[1].plot(time_axis, diff, linewidth=0.8, color='#2ca02c')
        axes[1].set_title("Difference Signal", fontsize=12, fontweight='bold')
        axes[1].set_xlabel('Time (s)', fontsize=10)
        axes[1].set_ylabel('Amplitude', fontsize=10)
        axes[1].set_xlim(0, duration)
        if show_grid:
            axes[1].grid(True, alpha=0.3, linestyle='--')
        axes[1].axhline(y=0, color='#333333', linewidth=0.5, alpha=0.5)

        plt.tight_layout()

        if filename is None:
            import uuid
            filename = f"comparison_{uuid.uuid4().hex[:8]}.png"

        output_path = Visualizer.get_output_path(filename)
        plt.savefig(output_path, dpi=dpi, bbox_inches='tight')
        plt.close(fig)

        return filename

    @staticmethod
    def plot_combined_matplotlib(
        time_data: np.ndarray,
        frequencies: np.ndarray,
        amplitudes: np.ndarray,
        sample_rate: float,
        title: str = "Signal Analysis",
        filename: Optional[str] = None,
        show_grid: bool = True,
        figsize: Tuple[int, int] = (14, 8),
        dpi: int = 100,
    ) -> str:
        if len(time_data) == 0:
            raise ValueError("Time data is empty")
        if len(frequencies) == 0 or len(amplitudes) == 0:
            raise ValueError("Spectrum data is empty")

        fig = plt.figure(figsize=figsize, dpi=dpi)
        fig.suptitle(title, fontsize=14, fontweight='bold')

        ax1 = plt.subplot(211)
        n_samples = len(time_data)
        duration = n_samples / sample_rate
        time_axis = np.linspace(0, duration, n_samples)
        ax1.plot(time_axis, time_data, linewidth=0.8, color='#1f77b4')
        ax1.set_title("Time Domain Waveform", fontsize=11)
        ax1.set_ylabel('Amplitude', fontsize=10)
        ax1.set_xlim(0, duration)
        if show_grid:
            ax1.grid(True, alpha=0.3, linestyle='--')
        ax1.axhline(y=0, color='#333333', linewidth=0.5, alpha=0.5)

        ax2 = plt.subplot(212)
        ax2.plot(frequencies, amplitudes, linewidth=0.8, color='#ff7f0e')
        ax2.fill_between(frequencies, amplitudes, alpha=0.2, color='#ff7f0e')
        ax2.set_title("Frequency Spectrum", fontsize=11)
        ax2.set_xlabel('Frequency (Hz)', fontsize=10)
        ax2.set_ylabel('Amplitude', fontsize=10)
        if show_grid:
            ax2.grid(True, alpha=0.3, linestyle='--')

        plt.tight_layout()

        if filename is None:
            import uuid
            filename = f"combined_{uuid.uuid4().hex[:8]}.png"

        output_path = Visualizer.get_output_path(filename)
        plt.savefig(output_path, dpi=dpi, bbox_inches='tight')
        plt.close(fig)

        return filename

    @staticmethod
    def plot_waveform_plotly(
        data: np.ndarray,
        sample_rate: float,
        title: str = "Signal Waveform",
        show_controls: bool = True,
    ) -> Dict[str, Any]:
        if len(data) == 0:
            raise ValueError("Signal data is empty")

        n_samples = len(data)
        duration = n_samples / sample_rate
        time_axis = np.linspace(0, duration, n_samples)

        fig = go.Figure()
        fig.add_trace(go.Scatter(
            x=time_axis,
            y=data,
            mode='lines',
            name='Signal',
            line=dict(color='#1f77b4', width=1),
            hovertemplate='Time: %{x:.4f}s<br>Amplitude: %{y:.4f}<extra></extra>'
        ))

        fig.update_layout(
            title=dict(text=title, font=dict(size=14)),
            xaxis_title='Time (s)',
            yaxis_title='Amplitude',
            xaxis=dict(range=[0, duration]),
            hovermode='x unified',
            dragmode='pan' if show_controls else 'select',
        )

        if show_controls:
            fig.update_layout(
                modebar=dict(
                    add=['hoverclosest', 'hovercompare', 'togglehover', 'togglespikes'],
                    remove=['lasso2d', 'select2d'],
                )
            )

        return fig.to_dict()

    @staticmethod
    def plot_spectrum_plotly(
        frequencies: np.ndarray,
        amplitudes: np.ndarray,
        title: str = "Frequency Spectrum",
        show_controls: bool = True,
    ) -> Dict[str, Any]:
        if len(frequencies) == 0 or len(amplitudes) == 0:
            raise ValueError("Spectrum data is empty")

        fig = go.Figure()
        fig.add_trace(go.Scatter(
            x=frequencies,
            y=amplitudes,
            mode='lines',
            fill='tozeroy',
            name='Spectrum',
            line=dict(color='#ff7f0e', width=1),
            hovertemplate='Frequency: %{x:.1f}Hz<br>Amplitude: %{y:.4f}<extra></extra>'
        ))

        fig.update_layout(
            title=dict(text=title, font=dict(size=14)),
            xaxis_title='Frequency (Hz)',
            yaxis_title='Amplitude',
            hovermode='x unified',
            dragmode='pan' if show_controls else 'select',
        )

        if show_controls:
            fig.update_layout(
                modebar=dict(
                    add=['hoverclosest', 'hovercompare'],
                    remove=['lasso2d', 'select2d'],
                )
            )

        return fig.to_dict()

    @staticmethod
    def plot_combined_plotly(
        time_data: np.ndarray,
        frequencies: np.ndarray,
        amplitudes: np.ndarray,
        sample_rate: float,
        title: str = "Signal Analysis",
        show_controls: bool = True,
    ) -> Dict[str, Any]:
        if len(time_data) == 0:
            raise ValueError("Time data is empty")
        if len(frequencies) == 0 or len(amplitudes) == 0:
            raise ValueError("Spectrum data is empty")

        fig = make_subplots(
            rows=2, cols=1,
            subplot_titles=("Time Domain Waveform", "Frequency Spectrum"),
            vertical_spacing=0.15,
        )

        n_samples = len(time_data)
        duration = n_samples / sample_rate
        time_axis = np.linspace(0, duration, n_samples)

        fig.add_trace(
            go.Scatter(
                x=time_axis,
                y=time_data,
                mode='lines',
                name='Signal',
                line=dict(color='#1f77b4', width=1),
                hovertemplate='Time: %{x:.4f}s<br>Amplitude: %{y:.4f}<extra></extra>'
            ),
            row=1, col=1
        )

        fig.add_trace(
            go.Scatter(
                x=frequencies,
                y=amplitudes,
                mode='lines',
                fill='tozeroy',
                name='Spectrum',
                line=dict(color='#ff7f0e', width=1),
                hovertemplate='Frequency: %{x:.1f}Hz<br>Amplitude: %{y:.4f}<extra></extra>'
            ),
            row=2, col=1
        )

        fig.update_xaxes(title_text='Time (s)', row=1, col=1, range=[0, duration])
        fig.update_xaxes(title_text='Frequency (Hz)', row=2, col=1)
        fig.update_yaxes(title_text='Amplitude', row=1, col=1)
        fig.update_yaxes(title_text='Amplitude', row=2, col=1)

        fig.update_layout(
            title=dict(text=title, font=dict(size=16), x=0.5),
            hovermode='x unified',
            dragmode='pan' if show_controls else 'select',
            showlegend=False,
            height=700,
        )

        return fig.to_dict()
