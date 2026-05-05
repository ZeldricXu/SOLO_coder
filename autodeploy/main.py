#!/usr/bin/env python3
"""
AutoDeploy 命令行入口
"""

import argparse
import sys
import os
from pathlib import Path
from typing import Optional

from .__init__ import __version__
from .core import DeployOrchestrator, DeployStatus, StepStatus
from .config import ConfigParser, ConfigValidator


def print_banner():
    """
    打印欢迎横幅
    """
    banner = """
╔══════════════════════════════════════════════════════════════╗
║                    AutoDeploy v%s                           ║
║           多环境自动化部署与配置管理工具                        ║
╚══════════════════════════════════════════════════════════════╝
""" % __version__
    print(banner)


def create_deploy_parser(subparsers):
    """
    创建部署命令解析器
    """
    deploy_parser = subparsers.add_parser(
        "run",
        help="执行部署",
        description="执行指定环境的自动化部署"
    )
    
    deploy_parser.add_argument(
        "--env", "-e",
        required=True,
        help="目标环境名称（如 production, staging, development）"
    )
    
    deploy_parser.add_argument(
        "--build-command", "-b",
        help="覆盖配置中的构建命令"
    )
    
    deploy_parser.add_argument(
        "--skip-build",
        action="store_true",
        help="跳过构建步骤"
    )
    
    deploy_parser.add_argument(
        "--max-concurrent", "-c",
        type=int,
        default=3,
        help="最大并发部署服务器数（默认：3）"
    )
    
    deploy_parser.add_argument(
        "--config-dir",
        help="配置文件目录（默认：当前目录下的 configs）"
    )
    
    deploy_parser.add_argument(
        "--log-dir",
        help="日志目录（默认：当前目录下的 logs）"
    )
    
    deploy_parser.add_argument(
        "--work-dir",
        help="工作目录（默认：当前目录）"
    )
    
    deploy_parser.set_defaults(func=handle_deploy)


def create_config_parser(subparsers):
    """
    创建配置管理命令解析器
    """
    config_parser = subparsers.add_parser(
        "config",
        help="配置管理",
        description="管理部署配置文件"
    )
    
    config_subparsers = config_parser.add_subparsers(
        dest="config_action",
        help="配置操作"
    )
    
    list_parser = config_subparsers.add_parser(
        "list",
        help="列出所有环境配置"
    )
    list_parser.set_defaults(func=handle_config_list)
    
    add_parser = config_subparsers.add_parser(
        "add",
        help="添加新的环境配置（交互式）"
    )
    add_parser.add_argument(
        "--env", "-e",
        required=True,
        help="环境名称"
    )
    add_parser.add_argument(
        "--config-dir",
        help="配置文件目录"
    )
    add_parser.set_defaults(func=handle_config_add)
    
    show_parser = config_subparsers.add_parser(
        "show",
        help="显示指定环境的配置"
    )
    show_parser.add_argument(
        "--env", "-e",
        required=True,
        help="环境名称"
    )
    show_parser.add_argument(
        "--config-dir",
        help="配置文件目录"
    )
    show_parser.set_defaults(func=handle_config_show)


def create_history_parser(subparsers):
    """
    创建历史查询命令解析器
    """
    history_parser = subparsers.add_parser(
        "history",
        help="查询部署历史",
        description="查看部署历史记录"
    )
    
    history_parser.add_argument(
        "--env", "-e",
        help="环境名称筛选（可选）"
    )
    
    history_parser.add_argument(
        "--limit", "-l",
        type=int,
        default=20,
        help="显示记录数量（默认：20）"
    )
    
    history_parser.add_argument(
        "--log-dir",
        help="日志目录"
    )
    
    history_parser.set_defaults(func=handle_history)


def create_version_parser(subparsers):
    """
    创建版本命令解析器
    """
    version_parser = subparsers.add_parser(
        "version",
        help="显示版本信息"
    )
    version_parser.set_defaults(func=handle_version)


def handle_deploy(args):
    """
    处理部署命令
    """
    print_banner()
    
    orchestrator = DeployOrchestrator(
        config_dir=args.config_dir,
        log_dir=args.log_dir,
        work_dir=args.work_dir
    )
    
    def step_callback(step_name, status, message):
        status_icon = {
            StepStatus.PENDING: "⏳",
            StepStatus.IN_PROGRESS: "🔄",
            StepStatus.SUCCESS: "✅",
            StepStatus.FAILED: "❌",
            StepStatus.SKIPPED: "⏭️",
            StepStatus.ROLLED_BACK: "↩️"
        }.get(status, "❓")
        
        print(f"  [{status_icon}] {step_name}: {message}")
    
    def server_callback(server_host, status, message):
        status_icon = {
            DeployStatus.PENDING: "⏳",
            DeployStatus.IN_PROGRESS: "🔄",
            DeployStatus.COMPLETED: "✅",
            DeployStatus.PARTIAL_SUCCESS: "⚠️",
            DeployStatus.FAILED: "❌",
            DeployStatus.ROLLED_BACK: "↩️"
        }.get(status, "❓")
        
        print(f"\n  [{status_icon}] {server_host}: {message}")
    
    orchestrator.set_step_callback(step_callback)
    orchestrator.set_server_callback(server_callback)
    
    print(f"🚀 开始部署环境: {args.env}")
    print(f"   并发数: {args.max_concurrent}")
    if args.skip_build:
        print(f"   跳过构建: 是")
    print("-" * 60)
    
    try:
        result = orchestrator.deploy(
            env_name=args.env,
            build_override=args.build_command,
            max_concurrent=args.max_concurrent,
            skip_build=args.skip_build
        )
        
        print("\n" + "=" * 60)
        print("📊 部署结果摘要")
        print("=" * 60)
        
        print(f"\n部署ID: {result.deploy_id}")
        print(f"环境: {result.env_name}")
        print(f"状态: {result.status.value}")
        print(f"是否成功: {'是' if result.success else '否'}")
        
        if result.summary:
            print(f"服务器总数: {result.summary.get('total_servers', 0)}")
            print(f"成功: {result.summary.get('success_count', 0)}")
            print(f"失败: {result.summary.get('failed_count', 0)}")
        
        if result.server_results:
            print(f"\n各服务器部署详情:")
            for server_result in result.server_results:
                status_icon = "✅" if server_result.success else "❌"
                rollback_info = " (已回滚)" if server_result.rollback_performed else ""
                print(f"  {status_icon} {server_result.server_host}: {server_result.status.value}{rollback_info}")
                
                for step in server_result.steps:
                    step_icon = "✅" if step.status == StepStatus.SUCCESS else "❌"
                    print(f"      {step_icon} {step.step_name}: {step.message}")
        
        if result.error_message:
            print(f"\n错误信息: {result.error_message}")
        
        return 0 if result.success else 1
        
    except Exception as e:
        print(f"\n❌ 部署过程发生致命错误: {str(e)}")
        import traceback
        traceback.print_exc()
        return 1


def handle_config_list(args):
    """
    处理配置列表命令
    """
    config_parser = ConfigParser(config_dir=args.config_dir if hasattr(args, 'config_dir') else None)
    environments = config_parser.list_environments()
    
    print_banner()
    print("📋 可用环境配置:")
    print("-" * 40)
    
    if environments:
        for env in environments:
            print(f"  ✅ {env}")
    else:
        print("  ❌ 没有找到任何环境配置")
        print(f"  💡 配置目录: {config_parser.config_dir}")
    
    return 0


def handle_config_add(args):
    """
    处理添加配置命令（交互式）
    """
    print_banner()
    print(f"➕ 创建新环境配置: {args.env}")
    print("-" * 40)
    
    config_dir = Path(args.config_dir) if args.config_dir else Path("configs")
    config_dir.mkdir(parents=True, exist_ok=True)
    
    config_file = config_dir / f"{args.env}.yaml"
    
    if config_file.exists():
        print(f"❌ 配置文件已存在: {config_file}")
        return 1
    
    print("\n请输入配置信息（按 Enter 使用默认值）:")
    
    servers = []
    while True:
        print(f"\n--- 服务器配置 ({len(servers) + 1}) ---")
        host = input("  主机地址: ").strip()
        if not host:
            break
        
        port = input("  SSH端口 [22]: ").strip() or "22"
        user = input("  SSH用户名 [root]: ").strip() or "root"
        key_file = input("  密钥文件路径（可选）: ").strip() or None
        password = input("  SSH密码（可选，建议使用密钥）: ").strip() or None
        
        server = {
            "host": host,
            "port": int(port),
            "user": user
        }
        if key_file:
            server["key_file"] = key_file
        if password:
            server["password"] = password
        
        servers.append(server)
        
        more = input("\n添加更多服务器？(y/n) [n]: ").strip().lower()
        if more != "y":
            break
    
    if not servers:
        print("❌ 必须至少配置一台服务器")
        return 1
    
    build_command = input("\n构建命令 [npm run build]: ").strip() or "npm run build"
    build_output = input("构建输出目录 [./dist]: ").strip() or "./dist"
    deploy_path = input("远程部署路径 [/var/www/app]: ").strip() or "/var/www/app"
    
    start_command = input("\n服务启动命令 [systemctl restart app-service]: ").strip() or "systemctl restart app-service"
    stop_command = input("服务停止命令（可选）: ").strip() or None
    
    health_check_type = input("\n健康检查类型 (http/process/none) [http]: ").strip().lower() or "http"
    health_check_config = None
    
    if health_check_type == "http":
        health_url = input("  健康检查URL [http://localhost:8080/health]: ").strip() or "http://localhost:8080/health"
        health_timeout = input("  超时时间（秒）[30]: ").strip() or "30"
        health_check_config = {
            "type": "http",
            "url": health_url,
            "timeout": int(health_timeout)
        }
    elif health_check_type == "process":
        process_name = input("  进程名称: ").strip()
        if process_name:
            health_check_config = {
                "type": "process",
                "process_name": process_name
            }
    
    rollback_enabled = input("\n启用回滚功能？(y/n) [y]: ").strip().lower() != "n"
    start_delay = input("服务启动后等待时间（秒）[5]: ").strip() or "5"
    
    config = {
        "env_name": args.env,
        "servers": servers,
        "build_command": build_command,
        "build_output": build_output,
        "deploy_path": deploy_path,
        "start_command": start_command,
        "rollback_enabled": rollback_enabled,
        "start_delay": int(start_delay)
    }
    
    if stop_command:
        config["stop_command"] = stop_command
    
    if health_check_config:
        config["health_check"] = health_check_config
    
    import yaml
    
    with open(config_file, 'w', encoding='utf-8') as f:
        yaml.dump(config, f, allow_unicode=True, default_flow_style=False, sort_keys=False)
    
    print(f"\n✅ 配置文件已创建: {config_file}")
    print(f"\n💡 使用以下命令执行部署:")
    print(f"   autodeploy run --env {args.env}")
    
    return 0


def handle_config_show(args):
    """
    处理显示配置命令
    """
    config_parser = ConfigParser(config_dir=args.config_dir if hasattr(args, 'config_dir') else None)
    
    print_banner()
    print(f"📄 环境配置: {args.env}")
    print("-" * 60)
    
    try:
        config = config_parser.parse(args.env)
        
        import json
        print(json.dumps(config, ensure_ascii=False, indent=2))
        
        return 0
    except FileNotFoundError:
        print(f"❌ 配置文件不存在: {args.env}")
        return 1
    except Exception as e:
        print(f"❌ 读取配置失败: {str(e)}")
        return 1


def handle_history(args):
    """
    处理历史查询命令
    """
    orchestrator = DeployOrchestrator(log_dir=args.log_dir)
    
    print_banner()
    print("📜 部署历史记录")
    if args.env:
        print(f"   环境筛选: {args.env}")
    print(f"   显示数量: {args.limit}")
    print("-" * 80)
    
    records = orchestrator.get_deploy_history(
        env_name=args.env,
        limit=args.limit
    )
    
    if not records:
        print("  ❌ 没有找到部署记录")
        return 0
    
    print(f"\n{'部署ID':<25} {'环境':<12} {'状态':<16} {'触发时间':<25} {'持续时间'}")
    print("-" * 80)
    
    for record in records:
        status_icon = {
            "completed": "✅",
            "partial_success": "⚠️",
            "failed": "❌",
            "rolled_back": "↩️",
            "in_progress": "🔄"
        }.get(record.status, "❓")
        
        print(f"{record.deploy_id:<25} {record.env_name:<12} {status_icon} {record.status:<14} "
              f"{record.trigger_time:<25} {record.total_duration or '-'}")
    
    return 0


def handle_version(args):
    """
    处理版本命令
    """
    print(f"AutoDeploy v{__version__}")
    return 0


def main():
    """
    主函数
    """
    parser = argparse.ArgumentParser(
        prog="autodeploy",
        description="AutoDeploy - 多环境自动化部署与配置管理工具",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    
    subparsers = parser.add_subparsers(
        title="可用命令",
        dest="command",
        help="命令帮助"
    )
    
    create_deploy_parser(subparsers)
    create_config_parser(subparsers)
    create_history_parser(subparsers)
    create_version_parser(subparsers)
    
    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(0)
    
    args = parser.parse_args()
    
    if hasattr(args, 'func'):
        sys.exit(args.func(args))
    else:
        parser.print_help()
        sys.exit(0)


if __name__ == "__main__":
    main()
