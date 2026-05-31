import asyncio
import json
import os
import sys
from pathlib import Path

import click
from rich.console import Console
from rich.table import Table

console = Console()


@click.group()
def main():
    """DB Pool Platform - 云原生基础设施平台命令行工具"""
    pass


@main.command()
@click.option("--host", default="0.0.0.0", help="绑定地址")
@click.option("--port", default=8000, type=int, help="端口号")
@click.option("--reload/--no-reload", default=False, help="热重载模式")
@click.option("--workers", default=1, type=int, help="工作进程数")
def serve(host, port, reload, workers):
    """启动API服务器"""
    import uvicorn
    from app.config.settings import get_settings

    settings = get_settings()
    console.print(f"[green]启动服务器[/green] {host}:{port}")
    console.print(f"[cyan]文档地址[/cyan] http://{host}:{port}/docs")

    uvicorn.run(
        "app.main:app",
        host=host or settings.app_host,
        port=port or settings.app_port,
        reload=reload,
        workers=workers if not reload else 1
    )


@main.command()
@click.argument("path")
@click.option("--output", "-o", help="输出报告文件路径")
@click.option("--format", "-f", type=click.Choice(["json", "html", "text"]), default="json")
def quality(path, output, format):
    """运行代码质量检查"""
    from app.quality.gate import get_quality_gate

    console.print(f"[cyan]分析代码质量[/cyan]: {path}")

    gate = get_quality_gate()
    report = gate.check_quality(path)

    if format == "html":
        content = gate.generate_html_report(report)
        if output:
            with open(output, "w", encoding="utf-8") as f:
                f.write(content)
            console.print(f"[green]HTML报告已写入[/green]: {output}")
        else:
            console.print(content)
    else:
        report_dict = report.to_dict()
        if output:
            with open(output, "w", encoding="utf-8") as f:
                json.dump(report_dict, f, indent=2, ensure_ascii=False)
            console.print(f"[green]报告已写入[/green]: {output}")
        else:
            console.print_json(data=report_dict)

    table = Table(title="质量检查结果")
    table.add_column("级别", style="cyan")
    table.add_column("数量", justify="right")

    counts = report.get_severity_counts()
    for severity, count in counts.items():
        style = "red" if severity in ["blocker", "critical"] else \
                "yellow" if severity == "major" else \
                "white"
        table.add_row(severity.upper(), str(count), style=style)

    console.print(table)
    status = "[green]PASS[/green]" if report.passed else "[red]FAIL[/red]"
    console.print(f"总体状态: {status}")


@main.command()
@click.argument("sbom_file")
@click.option("--output", "-o", help="输出报告文件路径")
def vulnerability(sbom_file, output):
    """分析SBOM文件中的漏洞"""
    from app.vulnerability.analyzer import analyze_sbom

    console.print(f"[cyan]分析SBOM文件[/cyan]: {sbom_file}")

    if not os.path.exists(sbom_file):
        console.print(f"[red]错误[/red]: 文件不存在: {sbom_file}")
        sys.exit(1)

    report = analyze_sbom(sbom_file)

    if output:
        with open(output, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        console.print(f"[green]报告已写入[/green]: {output}")
    else:
        console.print_json(data=report)

    summary = report.get("summary", {})
    table = Table(title="漏洞统计")
    table.add_column("严重程度", style="cyan")
    table.add_column("数量", justify="right")

    breakdown = summary.get("severity_breakdown", {})
    for severity in ["critical", "high", "medium", "low", "unknown"]:
        count = breakdown.get(severity, 0)
        style = "red" if severity in ["critical", "high"] else \
                "yellow" if severity == "medium" else "white"
        table.add_row(severity.upper(), str(count), style=style)

    console.print(table)
    console.print(f"[yellow]受影响组件:[/yellow] {summary.get('total_vulnerable_components', 0)}")
    console.print(f"[yellow]总漏洞数:[/yellow] {summary.get('total_vulnerabilities', 0)}")


@main.command()
@click.argument("target", default="./")
@click.option("--bucket", default="default", help="存储桶名称")
@click.option("--ttl", type=int, help="TTL天数")
def upload(target, bucket, ttl):
    """上传文件到存储"""
    from app.storage.manager import upload_file

    path = Path(target)
    if not path.exists():
        console.print(f"[red]错误[/red]: 文件不存在: {target}")
        sys.exit(1)

    async def do_upload():
        with open(path, "rb") as f:
            content = f.read()

        meta = await upload_file(
            file_name=path.name,
            content=content,
            bucket=bucket,
            ttl_days=ttl
        )
        return meta

    meta = asyncio.run(do_upload())

    console.print(f"[green]上传成功[/green]")
    console.print(f"  文件ID: {meta.file_id}")
    console.print(f"  原文件名: {meta.original_name}")
    console.print(f"  大小: {meta.size} bytes")
    console.print(f"  MD5: {meta.md5_hash}")
    if ttl:
        console.print(f"  TTL: {ttl} 天")


@main.command()
@click.argument("file_id")
@click.option("--bucket", default="default", help="存储桶名称")
@click.option("--output", "-o", help="输出文件路径")
def download(file_id, bucket, output):
    """从存储下载文件"""
    from app.storage.manager import download_file

    async def do_download():
        return await download_file(file_id, bucket)

    stored = asyncio.run(do_download())

    if not stored:
        console.print(f"[red]错误[/red]: 文件不存在: {file_id}")
        sys.exit(1)

    output_path = output or stored.metadata.original_name
    with open(output_path, "wb") as f:
        f.write(stored.content)

    console.print(f"[green]下载成功[/green]: {output_path}")
    console.print(f"  大小: {stored.metadata.size} bytes")


@main.command()
@click.option("--port", default=9090, type=int, help="Prometheus端口")
def metrics(port):
    """导出Prometheus格式指标"""
    from app.monitoring.metrics import get_metrics_collector

    metrics = get_metrics_collector()
    output = metrics.export_prometheus()
    console.print(output)


@main.command()
def status():
    """显示系统状态"""
    from app.config.manager import get_config_manager
    from app.data.database import get_db_manager, PoolEventEmitter, PoolEventType
    from app.monitoring.metrics import get_metrics_collector
    from app.data.read_write_router import get_router_manager
    from app.monitoring.plugin import get_plugin_manager

    table = Table(title="系统状态")
    table.add_column("组件", style="cyan")
    table.add_column("状态", style="green")

    config_mgr = get_config_manager()
    table.add_row("配置管理", f"已加载 {len(config_mgr.get_namespaces())} 命名空间")

    db_mgr = get_db_manager()
    table.add_row("数据库连接池", f"{len(db_mgr.list_pools())} 个连接池")

    metrics = get_metrics_collector()
    table.add_row("指标收集器", "运行中")

    router_mgr = get_router_manager()
    routers = router_mgr.list_routers()
    table.add_row("读写分离路由", f"{len(routers)} 个路由配置")

    plugin_mgr = get_plugin_manager()
    plugins = plugin_mgr.list_all()
    table.add_row("监控插件", f"{len(plugins)} 个插件已加载")

    event_emitter = PoolEventEmitter.get_instance()
    event_stats = event_emitter.get_event_stats()
    table.add_row("连接池事件", f"{event_stats['total_events']} 个事件已触发")

    console.print(table)


@main.group()
def router():
    """读写分离路由管理"""
    pass


@router.command("list")
def router_list():
    """列出所有路由配置"""
    from app.data.read_write_router import get_router_manager

    mgr = get_router_manager()
    routers = mgr.list_routers()

    if not routers:
        console.print("[yellow]暂无路由配置[/yellow]")
        return

    table = Table(title="读写分离路由配置")
    table.add_column("名称", style="cyan")
    table.add_column("策略", style="green")
    table.add_column("副本数", justify="right")

    for name in routers:
        router = mgr.get_router(name)
        if router:
            stats = router.get_stats()
            table.add_row(
                name,
                stats["strategy"],
                str(len(stats["replicas"]))
            )

    console.print(table)


@router.command("stats")
@click.argument("name", default="default")
def router_stats(name):
    """查看路由统计信息"""
    from app.data.read_write_router import get_router_manager

    mgr = get_router_manager()
    router = mgr.get_router(name)
    if not router:
        console.print(f"[red]错误[/red]: 路由 '{name}' 不存在")
        sys.exit(1)

    stats = router.get_stats()
    console.print_json(data=stats)


@main.group()
def plugin():
    """监控插件管理"""
    pass


@plugin.command("list")
def plugin_list():
    """列出所有已注册插件"""
    from app.monitoring.plugin import get_plugin_manager

    mgr = get_plugin_manager()
    plugins = mgr.list_all()

    if not plugins:
        console.print("[yellow]暂无插件[/yellow]")
        return

    table = Table(title="已注册插件")
    table.add_column("名称", style="cyan")
    table.add_column("版本", style="green")
    table.add_column("状态", style="yellow")
    table.add_column("优先级", justify="right")
    table.add_column("描述", style="white")

    for info in plugins:
        status_style = "green" if info.enabled else "dim"
        table.add_row(
            info.name,
            info.version,
            "已启用" if info.enabled else "已禁用",
            str(info.priority),
            info.description or "-"
        )

    console.print(table)


@plugin.command("enable")
@click.argument("name")
def plugin_enable(name):
    """启用插件"""
    from app.monitoring.plugin import get_plugin_manager

    mgr = get_plugin_manager()
    if mgr.enable(name):
        console.print(f"[green]插件 '{name}' 已启用[/green]")
    else:
        console.print(f"[red]错误[/red]: 插件 '{name}' 不存在")


@plugin.command("disable")
@click.argument("name")
def plugin_disable(name):
    """禁用插件"""
    from app.monitoring.plugin import get_plugin_manager

    mgr = get_plugin_manager()
    if mgr.disable(name):
        console.print(f"[green]插件 '{name}' 已禁用[/green]")
    else:
        console.print(f"[red]错误[/red]: 插件 '{name}' 不存在")


@main.group()
def db_events():
    """数据库连接池事件管理"""
    pass


@db_events.command("list")
@click.option("--limit", "-n", default=20, type=int, help="显示事件数量")
@click.option("--pool", help="按连接池过滤")
def db_events_list(limit, pool):
    """查看最近的连接池事件"""
    from app.data.database import PoolEventEmitter

    emitter = PoolEventEmitter.get_instance()
    events = emitter.get_recent_events(pool_name=pool, limit=limit)

    if not events:
        console.print("[yellow]暂无事件[/yellow]")
        return

    table = Table(title="连接池事件")
    table.add_column("时间", style="cyan")
    table.add_column("类型", style="green")
    table.add_column("连接池", style="yellow")
    table.add_column("元数据", style="white")

    for event in events:
        table.add_row(
            event.timestamp.strftime("%H:%M:%S"),
            event.event_type.value,
            event.pool_name,
            str(event.metadata)[:60]
        )

    console.print(table)


@db_events.command("stats")
def db_events_stats():
    """查看事件统计"""
    from app.data.database import PoolEventEmitter

    emitter = PoolEventEmitter.get_instance()
    stats = emitter.get_event_stats()
    console.print_json(data=stats)


@main.command()
@click.argument("template_name")
@click.argument("output_dir")
@click.option("--name", help="项目名称")
@click.option("--interactive/--no-interactive", default=False, help="交互式模式")
def scaffold(template_name, output_dir, name, interactive):
    """从模板生成项目脚手架"""
    from app.scaffold.generator import generate_project

    variables = {}
    if name:
        variables["project_name"] = name
        variables["package_name"] = name.replace("-", "_").replace(" ", "_")

    try:
        if interactive:
            from app.scaffold.generator import interactive_generate
            result = interactive_generate(template_name, output_dir, variables, False)
        else:
            result = generate_project(template_name, output_dir, variables, False)

        console.print(f"[green]项目生成成功[/green]")
        console.print(f"  输出目录: {result['output_dir']}")
        console.print(f"  生成文件数: {result['files_generated']}")
    except ValueError as e:
        console.print(f"[red]错误[/red]: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
