import base64
import contextlib
import datetime
import hashlib
import os
import ssl
import tempfile

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

CERTIFICATE_FILE = "lan-cert.pem"
KEY_FILE = "lan-key.pem"
VALID_DAYS = 20 * 365


def write_private(path, text):
    with open(path, "w", encoding="utf-8", opener=lambda name, flags: os.open(name, flags, 0o600)) as f:
        f.write(text)
    with contextlib.suppress(OSError):
        os.chmod(path, 0o600)


def der_pin(der):
    return base64.urlsafe_b64encode(hashlib.sha256(der).digest()).decode().rstrip("=")


def certificate_pin(path):
    with open(path, "rb") as f:
        certificate = x509.load_pem_x509_certificate(f.read())
    return der_pin(certificate.public_bytes(serialization.Encoding.DER))


def publish_once(path, text):
    """Writes the file only if it isn't there yet, all at once: a server and
    the pairing page starting together each made their own certificate, and
    the QR code could carry the pin of the one the server didn't load."""
    handle, temporary = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".lan-", suffix=".tmp")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as f:
            f.write(text)
        with contextlib.suppress(FileExistsError):
            os.link(temporary, path)
    finally:
        os.unlink(temporary)


def belongs_to(cert_path, key):
    with open(cert_path, "rb") as f:
        certificate = x509.load_pem_x509_certificate(f.read())
    spki = (serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return certificate.public_key().public_bytes(*spki) == key.public_key().public_bytes(*spki)


def ensure_certificate(folder):
    cert_path = os.path.join(folder, CERTIFICATE_FILE)
    key_path = os.path.join(folder, KEY_FILE)
    if not os.path.exists(key_path):
        publish_once(key_path, ec.generate_private_key(ec.SECP256R1()).private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode("ascii"))
    # The key that won, whoever wrote it, is the one the certificate is for.
    with open(key_path, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    if os.path.exists(cert_path) and not belongs_to(cert_path, key):
        with contextlib.suppress(FileNotFoundError):
            os.remove(cert_path)
    if not os.path.exists(cert_path):
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Ozen home computer")])
        now = datetime.datetime.now(datetime.timezone.utc)
        certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                       .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
                       .not_valid_after(now + datetime.timedelta(days=VALID_DAYS)).sign(key, hashes.SHA256()))
        publish_once(cert_path, certificate.public_bytes(serialization.Encoding.PEM).decode("ascii"))
    return cert_path, key_path


def server_context(folder):
    cert_path, key_path = ensure_certificate(folder)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert_path, key_path)
    return context
