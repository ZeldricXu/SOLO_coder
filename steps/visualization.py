import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import logging
import json
from typing import List, Dict, Any, Optional, Tuple
from pathlib import Path

from pipeline.executor import (
    BaseStepExecutor,
    StepResult,
    register_executor,
)

logger = logging.getLogger(__name__)


class GenomeBrowserVisualizer:

    DEFAULT_WINDOW = 50
    MAX_READS_DISPLAY = 200

    def __init__(self, work_dir: str, temp_dir: Optional[str] = None):
        self.work_dir = Path(work_dir)
        self.work_dir.mkdir(parents=True, exist_ok=True)
        self.temp_dir = Path(temp_dir) if temp_dir else self.work_dir / "tmp"

    def generate_pileup_image(
        self,
        bam_path: str,
        chromosome: str,
        position: int,
        window_size: int = None,
        output_path: str = None,
        sample_id: str = "",
        variant_info: Optional[Dict[str, Any]] = None,
    ) -> Optional[str]:
        try:
            import pysam
            import matplotlib
            matplotlib.use("Agg")
            import matplotlib.pyplot as plt
            import matplotlib.patches as mpatches
            import numpy as np
        except ImportError as e:
            logger.error(f"Required packages not available: {e}")
            return None

        window = window_size or self.DEFAULT_WINDOW
        start = max(1, position - window)
        end = position + window

        if output_path is None:
            output_path = str(
                self.work_dir / f"{sample_id}_pileup_{chromosome}_{position}.png"
            )

        try:
            bam = pysam.AlignmentFile(bam_path, "rb")
        except Exception as e:
            logger.error(f"Failed to open BAM file {bam_path}: {e}")
            return None

        reads = []
        try:
            for read in bam.fetch(chromosome, start, end):
                if read.is_unmapped or read.is_secondary or read.is_supplementary:
                    continue
                reads.append(read)
        except ValueError as e:
            logger.error(f"Failed to fetch reads from {chromosome}:{start}-{end}: {e}")
            bam.close()
            return None

        if not reads:
            logger.warning(f"No reads found at {chromosome}:{start}-{end}")
            bam.close()
            return None

        display_reads = reads[:self.MAX_READS_DISPLAY]

        fig, (ax_cov, ax_reads) = plt.subplots(
            2, 1, figsize=(14, 8),
            gridspec_kw={"height_ratios": [1, 3]},
        )

        coverage = np.zeros(end - start + 1)
        for read in reads:
            for block_start, block_end in read.get_blocks():
                bs = max(block_start, start) - start
                be = min(block_end, end) - start
                if bs >= 0 and be >= bs:
                    coverage[bs:be + 1] += 1

        positions = np.arange(start, end + 1)
        ax_cov.fill_between(positions, coverage, alpha=0.4, color="steelblue")
        ax_cov.plot(positions, coverage, color="steelblue", linewidth=0.5)

        if variant_info:
            var_pos = variant_info.get("position", position)
            ax_cov.axvline(x=var_pos, color="red", linewidth=1.5, linestyle="--", alpha=0.8)

        ax_cov.set_xlim(start, end)
        ax_cov.set_ylabel("Coverage", fontsize=9)
        ax_cov.set_title(
            f"Genome Browser View - {sample_id} | {chromosome}:{position}",
            fontsize=11, fontweight="bold",
        )
        ax_cov.tick_params(labelsize=7)

        y_offset = 0
        read_height = 0.8
        read_colors = {
            "A": "#2ecc71",
            "T": "#e74c3c",
            "C": "#3498db",
            "G": "#f39c12",
            "N": "#95a5a6",
        }

        for read in display_reads:
            read_start = read.reference_start
            read_end = read.reference_end

            y = y_offset

            ax_reads.add_patch(plt.Rectangle(
                (read_start, y), read_end - read_start, read_height * 0.6,
                facecolor="#bdc3c7", edgecolor="none", alpha=0.6,
            ))

            try:
                pairs = read.get_aligned_pairs(with_seq=True)
                for query_pos, ref_pos, ref_base in pairs:
                    if ref_pos is None or query_pos is None:
                        continue
                    if ref_pos < start or ref_pos > end:
                        continue

                    if read.has_tag("MD"):
                        base = read.query_sequence[query_pos]
                        if ref_base and base != ref_base.upper():
                            color = read_colors.get(base.upper(), "#95a5a6")
                            ax_reads.add_patch(plt.Rectangle(
                                (ref_pos, y), 1, read_height * 0.6,
                                facecolor=color, edgecolor="none", alpha=0.9,
                            ))
            except Exception:
                pass

            if read.is_reverse:
                ax_reads.annotate(
                    "", xy=(read_end - 2, y + read_height * 0.3),
                    xytext=(read_start + 2, y + read_height * 0.3),
                    arrowprops=dict(arrowstyle="<-", color="gray", lw=0.5),
                )

            y_offset += 1

        if variant_info:
            var_pos = variant_info.get("position", position)
            ax_reads.axvline(x=var_pos, color="red", linewidth=1.5, linestyle="--", alpha=0.8)

            ref = variant_info.get("ref", "")
            alt = variant_info.get("alt", "")
            gene = variant_info.get("gene", "")
            label = f"{gene}: {ref}>{alt}" if gene else f"{ref}>{alt}"
            ax_reads.text(
                var_pos, y_offset + 0.5, label,
                fontsize=8, color="red", ha="center", fontweight="bold",
            )

        ax_reads.set_xlim(start, end)
        ax_reads.set_ylim(-0.5, y_offset + 2)
        ax_reads.set_ylabel("Reads", fontsize=9)
        ax_reads.set_xlabel(f"Position on {chromosome}", fontsize=9)
        ax_reads.tick_params(labelsize=7)
        ax_reads.set_yticks([])

        legend_patches = [
            mpatches.Patch(color="#2ecc71", label="A"),
            mpatches.Patch(color="#e74c3c", label="T"),
            mpatches.Patch(color="#3498db", label="C"),
            mpatches.Patch(color="#f39c12", label="G"),
        ]
        ax_reads.legend(
            handles=legend_patches, loc="upper right",
            fontsize=7, ncol=4, framealpha=0.8,
        )

        plt.tight_layout()
        plt.savefig(output_path, dpi=150, bbox_inches="tight")
        plt.close(fig)
        bam.close()

        logger.info(f"Generated pileup image: {output_path}")
        return output_path

    def batch_generate_variant_images(
        self,
        bam_path: str,
        variants: List[Dict[str, Any]],
        sample_id: str,
        window_size: int = None,
    ) -> List[Dict[str, Any]]:
        results = []
        for v in variants:
            chrom = v.get("chromosome", "")
            pos = v.get("position", 0)
            if not chrom or not pos:
                continue

            image_path = self.generate_pileup_image(
                bam_path=bam_path,
                chromosome=chrom,
                position=pos,
                window_size=window_size,
                sample_id=sample_id,
                variant_info=v,
            )

            if image_path:
                results.append({
                    "variant_id": v.get("variant_id", ""),
                    "chromosome": chrom,
                    "position": pos,
                    "image_path": image_path,
                    "gene": v.get("gene", ""),
                    "ref": v.get("ref", ""),
                    "alt": v.get("alt", ""),
                })

        return results


@register_executor("variant_visualization")
class VariantVisualizationExecutor(BaseStepExecutor):

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id", "unknown")
        bam_path = params.get("bam_file", "")
        variants_json = params.get("variants_json", "")
        window_size = params.get("window_size", 50)

        try:
            if not bam_path or not Path(bam_path).exists():
                return StepResult(
                    success=False, step_id=step_id,
                    error_message=f"BAM file not found: {bam_path}",
                )

            variants = []
            if variants_json and Path(variants_json).exists():
                with open(variants_json) as f:
                    variants = json.load(f)

            candidate_variants = [
                v for v in variants
                if v.get("is_candidate") or v.get("acmg_classification") in ("P", "LP")
                or v.get("inheritance_mode") in ("de_novo", "AR", "compound_het", "XL")
            ]

            if not candidate_variants:
                candidate_variants = variants[:20]

            visualizer = GenomeBrowserVisualizer(
                work_dir=str(self.work_dir),
                temp_dir=str(self.temp_dir),
            )

            image_results = visualizer.batch_generate_variant_images(
                bam_path=bam_path,
                variants=candidate_variants,
                sample_id=sample_id,
                window_size=window_size,
            )

            output_file = self.work_dir / f"{sample_id}_visualizations.json"
            with open(output_file, "w") as f:
                json.dump(image_results, f, indent=2, default=str)

            return StepResult(
                success=True, step_id=step_id,
                output_files=[str(output_file)] + [r["image_path"] for r in image_results],
                metrics={
                    "total_variants_visualized": len(image_results),
                    "candidate_variants": len(candidate_variants),
                },
            )

        except Exception as e:
            logger.exception(f"Visualization failed for sample {sample_id}")
            return StepResult(success=False, step_id=step_id, error_message=str(e))
