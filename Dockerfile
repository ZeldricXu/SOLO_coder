FROM python:3.11-slim-bookworm

LABEL maintainer="bioinformatics-team"
LABEL description="Genome Variant Pipeline - 基因组变异检测与注释自动化分析流程"
LABEL version="1.0.0"

ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    openjdk-17-jre-headless \
    perl \
    zlib1g-dev \
    libbz2-dev \
    liblzma-dev \
    libcurl4-openssl-dev \
    libssl-dev \
    wget \
    curl \
    git \
    less \
    && rm -rf /var/lib/apt/lists/*

RUN wget -P /tmp https://github.com/samtools/samtools/releases/download/1.19/samtools-1.19.tar.bz2 && \
    tar -xjf /tmp/samtools-1.19.tar.bz2 -C /tmp && \
    cd /tmp/samtools-1.19 && ./configure --prefix=/usr && make && make install && \
    rm -rf /tmp/samtools*

RUN wget -P /tmp https://github.com/lh3/bwa/releases/download/v0.7.17/bwa-0.7.17.tar.bz2 && \
    tar -xjf /tmp/bwa-0.7.17.tar.bz2 -C /tmp && \
    cd /tmp/bwa-0.7.17 && make && cp bwa /usr/bin/ && \
    rm -rf /tmp/bwa*

RUN wget -P /tmp https://www.bioinformatics.babraham.ac.uk/projects/fastqc/fastqc_v0.12.1.zip && \
    unzip /tmp/fastqc_v0.12.1.zip -d /opt && \
    chmod +x /opt/FastQC/fastqc && \
    ln -s /opt/FastQC/fastqc /usr/bin/fastqc && \
    rm /tmp/fastqc_v0.12.1.zip

RUN wget -P /tmp https://github.com/OpenGene/fastp/archive/refs/tags/v0.23.4.tar.gz && \
    tar -xzf /tmp/v0.23.4.tar.gz -C /tmp && \
    cd /tmp/fastp-0.23.4 && make && cp fastp /usr/bin/ && \
    rm -rf /tmp/fastp*

RUN wget -P /usr/bin https://github.com/broadinstitute/gatk/releases/download/4.4.0.0/gatk-4.4.0.0.zip && \
    cd /usr/bin && unzip gatk-4.4.0.0.zip && \
    ln -s /usr/bin/gatk-4.4.0.0/gatk /usr/bin/gatk && \
    rm /usr/bin/gatk-4.4.0.0.zip

RUN wget -P /opt https://github.com/AstraZeneca-NGS/VarDictJava/releases/download/v1.8.2/VarDict-1.8.2.tar && \
    tar -xf /opt/VarDict-1.8.2.tar -C /opt && \
    ln -s /opt/VarDict-1.8.2/bin/VarDict /usr/bin/vardict && \
    rm /opt/VarDict-1.8.2.tar

RUN wget -P /opt https://github.com/broadinstitute/picard/releases/download/3.1.0/picard.jar && \
    ln -s /opt/picard.jar /usr/bin/picard.jar

RUN wget -P /tmp https://github.com/Ensembl/ensembl-vep/archive/refs/tags/112.0.tar.gz && \
    tar -xzf /tmp/112.0.tar.gz -C /opt && \
    cd /opt/ensembl-vep-112.0 && \
    perl INSTALL.pl --AUTO ap --SPECIES homo_sapiens --ASSEMBLY GRCh38 --PLUGINS all && \
    ln -s /opt/ensembl-vep-112.0/vep /usr/bin/vep && \
    rm -rf /tmp/112.0.tar.gz

RUN wget -P /opt https://sourceforge.net/projects/snpeff/files/snpEff_v5_2.zip && \
    unzip /opt/snpEff_v5_2.zip -d /opt && \
    rm /opt/snpEff_v5_2.zip

COPY requirements.txt /app/
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY . /app/
RUN pip install --no-cache-dir -e /app/

RUN mkdir -p /data/work /reference/hg38 /annotation /data/output

HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 \
    CMD python -c "from db.database import init_db; init_db()" || exit 1

EXPOSE 8000

ENTRYPOINT ["gvp-pipeline"]
CMD ["--help"]
