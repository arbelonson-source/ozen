import os
import stat
import tempfile
import unittest

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
