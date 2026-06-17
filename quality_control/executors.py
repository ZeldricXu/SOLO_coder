import json
import logging
import re
from typing import List, Dict, Any
from pathlib import Path

from pipeline.executor import BaseStepExecutor, StepResult, StepExecutionError, register_executor
from config.pipeline_config import PipelineStepType
from config.settings import settings

logger = logging.getLogger(__name__)


@register_executor(PipelineStepType.FASTQC)
class FastQCExecutor(BaseStepExecutor):
    """FastQC quality control executor."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id")
        output_dir = self.work_dir / "fastqc_reports"
        output_dir.mkdir(parents=True, exist_ok=True)

        cmd = [
            settings.tools.fastqc,
            "--outdir", str(output_dir),
            "--threads", str(params.get("threads", 4)),
            "--extract",
            "--noextract",
        ]
        cmd.extend(input_files)

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 3600))

            if returncode != 0:
                raise StepExecutionError(
                    f"FastQC failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            output_files = []
            for input_file in input_files:
                base_name = Path(input_file).stem
                if base_name.endswith(".fastq"):
                    base_name = base_name[:-6]
                elif base_name.endswith(".fq"):
                    base_name = base_name[:-3]

                html_report = output_dir / f"{base_name}_fastqc.html"
                zip_report = output_dir / f"{base_name}_fastqc.zip"

                if html_report.exists():
                    output_files.append(str(html_report))
                if zip_report.exists():
                    output_files.append(str(zip_report))

            metrics = self._parse_fastqc_metrics(output_dir, sample_id)
            metrics_file = self._save_metrics(step_id, metrics)
            output_files.append(metrics_file)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"FastQC execution error: {e}")

    def _parse_fastqc_metrics(self, output_dir: Path, sample_id: str) -> Dict[str, Any]:
        """Parse FastQC summary.txt and fastqc_data.txt for metrics."""
        metrics = {
            "sample_id": sample_id,
            "total_reads": 0,
            "total_bases": 0,
            "q20_bases": 0,
            "q30_bases": 0,
            "gc_content": 0.0,
            "adapter_content": 0.0,
            "duplication_rate": 0.0,
            "basic_statistics": "PASS",
            "per_base_quality": "PASS",
            "per_sequence_quality": "PASS",
            "adapter_content": "PASS",
        }

        for data_file in output_dir.glob("*_fastqc/fastqc_data.txt"):
            try:
                with open(data_file, "r") as f:
                    content = f.read()

                total_reads_match = re.search(r"Total Sequences\s+(\d+)", content)
                if total_reads_match:
                    metrics["total_reads"] = int(total_reads_match.group(1))

                gc_match = re.search(r"%GC\s+(\d+\.?\d*)", content)
                if gc_match:
                    metrics["gc_content"] = float(gc_match.group(1))

                seq_len_match = re.search(r"Sequence length\s+(\d+)", content)
                if seq_len_match and metrics["total_reads"]:
                    seq_len = int(seq_len_match.group(1))
                    metrics["total_bases"] = metrics["total_reads"] * seq_len

            except Exception as e:
                logger.warning(f"Failed to parse FastQC metrics {data_file}: {e}")

        return metrics


@register_executor(PipelineStepType.FASTP)
class FastpExecutor(BaseStepExecutor):
    """fastp adapter trimming and quality filtering executor."""

    def execute(self, step_id: str, params: Dict[str, Any], input_files: List[str]) -> StepResult:
        sample_id = params.get("sample_id")

        if len(input_files) < 2:
            raise StepExecutionError("fastp requires paired-end input files (R1 and R2)")

        r1_in, r2_in = input_files[0], input_files[1]
        r1_out = str(self.work_dir / f"{sample_id}_clean_R1.fastq.gz")
        r2_out = str(self.work_dir / f"{sample_id}_clean_R2.fastq.gz")
        html_report = str(self.work_dir / f"{sample_id}_fastp.html")
        json_report = str(self.work_dir / f"{sample_id}_fastp.json")

        cmd = [
            settings.tools.fastp,
            "--in1", r1_in,
            "--in2", r2_in,
            "--out1", r1_out,
            "--out2", r2_out,
            "--html", html_report,
            "--json", json_report,
            "--thread", str(params.get("threads", 8)),
        ]

        quality_threshold = params.get("quality_threshold", 20)
        cmd.extend(["--qualified_quality_phred", str(quality_threshold)])

        adapter_sequence = params.get("adapter_sequence")
        if adapter_sequence:
            cmd.extend(["--adapter_sequence", adapter_sequence])
        adapter_sequence_r2 = params.get("adapter_sequence_r2")
        if adapter_sequence_r2:
            cmd.extend(["--adapter_sequence_r2", adapter_sequence_r2])

        if params.get("detect_adapter_for_pe", True):
            cmd.append("--detect_adapter_for_pe")

        if params.get("trim_poly_g", True):
            cmd.append("--trim_poly_g")

        if params.get("trim_poly_x", True):
            cmd.append("--trim_poly_x")

        n_base_limit = params.get("n_base_limit", 5)
        cmd.extend(["--n_base_limit", str(n_base_limit)])

        length_required = params.get("length_required", 30)
        cmd.extend(["--length_required", str(length_required)])

        try:
            returncode, stdout, stderr = self._run_command(cmd, timeout=params.get("timeout", 7200))

            if returncode != 0:
                raise StepExecutionError(
                    f"fastp failed with code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            output_files = [r1_out, r2_out, html_report, json_report]

            if not self._check_files_exist(output_files):
                raise StepExecutionError("fastp output files missing")

            metrics = self._parse_fastp_metrics(json_report, sample_id)
            metrics_file = self._save_metrics(step_id, metrics)
            output_files.append(metrics_file)

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
            )

        except StepExecutionError:
            raise
        except Exception as e:
            raise StepExecutionError(f"fastp execution error: {e}")

    def _parse_fastp_metrics(self, json_report: str, sample_id: str) -> Dict[str, Any]:
        """Parse fastp JSON report for quality metrics."""
        metrics = {
            "sample_id": sample_id,
            "before_filtering": {},
            "after_filtering": {},
            "filtering_result": {},
            "adapter_trimming": {},
        }

        try:
            with open(json_report, "r") as f:
                data = json.load(f)

            summary = data.get("summary", {})
            before = summary.get("before_filtering", {})
            after = summary.get("after_filtering", {})

            metrics["before_filtering"] = {
                "total_reads": before.get("total_reads", 0),
                "total_bases": before.get("total_bases", 0),
                "q20_bases": before.get("q20_bases", 0),
                "q30_bases": before.get("q30_bases", 0),
                "q20_rate": before.get("q20_rate", 0),
                "q30_rate": before.get("q30_rate", 0),
                "gc_content": before.get("gc_content", 0),
                "read1_mean_length": before.get("read1_mean_length", 0),
                "read2_mean_length": before.get("read2_mean_length", 0),
            }

            metrics["after_filtering"] = {
                "total_reads": after.get("total_reads", 0),
                "total_bases": after.get("total_bases", 0),
                "q20_bases": after.get("q20_bases", 0),
                "q30_bases": after.get("q30_bases", 0),
                "q20_rate": after.get("q20_rate", 0),
                "q30_rate": after.get("q30_rate", 0),
                "gc_content": after.get("gc_content", 0),
                "read1_mean_length": after.get("read1_mean_length", 0),
                "read2_mean_length": after.get("read2_mean_length", 0),
            }

            filtering = summary.get("filtering_result", {})
            metrics["filtering_result"] = {
                "passed_filter_reads": filtering.get("passed_filter_reads", 0),
                "low_quality_reads": filtering.get("low_quality_reads", 0),
                "too_many_N_reads": filtering.get("too_many_N_reads", 0),
                "too_short_reads": filtering.get("too_short_reads", 0),
                "adapter_reads": filtering.get("adapter_reads", 0),
                "duplication_rate": data.get("duplication", {}).get("rate", 0),
            }

            adapter = data.get("adapter_cutting", {})
            metrics["adapter_trimming"] = {
                "read1_adapter_trimmed": adapter.get("read1_adapter_trimmed_reads", 0),
                "read2_adapter_trimmed": adapter.get("read2_adapter_trimmed_reads", 0),
                "adapter_sequence": adapter.get("read1_adapter_sequence", ""),
            }

            if metrics["before_filtering"]["total_reads"] > 0:
                passed = metrics["filtering_result"]["passed_filter_reads"]
                total = metrics["before_filtering"]["total_reads"]
                metrics["passing_rate"] = passed / total * 100

        except Exception as e:
            logger.warning(f"Failed to parse fastp metrics: {e}")

        return metrics
