FROM python:3.11-slim

WORKDIR /app

# 安装系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    libmariadb-dev \
    libssl-dev \
    libffi-dev \
    libjpeg-dev \
    zlib1g-dev \
    libfreetype6-dev \
    liblcms2-dev \
    libopenjp2-7-dev \
    libtiff-dev \
    tk-dev \
    tcl-dev \
    libxml2-dev \
    libxslt-dev \
    wget \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 安装Playwright系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    libnss3 \
    libnspr4 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libxkbcommon0 \
    libxcomposite1 \
    libxdamage1 \
    libxfixes3 \
    libxrandr2 \
    libgbm1 \
    libpango-1.0-0 \
    libcairo2 \
    libasound2 \
    libatspi2.0-0 \
    libgtk-3-0 \
    libxshmfence1 \
    && rm -rf /var/lib/apt/lists/*

# 创建必要的目录
RUN mkdir -p /app/instance /app/data /app/uploads/reports /app/uploads/snapshots

# 复制requirements
COPY requirements.txt .

# 安装Python依赖
RUN pip install --no-cache-dir -r requirements.txt

# 安装Playwright浏览器
RUN playwright install chromium --with-deps

# 复制应用代码
COPY . .

# 初始化数据库和数据
RUN python -c "from app import create_app; app = create_app(); app.app_context().push(); from app.models import db; db.create_all()"

# 暴露端口
EXPOSE 5000

# 健康检查
HEALTHCHECK CMD curl -f http://localhost:5000/health || exit 1

# 启动命令
CMD ["python", "run.py", "--host", "0.0.0.0", "--port", "5000"]
