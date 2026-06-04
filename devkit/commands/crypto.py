import base64
import hashlib
import json as json_module
import os
import sys
import datetime
from pathlib import Path

import click
import jwt as pyjwt
import bcrypt
from cryptography.hazmat.primitives import hashes, padding as sym_padding
from cryptography.hazmat.primitives.asymmetric import rsa as crypto_rsa, padding as asym_padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.backends import default_backend
from cryptography import x509
from cryptography.x509.oid import NameOID

from ..core import Color, cprint


def _pad_key(key, length=32):
    key_bytes = key.encode('utf-8')
    if len(key_bytes) < length:
        key_bytes = key_bytes + b'\x00' * (length - len(key_bytes))
    elif len(key_bytes) > length:
        key_bytes = key_bytes[:length]
    return key_bytes


def _read_input(filepath, content, binary=False):
    if filepath:
        mode = 'rb' if binary else 'r'
        with open(filepath, mode) as f:
            return f.read()
    if content:
        return content.encode('utf-8') if binary else content
    if not sys.stdin.isatty():
        return sys.stdin.buffer.read() if binary else sys.stdin.read()
    return None


@click.group()
def crypto():
    """Encryption and certificate commands"""
    pass


@crypto.group()
def aes():
    """AES encryption/decryption commands"""
    pass


@aes.command('encrypt')
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string to encrypt')
@click.option('--key', '-k', required=True, help='Encryption key')
@click.option('--mode', '-m', default='gcm', 
              type=click.Choice(['cbc', 'gcm']),
              show_default=True, help='AES mode')
@click.option('--output', '-o', type=click.Path(), help='Output file path')
def aes_encrypt(filepath, content, key, mode, output):
    """Encrypt data with AES.
    
    **Why GCM is the default (and more secure than CBC)**:
    
    AES-CBC (Cipher Block Chaining):
    - Provides **confidentiality only** (no integrity/authenticity)
    - Requires separate padding (PKCS7) which can lead to padding oracle attacks
    - If an attacker can modify the ciphertext, they can perform chosen-ciphertext attacks
    - IV is 16 bytes, must be unpredictable but not necessarily secret
    
    AES-GCM (Galois/Counter Mode):
    - Provides **authenticated encryption** (confidentiality + integrity + authenticity)
    - No padding needed (stream cipher mode internally), eliminates padding oracle attacks
    - Produces an authentication tag (16 bytes) that must be verified before decryption
    - Any modification to ciphertext, IV, or associated data will be detected
    - IV is 12 bytes (recommended by NIST for GCM)
    - **Critical**: GCM fails catastrophically if IV+key pair repeats. Never reuse the same
      IV with the same key - that would leak the authentication key.
    
    **Output Format**:
    - GCM: `iv (12 bytes) + auth_tag (16 bytes) + ciphertext`
    - CBC: `iv (16 bytes) + ciphertext (with PKCS7 padding)`
    
    Args:
        filepath: Path to file to encrypt (one of filepath or content must be provided).
        content: Direct content string to encrypt (one of filepath or content).
        key: Encryption key (any length, will be padded/truncated to 32 bytes).
        mode: Encryption mode, 'gcm' (default) or 'cbc'.
        output: Optional output file path. If None, prints to stdout.
    
    Returns:
        None. Outputs base64-encoded result to file or stdout.
    """
    data = _read_input(filepath, content, binary=True)
    if not data:
        cprint('Error: No input provided', Color.RED)
        return
    
    key_bytes = _pad_key(key)
    
    # GCM uses 12-byte IV (per NIST SP 800-38D recommendation), CBC uses 16-byte IV.
    # Using os.urandom() ensures cryptographically secure random IV generation.
    # GCM is highly sensitive to IV reuse with the same key - we must always generate
    # a fresh IV for every encryption operation.
    iv = os.urandom(12 if mode == 'gcm' else 16)
    
    try:
        if mode == 'gcm':
            # GCM is authenticated encryption - provides BOTH confidentiality and integrity.
            # The authentication tag will be generated during finalization and MUST be
            # verified during decryption before releasing any plaintext.
            cipher = Cipher(algorithms.AES(key_bytes), modes.GCM(iv), backend=default_backend())
            encryptor = cipher.encryptor()
            ciphertext = encryptor.update(data) + encryptor.finalize()
            # Tag is stored with ciphertext for later verification
            result = iv + encryptor.tag + ciphertext
        else:
            # CBC mode provides confidentiality ONLY, no integrity.
            # PKCS7 padding is required since CBC operates on fixed-size blocks (128 bits).
            # Padding adds (16 - len(data) % 16) bytes, each with value equal to the padding length.
            # This introduces vulnerability to padding oracle attacks if not properly implemented.
            padder = sym_padding.PKCS7(128).padder()
            padded_data = padder.update(data) + padder.finalize()
            cipher = Cipher(algorithms.AES(key_bytes), modes.CBC(iv), backend=default_backend())
            encryptor = cipher.encryptor()
            ciphertext = encryptor.update(padded_data) + encryptor.finalize()
            result = iv + ciphertext
    except Exception as e:
        cprint(f'Encryption failed: {e}', Color.RED)
        return
    
    encoded = base64.b64encode(result).decode('ascii')
    
    if output:
        with open(output, 'w') as f:
            f.write(encoded)
        cprint(f'Encrypted to {output}', Color.GREEN)
    else:
        click.echo(encoded)


@aes.command('decrypt')
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string to decrypt')
@click.option('--key', '-k', required=True, help='Decryption key')
@click.option('--mode', '-m', default='gcm',
              type=click.Choice(['cbc', 'gcm']),
              show_default=True, help='AES mode')
@click.option('--output', '-o', type=click.Path(), help='Output file path')
def aes_decrypt(filepath, content, key, mode, output):
    """Decrypt AES encrypted data.
    
    **Why GCM Decryption Verifies the Tag First**
    
    In GCM mode, the authentication tag verification happens during `finalize()`.
    The cryptography library's design ensures that NO plaintext is released until
    the tag has been successfully verified. This is critical for security:
    
    1. **Prevents chosen-ciphertext attacks**: An attacker cannot feed in modified
       ciphertexts and learn from partial decryption output.
    2. **Detects tampering**: Any modification to ciphertext, IV, or associated
       data will cause tag verification to fail, and an exception is raised
       before any plaintext bytes are produced.
    3. **No partial plaintext**: Unlike some implementations that might give
       you decrypted bytes and then raise an error at the end, this implementation
       buffers everything and only returns plaintext after tag verification.
    
    **CBC Mode Caveats**:
    - CBC does NOT authenticate, so we can't detect if ciphertext was modified
    - Padding oracle attack mitigation: `unpadder.finalize()` will raise an error
      if padding is invalid, but this timing could leak information in a real
      server setting. For this CLI tool, the risk is minimal.
    
    Args:
        filepath: Path to file containing base64-encoded encrypted data.
        content: Direct base64-encoded string to decrypt.
        key: Decryption key (any length, will be padded/truncated to 32 bytes).
        mode: Decryption mode, 'gcm' (default) or 'cbc'. Must match encryption mode.
        output: Optional output file path. If None, prints to stdout.
    
    Raises:
        cryptography.exceptions.InvalidTag: If GCM tag verification fails.
            This indicates the data was tampered with or wrong key/mode used.
        ValueError: If padding is invalid (CBC mode only).
    """
    raw = _read_input(filepath, content, binary=False)
    if not raw:
        cprint('Error: No input provided', Color.RED)
        return
    
    try:
        data = base64.b64decode(raw.strip())
    except Exception as e:
        cprint(f'Invalid base64 input: {e}', Color.RED)
        return
    
    key_bytes = _pad_key(key)
    
    try:
        if mode == 'gcm':
            # GCM output format: iv(12) + auth_tag(16) + ciphertext
            iv = data[:12]
            tag = data[12:28]
            ciphertext = data[28:]
            
            # Pass tag to GCM mode during cipher construction.
            # The cryptography library will verify the tag during finalize()
            # and raise InvalidTag BEFORE returning any plaintext.
            cipher = Cipher(algorithms.AES(key_bytes), modes.GCM(iv, tag), backend=default_backend())
            decryptor = cipher.decryptor()
            plaintext = decryptor.update(ciphertext) + decryptor.finalize()
        else:
            # CBC output format: iv(16) + ciphertext(padded)
            iv = data[:16]
            ciphertext = data[16:]
            
            cipher = Cipher(algorithms.AES(key_bytes), modes.CBC(iv), backend=default_backend())
            decryptor = cipher.decryptor()
            padded_data = decryptor.update(ciphertext) + decryptor.finalize()
            
            # Remove PKCS7 padding. Will raise ValueError if padding is invalid.
            # Note: This could be vulnerable to padding oracle timing attacks
            # in a network-exposed service, but acceptable for a CLI tool.
            unpadder = sym_padding.PKCS7(128).unpadder()
            plaintext = unpadder.update(padded_data) + unpadder.finalize()
    except Exception as e:
        cprint(f'Decryption failed: {e}', Color.RED)
        return
    
    if output:
        with open(output, 'wb') as f:
            f.write(plaintext)
        cprint(f'Decrypted to {output}', Color.GREEN)
    else:
        try:
            click.echo(plaintext.decode('utf-8'))
        except UnicodeDecodeError:
            click.echo(plaintext)


@crypto.group()
def rsa():
    """RSA key generation, signing and verification"""
    pass


@rsa.command('genkey')
@click.option('--bits', '-b', default=2048, show_default=True, help='Key size in bits')
@click.option('--output', '-o', default='rsa_key', show_default=True, help='Output file prefix')
@click.option('--passphrase', '-p', help='Passphrase for private key encryption')
def rsa_genkey(bits, output, passphrase):
    """Generate RSA key pair"""
    private_key = crypto_rsa.generate_private_key(
        public_exponent=65537,
        key_size=bits,
        backend=default_backend()
    )
    
    encryption_algorithm = serialization.NoEncryption()
    if passphrase:
        encryption_algorithm = serialization.BestAvailableEncryption(passphrase.encode('utf-8'))
    
    private_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=encryption_algorithm
    )
    
    public_key = private_key.public_key()
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )
    
    with open(f'{output}.pem', 'wb') as f:
        f.write(private_pem)
    with open(f'{output}.pub.pem', 'wb') as f:
        f.write(public_pem)
    
    cprint(f'Private key: {output}.pem', Color.GREEN)
    cprint(f'Public key:  {output}.pub.pem', Color.GREEN)


@rsa.command('sign')
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string to sign')
@click.option('--key', '-k', required=True, type=click.Path(exists=True), help='Private key file')
@click.option('--passphrase', '-p', help='Private key passphrase')
@click.option('--algorithm', '-a', default='sha256',
              type=click.Choice(['sha256', 'sha1', 'sha512']),
              show_default=True, help='Hash algorithm')
def rsa_sign(filepath, content, key, passphrase, algorithm):
    """Sign data with RSA private key"""
    data = _read_input(filepath, content, binary=True)
    if not data:
        cprint('Error: No input provided', Color.RED)
        return
    
    try:
        with open(key, 'rb') as f:
            private_key = serialization.load_pem_private_key(
                f.read(),
                password=passphrase.encode('utf-8') if passphrase else None,
                backend=default_backend()
            )
    except Exception as e:
        cprint(f'Error loading private key: {e}', Color.RED)
        return
    
    hash_algo = {'sha1': hashes.SHA1(), 'sha256': hashes.SHA256(), 'sha512': hashes.SHA512()}[algorithm]
    
    try:
        signature = private_key.sign(
            data,
            asym_padding.PSS(
                mgf=asym_padding.MGF1(hash_algo),
                salt_length=asym_padding.PSS.MAX_LENGTH
            ),
            hash_algo
        )
    except Exception as e:
        cprint(f'Signing failed: {e}', Color.RED)
        return
    
    click.echo(base64.b64encode(signature).decode('ascii'))


@rsa.command('verify')
@click.argument('signature')
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string to verify')
@click.option('--key', '-k', required=True, type=click.Path(exists=True), help='Public key file')
@click.option('--algorithm', '-a', default='sha256',
              type=click.Choice(['sha256', 'sha1', 'sha512']),
              show_default=True, help='Hash algorithm')
def rsa_verify(signature, filepath, content, key, algorithm):
    """Verify RSA signature"""
    data = _read_input(filepath, content, binary=True)
    if not data:
        cprint('Error: No input provided', Color.RED)
        return
    
    try:
        with open(key, 'rb') as f:
            public_key = serialization.load_pem_public_key(
                f.read(),
                backend=default_backend()
            )
    except Exception as e:
        cprint(f'Error loading public key: {e}', Color.RED)
        return
    
    try:
        sig_bytes = base64.b64decode(signature)
    except Exception as e:
        cprint(f'Invalid signature: {e}', Color.RED)
        return
    
    hash_algo = {'sha1': hashes.SHA1(), 'sha256': hashes.SHA256(), 'sha512': hashes.SHA512()}[algorithm]
    
    try:
        public_key.verify(
            sig_bytes,
            data,
            asym_padding.PSS(
                mgf=asym_padding.MGF1(hash_algo),
                salt_length=asym_padding.PSS.MAX_LENGTH
            ),
            hash_algo
        )
        cprint('Signature verified successfully', Color.GREEN)
    except Exception as e:
        cprint(f'Signature verification failed: {e}', Color.RED)


@crypto.group()
def jwt():
    """JWT (JSON Web Token) commands"""
    pass


@jwt.command('encode')
@click.option('--payload', '-p', required=True, help='JSON payload string or @file')
@click.option('--secret', '-s', required=True, help='Secret key or @file')
@click.option('--algorithm', '-a', default='HS256',
              type=click.Choice(['HS256', 'RS256']),
              show_default=True, help='Signing algorithm')
@click.option('--expire', '-e', type=int, help='Expiration time in seconds from now')
@click.option('--issuer', help='Issuer (iss claim)')
@click.option('--subject', help='Subject (sub claim)')
def jwt_encode(payload, secret, algorithm, expire, issuer, subject):
    """Generate JWT token"""
    try:
        if payload.startswith('@'):
            with open(payload[1:], 'r') as f:
                payload_data = json_module.load(f)
        else:
            payload_data = json_module.loads(payload)
    except Exception as e:
        cprint(f'Invalid payload: {e}', Color.RED)
        return
    
    if secret.startswith('@'):
        with open(secret[1:], 'rb') as f:
            secret_data = f.read()
    else:
        secret_data = secret
    
    if expire:
        payload_data['exp'] = datetime.datetime.utcnow() + datetime.timedelta(seconds=expire)
    if issuer:
        payload_data['iss'] = issuer
    if subject:
        payload_data['sub'] = subject
    payload_data['iat'] = datetime.datetime.utcnow()
    
    try:
        token = pyjwt.encode(payload_data, secret_data, algorithm=algorithm)
        click.echo(token)
    except Exception as e:
        cprint(f'JWT encoding failed: {e}', Color.RED)


@jwt.command('decode')
@click.argument('token')
@click.option('--secret', '-s', help='Secret key or @file for verification')
@click.option('--algorithm', '-a', default='HS256',
              type=click.Choice(['HS256', 'RS256']),
              show_default=True, help='Signing algorithm')
@click.option('--no-verify', is_flag=True, help='Skip signature verification')
def jwt_decode(token, secret, algorithm, no_verify):
    """Decode and optionally verify a JWT token.
    
    **Security: Algorithm Confusion Attack Prevention**
    
    JWT algorithm confusion (a.k.a. "alg:none" or "RS256 vs HS256" attack) exploits
    the fact that the algorithm is specified in the *unverified* header. An attacker
    can:
    1. Take a valid JWT signed with RS256 (asymmetric, public key known)
    2. Change the header "alg" from "RS256" to "HS256" (symmetric)
    3. Re-sign the token using the *public key* as the HMAC secret
    
    If the server trusts the header's alg field and uses HS256 with the public key
    as the secret, the attacker's forged token will be accepted.
    
    **Defense Mechanisms Used Here**:
    - `algorithms=[algorithm]` explicitly specifies the expected algorithm,
      overriding whatever the token header claims. This is the primary defense.
    - The caller must specify `--algorithm` explicitly, defaulting to HS256.
    - When verifying RS256 signatures, the secret is treated as a public key,
      not an HMAC shared secret.
    
    Args:
        token: The JWT token string to decode.
        secret: Secret key (for HS256) or public key (for RS256) for verification.
            Prefix with "@" to read from file.
        algorithm: Expected signing algorithm ('HS256' or 'RS256'). This MUST be
            explicitly set and is NOT read from the token header (security measure).
        no_verify: If True, skip signature verification entirely (use with caution).
    
    Raises:
        pyjwt.ExpiredSignatureError: If the token's exp claim has passed.
        pyjwt.InvalidSignatureError: If the signature is invalid.
        ValueError: If verification is requested but no secret provided.
    """
    try:
        if no_verify:
            # Skip verification entirely - only use this for debugging!
            # The payload and header are extracted without checking the signature.
            payload = pyjwt.decode(token, options={"verify_signature": False})
            header = pyjwt.get_unverified_header(token)
            
            cprint('Header:', Color.CYAN)
            click.echo(json_module.dumps(header, ensure_ascii=False, indent=2))
            cprint('\nPayload:', Color.CYAN)
            click.echo(json_module.dumps(payload, ensure_ascii=False, indent=2))
            
            cprint('\nWarning: Signature not verified', Color.YELLOW)
        else:
            if not secret:
                cprint('Error: --secret required for verification', Color.RED)
                return
            
            if secret.startswith('@'):
                with open(secret[1:], 'rb') as f:
                    secret_data = f.read()
            else:
                secret_data = secret
            
            # CRITICAL SECURITY: Pass algorithms=[algorithm] to prevent algorithm confusion.
            # This ensures PyJWT only accepts tokens signed with the EXPLICITLY specified
            # algorithm, ignoring whatever the token header claims.
            # Without this, an attacker could forge a token with a different algorithm
            # using the same key material.
            payload = pyjwt.decode(token, secret_data, algorithms=[algorithm])
            header = pyjwt.get_unverified_header(token)
            
            cprint('Header:', Color.CYAN)
            click.echo(json_module.dumps(header, ensure_ascii=False, indent=2))
            cprint('\nPayload:', Color.CYAN)
            click.echo(json_module.dumps(payload, ensure_ascii=False, indent=2))
            
            cprint('\nSignature verified', Color.GREEN)
            
            if 'exp' in payload:
                exp_time = datetime.datetime.fromtimestamp(payload['exp'])
                now = datetime.datetime.now()
                if exp_time < now:
                    cprint(f'Token expired at: {exp_time}', Color.RED)
                else:
                    cprint(f'Token expires at: {exp_time}', Color.GREEN)
    except pyjwt.ExpiredSignatureError:
        cprint('Error: Token has expired', Color.RED)
    except pyjwt.InvalidSignatureError:
        cprint('Error: Invalid signature', Color.RED)
    except Exception as e:
        cprint(f'JWT decoding failed: {e}', Color.RED)


@crypto.command('gencert')
@click.option('--common-name', '-cn', required=True, help='Common Name (e.g., example.com)')
@click.option('--output', '-o', default='cert', show_default=True, help='Output file prefix')
@click.option('--days', '-d', default=365, show_default=True, help='Validity in days')
@click.option('--key-size', '-b', default=2048, show_default=True, help='RSA key size')
@click.option('--country', default='CN', help='Country code')
@click.option('--state', default='Beijing', help='State or province')
@click.option('--city', default='Beijing', help='Locality/city')
@click.option('--org', default='DevKit', help='Organization')
@click.option('--org-unit', default='IT', help='Organizational unit')
def gencert(common_name, output, days, key_size, country, state, city, org, org_unit):
    """Generate self-signed certificate"""
    private_key = crypto_rsa.generate_private_key(
        public_exponent=65537,
        key_size=key_size,
        backend=default_backend()
    )
    
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COUNTRY_NAME, country),
        x509.NameAttribute(NameOID.STATE_OR_PROVINCE_NAME, state),
        x509.NameAttribute(NameOID.LOCALITY_NAME, city),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, org),
        x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, org_unit),
        x509.NameAttribute(NameOID.COMMON_NAME, common_name),
    ])
    
    cert = x509.CertificateBuilder().subject_name(
        subject
    ).issuer_name(
        issuer
    ).public_key(
        private_key.public_key()
    ).serial_number(
        x509.random_serial_number()
    ).not_valid_before(
        datetime.datetime.utcnow()
    ).not_valid_after(
        datetime.datetime.utcnow() + datetime.timedelta(days=days)
    ).add_extension(
        x509.SubjectAlternativeName([x509.DNSName(common_name)]),
        critical=False,
    ).sign(private_key, hashes.SHA256(), default_backend())
    
    cert_pem = cert.public_bytes(serialization.Encoding.PEM)
    key_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption()
    )
    
    with open(f'{output}.crt', 'wb') as f:
        f.write(cert_pem)
    with open(f'{output}.key', 'wb') as f:
        f.write(key_pem)
    
    cprint(f'Certificate: {output}.crt', Color.GREEN)
    cprint(f'Private key: {output}.key', Color.GREEN)


@crypto.group()
def password():
    """Password hashing commands"""
    pass


@password.command('hash')
@click.argument('password', required=False)
@click.option('--content', '-c', help='Direct password string')
@click.option('--algorithm', '-a', default='bcrypt',
              type=click.Choice(['bcrypt', 'scrypt', 'argon2']),
              show_default=True, help='Hash algorithm')
@click.option('--rounds', '-r', type=int, help='Cost/rounds parameter')
def password_hash(password, content, algorithm, rounds):
    """Hash a password"""
    pwd = content or password
    if not pwd and not sys.stdin.isatty():
        pwd = sys.stdin.read().strip()
    if not pwd:
        cprint('Error: No password provided', Color.RED)
        return
    
    pwd_bytes = pwd.encode('utf-8')
    
    try:
        if algorithm == 'bcrypt':
            cost = rounds or 12
            salt = bcrypt.gensalt(rounds=cost)
            hashed = bcrypt.hashpw(pwd_bytes, salt)
            click.echo(hashed.decode('ascii'))
        elif algorithm == 'scrypt':
            n = rounds or 16384
            salt = os.urandom(16)
            dk = hashlib.scrypt(pwd_bytes, salt=salt, n=n, r=8, p=1, dklen=64)
            result = f'scrypt${n}${salt.hex()}${dk.hex()}'
            click.echo(result)
        elif algorithm == 'argon2':
            try:
                from argon2 import PasswordHasher
                time_cost = rounds or 3
                ph = PasswordHasher(time_cost=time_cost)
                click.echo(ph.hash(pwd))
            except ImportError:
                cprint('Error: argon2-cffi not installed. Run: pip install argon2-cffi', Color.RED)
    except Exception as e:
        cprint(f'Hashing failed: {e}', Color.RED)


@password.command('verify')
@click.argument('password')
@click.argument('hash_str')
@click.option('--algorithm', '-a', default='bcrypt',
              type=click.Choice(['bcrypt', 'scrypt', 'argon2']),
              show_default=True, help='Hash algorithm')
def password_verify(password, hash_str, algorithm):
    """Verify a password against a hash"""
    pwd_bytes = password.encode('utf-8')
    
    try:
        if algorithm == 'bcrypt':
            if bcrypt.checkpw(pwd_bytes, hash_str.encode('ascii')):
                cprint('Password matches', Color.GREEN)
            else:
                cprint('Password does not match', Color.RED)
        elif algorithm == 'scrypt':
            parts = hash_str.split('$')
            if len(parts) != 4:
                cprint('Invalid scrypt hash format', Color.RED)
                return
            _, n, salt_hex, dk_hex = parts
            dk = hashlib.scrypt(pwd_bytes, salt=bytes.fromhex(salt_hex), n=int(n), r=8, p=1, dklen=64)
            if dk.hex() == dk_hex:
                cprint('Password matches', Color.GREEN)
            else:
                cprint('Password does not match', Color.RED)
        elif algorithm == 'argon2':
            try:
                from argon2 import PasswordHasher
                from argon2.exceptions import VerifyMismatchError
                ph = PasswordHasher()
                ph.verify(hash_str, password)
                cprint('Password matches', Color.GREEN)
            except VerifyMismatchError:
                cprint('Password does not match', Color.RED)
            except ImportError:
                cprint('Error: argon2-cffi not installed. Run: pip install argon2-cffi', Color.RED)
    except Exception as e:
        cprint(f'Verification failed: {e}', Color.RED)
