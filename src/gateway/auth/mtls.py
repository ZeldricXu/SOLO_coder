from typing import Any, Dict, Optional, Tuple
import ssl
import base64
from datetime import datetime, timezone

from gateway.config import get_settings
from gateway.logger import get_logger

logger = get_logger("mtls")


class MTLSValidator:
    def __init__(self):
        self.settings = get_settings()
        self._ca_certs: Dict[str, str] = {}

    def load_ca_cert(self, name: str, cert_path: str) -> None:
        try:
            with open(cert_path, "r") as f:
                self._ca_certs[name] = f.read()
            logger.info("CA certificate loaded", name=name)
        except Exception as e:
            logger.error("Failed to load CA certificate", name=name, error=str(e))

    def add_ca_cert(self, name: str, cert_content: str) -> None:
        self._ca_certs[name] = cert_content
        logger.info("CA certificate added", name=name)

    async def validate(self, request) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        try:
            client_cert = self._extract_client_cert(request)
            if not client_cert:
                return False, None, "Client certificate not provided"

            cert_info = self._parse_certificate(client_cert)
            if not cert_info:
                return False, None, "Invalid client certificate"

            if not self._verify_certificate_chain(client_cert):
                return False, None, "Certificate chain verification failed"

            if self._is_certificate_revoked(cert_info):
                return False, None, "Certificate has been revoked"

            if not self._verify_certificate_validity(cert_info):
                return False, None, "Certificate is expired or not yet valid"

            return True, cert_info, None

        except Exception as e:
            logger.error("mTLS validation error", error=str(e), exc_info=True)
            return False, None, f"Validation error: {str(e)}"

    def _extract_client_cert(self, request) -> Optional[bytes]:
        if hasattr(request, "client") and request.client:
            ssl_object = request.scope.get("transport", {}).get("ssl_object") if request.scope else None
            if ssl_object:
                try:
                    return ssl_object.getpeercert(binary_form=True)
                except Exception:
                    pass

        cert_header = request.headers.get("X-Client-Cert")
        if cert_header:
            try:
                return base64.b64decode(cert_header)
            except Exception:
                pass

        cert_chain_header = request.headers.get("X-Forwarded-Tls-Client-Cert")
        if cert_chain_header:
            try:
                return base64.b64decode(cert_chain_header.split(",")[0])
            except Exception:
                pass

        return None

    def _parse_certificate(self, cert_der: bytes) -> Optional[Dict[str, Any]]:
        try:
            from cryptography import x509
            from cryptography.hazmat.backends import default_backend

            cert = x509.load_der_x509_certificate(cert_der, default_backend())

            subject = {}
            for attr in cert.subject:
                subject[attr.oid._name] = attr.value

            issuer = {}
            for attr in cert.issuer:
                issuer[attr.oid._name] = attr.value

            return {
                "serial_number": str(cert.serial_number),
                "subject": subject,
                "issuer": issuer,
                "not_valid_before": cert.not_valid_before_utc,
                "not_valid_after": cert.not_valid_after_utc,
                "fingerprint": cert.fingerprint(cert.signature_hash_algorithm).hex(),
                "user_id": subject.get("commonName", ""),
                "email": subject.get("emailAddress", ""),
                "organization": subject.get("organizationName", ""),
                "organizational_unit": subject.get("organizationalUnitName", ""),
            }
        except ImportError:
            logger.warning("cryptography library not available, using basic cert parsing")
            return {
                "user_id": "cert_user",
                "cert_verified": True,
            }
        except Exception as e:
            logger.error("Failed to parse certificate", error=str(e))
            return None

    def _verify_certificate_chain(self, cert_der: bytes) -> bool:
        if not self._ca_certs:
            logger.warning("No CA certificates configured, skipping chain verification")
            return True

        try:
            from cryptography import x509
            from cryptography.hazmat.backends import default_backend
            from cryptography.x509.verification import PolicyBuilder, Store

            cert = x509.load_der_x509_certificate(cert_der, default_backend())

            ca_certs = []
            for ca_content in self._ca_certs.values():
                if "-----BEGIN" in ca_content:
                    ca_cert = x509.load_pem_x509_certificate(ca_content.encode(), default_backend())
                else:
                    ca_cert = x509.load_der_x509_certificate(base64.b64decode(ca_content), default_backend())
                ca_certs.append(ca_cert)

            store = Store(ca_certs)
            builder = PolicyBuilder().store(store)
            verifier = builder.build_verifier()
            verifier.verify(cert, ca_certs)
            return True

        except ImportError:
            return True
        except Exception as e:
            logger.warning("Certificate chain verification failed", error=str(e))
            return False

    def _verify_certificate_validity(self, cert_info: Dict[str, Any]) -> bool:
        now = datetime.now(timezone.utc)
        not_before = cert_info.get("not_valid_before")
        not_after = cert_info.get("not_valid_after")

        if not_before and now < not_before:
            return False
        if not_after and now > not_after:
            return False
        return True

    def _is_certificate_revoked(self, cert_info: Dict[str, Any]) -> bool:
        return False


_mtls_instance: Optional[MTLSValidator] = None


def get_mtls_validator() -> MTLSValidator:
    global _mtls_instance
    if _mtls_instance is None:
        _mtls_instance = MTLSValidator()
    return _mtls_instance
