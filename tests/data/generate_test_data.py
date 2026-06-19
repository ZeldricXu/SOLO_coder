import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

import gzip
import random
import os
from typing import Dict, List, Tuple

REGION_START = 43044000
REGION_END = 43048000
REGION_LENGTH = REGION_END - REGION_START
READ_LENGTH = 150
COVERAGE = 50
INSERT_SIZE = 350
INSERT_STD = 50
ERROR_RATE = 0.001

KNOWN_VARIANTS: List[Dict] = [
    {
        "position": 43045629,
        "ref": "T",
        "alt": "C",
        "type": "SNV",
        "hgvsc": "c.5266T>C",
    },
    {
        "position": 43047643,
        "ref": "GA",
        "alt": "G",
        "type": "DEL",
        "hgvsc": "c.3256delA",
    },
    {
        "position": 43067607,
        "ref": "C",
        "alt": "CGAAAGCGGTACATGCCTAAGATTGTCACTCA",
        "type": "INS",
        "hgvsc": "c.1010_1011ins30",
    },
]

BASE_QUALITY = "IIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIII"


def generate_reference_sequence() -> str:
    random.seed(42)
    bases = ["A", "C", "G", "T"]
    extended_length = REGION_LENGTH + 4000
    seq = "".join(random.choices(bases, k=extended_length))
    return seq


def apply_variants(reference: str, variant_allele_frequency: float = 0.5) -> Tuple[str, Dict[int, str]]:
    alt_reference = list(reference)
    variant_map: Dict[int, str] = {}

    for variant in KNOWN_VARIANTS:
        rel_pos = variant["position"] - REGION_START

        if variant["type"] == "SNV":
            if 0 <= rel_pos < len(alt_reference):
                alt_reference[rel_pos] = variant["alt"]
                variant_map[variant["position"]] = variant["alt"]

        elif variant["type"] == "DEL":
            ref_len = len(variant["ref"])
            if 0 <= rel_pos < len(alt_reference) - ref_len:
                for i in range(1, ref_len):
                    alt_reference[rel_pos + i] = ""
                variant_map[variant["position"]] = "DEL_" + str(ref_len - 1)

        elif variant["type"] == "INS":
            ins_bases = variant["alt"][1:]
            if 0 <= rel_pos < len(alt_reference):
                alt_reference[rel_pos] = variant["ref"] + ins_bases
                variant_map[variant["position"]] = "INS_" + ins_bases

    return "".join(alt_reference), variant_map


def generate_read(ref_seq: str, alt_seq: str, start: int, strand: str, is_alt: bool = False) -> Tuple[str, str]:
    seq_template = alt_seq if is_alt else ref_seq
    read = seq_template[start:start + READ_LENGTH]

    if len(read) < READ_LENGTH:
        read = read + "N" * (READ_LENGTH - len(read))

    mutated_read = list(read)
    for i in range(len(mutated_read)):
        if random.random() < ERROR_RATE:
            orig = mutated_read[i]
            bases = [b for b in ["A", "C", "G", "T"] if b != orig]
            mutated_read[i] = random.choice(bases) if bases else orig

    final_read = "".join(mutated_read)
    if strand == "R":
        comp = {"A": "T", "T": "A", "C": "G", "G": "C", "N": "N"}
        final_read = "".join(comp.get(b, "N") for b in reversed(final_read))

    return final_read, BASE_QUALITY[:len(final_read)]


def generate_paired_reads(ref_seq: str, alt_seq: str, read_id: int) -> Tuple[str, str, str, str]:
    rel_positions = list(range(0, len(ref_seq) - INSERT_SIZE - READ_LENGTH))

    if not rel_positions:
        return "", "", "", ""

    start = random.choice(rel_positions)
    is_alt = random.random() < 0.5

    r1_seq, r1_qual = generate_read(ref_seq, alt_seq, start, "F", is_alt)
    r2_start = start + INSERT_SIZE - READ_LENGTH
    r2_seq, r2_qual = generate_read(ref_seq, alt_seq, r2_start, "R", is_alt)

    r1_name = f"@READ_{read_id}_R1_S{REGION_START + start}"
    r2_name = f"@READ_{read_id}_R2_S{REGION_START + r2_start}"

    return r1_name, r1_seq, r1_qual, r2_name, r2_seq, r2_qual


def write_fastq_gz(path: Path, entries: List[Tuple[str, str, str]]) -> None:
    with gzip.open(path, "wt") as f:
        for name, seq, qual in entries:
            if not name or not seq:
                continue
            f.write(f"{name}\n{seq}\n+\n{qual}\n")


def write_fasta(path: Path, seq: str, name: str = "chr17") -> None:
    with open(path, "w") as f:
        f.write(f">{name}\n")
        for i in range(0, len(seq), 80):
            f.write(seq[i:i + 80] + "\n")


def write_vcf(path: Path) -> None:
    with open(path, "w") as f:
        f.write("##fileformat=VCFv4.2\n")
        f.write("##contig=<ID=chr17,length=83257441>\n")
        f.write('##INFO=<ID=BRCA1,Number=0,Type=Flag,Description="Known BRCA1 variant">\n')
        f.write("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n")

        for v in KNOWN_VARIANTS:
            if REGION_START <= v["position"] <= REGION_END + 20000:
                f.write(f"chr17\t{v['position']}\t.\t{v['ref']}\t{v['alt']}\t999\tPASS\tBRCA1\n")


def main() -> None:
    data_dir = Path(__file__).parent
    data_dir.mkdir(parents=True, exist_ok=True)

    print("Generating reference sequence...")
    ref_seq = generate_reference_sequence()
    alt_seq, variant_map = apply_variants(ref_seq)

    genome_path = data_dir / "genome_reference.fa"
    write_fasta(genome_path, ref_seq)
    print(f"Written reference: {genome_path}")

    known_vcf_path = data_dir / "known_brca1_variants.vcf"
    write_vcf(known_vcf_path)
    print(f"Written known variants VCF: {known_vcf_path}")

    total_bases = len(ref_seq)
    reads_needed = (COVERAGE * total_bases) // (2 * READ_LENGTH)
    print(f"Generating {reads_needed} paired-end reads ({COVERAGE}x coverage)...")

    r1_entries = []
    r2_entries = []

    random.seed(2024)
    for i in range(reads_needed):
        result = generate_paired_reads(ref_seq, alt_seq, i)
        if len(result) == 6 and result[1]:
            r1_name, r1_seq, r1_qual, r2_name, r2_seq, r2_qual = result
            r1_entries.append((r1_name, r1_seq, r1_qual))
            r2_entries.append((r2_name, r2_seq, r2_qual))

    r1_path = data_dir / "test_R1.fastq.gz"
    r2_path = data_dir / "test_R2.fastq.gz"

    write_fastq_gz(r1_path, r1_entries)
    write_fastq_gz(r2_path, r2_entries)

    print(f"Written R1: {r1_path} ({len(r1_entries)} reads)")
    print(f"Written R2: {r2_path} ({len(r2_entries)} reads)")
    print(f"Total reads: {len(r1_entries)} pairs")
    print("Applied variants:")
    for pos, alt in sorted(variant_map.items()):
        print(f"  Position {pos}: {alt}")

    bwa_index_stub = data_dir / "bwa_index_stub.txt"
    bwa_index_stub.write_text(
        "Run `bwa index genome_reference.fa` to create BWA index files.\n"
        "This stub file is used as a marker in test environments.\n"
    )
    print(f"Written BWA index stub: {bwa_index_stub}")


if __name__ == "__main__":
    main()
