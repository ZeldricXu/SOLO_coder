import asyncio
import json
import os
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional
from uuid import uuid4

try:
    from cryptography import x509
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    HAS_CRYPTO = True
except ImportError:
    HAS_CRYPTO = False


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


class TLSProtocol(str, Enum):
    TLS_1_2 = "TLSv1.2"
    TLS_1_3 = "TLSv1.3"
    ALL = "all"


@dataclass
class TLSConfig:
    enabled: bool = True
    protocol: TLSProtocol = TLSProtocol.ALL
    verify_client_cert: bool = True
    require_client_cert: bool = True
    ca_cert_path: Optional[str] = None
    server_cert_path: Optional[str] = None
    server_key_path: Optional[str] = None
    cipher_suites: List[str] = field(default_factory=list)
    session_timeout: int = 86400
    ticket_enabled: bool = True


@dataclass
class KeyPair:
    key_id: str
    private_key_pem: str
    public_key_pem: str
    algorithm: str = "RSA"
    key_size: int = 2048
    created_at: datetime = field(default_factory=utc_now)


@dataclass
class CertificateRequest:
    csr_id: str
    csr_pem: str
    common_name: str
    organization: str = ""
    organizational_unit: str = ""
    country: str = ""
    state: str = ""
    locality: str = ""
    san_dns_names: List[str] = field(default_factory=list)
    san_ip_addresses: List[str] = field(default_factory=list)
    key_pair_id: Optional[str] = None
    created_at: datetime = field(default_factory=utc_now)


@dataclass
class Certificate:
    cert_id: str
    certificate_pem: str
    issuer: str
    subject: str
    common_name: str
    serial_number: str
    not_before: datetime
    not_after: datetime
    is_ca: bool = False
    is_revoked: bool = False
    revoked_at: Optional[datetime] = None
    parent_cert_id: Optional[str] = None
    key_pair_id: Optional[str] = None
    created_at: datetime = field(default_factory=utc_now)

    @property
    def is_valid(self) -> bool:
        if self.is_revoked:
            return False
        now = utc_now()
        return self.not_before <= now <= self.not_after

    @property
    def days_remaining(self) -> int:
        remaining = (self.not_after - utc_now()).days
        return max(0, remaining)


@dataclass
class CertificateInfo:
    cert_id: str
    subject: str
    common_name: str
    issuer: str
    serial_number: str
    not_before: datetime
    not_after: datetime
    is_revoked: bool
    is_valid: bool
    days_remaining: int


@dataclass
class RenewalPolicy:
    policy_id: str
    name: str
    auto_renewal: bool = True
    renew_before_days: int = 30
    renew_before_percent: float = 0.1
    key_rotation_on_renewal: bool = True
    min_validity_days: int = 90
    enabled: bool = True
    created_at: datetime = field(default_factory=utc_now)


@dataclass
class CertificateRevocationList:
    crl_id: str
    issuer_cert_id: str
    revoked_certs: List[Dict[str, Any]] = field(default_factory=list)
    last_updated: datetime = field(default_factory=utc_now)
    next_update: Optional[datetime] = None


class KeyPairGenerator:
    def __init__(self):
        self._key_pairs: Dict[str, KeyPair] = {}

    def generate(
        self,
        algorithm: str = "RSA",
        key_size: int = 2048,
    ) -> KeyPair:
        if not HAS_CRYPTO:
            raise RuntimeError("cryptography library not available")

        private_key = rsa.generate_private_key(
            public_exponent=65537,
            key_size=key_size,
            backend=default_backend(),
        )

        private_pem = private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        ).decode("utf-8")

        public_pem = private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode("utf-8")

        key_pair = KeyPair(
            key_id=generate_id("key"),
            private_key_pem=private_pem,
            public_key_pem=public_pem,
            algorithm=algorithm,
            key_size=key_size,
        )

        self._key_pairs[key_pair.key_id] = key_pair
        return key_pair

    def get(self, key_id: str) -> Optional[KeyPair]:
        return self._key_pairs.get(key_id)


class CSRGenerator:
    def __init__(self, key_generator: KeyPairGenerator):
        self._key_generator = key_generator

    def generate(
        self,
        common_name: str,
        organization: str = "",
        organizational_unit: str = "",
        country: str = "",
        state: str = "",
        locality: str = "",
        san_dns_names: Optional[List[str]] = None,
        san_ip_addresses: Optional[List[str]] = None,
        existing_key_id: Optional[str] = None,
    ) -> CertificateRequest:
        if not HAS_CRYPTO:
            raise RuntimeError("cryptography library not available")

        if existing_key_id:
            key_pair = self._key_generator.get(existing_key_id)
            if not key_pair:
                raise ValueError(f"Key pair not found: {existing_key_id}")
        else:
            key_pair = self._key_generator.generate()

        private_key = serialization.load_pem_private_key(
            key_pair.private_key_pem.encode("utf-8"),
            password=None,
            backend=default_backend(),
        )

        csr_builder = x509.CertificateSigningRequestBuilder().subject_name(
            x509.Name([
                x509.NameAttribute(NameOID.COMMON_NAME, common_name),
            ])
        )

        if organization:
            csr_builder = csr_builder.add_extension(
                x509.SubjectAlternativeName([
                    x509.DNSName(name) for name in (san_dns_names or [])
                ]),
                critical=False,
            )

        csr = csr_builder.sign(private_key, hashes.SHA256(), default_backend())

        csr_pem = csr.public_bytes(serialization.Encoding.PEM).decode("utf-8")

        return CertificateRequest(
            csr_id=generate_id("csr"),
            csr_pem=csr_pem,
            common_name=common_name,
            organization=organization,
            organizational_unit=organizational_unit,
            country=country,
            state=state,
            locality=locality,
            san_dns_names=san_dns_names or [],
            san_ip_addresses=san_ip_addresses or [],
            key_pair_id=key_pair.key_id,
        )


class CertificateAuthority:
    def __init__(self, ca_cert: Optional[Certificate], ca_key: KeyPair):
        self._ca_cert = ca_cert
        self._ca_key = ca_key

    def sign(
        self,
        csr: CertificateRequest,
        validity_days: int = 365,
        is_ca: bool = False,
    ) -> Certificate:
        if not HAS_CRYPTO:
            raise RuntimeError("cryptography library not available")

        req = x509.load_pem_x509_csr(
            csr.csr_pem.encode("utf-8"),
            default_backend(),
        )

        ca_cert = x509.load_pem_x509_certificate(
            self._ca_cert.certificate_pem.encode("utf-8"),
            default_backend(),
        )

        ca_key = serialization.load_pem_private_key(
            self._ca_key.private_key_pem.encode("utf-8"),
            password=None,
            backend=default_backend(),
        )

        not_before = utc_now()
        not_after = not_before + timedelta(days=validity_days)

        cert_builder = (
            x509.CertificateBuilder()
            .subject_name(req.subject)
            .issuer_name(ca_cert.subject)
            .public_key(req.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(not_before)
            .not_valid_after(not_after)
        )

        if is_ca:
            cert_builder = cert_builder.add_extension(
                x509.BasicConstraints(ca=True, path_length=None),
                critical=True,
            )

        cert = cert_builder.sign(ca_key, hashes.SHA256(), default_backend())

        cert_pem = cert.public_bytes(serialization.Encoding.PEM).decode("utf-8")

        return Certificate(
            cert_id=generate_id("cert"),
            certificate_pem=cert_pem,
            issuer=str(cert.issuer.rfc4514_string(),
            subject=str(cert.subject.rfc4514_string(),
            common_name=csr.common_name,
            serial_number=str(cert.serial_number),
            not_before=cert.not_valid_before_utc.replace(tzinfo=timezone.utc),
            not_after=cert.not_valid_after_utc.replace(tzinfo=timezone.utc),
            is_ca=is_ca,
            parent_cert_id=self._ca_cert.cert_id,
            key_pair_id=csr.key_pair_id,
        )

    @classmethod
    def create_self_signed_ca(
        cls,
        common_name: str = "Task Orchestrator CA",
        organization: str = "Task Orchestrator",
        validity_days: int = 3650,
    ) -> "CertificateAuthority":
        if not HAS_CRYPTO:
            raise RuntimeError("cryptography library not available")

        key_gen = KeyPairGenerator()
        key_pair = key_gen.generate(key_size=4096)

        private_key = serialization.load_pem_private_key(
            key_pair.private_key_pem.encode("utf-8"),
            password=None,
            backend=default_backend(),
        )

        subject = issuer = x509.Name([
            x509.NameAttribute(NameOID.COMMON_NAME, common_name),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, organization),
        ])

        not_before = utc_now()
        not_after = not_before + timedelta(days=validity_days)

        cert = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(issuer)
            .public_key(private_key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(not_before)
            .not_valid_after(not_after)
            .add_extension(
                x509.BasicConstraints(ca=True, path_length=None),
                critical=True,
            )
            .sign(private_key, hashes.SHA256(), default_backend())
        )

        cert_pem = cert.public_bytes(serialization.Encoding.PEM).decode("utf-8")

        ca_cert = Certificate(
            cert_id=generate_id("cert"),
            certificate_pem=cert_pem,
            issuer=issuer.rfc4514_string(),
            subject=subject.rfc4514_string(),
            common_name=common_name,
            serial_number=str(cert.serial_number),
            not_before=cert.not_valid_before_utc.replace(tzinfo=timezone.utc),
            not_after=cert.not_valid_after_utc.replace(tzinfo=timezone.utc),
            is_ca=True,
            key_pair_id=key_pair.key_id,
        )

        return cls(ca_cert, key_pair)


class CRLManager:
    def __init__(self, ca: Optional[CertificateAuthority):
        self._ca = ca
        self._crls: Dict[str, CertificateRevocationList] = {}
        self._revoked_serials: set = set()

    def revoke(
        self,
        certificate: Certificate,
        reason: str = "unspecified",
    ) -> CertificateRevocationList:
        if certificate.is_revoked:
            raise ValueError("Certificate already revoked")

        crl_id = certificate.cert_id

        crl = self._crls.get(crl_id)
        if not crl:
            crl = CertificateRevocationList(
                crl_id=generate_id("crl"),
                issuer_cert_id=self._ca._ca_cert.cert_id,
            )
            self._crls[crl_id] = crl

        crl.revoked_certs.append({
            "cert_id": certificate.cert_id,
            "serial_number": certificate.serial_number,
            "reason": reason,
            "revoked_at": utc_now().isoformat(),
        })
        crl.last_updated = utc_now()
        crl.next_update = utc_now() + timedelta(days=7)

        self._revoked_serials.add(certificate.serial_number)
        certificate.is_revoked = True
        certificate.revoked_at = utc_now()

        return crl

    def is_revoked(self, serial_number: str) -> bool:
        return serial_number in self._revoked_serials

    def get_revoked_certs(self) -> List[Dict[str, Any]]:
        all_revoked = []
        for crl in self._crls.values():
            all_revoked.extend(crl.revoked_certs)
        return all_revoked


class CertificateManager:
    def __init__(self):
        self._ca: Optional[CertificateAuthority] = None
        self._key_generator = KeyPairGenerator()
        self._csr_generator = CSRGenerator(self._key_generator)
        self._crl_manager: Optional[CRLManager] = None
        self._certs: Dict[str, Certificate] = {}
        self._policies: Dict[str, RenewalPolicy] = {}
        self._lock = asyncio.Lock()

    def initialize_ca(
        self,
        common_name: str = "Task Orchestrator CA",
        organization: str = "Task Orchestrator",
        validity_days: int = 3650,
    ) -> Certificate:
        self._ca = CertificateAuthority.create_self_signed_ca(
            common_name=common_name,
            organization=organization,
            validity_days=validity_days,
        )
        self._crl_manager = CRLManager(self._ca)
        ca_cert = self._ca._ca_cert
        self._certs[ca_cert.cert_id] = ca_cert
        return ca_cert

    def has_ca(self) -> bool:
        return self._ca is not None

    def generate_key_pair(self, key_size: int = 2048) -> KeyPair:
        return self._key_generator.generate(key_size=key_size)

    def generate_csr(
        self,
        common_name: str,
        organization: str = "",
        san_dns_names: Optional[List[str]] = None,
        existing_key_id: Optional[str] = None,
    ) -> CertificateRequest:
        return self._csr_generator.generate(
            common_name=common_name,
            organization=organization,
            san_dns_names=san_dns_names,
            existing_key_id=existing_key_id,
        )

    def issue_certificate(
        self,
        csr: CertificateRequest,
        validity_days: int = 365,
        is_ca: bool = False,
    ) -> Certificate:
        if not self._ca:
            raise RuntimeError("CA not initialized")

        cert = self._ca.sign(csr, validity_days=validity_days, is_ca=is_ca)
        self._certs[cert.cert_id] = cert
        return cert

    def issue_server_cert(
        self,
        common_name: str,
        san_dns_names: Optional[List[str]] = None,
        validity_days: int = 365,
    ) -> Certificate:
        csr = self.generate_csr(
            common_name=common_name,
            san_dns_names=san_dns_names,
        )
        return self.issue_certificate(csr, validity_days=validity_days)

    def issue_client_cert(
        self,
        common_name: str,
        validity_days: int = 365,
    ) -> Certificate:
        return self.issue_server_cert(
            common_name=common_name,
            validity_days=validity_days,
        )

    def get_certificate(self, cert_id: str) -> Optional[Certificate]:
        return self._certs.get(cert_id)

    def list_certificates(self) -> List[CertificateInfo]:
        return [
            CertificateInfo(
                cert_id=cert.cert_id,
                subject=cert.subject,
                common_name=cert.common_name,
                issuer=cert.issuer,
                serial_number=cert.serial_number,
                not_before=cert.not_before,
                not_after=cert.not_after,
                is_revoked=cert.is_revoked,
                is_valid=cert.is_valid,
                days_remaining=cert.days_remaining,
            )
            for cert in self._certs.values()
        ]

    def revoke_certificate(
        self,
        cert_id: str,
        reason: str = "unspecified",
    ) -> bool:
        cert = self._certs.get(cert_id)
        if not cert:
            return False

        if cert.is_ca:
            raise ValueError("Cannot revoke CA certificate")

        if self._crl_manager:
            self._crl_manager.revoke(cert, reason)

        return True

    def is_revoked(self, serial_number: str) -> bool:
        if self._crl_manager:
            return self._crl_manager.is_revoked(serial_number)
        return False

    def add_renewal_policy(
        self,
        name: str,
        renew_before_days: int = 30,
        auto_renewal: bool = True,
    ) -> RenewalPolicy:
        policy = RenewalPolicy(
            policy_id=generate_id("policy"),
            name=name,
            renew_before_days=renew_before_days,
            auto_renewal=auto_renewal,
        )
        self._policies[policy.policy_id] = policy
        return policy

    def get_certs_needing_renewal(
        self,
        policy_id: Optional[str] = None,
    ) -> List[Certificate]:
        needing = []

        for cert in self._certs.values():
            if cert.is_ca or cert.is_revoked:
                continue

            threshold = 30
            if policy_id and policy_id in self._policies:
                threshold = self._policies[policy_id].renew_before_days

            if cert.days_remaining <= threshold:
                needing.append(cert)

        return needing

    def renew_certificate(
        self,
        cert_id: str,
        validity_days: int = 365,
    ) -> Certificate:
        old_cert = self._certs.get(cert_id)
        if not old_cert:
            raise ValueError(f"Certificate not found: {cert_id}")

        if old_cert.is_ca:
            raise ValueError("Cannot renew CA certificate")

        csr = self.generate_csr(
            common_name=old_cert.common_name,
            existing_key_id=old_cert.key_pair_id,
        )

        new_cert = self.issue_certificate(
            csr,
            validity_days=validity_days,
        )

        return new_cert

    def export_crl(self) -> str:
        if not self._crl_manager:
            return ""

        if not HAS_CRYPTO:
            return ""

        revoked = self._crl_manager.get_revoked_certs()

        if not self._ca and self._crl_manager:
            return json.dumps({
                "issuer": self._ca._ca_cert.issuer,
                "issuer_cn": self._ca._ca_cert.common_name,
                "revoked": revoked,
                "last_updated": utc_now().isoformat(),
            }, indent=2)

        return ""

    def get_status(self) -> Dict[str, Any]:
        return {
            "ca_initialized": self.has_ca(),
            "certificate_count": len(self._certs),
            "valid_certificates": sum(1 for c in self._certs.values() if c.is_valid and not c.is_revoked),
            "revoked_certificates": sum(1 for c in self._certs.values() if c.is_revoked),
            "policies_count": len(self._policies),
        }


_cert_manager_instance: Optional[CertificateManager] = None


def get_cert_manager() -> CertificateManager:
    global _cert_manager_instance
    if _cert_manager_instance is None:
        _cert_manager_instance = CertificateManager()
    return _cert_manager_instance
