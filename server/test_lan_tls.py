import os
import stat
import tempfile
import unittest
from unittest import mock

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

import lan_tls

HERE = os.path.dirname(os.path.abspath(__file__))
TEST_CERTIFICATE = os.path.join(HERE, "testdata", "lan-test-cert.pem")
TEST_PIN = "EqUZTsqNgJ8Ol0fkhpLbMwToY-7X9n7_oEzJ2hZACBk"


def read(path):
    with open(path, "rb") as f:
        return f.read()


class Certificate(unittest.TestCase):
    def test_the_pin_of_the_shared_test_certificate_is_the_committed_value(self):
        self.assertEqual(lan_tls.certificate_pin(TEST_CERTIFICATE), TEST_PIN)

    def test_the_certificate_is_made_once_and_kept(self):
        with tempfile.TemporaryDirectory() as folder:
            cert, key = lan_tls.ensure_certificate(folder)
            first = (read(cert), read(key))
            self.assertEqual(lan_tls.ensure_certificate(folder), (cert, key))
            self.assertEqual((read(cert), read(key)), first)
            self.assertEqual(sorted(os.listdir(folder)), ["lan-cert.pem", "lan-key.pem"])

    def test_a_certificate_with_its_key_missing_is_made_again_as_a_pair(self):
        with tempfile.TemporaryDirectory() as folder:
            cert, key = lan_tls.ensure_certificate(folder)
            old = read(cert)
            os.remove(key)
            lan_tls.ensure_certificate(folder)
            self.assertNotEqual(read(cert), old)
            self.assertTrue(os.path.exists(key))

    def test_a_key_another_start_wrote_meanwhile_is_kept_and_the_certificate_is_made_for_it(self):
        with tempfile.TemporaryDirectory() as folder:
            key_path = os.path.join(folder, lan_tls.KEY_FILE)
            theirs = ec.generate_private_key(ec.SECP256R1()).private_bytes(
                serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
            real = lan_tls.publish_once

            def the_server_got_there_first(path, text):
                if path == key_path and not os.path.exists(path):
                    with open(path, "wb") as f:
                        f.write(theirs)
                real(path, text)

            with mock.patch.object(lan_tls, "publish_once", the_server_got_there_first):
                cert, key = lan_tls.ensure_certificate(folder)
            self.assertEqual(read(key), theirs)
            self.assertTrue(lan_tls.belongs_to(cert, serialization.load_pem_private_key(theirs, password=None)))
            self.assertEqual(sorted(os.listdir(folder)), ["lan-cert.pem", "lan-key.pem"])

    def test_a_file_that_is_already_there_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, lan_tls.CERTIFICATE_FILE)
            with open(path, "w", encoding="utf-8") as f:
                f.write("theirs")
            lan_tls.publish_once(path, "ours")
            self.assertEqual(read(path), b"theirs")
            self.assertEqual(os.listdir(folder), ["lan-cert.pem"])

    @unittest.skipIf(os.name == "nt", "Windows has no permission bits")
    def test_the_key_file_is_readable_only_by_its_owner(self):
        old = os.umask(0o022)
        try:
            with tempfile.TemporaryDirectory() as folder:
                _, key = lan_tls.ensure_certificate(folder)
                self.assertEqual(stat.S_IMODE(os.stat(key).st_mode), 0o600)
        finally:
            os.umask(old)

    def test_the_pin_is_url_safe_base64_without_padding_of_the_certificate_hash(self):
        with tempfile.TemporaryDirectory() as folder:
            pin = lan_tls.certificate_pin(lan_tls.ensure_certificate(folder)[0])
        self.assertRegex(pin, r"^[A-Za-z0-9_-]{43}$")


if __name__ == "__main__":
    unittest.main()
