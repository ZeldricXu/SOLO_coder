# genome_pipeline

High-performance genome sequence analysis pipeline written in Rust, combining FASTQ quality control, BWT-FM alignment, Bayesian variant calling, gene annotation, and BAM indexing into a single efficient tool.

## Features

- **FASTQ/FASTA Parsing** - Streaming parser with gzip/bgzip support, automatic Phred encoding detection, paired-end read validation
- **BWT-FM Alignment** - Fast short read alignment with global, local, and splice-aware modes
- **Bayesian Variant Calling** - SNP and indel detection with multi-sample joint genotyping and Mendelian inheritance validation
- **Gene Annotation** - GFF3/GTF support, variant effect prediction (exonic/intronic/UTR/...)
- **Adaptive BAM Indexing** - Density-aware binning with density index layer for fast random access
- **SIMD Acceleration** - SSE/AVX accelerated alignment scoring matrices
- **Rayon Parallel** - Automatic parallel processing across all cores

## Installation

### Bioconda

```bash
conda install -c bioconda -c conda-forge genome-pipeline
```

### crates.io

```bash
cargo install genome_pipeline
```

### Docker

```bash
docker pull ghcr.io/your-org/genome_pipeline:latest
docker run -v /data:/data -v /reference:/reference ghcr.io/your-org/genome_pipeline:latest --help
```

### Singularity

```bash
singularity pull docker://ghcr.io/your-org/genome_pipeline:latest
singularity run -B /data:/data -B /reference:/reference genome_pipeline_latest.sif --help
```

## Quick Start

```bash
# Quality control
genome_pipeline quality-control --input sample_R1.fastq.gz --output qc_stats.json

# Alignment
genome_pipeline align \
  --reference ref.fasta \
  --fastq1 sample_R1.fastq.gz \
  --fastq2 sample_R2.fastq.gz \
  --mode global \
  --output aligned.sam

# Variant calling
genome_pipeline call-variants \
  --bam aligned.bam \
  --reference ref.fasta \
  --output variants.vcf \
  --min-quality 20 \
  --min-depth 10

# Full pipeline
genome_pipeline pipeline \
  --reference ref.fasta \
  --fastq1 sample_R1.fastq.gz \
  --fastq2 sample_R2.fastq.gz \
  --gff annotation.gff3 \
  --output-prefix results/sample1 \
  --align-mode splice
```

## Architecture

```
genome_pipeline/
├── src/
│   ├── io/          # File parsing (FASTQ/FASTA/SAM/BAM/VCF/GFF)
│   ├── alignment/   # BWT-FM index + seed-and-extend alignment
│   │   ├── bwt.rs   # Index construction (trait-based)
│   │   ├── seed.rs  # Seed search (trait-based)
│   │   ├── extend.rs # Alignment extension (trait-based)
│   │   └── mod.rs   # Strategy layer
│   ├── variant/     # Bayesian genotype inference
│   ├── annotation/  # Gene annotation
│   ├── bam/         # BAM indexing and query
│   ├── cli.rs       # Unified CLI definitions
│   └── main.rs      # Entry point
├── tests/           # Integration tests
├── conda/           # Bioconda recipe
└── .github/
    └── workflows/   # CI/CD pipelines
```

## Development

### Prerequisites

```bash
# Install Rust toolchain
rustup install 1.78
rustup default 1.78
rustup component add rustfmt clippy

# Install Git LFS
brew install git-lfs
git lfs install
```

### Build

```bash
cargo build --release --features parallel,simd
```

### Test

```bash
# Download test fixtures
cd test/fixtures
git lfs pull

# Run all tests
cargo test --all-features

# Lint
cargo fmt --all
cargo clippy --all-targets --all-features -- -D warnings
```

### Release

Tag and push to trigger release:

```bash
git tag v0.1.0
git push origin v0.1.0
```

## License

MIT
