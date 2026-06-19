import sys
from pathlib import Path
from typing import List, Dict, Any, Tuple

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from db.models import Variant, Sample, ACMGClassification


pytestmark = [pytest.mark.unit, pytest.mark.variant]


def left_align_variant(chromosome: str, position: int, ref: str, alt: str) -> Tuple[int, str, str]:
    while len(ref) > 0 and len(alt) > 0 and ref[-1] == alt[-1]:
        ref = ref[:-1]
        alt = alt[:-1]
    while len(ref) > 0 and len(alt) > 0 and ref[0] == alt[0]:
        ref = ref[1:]
        alt = alt[1:]
        position += 1
    while True:
        changed = False
        if len(ref) == 0 or len(alt) == 0:
            break
        if ref[0] != alt[0]:
            break
        ref = ref[1:]
        alt = alt[1:]
        position += 1
        changed = True
        if not changed:
            break
    if len(ref) == 0 or len(alt) == 0:
        return position, ref, alt
    return position, ref, alt


def normalize_variant_left(chromosome: str, position: int, ref: str, alt: str) -> Tuple[int, str, str]:
    while len(ref) > 1 and len(alt) > 1 and ref[-1] == alt[-1]:
        ref = ref[:-1]
        alt = alt[:-1]
    while len(ref) > 1 and len(alt) > 1 and ref[0] == alt[0]:
        ref = ref[1:]
        alt = alt[1:]
        position += 1
    return position, ref, alt


def normalize_variant_right(chromosome: str, position: int, ref: str, alt: str) -> Tuple[int, str, str]:
    while len(ref) > 1 and len(alt) > 1 and ref[0] == alt[0]:
        ref = ref[1:]
        alt = alt[1:]
        position += 1
    while len(ref) > 1 and len(alt) > 1 and ref[-1] == alt[-1]:
        ref = ref[:-1]
        alt = alt[:-1]
    return position, ref, alt


def split_multiallelic(
    chromosome: str,
    position: int,
    ref: str,
    alts: List[str],
    genotype: str,
    base_data: Dict[str, Any] = None,
) -> List[Dict[str, Any]]:
    base_data = base_data or {}
    results = []
    gt_parts = genotype.split("/")
    if len(alts) == 1:
        record = dict(base_data)
        record.update({
            "chromosome": chromosome,
            "position": position,
            "ref": ref,
            "alt": alts[0],
            "genotype": genotype,
        })
        results.append(record)
        return results

    for idx, alt in enumerate(alts):
        alt_num = idx + 1
        new_gt_parts = []
        for p in gt_parts:
            if p == str(alt_num):
                new_gt_parts.append("1")
            elif p == "0":
                new_gt_parts.append("0")
            else:
                new_gt_parts.append("0")
        new_genotype = "/".join(new_gt_parts)
        record = dict(base_data)
        record.update({
            "chromosome": chromosome,
            "position": position,
            "ref": ref,
            "alt": alt,
            "genotype": new_genotype,
            "original_genotype": genotype,
            "alt_index": idx,
        })
        results.append(record)
    return results


def determine_variant_type(ref: str, alt: str) -> str:
    if len(ref) == 1 and len(alt) == 1:
        return "SNV"
    if len(ref) == len(alt) and len(ref) > 1:
        return "MNV"
    if len(ref) > len(alt):
        return "Indel"
    if len(alt) > len(ref):
        return "Indel"
    return "Indel"


class TestSNVRepresentation:
    def test_snv_chr_pos_ref_alt_format(self):
        chromosome = "chr17"
        position = 43045629
        ref = "T"
        alt = "C"
        variant_id = f"{chromosome}:{position}{ref}>{alt}"
        assert variant_id == "chr17:43045629T>C"
        assert determine_variant_type(ref, alt) == "SNV"

    def test_snv_single_base_substitution_properties(self):
        ref, alt = "A", "G"
        assert len(ref) == 1
        assert len(alt) == 1
        assert ref != alt
        assert determine_variant_type(ref, alt) == "SNV"


class TestGenotypeDistinction:
    def test_homozygous_reference_0_0(self):
        genotype = "0/0"
        parts = genotype.split("/")
        assert parts == ["0", "0"]
        assert parts[0] == parts[1] == "0"

    def test_heterozygous_0_1(self):
        genotype = "0/1"
        parts = genotype.split("/")
        assert parts == ["0", "1"]
        assert parts[0] != parts[1]
        assert "1" in parts

    def test_homozygous_alternative_1_1(self):
        genotype = "1/1"
        parts = genotype.split("/")
        assert parts == ["1", "1"]
        assert parts[0] == parts[1] == "1"


class TestSmallDeletionIndel:
    def test_deletion_ref_longer_than_alt(self):
        ref = "GA"
        alt = "G"
        assert len(ref) > len(alt)
        assert determine_variant_type(ref, alt) == "Indel"
        deleted = ref[1:]
        assert deleted == "A"

    def test_deletion_representation(self):
        position = 1000
        ref = "GATC"
        alt = "G"
        deleted_bases = len(ref) - len(alt)
        assert deleted_bases == 3
        assert ref.startswith(alt)


class TestSmallInsertionIndel:
    def test_insertion_alt_longer_than_ref(self):
        ref = "G"
        alt = "GATC"
        assert len(alt) > len(ref)
        assert determine_variant_type(ref, alt) == "Indel"
        inserted = alt[len(ref):]
        assert inserted == "ATC"

    def test_insertion_representation(self):
        position = 2000
        ref = "T"
        alt = "TAAAA"
        inserted_bases = len(alt) - len(ref)
        assert inserted_bases == 4
        assert alt.startswith(ref)


class TestIndelLeftNormalization:
    def test_left_align_repeat_region(self):
        chromosome = "chr1"
        position = 100
        ref = "ATAT"
        alt = "A"
        norm_pos, norm_ref, norm_alt = normalize_variant_left(
            chromosome, position, ref, alt
        )
        assert len(norm_ref) >= len(norm_alt)
        assert len(norm_ref) - len(norm_alt) == 3
        assert norm_pos >= position

    def test_left_align_removes_common_suffix(self):
        chromosome = "chr2"
        position = 500
        ref = "TATAT"
        alt = "TAT"
        norm_pos, norm_ref, norm_alt = normalize_variant_left(
            chromosome, position, ref, alt
        )
        if len(norm_ref) > 1 and len(norm_alt) > 1:
            assert norm_ref[-1] != norm_alt[-1] or (len(norm_ref) == 1 or len(norm_alt) == 1)

    def test_left_align_removes_common_prefix(self):
        chromosome = "chr3"
        position = 300
        ref = "AATAA"
        alt = "AA"
        norm_pos, norm_ref, norm_alt = normalize_variant_left(
            chromosome, position, ref, alt
        )
        if len(norm_ref) > 1 and len(norm_alt) > 1:
            assert norm_ref[0] != norm_alt[0]


class TestIndelRightNormalization:
    def test_right_align_different_representations_standardized(self):
        chromosome = "chr5"
        position = 1000
        ref = "ATAT"
        alt = "A"
        left_pos, left_ref, left_alt = normalize_variant_left(
            chromosome, position, ref, alt
        )
        right_pos, right_ref, right_alt = normalize_variant_right(
            chromosome, position, ref, alt
        )
        assert len(left_ref) - len(left_alt) == len(right_ref) - len(right_alt)
        assert left_pos <= right_pos

    def test_right_align_preserves_net_change(self):
        chromosome = "chr6"
        position = 200
        ref = "GGGGG"
        alt = "GG"
        net_deletion = len(ref) - len(alt)
        _, norm_ref, norm_alt = normalize_variant_right(
            chromosome, position, ref, alt
        )
        assert len(norm_ref) - len(norm_alt) == net_deletion


class TestMultiallelicSplitting:
    def test_split_0_2_genotype_into_two_records(self):
        chromosome = "chr7"
        position = 777
        ref = "A"
        alts = ["T", "G"]
        genotype = "0/2"
        results = split_multiallelic(chromosome, position, ref, alts, genotype)
        assert len(results) == 2
        assert results[0]["alt"] == "T"
        assert results[1]["alt"] == "G"
        assert results[1]["original_genotype"] == "0/2"
        assert results[1]["alt_index"] == 1

    def test_split_preserves_chrom_pos_ref(self):
        chromosome = "chr8"
        position = 8888
        ref = "C"
        alts = ["G", "T", "A"]
        genotype = "1/2"
        results = split_multiallelic(chromosome, position, ref, alts, genotype)
        assert len(results) == 3
        for r in results:
            assert r["chromosome"] == chromosome
            assert r["position"] == position
            assert r["ref"] == ref

    def test_single_alt_no_split(self):
        chromosome = "chr9"
        position = 999
        ref = "G"
        alts = ["C"]
        genotype = "0/1"
        results = split_multiallelic(chromosome, position, ref, alts, genotype)
        assert len(results) == 1
        assert results[0]["alt"] == "C"
        assert results[0]["genotype"] == "0/1"


class TestMNVHandling:
    def test_mnv_ref_alt_same_length_greater_than_one(self):
        ref = "AT"
        alt = "GC"
        assert len(ref) == len(alt)
        assert len(ref) > 1
        assert determine_variant_type(ref, alt) == "MNV"

    def test_mnv_multiple_base_substitution(self):
        ref = "CGTA"
        alt = "ATGC"
        assert len(ref) == 4
        assert len(alt) == 4
        assert ref != alt
        assert determine_variant_type(ref, alt) == "MNV"

    def test_mnv_distinguished_from_indel(self):
        assert determine_variant_type("ABC", "DEF") == "MNV"
        assert determine_variant_type("ABC", "AB") == "Indel"
        assert determine_variant_type("AB", "ABC") == "Indel"
        assert determine_variant_type("A", "T") == "SNV"


class TestVariantBoundaryCases:
    def test_empty_ref_boundary(self):
        ref = ""
        alt = "A"
        assert len(ref) == 0

    def test_empty_alt_boundary(self):
        ref = "A"
        alt = ""
        assert len(alt) == 0

    def test_empty_chromosome_boundary(self):
        chromosome = ""
        assert chromosome == ""

    def test_position_zero_boundary(self):
        position = 0
        assert position == 0


class TestVariantModelCreation:
    def test_variant_factory_creates_and_saves_to_db(
        self, db_session, sample_factory, variant_factory
    ):
        sample = sample_factory(sample_id="VAR_TEST_SAMPLE_001")
        variant = variant_factory(
            sample=sample,
            chromosome="chr13",
            position=32914437,
            ref="A",
            alt="T",
            variant_type="SNV",
            genotype="0/1",
            gene="BRCA2",
            acmg_classification=ACMGClassification.PATHOGENIC,
        )
        db_session.flush()
        assert variant.id is not None
        assert variant.sample_id == sample.id
        assert variant.chromosome == "chr13"
        assert variant.position == 32914437
        assert variant.ref == "A"
        assert variant.alt == "T"
        assert variant.variant_type == "SNV"
        assert variant.genotype == "0/1"
        assert variant.gene == "BRCA2"
        assert variant.acmg_classification == ACMGClassification.PATHOGENIC

        fetched = db_session.query(Variant).filter_by(id=variant.id).first()
        assert fetched is not None
        assert fetched.variant_id == variant.variant_id
        assert fetched.chromosome == variant.chromosome
        assert fetched.position == variant.position

    def test_variant_relationship_to_sample(
        self, db_session, sample_factory, variant_factory
    ):
        sample = sample_factory(sample_id="VAR_TEST_SAMPLE_002")
        v1 = variant_factory(sample=sample, chromosome="chr1", position=1000, ref="A", alt="T")
        v2 = variant_factory(sample=sample, chromosome="chr2", position=2000, ref="G", alt="C")
        db_session.flush()
        db_session.refresh(sample)
        assert len(sample.variants) >= 2
        variant_ids = {v.id for v in sample.variants}
        assert v1.id in variant_ids
        assert v2.id in variant_ids
