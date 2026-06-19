#!/bin/bash
# ============================================================
# SLURM 作业快速提交工具
# 用法:
#   bash scripts/slurm/submit.sh --env prod --mode forecast --hours 168
#   bash scripts/slurm/submit.sh --env staging --mode test
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TEMPLATE="${SCRIPT_DIR}/submit-nwp.job"

# 默认值
NWP_ENV="${NWP_ENV:-prod}"
NWP_MODE="${NWP_MODE:-forecast}"
NWP_FORECAST_HOURS="${NWP_FORECAST_HOURS:-168}"
NWP_INIT_TIME="${NWP_INIT_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
NWP_DOMAIN="${NWP_DOMAIN:-china}"
PARTITION="${PARTITION:-cpu-hpc}"
ACCOUNT="${ACCOUNT:-nwp-group}"
QOS="${QOS:-normal}"
TIME="${TIME:-24:00:00}"
NODES="${NODES:-1}"
NTASKS="${NTASKS:-1}"
CPUS="${CPUS:-16}"
MEM="${MEM:-128G}"
DRY_RUN=0
EXTRA_ARGS=()

print_help() {
    cat << EOF
NWP SLURM 作业提交工具

用法: $0 [OPTIONS]

选项:
  -e, --env ENV             运行环境: dev/staging/prod (默认: prod)
  -m, --mode MODE           运行模式: forecast/assimilation/cycle/benchmark/verify/visualize/test (默认: forecast)
  -f, --hours HOURS         预报小时数 (默认: 168)
  -t, --initTime TIME       起报时间 ISO格式 (默认: 当前时间)
  -d, --domain DOMAIN       区域: global/china/east-asia (默认: china)
  --nx NX                   网格X格点数 (覆盖配置)
  --ny NY                   网格Y格点数 (覆盖配置)
  --nz NZ                   网格垂直层数 (覆盖配置)
  --dt SECONDS              积分步长秒 (覆盖配置)
  --truncation T            谱截断波数 (覆盖配置)
  --partition PARTITION     SLURM分区 (默认: cpu-hpc)
  --account ACCOUNT         SLURM账户 (默认: nwp-group)
  --qos QOS                 SLURM QOS (默认: normal)
  --time TIME               时间限制 (默认: 24:00:00)
  --nodes NODES             节点数 (默认: 1)
  --ntasks NTASKS           每节点任务数 (默认: 1)
  --cpus CPUS               每任务CPU核数 (默认: 16)
  --mem MEM                 每节点内存 (默认: 128G)
  --executors NUM           Spark Executor数量 (默认: 16)
  --exec-cores CORES        每个Executor CPU核数 (默认: 8)
  --exec-mem MEM            每个Executor内存 (默认: 16g)
  --driver-cores CORES      Driver CPU核数 (默认: 4)
  --driver-mem MEM          Driver内存 (默认: 8g)
  --sif PATH                Singularity镜像路径
  --dry-run                 只打印命令不提交
  -h, --help                显示帮助

环境变量:
  所有选项也可通过环境变量控制，带 NWP_ / SPARK_ / 前缀

示例:
  # 生产环境 - 7天预报
  $0 --env prod --mode forecast --hours 168 --initTime 2024-01-01T00:00:00Z

  # 预发布环境 - smoke test
  $0 --env staging --mode forecast --hours 24 --nx 256 --ny 129 --nz 20

  # 基准测试
  $0 --env dev --mode benchmark --hours 1 --partition debug --time 1:00:00
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            print_help
            exit 0
            ;;
        -e|--env)
            NWP_ENV="$2"; shift 2 ;;
        -m|--mode)
            NWP_MODE="$2"; shift 2 ;;
        -f|--hours)
            NWP_FORECAST_HOURS="$2"; shift 2 ;;
        -t|--initTime)
            NWP_INIT_TIME="$2"; shift 2 ;;
        -d|--domain)
            NWP_DOMAIN="$2"; shift 2 ;;
        --nx)
            NWP_GRID_NX="$2"; shift 2 ;;
        --ny)
            NWP_GRID_NY="$2"; shift 2 ;;
        --nz)
            NWP_GRID_NZ="$2"; shift 2 ;;
        --dt)
            NWP_DYN_DT="$2"; shift 2 ;;
        --truncation)
            NWP_DYN_TRUNCATION="$2"; shift 2 ;;
        --partition)
            PARTITION="$2"; shift 2 ;;
        --account)
            ACCOUNT="$2"; shift 2 ;;
        --qos)
            QOS="$2"; shift 2 ;;
        --time)
            TIME="$2"; shift 2 ;;
        --nodes)
            NODES="$2"; shift 2 ;;
        --ntasks)
            NTASKS="$2"; shift 2 ;;
        --cpus)
            CPUS="$2"; shift 2 ;;
        --mem)
            MEM="$2"; shift 2 ;;
        --executors)
            SPARK_NUM_EXECUTORS="$2"; shift 2 ;;
        --exec-cores)
            SPARK_EXECUTOR_CORES="$2"; shift 2 ;;
        --exec-mem)
            SPARK_EXECUTOR_MEMORY="$2"; shift 2 ;;
        --driver-cores)
            SPARK_DRIVER_CORES="$2"; shift 2 ;;
        --driver-mem)
            SPARK_DRIVER_MEMORY="$2"; shift 2 ;;
        --sif)
            SIF_IMAGE="$2"; shift 2 ;;
        --dry-run)
            DRY_RUN=1; shift ;;
        *)
            EXTRA_ARGS+=("$1"); shift ;;
    esac
done

# 环境变量导出
export NWP_ENV
export NWP_MODE
export NWP_FORECAST_HOURS
export NWP_INIT_TIME
export NWP_DOMAIN
export NWP_GRID_NX
export NWP_GRID_NY
export NWP_GRID_NZ
export NWP_DYN_DT
export NWP_DYN_TRUNCATION
export SPARK_NUM_EXECUTORS
export SPARK_EXECUTOR_CORES
export SPARK_EXECUTOR_MEMORY
export SPARK_DRIVER_CORES
export SPARK_DRIVER_MEMORY
export SIF_IMAGE

# 根据环境自动设置默认值
case "${NWP_ENV}" in
    dev)
        NWP_GRID_NX="${NWP_GRID_NX:-180}"
        NWP_GRID_NY="${NWP_GRID_NY:-91}"
        NWP_GRID_NZ="${NWP_GRID_NZ:-10}"
        NWP_DYN_DT="${NWP_DYN_DT:-120}"
        NWP_DYN_TRUNCATION="${NWP_DYN_TRUNCATION:-85}"
        PARTITION="${PARTITION:-debug}"
        TIME="${TIME:-2:00:00}"
        MEM="${MEM:-32G}"
        CPUS="${CPUS:-8}"
        SPARK_NUM_EXECUTORS="${SPARK_NUM_EXECUTORS:-2}"
        SPARK_EXECUTOR_CORES="${SPARK_EXECUTOR_CORES:-4}"
        SPARK_EXECUTOR_MEMORY="${SPARK_EXECUTOR_MEMORY:-8g}"
        SPARK_DRIVER_MEMORY="${SPARK_DRIVER_MEMORY:-4g}"
        ;;
    staging)
        NWP_GRID_NX="${NWP_GRID_NX:-256}"
        NWP_GRID_NY="${NWP_GRID_NY:-129}"
        NWP_GRID_NZ="${NWP_GRID_NZ:-20}"
        NWP_DYN_DT="${NWP_DYN_DT:-90}"
        NWP_DYN_TRUNCATION="${NWP_DYN_TRUNCATION:-128}"
        PARTITION="${PARTITION:-cpu-hpc}"
        TIME="${TIME:-12:00:00}"
        MEM="${MEM:-64G}"
        CPUS="${CPUS:-16}"
        SPARK_NUM_EXECUTORS="${SPARK_NUM_EXECUTORS:-8}"
        SPARK_EXECUTOR_CORES="${SPARK_EXECUTOR_CORES:-8}"
        SPARK_EXECUTOR_MEMORY="${SPARK_EXECUTOR_MEMORY:-16g}"
        SPARK_DRIVER_MEMORY="${SPARK_DRIVER_MEMORY:-8g}"
        ;;
    prod)
        NWP_GRID_NX="${NWP_GRID_NX:-720}"
        NWP_GRID_NY="${NWP_GRID_NY:-361}"
        NWP_GRID_NZ="${NWP_GRID_NZ:-60}"
        NWP_DYN_DT="${NWP_DYN_DT:-30}"
        NWP_DYN_TRUNCATION="${NWP_DYN_TRUNCATION:-213}"
        PARTITION="${PARTITION:-cpu-hpc}"
        TIME="${TIME:-24:00:00}"
        MEM="${MEM:-128G}"
        CPUS="${CPUS:-16}"
        SPARK_NUM_EXECUTORS="${SPARK_NUM_EXECUTORS:-16}"
        SPARK_EXECUTOR_CORES="${SPARK_EXECUTOR_CORES:-8}"
        SPARK_EXECUTOR_MEMORY="${SPARK_EXECUTOR_MEMORY:-16g}"
        SPARK_DRIVER_MEMORY="${SPARK_DRIVER_MEMORY:-8g}"
        ;;
    *)
        echo "[ERROR] 未知环境: ${NWP_ENV}"
        exit 1
        ;;
esac

export NWP_GRID_NX
export NWP_GRID_NY
export NWP_GRID_NZ
export NWP_DYN_DT
export NWP_DYN_TRUNCATION
export SPARK_NUM_EXECUTORS
export SPARK_EXECUTOR_CORES
export SPARK_EXECUTOR_MEMORY
export SPARK_DRIVER_CORES
export SPARK_DRIVER_MEMORY

# 作业名
JOB_NAME="nwp-${NWP_MODE}-${NWP_ENV}-${NWP_INIT_TIME//[:-]/}"

echo "========================================================"
echo "NWP SLURM 作业提交"
echo "========================================================"
echo "作业名:   ${JOB_NAME}"
echo "环境:     ${NWP_ENV}"
echo "模式:     ${NWP_MODE}"
echo "区域:     ${NWP_DOMAIN}"
echo "起报时间: ${NWP_INIT_TIME}"
echo "预报时长: ${NWP_FORECAST_HOURS}小时"
echo "网格:     ${NWP_GRID_NX}x${NWP_GRID_NY}x${NWP_GRID_NZ}"
echo "步长:     ${NWP_DYN_DT}s"
echo "谱截断:   T${NWP_DYN_TRUNCATION}"
echo ""
echo "SLURM:"
echo "  分区:   ${PARTITION}"
echo "  账户:   ${ACCOUNT}"
echo "  QOS:    ${QOS}"
echo "  时间:   ${TIME}"
echo "  节点:   ${NODES}"
echo "  CPU:    ${CPUS}"
echo "  内存:   ${MEM}"
echo ""
echo "Spark:"
echo "  Executors: ${SPARK_NUM_EXECUTORS} x ${SPARK_EXECUTOR_CORES} cores, ${SPARK_EXECUTOR_MEMORY}"
echo "  Driver:    ${SPARK_DRIVER_CORES} cores, ${SPARK_DRIVER_MEMORY}"
echo "========================================================"

# 构建 sbatch 命令
SBATCH_ARGS=(
    "--job-name=${JOB_NAME}"
    "--partition=${PARTITION}"
    "--account=${ACCOUNT}"
    "--qos=${QOS}"
    "--time=${TIME}"
    "--nodes=${NODES}"
    "--ntasks-per-node=${NTASKS}"
    "--cpus-per-task=${CPUS}"
    "--mem=${MEM}"
    "--output=/nwp/logs/${JOB_NAME}-%j.out"
    "--error=/nwp/logs/${JOB_NAME}-%j.err"
    "--export=ALL"
    "--requeue"
)

# 环境变量导出
EXPORT_VARS=(
    NWP_ENV NWP_MODE NWP_DOMAIN NWP_INIT_TIME NWP_FORECAST_HOURS
    NWP_GRID_NX NWP_GRID_NY NWP_GRID_NZ NWP_GRID_DX NWP_GRID_DY
    NWP_DYN_DT NWP_DYN_TRUNCATION
    SPARK_QUEUE SPARK_NUM_EXECUTORS SPARK_EXECUTOR_CORES
    SPARK_EXECUTOR_MEMORY SPARK_DRIVER_CORES SPARK_DRIVER_MEMORY
    SIF_IMAGE HDFS_BASE_PATH HDFS_OUTPUT_DIR
)

EXPORT_STR=""
for var in "${EXPORT_VARS[@]}"; do
    if [ -n "${!var}" ]; then
        EXPORT_STR="${EXPORT_STR},${var}=${!var}"
    fi
done
if [ -n "${EXPORT_STR}" ]; then
    SBATCH_ARGS+=("--export=ALL${EXPORT_STR}")
fi

echo ""
echo "SBATCH命令:"
echo "  sbatch ${SBATCH_ARGS[*]} ${TEMPLATE} ${EXTRA_ARGS[*]}"
echo ""

if [ "${DRY_RUN}" -eq 1 ]; then
    echo "[DRY RUN] 作业未提交"
    exit 0
fi

# 确认
read -p "确认提交作业? [y/N]: " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "已取消"
    exit 1
fi

# 提交
JOB_ID=$(sbatch --parsable "${SBATCH_ARGS[@]}" "${TEMPLATE}" "${EXTRA_ARGS[@]}")

echo ""
echo "✅ 作业已提交，作业ID: ${JOB_ID}"
echo ""
echo "查看状态:"
echo "  squeue -j ${JOB_ID}"
echo "  scontrol show job ${JOB_ID}"
echo ""
echo "查看输出:"
echo "  tail -f /nwp/logs/${JOB_NAME}-${JOB_ID}.out"
echo ""
echo "取消作业:"
echo "  scancel ${JOB_ID}"
