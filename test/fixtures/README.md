# Test Fixtures

This directory contains test data used by integration tests. All large binary files
(FASTQ, BAM, FASTA reference genomes) are managed by **Git LFS** (Large File Storage).

## Setup

```bash
# Install Git LFS
git lfs install

# Pull large files
git lfs pull

# Or download from fixtures repository
curl -sL -o small_ref.fasta "https://raw.githubusercontent.com/your-org/genome_pipeline-fixtures/main/small_ref.fasta
curl -sL -o sample_R1.fastq.gz "https://raw.githubusercontent.com/your-org/genome_pipeline-fixtures/main/sample_R1.fastq.gz
curl -sL -o sample_R2.fastq.gz "https://raw.githubusercontent.com/your-org/genome_pipeline-fixtures/main/sample_R2.fastq.gz
curl -sL -o annotation.gff3 "https://raw.githubusercontent.com/your-org/genome_pipeline-fixtures/main/annotation.gff3
```

## Files

| File | Description | Size |
|------|-------------|------|
| `small_ref.fasta` | Small reference genome (chr1 + chr2) | ~1MB |
| `sample_R1.fastq.gz` | Paired-end read 1 | ~50MB |
| `sample_R2.fastq.gz` | Paired-end read 2 | ~50MB |
| `annotation.gff3` | Gene annotation | ~5MB |

## Git LFS Configuration

Files tracked by LFS (see `.gitattributes`):
- `*.fasta`
- `*.fastq.gz`
- `*.bam`
- `*.bai`
- `*.gff3`
- `*.gtf`
- `*.vcf.gz`
