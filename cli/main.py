import logging
import json
import sys
from pathlib import Path
from typing import Optional, List
from datetime import datetime

import click
from tabulate import tabulate

from db.models import SampleType, SampleStatus, TaskStatus, StepStatus
from db.database import init_db, get_db_session
from db.models import Sample, AnalysisTask
from config.settings import settings
from data_management.sample_manager import SampleManager
from data_management.task_manager import TaskManager
from data_management.retention_policy import RetentionPolicyManager
from celery_app.tasks import run_analysis_pipeline, retry_failed_task, cleanup_expired_data

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)


@click.group()
@click.version_option(version="1.0.0")
def app():
    """Genome Variant Pipeline - 基因组变异检测与注释自动化分析流程"""
    pass


@app.group()
def db():
    """数据库管理命令"""
    pass


@db.command("init")
def db_init():
    """初始化数据库"""
    click.echo("正在初始化数据库...")
    try:
        init_db()
        click.echo("✅ 数据库初始化成功")
    except Exception as e:
        click.echo(f"❌ 数据库初始化失败: {e}", err=True)
        sys.exit(1)


@db.command("check")
def db_check():
    """检查数据库连接"""
    click.echo("正在检查数据库连接...")
    try:
        with get_db_session() as session:
            count = session.query(Sample).count()
            task_count = session.query(AnalysisTask).count()
            click.echo(f"✅ 数据库连接正常")
            click.echo(f"   - 样本数: {count}")
            click.echo(f"   - 任务数: {task_count}")
    except Exception as e:
        click.echo(f"❌ 数据库连接失败: {e}", err=True)
        sys.exit(1)


@app.group()
def sample():
    """样本管理命令"""
    pass


@sample.command("register")
@click.option("--sample-id", required=True, help="样本唯一ID")
@click.option("--sample-type", required=True,
              type=click.Choice(["WES", "WGS", "PANEL", "cfDNA"]),
              help="样本类型")
@click.option("--fastq-r1", required=True, help="R1 FASTQ文件路径")
@click.option("--fastq-r2", required=True, help="R2 FASTQ文件路径")
@click.option("--patient-id", help="患者ID")
@click.option("--library-id", help="文库ID")
@click.option("--platform", default="Illumina", help="测序平台")
@click.option("--read-length", type=int, help="读长")
@click.option("--phenotype", multiple=True, help="HPO表型术语，可多次指定")
@click.option("--diagnosis", help="临床诊断")
@click.option("--physician", help="送检医生")
@click.option("--institution", help="送检机构")
def sample_register(sample_id, sample_type, fastq_r1, fastq_r2, patient_id,
                    library_id, platform, read_length, phenotype, diagnosis,
                    physician, institution):
    """登记新样本并上传原始数据"""
    click.echo(f"正在登记样本: {sample_id}")

    try:
        manager = SampleManager()
        sample_enum = SampleType(sample_type)

        sample = manager.register_sample(
            sample_id=sample_id,
            sample_type=sample_enum,
            fastq_r1_path=fastq_r1,
            fastq_r2_path=fastq_r2,
            patient_id=patient_id,
            library_id=library_id,
            sequencing_platform=platform,
            read_length=read_length,
            phenotype_hpo=list(phenotype) if phenotype else None,
            clinical_diagnosis=diagnosis,
            referring_physician=physician,
            institution=institution,
        )

        click.echo(f"✅ 样本登记成功")
        click.echo(f"   样本ID: {sample.sample_id}")
        click.echo(f"   类型: {sample.sample_type.value}")
        click.echo(f"   患者ID: {sample.patient_id or 'N/A'}")
        click.echo(f"   FASTQ R1: {sample.fastq_r1_path}")
        click.echo(f"   FASTQ R2: {sample.fastq_r2_path}")
        click.echo(f"   状态: {sample.status.value}")

    except Exception as e:
        click.echo(f"❌ 样本登记失败: {e}", err=True)
        sys.exit(1)


@sample.command("list")
@click.option("--status", type=click.Choice([s.value for s in SampleStatus]),
              help="按状态过滤")
@click.option("--type", "sample_type",
              type=click.Choice([s.value for s in SampleType]),
              help="按类型过滤")
@click.option("--limit", default=20, help="显示数量限制")
@click.option("--offset", default=0, help="偏移量")
def sample_list(status, sample_type, limit, offset):
    """列出示例"""
    manager = SampleManager()

    status_enum = SampleStatus(status) if status else None
    type_enum = SampleType(sample_type) if sample_type else None

    result = manager.list_samples(
        skip=offset,
        limit=limit,
        status=status_enum,
        sample_type=type_enum,
    )

    table_data = []
    for s in result["samples"]:
        table_data.append([
            s.sample_id,
            s.sample_type.value,
            s.patient_id or "-",
            s.status.value,
            s.created_at.strftime("%Y-%m-%d %H:%M") if s.created_at else "-",
            s.total_variants or "-",
        ])

    click.echo(f"样本列表 (共 {result['total']} 个，显示 {offset}-{offset+limit}):")
    click.echo(tabulate(
        table_data,
        headers=["样本ID", "类型", "患者ID", "状态", "创建时间", "变异数"],
        tablefmt="pretty",
    ))


@sample.command("show")
@click.argument("sample_id")
def sample_show(sample_id):
    """显示样本详细信息"""
    manager = SampleManager()
    sample = manager.get_sample(sample_id)

    if not sample:
        click.echo(f"❌ 样本不存在: {sample_id}", err=True)
        sys.exit(1)

    click.echo(f"样本详情: {sample_id}")
    click.echo("=" * 50)
    click.echo(f"样本ID:     {sample.sample_id}")
    click.echo(f"样本类型:   {sample.sample_type.value}")
    click.echo(f"患者ID:     {sample.patient_id or 'N/A'}")
    click.echo(f"文库ID:     {sample.library_id or 'N/A'}")
    click.echo(f"测序平台:   {sample.sequencing_platform or 'N/A'}")
    click.echo(f"读长:       {sample.read_length or 'N/A'}")
    click.echo(f"当前状态:   {sample.status.value}")
    click.echo(f"总变异数:   {sample.total_variants or 'N/A'}")
    click.echo(f"创建时间:   {sample.created_at.strftime('%Y-%m-%d %H:%M:%S') if sample.created_at else 'N/A'}")
    if sample.analysis_started_at:
        click.echo(f"分析开始:   {sample.analysis_started_at.strftime('%Y-%m-%d %H:%M:%S')}")
    if sample.analysis_completed_at:
        click.echo(f"分析完成:   {sample.analysis_completed_at.strftime('%Y-%m-%d %H:%M:%S')}")
    if sample.report_path:
        click.echo(f"报告路径:   {sample.report_path}")
    click.echo(f"表型(HPO):  {', '.join(sample.phenotype_hpo) if sample.phenotype_hpo else 'N/A'}")
    if sample.clinical_diagnosis:
        click.echo(f"临床诊断:   {sample.clinical_diagnosis}")
    if sample.referring_physician:
        click.echo(f"送检医生:   {sample.referring_physician}")
    if sample.institution:
        click.echo(f"送检机构:   {sample.institution}")

    if sample.qc_metrics:
        click.echo("\nQC指标:")
        for k, v in sample.qc_metrics.items():
            click.echo(f"  {k}: {v}")


@app.group()
def task():
    """分析任务管理命令"""
    pass


@task.command("create")
@click.option("--sample-id", required=True, help="样本ID")
@click.option("--task-name", help="任务名称")
@click.option("--with-vardict", is_flag=True, help="包含VarDict低频变异检测")
@click.option("--priority", default=0, type=int, help="优先级（数值越高越优先）")
def task_create(sample_id, task_name, with_vardict, priority):
    """创建分析任务"""
    click.echo(f"正在为样本 {sample_id} 创建分析任务...")

    try:
        manager = TaskManager()
        task = manager.create_analysis_task(
            sample_id=sample_id,
            task_name=task_name,
            with_vardict=with_vardict,
            priority=priority,
        )

        click.echo(f"✅ 任务创建成功")
        click.echo(f"   任务ID: {task.task_id}")
        click.echo(f"   名称: {task.task_name}")
        click.echo(f"   优先级: {task.priority}")
        click.echo(f"   状态: {task.status.value}")

    except Exception as e:
        click.echo(f"❌ 任务创建失败: {e}", err=True)
        sys.exit(1)


@task.command("submit")
@click.argument("task_id")
@click.option("--resume/--no-resume", default=True, help="是否断点续跑")
def task_submit(task_id, resume):
    """提交任务到队列执行"""
    click.echo(f"正在提交任务: {task_id}")

    try:
        result = run_analysis_pipeline.apply_async(
            args=[task_id],
            kwargs={"resume": resume},
            task_id=f"pipeline_{task_id}",
        )

        manager = TaskManager()
        manager.queue_task(task_id)

        click.echo(f"✅ 任务已提交到队列")
        click.echo(f"   Celery任务ID: {result.id}")
        click.echo(f"   断点续跑: {'启用' if resume else '禁用'}")

    except Exception as e:
        click.echo(f"❌ 任务提交失败: {e}", err=True)
        sys.exit(1)


@task.command("run")
@click.argument("task_id")
@click.option("--resume/--no-resume", default=True, help="是否断点续跑")
def task_run(task_id, resume):
    """立即运行任务（前台执行）"""
    click.echo(f"正在运行任务: {task_id}（前台模式）")
    click.echo("=" * 60)

    try:
        result = run_analysis_pipeline(task_id, resume=resume)

        if result.get("success"):
            click.echo("=" * 60)
            click.echo("✅ 分析完成！")
            click.echo(f"   总耗时: {result.get('duration_seconds', 0):.1f} 秒")
            click.echo(f"   输出文件: {len(result.get('output_files', []))} 个")
            summary = result.get("summary", {})
            if summary:
                click.echo(f"   总变异数: {summary.get('total_variants', 'N/A')}")
                click.echo(f"   阳性位点: {summary.get('positive_variants', 'N/A')}")
                click.echo(f"   次要发现: {summary.get('secondary_findings', 'N/A')}")
        else:
            click.echo("=" * 60)
            click.echo(f"❌ 分析失败: {result.get('error', '未知错误')}", err=True)
            if result.get("failed_step"):
                click.echo(f"   失败步骤: {result.get('failed_step')}")
            sys.exit(1)

    except Exception as e:
        click.echo(f"❌ 任务执行失败: {e}", err=True)
        sys.exit(1)


@task.command("list")
@click.option("--status", type=click.Choice([s.value for s in TaskStatus]),
              help="按状态过滤")
@click.option("--limit", default=20, help="显示数量限制")
@click.option("--offset", default=0, help="偏移量")
def task_list(status, limit, offset):
    """列出任务"""
    manager = TaskManager()
    status_enum = TaskStatus(status) if status else None

    result = manager.list_tasks(
        skip=offset,
        limit=limit,
        status=status_enum,
    )

    table_data = []
    for t in result["tasks"]:
        sample_id = t.sample.sample_id if t.sample else "-"
        table_data.append([
            t.task_id,
            t.task_name,
            sample_id,
            t.status.value,
            f"{t.progress_percent:.0f}%" if t.progress_percent else "-",
            t.current_step or "-",
            t.created_at.strftime("%Y-%m-%d %H:%M") if t.created_at else "-",
        ])

    click.echo(f"任务列表 (共 {result['total']} 个，显示 {offset}-{offset+limit}):")
    click.echo(tabulate(
        table_data,
        headers=["任务ID", "名称", "样本ID", "状态", "进度", "当前步骤", "创建时间"],
        tablefmt="pretty",
    ))


@task.command("show")
@click.argument("task_id")
def task_show(task_id):
    """显示任务详细信息和步骤"""
    manager = TaskManager()
    summary = manager.get_task_summary(task_id)

    if not summary:
        click.echo(f"❌ 任务不存在: {task_id}", err=True)
        sys.exit(1)

    click.echo(f"任务详情: {task_id}")
    click.echo("=" * 60)
    click.echo(f"任务ID:     {summary['task_id']}")
    click.echo(f"名称:       {summary['task_name']}")
    click.echo(f"样本ID:     {summary['sample_id']}")
    click.echo(f"状态:       {summary['status']}")
    click.echo(f"进度:       {summary['progress_percent']:.0f}%" if summary['progress_percent'] else "进度:       -")
    click.echo(f"当前步骤:   {summary['current_step'] or '-'}")
    click.echo(f"优先级:     {summary['priority']}")
    click.echo(f"创建时间:   {summary['created_at'].strftime('%Y-%m-%d %H:%M:%S') if summary['created_at'] else '-'}")
    if summary['started_at']:
        click.echo(f"开始时间:   {summary['started_at'].strftime('%Y-%m-%d %H:%M:%S')}")
    if summary['completed_at']:
        click.echo(f"完成时间:   {summary['completed_at'].strftime('%Y-%m-%d %H:%M:%S')}")
    if summary['failed_at']:
        click.echo(f"失败时间:   {summary['failed_at'].strftime('%Y-%m-%d %H:%M:%S')}")
    if summary['error_message']:
        click.echo(f"错误信息:   {summary['error_message'][:200]}")

    click.echo("\n执行步骤:")
    step_data = []
    for step in summary['steps']:
        step_data.append([
            step['step_name'],
            step['status'],
            step['retry_count'],
            f"{step['duration_seconds']:.1f}s" if step['duration_seconds'] else "-",
            step['error_message'] or "-",
        ])

    click.echo(tabulate(
        step_data,
        headers=["步骤", "状态", "重试次数", "耗时", "错误"],
        tablefmt="grid",
    ))

    if summary.get('output_files'):
        click.echo(f"\n输出文件 ({len(summary['output_files'])}):")
        for f in summary['output_files']:
            click.echo(f"  - {f}")


@task.command("retry")
@click.argument("task_id")
def task_retry(task_id):
    """重试失败的任务"""
    click.echo(f"正在重试任务: {task_id}")

    try:
        result = retry_failed_task(task_id)
        if "error" in result:
            click.echo(f"❌ 重试失败: {result['error']}", err=True)
            sys.exit(1)

        click.echo(f"✅ 任务已提交重试")
        click.echo(f"   状态: {result.get('status')}")

    except Exception as e:
        click.echo(f"❌ 重试失败: {e}", err=True)
        sys.exit(1)


@task.command("cancel")
@click.argument("task_id")
def task_cancel(task_id):
    """取消待执行的任务"""
    manager = TaskManager()
    task = manager.cancel_task(task_id)

    if not task:
        click.echo(f"❌ 无法取消任务 {task_id}（可能已在执行或已取消）", err=True)
        sys.exit(1)

    click.echo(f"✅ 任务已取消: {task_id}")


@app.group()
def retention():
    """数据保留策略管理"""
    pass


@retention.command("cleanup")
@click.option("--dry-run", is_flag=True, help="仅预览，不实际删除")
def retention_cleanup(dry_run):
    """清理过期数据"""
    if dry_run:
        click.echo("正在预览过期数据清理...")
    else:
        click.echo("正在清理过期数据...")
        click.echo("⚠️  此操作不可恢复！")
        if not click.confirm("确定要继续吗？"):
            click.echo("操作已取消")
            return

    try:
        summary = cleanup_expired_data() if not dry_run else \
            RetentionPolicyManager().cleanup_expired_data(dry_run=True)

        click.echo(f"\n清理摘要:")
        click.echo(f"  原始FASTQ删除: {len(summary.get('raw_fastq_deleted', []))} 个")
        click.echo(f"  归档文件删除: {len(summary.get('archives_deleted', []))} 个")
        click.echo(f"  本地文件删除: {len(summary.get('local_files_deleted', []))} 个")
        total_gb = summary.get('total_size_freed', 0) / (1024 * 1024 * 1024)
        click.echo(f"  释放空间: {total_gb:.2f} GB")

        if dry_run:
            click.echo(f"\n⚠️  预览模式，未实际删除任何文件")

    except Exception as e:
        click.echo(f"❌ 清理失败: {e}", err=True)
        sys.exit(1)


@retention.command("policies")
def retention_policies():
    """查看当前保留策略"""
    manager = RetentionPolicyManager()
    policies = manager.get_all_policies()

    click.echo("当前数据保留策略:")
    click.echo("=" * 60)

    for file_type, policy in policies.items():
        if file_type == "default_retention_days":
            continue
        retention = policy.get("retention_days", "N/A")
        if isinstance(retention, int) and retention < 0:
            retention_str = "永久保存"
        elif isinstance(retention, int):
            retention_str = f"{retention} 天"
        else:
            retention_str = str(retention)

        click.echo(f"文件类型: {file_type}")
        click.echo(f"  描述: {policy.get('description', '')}")
        click.echo(f"  保留期: {retention_str}")
        click.echo(f"  处理: {policy.get('action', '')}")
        click.echo()


@retention.command("expired")
def retention_expired():
    """查看过期数据"""
    manager = RetentionPolicyManager()

    click.echo("过期数据检查:")
    click.echo("=" * 60)

    expired_samples = manager.get_expired_raw_data()
    click.echo(f"\n原始FASTQ过期 (超过 {settings.retention.raw_fastq_days} 天):")
    if expired_samples:
        for s in expired_samples:
            click.echo(f"  - {s.sample_id} (创建于: {s.created_at.strftime('%Y-%m-%d')})")
    else:
        click.echo("  无过期数据")

    expired_archives = manager.get_expired_archives()
    click.echo(f"\n过期归档文件:")
    if expired_archives:
        for a in expired_archives:
            click.echo(f"  - {a.object_key} (到期: {a.delete_after.strftime('%Y-%m-%d') if a.delete_after else 'N/A'})")
    else:
        click.echo("  无过期数据")


@app.command("run-pipeline")
@click.option("--sample-id", required=True, help="样本ID")
@click.option("--fastq-r1", required=True, help="R1 FASTQ文件路径")
@click.option("--fastq-r2", required=True, help="R2 FASTQ文件路径")
@click.option("--output-dir", default="./output", help="输出目录")
@click.option("--resume/--no-resume", default=True, help="是否断点续跑")
@click.option("--with-vardict", is_flag=True, help="包含VarDict低频变异检测")
def run_pipeline(sample_id, fastq_r1, fastq_r2, output_dir, resume, with_vardict):
    """快速运行完整分析流程（无需登记样本）"""
    from pipeline.runner import PipelineRunner, PipelineContext
    from pipeline.dag import PipelineDAG
    from config.pipeline_config import PipelineDefinition

    click.echo(f"开始分析样本: {sample_id}")
    click.echo(f"输出目录: {output_dir}")
    click.echo(f"断点续跑: {'启用' if resume else '禁用'}")
    click.echo(f"VarDict: {'启用' if with_vardict else '禁用'}")
    click.echo("=" * 60)

    try:
        output_path = Path(output_dir)
        output_path.mkdir(parents=True, exist_ok=True)

        steps = PipelineDefinition.get_single_sample_pipeline(sample_id, with_vardict)
        dag = PipelineDAG(steps)

        def progress_callback(step_id, step_name, status, progress, message=""):
            bar_length = 40
            filled = int(bar_length * progress)
            bar = "█" * filled + "░" * (bar_length - filled)
            click.echo(f"\r[{bar}] {progress*100:5.1f}% | {step_name} | {status}", nl=False)
            if status == "completed":
                click.echo()

        context = PipelineContext(
            sample_id=sample_id,
            work_dir=output_path,
            temp_dir=output_path / "tmp",
            log_dir=output_path / "logs",
            fastq_r1=fastq_r1,
            fastq_r2=fastq_r2,
            reference_genome=settings.reference.hg38_fasta,
            params={},
        )
        context.temp_dir.mkdir(parents=True, exist_ok=True)
        context.log_dir.mkdir(parents=True, exist_ok=True)

        runner = PipelineRunner(
            dag=dag,
            context=context,
            resume=resume,
            max_parallel=settings.pipeline.max_parallel,
            progress_callback=progress_callback,
        )

        result = runner.run()

        click.echo("\n" + "=" * 60)
        if result.success:
            click.echo("✅ 分析完成！")
            click.echo(f"   总耗时: {result.duration_seconds:.1f} 秒")
            click.echo(f"   输出文件: {len(result.output_files)} 个")

            report_pdf = next((f for f in result.output_files if f.endswith(".pdf")), None)
            report_json = next((f for f in result.output_files if f.endswith(".json")), None)

            if report_pdf:
                click.echo(f"   PDF报告: {report_pdf}")
            if report_json:
                click.echo(f"   JSON结果: {report_json}")

            if result.summary:
                click.echo(f"\n   分析摘要:")
                for k, v in result.summary.items():
                    if k not in ("variants", "step_statuses"):
                        click.echo(f"     {k}: {v}")
        else:
            click.echo(f"❌ 分析失败: {result.error_message}", err=True)
            sys.exit(1)

    except Exception as e:
        click.echo(f"\n❌ 分析失败: {e}", err=True)
        import traceback
        traceback.print_exc()
        sys.exit(1)


def main():
    """CLI入口点"""
    app()


if __name__ == "__main__":
    main()
