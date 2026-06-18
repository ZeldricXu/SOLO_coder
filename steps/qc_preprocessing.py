import logging
import json
import gzip
from pathlib import Path
from typing import List, Dict, Any, Tuple
from datetime import datetime

from pipeline.executor import BaseStepExecutor, StepResult, register_executor, StepExecutionError
from config.settings import settings
from config.pipeline_config import PipelineStepType
from storage.repository import SampleRepository, QCMetricRepository

logger = logging.getLogger(__name__)


@register_executor(PipelineStepType.FASTQC)
class FastQCExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()
        sample_id = params.get("sample_id", step_id.split("_")[0])

        if len(input_files) < 2:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message="FastQC requires at least 2 input files (R1 and R2)",
            )

        r1_path, r2_path = input_files[0], input_files[1]
        output_dir = self.work_dir / "fastqc_results"
        output_dir.mkdir(parents=True, exist_ok=True)

        cmd = [
            settings.tools.fastqc,
            "--outdir", str(output_dir),
            "--threads", "4",
            "--format", "fastq",
            "--extract",
            r1_path,
            r2_path,
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd)

            if returncode != 0:
                raise StepExecutionError(
                    f"FastQC failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            r1_base = Path(r1_path).stem.replace(".fastq", "").replace(".gz", "")
            r2_base = Path(r2_path).stem.replace(".fastq", "").replace(".gz", "")

            r1_html = output_dir / f"{r1_base}_fastqc.html"
            r2_html = output_dir / f"{r2_base}_fastqc.html"
            r1_data = output_dir / f"{r1_base}_fastqc_data.txt"
            r2_data = output_dir / f"{r2_base}_fastqc_data.txt"

            metrics = self._parse_fastqc_metrics(r1_data, r2_data)

            qc_json_path = self.work_dir / f"{sample_id}_fastqc_data.json"
            with open(qc_json_path, "w") as f:
                json.dump(metrics, f, indent=2)

            output_files = []
            if r1_html.exists():
                output_files.append(str(r1_html))
            if r2_html.exists():
                output_files.append(str(r2_html))
            output_files.append(str(qc_json_path))

            duration = (datetime.now() - start_time).total_seconds()

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
                duration_seconds=duration,
            )

        except StepExecutionError as e:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message=str(e),
                stdout=e.stdout,
                stderr=e.stderr,
                duration_seconds=(datetime.now() - start_time).total_seconds(),
            )

    def _parse_fastqc_metrics(self, r1_data: Path, r2_data: Path) -> Dict[str, Any]:
        metrics = {
            "r1": {},
            "r2": {},
            "summary": {},
        }

        for idx, data_file in enumerate([r1_data, r2_data], 1):
            if not data_file.exists():
                continue

            read_key = f"r{idx}"
            current_section = None

            with open(data_file, "r") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith(">>"):
                        parts = line[2:].split("\t")
                        if len(parts) >= 1:
                            current_section = parts[0].lower().replace(" ", "_")
                        continue

                    if line.startswith("#") or not line:
                        continue

                    if current_section == "basic_statistics":
                        parts = line.split("\t")
                        if len(parts) >= 2:
                            key = parts[0].lower().replace(" ", "_")
                            value = parts[1]
                            try:
                                if "sequences" in key or "bp" in key or "length" in key:
                                    value = int(value.replace(",", ""))
                                elif "%" in key or "gc" in key:
                                    value = float(value.replace("%", ""))
                            except (ValueError, TypeError):
                                pass
                            metrics[read_key][key] = value

                    elif current_section == "per_base_quality":
                        parts = line.split("\t")
                        if len(parts) >= 3:
                            if "mean_quality" not in metrics[read_key]:
                                metrics[read_key]["mean_quality"] = []
                            try:
                                metrics[read_key]["mean_quality"].append(float(parts[1]))
                            except ValueError:
                                pass

                    elif current_section == "per_sequence_quality_scores":
                        parts = line.split("\t")
                        if len(parts) >= 2:
                            try:
                                metrics[read_key]["median_quality"] = float(parts[0])
                            except ValueError:
                                pass

                    elif current_section == "adapter_content":
                        parts = line.split("\t")
                        if len(parts) >= 2:
                            try:
                                adapter_pct = float(parts[-1])
                                if "adapter_content" not in metrics[read_key]:
                                    metrics[read_key]["adapter_content"] = 0
                                metrics[read_key]["adapter_content"] = max(
                                    metrics[read_key]["adapter_content"],
                                    adapter_pct,
                                )
                            except ValueError:
                                pass

        total_reads = (
            metrics["r1"].get("total_sequences", 0)
            + metrics["r2"].get("total_sequences", 0)
        )
        q20_rate = (
            (metrics["r1"].get("mean_quality", []) and sum(1 for q in metrics["r1"]["mean_quality"] if q >= 20) / len(metrics["r1"]["mean_quality"]))
            + (metrics["r2"].get("mean_quality", []) and sum(1 for q in metrics["r2"]["mean_quality"] if q >= 20) / len(metrics["r2"]["mean_quality"]))
        ) / 2 if (metrics["r1"].get("mean_quality") and metrics["r2"].get("mean_quality")) else 0

        metrics["summary"] = {
            "total_reads": total_reads,
            "avg_gc_content": (
                metrics["r1"].get("%gc", 0) + metrics["r2"].get("%gc", 0)
            ) / 2,
            "q20_rate": q20_rate,
            "r1_adapter_content": metrics["r1"].get("adapter_content", 0),
            "r2_adapter_content": metrics["r2"].get("adapter_content", 0),
            "r1_median_quality": metrics["r1"].get("median_quality", 0),
            "r2_median_quality": metrics["r2"].get("median_quality", 0),
        }

        return metrics


@register_executor(PipelineStepType.FASTP)
class FastpExecutor(BaseStepExecutor):
    def execute(
        self,
        step_id: str,
        params: Dict[str, Any],
        input_files: List[str],
    ) -> StepResult:
        start_time = datetime.now()
        sample_id = params.get("sample_id", step_id.split("_")[0])

        if len(input_files) < 2:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message="fastp requires at least 2 input files (R1 and R2)",
            )

        r1_path, r2_path = input_files[0], input_files[1]

        clean_r1 = self.work_dir / f"{sample_id}_clean_R1.fastq.gz"
        clean_r2 = self.work_dir / f"{sample_id}_clean_R2.fastq.gz"
        html_report = self.work_dir / f"{sample_id}_fastp_report.html"
        json_report = self.work_dir / f"{sample_id}_fastp_report.json"

        cmd = [
            settings.tools.fastp,
            "--in1", r1_path,
            "--in2", r2_path,
            "--out1", str(clean_r1),
            "--out2", str(clean_r2),
            "--html", str(html_report),
            "--json", str(json_report),
            "--thread", "8",
            "--adapter_sequence", "AGATCGGAAGAGCACACGTCTGAACTCCAGTCA",
            "--adapter_sequence_r2", "AGATCGGAAGAGCGTCGTGTAGGGAAAGAGTGT",
            "--cut_front",
            "--cut_tail",
            "--cut_mean_quality", "20",
            "--qualified_quality_phred", "20",
            "--unqualified_percent_limit", "40",
            "--n_base_limit", "5",
            "--length_required", "50",
        ]

        try:
            returncode, stdout, stderr = self._run_command(cmd)

            if returncode != 0:
                raise StepExecutionError(
                    f"fastp failed with exit code {returncode}",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            if not self._check_files_exist([str(clean_r1), str(clean_r2), str(json_report)]):
                raise StepExecutionError(
                    "fastp did not produce expected output files",
                    return_code=returncode,
                    stdout=stdout,
                    stderr=stderr,
                )

            metrics = self._parse_fastp_metrics(json_report)

            sample = SampleRepository.get_by_id(sample_id)
            if sample:
                QCMetricRepository.create(sample.id, "fastp", metrics)
                SampleRepository.update_qc_metrics(sample_id, metrics)

            output_files = [
                str(clean_r1),
                str(clean_r2),
                str(html_report),
                str(json_report),
            ]

            duration = (datetime.now() - start_time).total_seconds()

            return StepResult(
                success=True,
                step_id=step_id,
                output_files=output_files,
                metrics=metrics,
                stdout=stdout,
                stderr=stderr,
                duration_seconds=duration,
            )

        except StepExecutionError as e:
            return StepResult(
                success=False,
                step_id=step_id,
                error_message=str(e),
                stdout=e.stdout,
                stderr=e.stderr,
                duration_seconds=(datetime.now() - start_time).total_seconds(),
            )

    def _parse_fastp_metrics(self, json_report: Path) -> Dict[str, Any]:
        with open(json_report, "r") as f:
            data = json.load(f)

        before = data.get("summary", {}).get("before_filtering", {})
        after = data.get("summary", {}).get("after_filtering", {})
        adapter = data.get("adapter_cutting", {})

        total_reads_before = before.get("total_reads", 0)
        total_reads_after = after.get("total_reads", 0)
        total_bases_before = before.get("total_bases", 0)
        total_bases_after = after.get("total_bases", 0)

        q20_bases_before = before.get("q20_bases", 0)
        q20_bases_after = after.get("q20_bases", 0)
        q30_bases_before = before.get("q30_bases", 0)
        q30_bases_after = after.get("q30_bases", 0)

        duplication_rate = data.get("duplication", {}).get("rate", 0)
        if duplication_rate and isinstance(duplication_rate, (int, float)) and duplication_rate > 1:
            duplication_rate = duplication_rate / 100

        metrics = {
            "total_reads_before": total_reads_before,
            "total_reads_after": total_reads_after,
            "reads_filtered": total_reads_before - total_reads_after,
            "reads_pass_rate": (
                total_reads_after / total_reads_before if total_reads_before > 0 else 0
            ),
            "total_bases_before": total_bases_before,
            "total_bases_after": total_bases_after,
            "bases_filtered": total_bases_before - total_bases_after,
            "bases_pass_rate": (
                total_bases_after / total_bases_before if total_bases_before > 0 else 0
            ),
            "q20_bases_before": q20_bases_before,
            "q20_bases_after": q20_bases_after,
            "q20_rate_before": (
                q20_bases_before / total_bases_before if total_bases_before > 0 else 0
            ),
            "q20_rate_after": (
                q20_bases_after / total_bases_after if total_bases_after > 0 else 0
            ),
            "q30_bases_before": q30_bases_before,
            "q30_bases_after": q30_bases_after,
            "q30_rate_before": (
                q30_bases_before / total_bases_before if total_bases_before > 0 else 0
            ),
            "q30_rate_after": (
                q30_bases_after / total_bases_after if total_bases_after > 0 else 0
            ),
            "gc_content_before": before.get("gc_content", 0),
            "gc_content_after": after.get("gc_content", 0),
            "adapter_content": adapter.get("adapter_trimmed_reads", 0) / total_reads_before if total_reads_before > 0 else 0,
            "duplication_rate": duplication_rate,
            "insert_size": data.get("insert_size", {}).get("peak", 0),
        }

        return metrics
