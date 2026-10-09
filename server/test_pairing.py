import io
import json
import os
import runpy
import tempfile
import unittest
from unittest import mock

import pairing


class Done:
    def __init__(self, stdout):
        self.stdout = stdout


class TailscaleAddress(unittest.TestCase):
    def test_a_fresh_install_is_found_by_its_full_path(self):
        def run(command, **options):
            if command[0] == "tailscale":
                raise FileNotFoundError("not on PATH yet")
            if not (options.get("capture_output") and options.get("text")):
                return Done(None)
            return Done(json.dumps({"Self": {"DNSName": "desktop.tail0example.ts.net."}}))

        with mock.patch("subprocess.run", run):
            self.assertEqual(pairing.tailscale_address(), "wss://desktop.tail0example.ts.net")

    def test_no_tailscale_anywhere_gives_no_address(self):
        def run(command, **_):
            raise FileNotFoundError(command[0])

        with mock.patch("subprocess.run", run):
            self.assertIsNone(pairing.tailscale_address())


class LanAddress(unittest.TestCase):
    def find(self, reachable=True):
        probes = []

        class Probe:
            def __init__(self, *args):
                self.peer, self.closed = None, False
                probes.append(self)

            def connect(self, peer):
                if not reachable:
                    raise OSError("network is unreachable")
                self.peer = peer

            def getsockname(self):
                return ("192.168.1.20", 50000) if self.peer else ("0.0.0.0", 0)

            def close(self):
                self.closed = True

        with mock.patch.object(pairing.socket, "socket", Probe):
            return pairing.lan_address(), probes

    def test_the_home_address_is_the_one_the_computer_reaches_out_from_and_the_probe_is_closed(self):
        address, probes = self.find()
        self.assertEqual(address, "192.168.1.20")
        self.assertTrue(probes[0].closed)

    def test_no_network_gives_no_address_and_still_closes_the_probe(self):
        address, probes = self.find(reachable=False)
        self.assertIsNone(address)
        self.assertTrue(probes[0].closed)


class PhoneAddress(unittest.TestCase):
    def test_the_funnel_https_address_becomes_wss(self):
        self.assertEqual(pairing.phone_address("https://pc.tail0example.ts.net"), "wss://pc.tail0example.ts.net")
        self.assertEqual(pairing.phone_address("HTTPS://pc.tail0example.ts.net"), "wss://pc.tail0example.ts.net")

    def test_other_addresses_are_left_alone(self):
        for address in ["wss://pc.tail0example.ts.net", "192.168.1.20", "ws://100.64.0.7:8765"]:
            self.assertEqual(pairing.phone_address(address), address)


class PairingPage(unittest.TestCase):
    def setUp(self):
        self.umask = os.umask(0o022)

    def tearDown(self):
        os.umask(self.umask)

    def make_page(self, folder):
        code = os.path.join(folder, "pairing-code")
        with open(code, "w", encoding="utf-8") as f:
            f.write("example-code-123\n")
        out = os.path.join(folder, "pairing.html")
        argv = ["pairing.py", "--address", "192.168.1.20", "--code-file", code, "--out", out, "--no-open"]
        with mock.patch("sys.argv", argv), mock.patch("sys.stdout", io.StringIO()):
            pairing.main()
        return out

    def test_the_page_holding_the_code_is_readable_only_by_its_owner(self):
        with tempfile.TemporaryDirectory() as folder:
            out = self.make_page(folder)
            self.assertEqual(os.stat(out).st_mode & 0o777, 0o600)
            with open(out, encoding="utf-8") as f:
                self.assertIn("example-code-123", f.read())

    def test_a_page_an_older_version_left_readable_is_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            out = os.path.join(folder, "pairing.html")
            with open(out, "w", encoding="utf-8") as f:
                f.write("old page")
            os.chmod(out, 0o644)
            self.make_page(folder)
            self.assertEqual(os.stat(out).st_mode & 0o777, 0o600)

    def test_the_page_shows_a_code_the_camera_can_scan(self):
        with tempfile.TemporaryDirectory() as folder:
            with open(self.make_page(folder), encoding="utf-8") as f:
                page = f.read()
            self.assertIn("<svg", page)
            self.assertIn("<path", page)

    def test_running_the_file_makes_the_page(self):
        with tempfile.TemporaryDirectory() as folder:
            code = os.path.join(folder, "pairing-code")
            with open(code, "w", encoding="utf-8") as f:
                f.write("example-code-123\n")
            out = os.path.join(folder, "pairing.html")
            argv = ["pairing.py", "--address", "192.168.1.20", "--code-file", code, "--out", out, "--no-open"]
            with mock.patch("sys.argv", argv), mock.patch("sys.stdout", io.StringIO()):
                runpy.run_path(pairing.__file__, run_name="__main__")
            self.assertTrue(os.path.exists(out))

    def test_a_page_whose_permissions_cannot_be_changed_is_still_made(self):
        with tempfile.TemporaryDirectory() as folder, mock.patch("os.chmod", side_effect=PermissionError("locked")):
            out = self.make_page(folder)
            with open(out, encoding="utf-8") as f:
                self.assertIn("example-code-123", f.read())


class AddressChoice(unittest.TestCase):
    def run_main(self, folder, extra, tailscale, lan, open_page=False):
        code = os.path.join(folder, "pairing-code")
        with open(code, "w", encoding="utf-8") as f:
            f.write("example-code-123\n")
        argv = ["pairing.py", "--code-file", code, "--out", os.path.join(folder, "pairing.html")] \
            + ([] if open_page else ["--no-open"]) + extra
        out, err = io.StringIO(), io.StringIO()
        asked = mock.Mock(return_value=tailscale)
        self.opened = mock.Mock()
        with mock.patch("sys.argv", argv), mock.patch("sys.stdout", out), mock.patch("sys.stderr", err), \
                mock.patch.object(pairing, "tailscale_address", asked), \
                mock.patch.object(pairing, "lan_address", return_value=lan), \
                mock.patch.object(pairing.webbrowser, "open", self.opened):
            pairing.main()
        return out.getvalue(), err.getvalue(), asked.called

    def test_the_page_opens_in_the_browser_unless_told_not_to(self):
        with tempfile.TemporaryDirectory() as folder:
            self.run_main(folder, [], "wss://pc.tail0example.ts.net", None, open_page=True)
            page = "file://" + os.path.abspath(os.path.join(folder, "pairing.html"))
            self.opened.assert_called_once_with(page)
            self.run_main(folder, [], "wss://pc.tail0example.ts.net", None)
            self.opened.assert_not_called()

    def test_the_tailscale_address_is_used_when_there_is_one(self):
        with tempfile.TemporaryDirectory() as folder:
            out, err, _ = self.run_main(folder, [], "wss://pc.tail0example.ts.net", "192.168.1.20")
            self.assertIn("address=wss%3A%2F%2Fpc.tail0example.ts.net", out)
            self.assertEqual(err, "")

    def test_without_tailscale_the_home_address_is_used_and_said_to_work_at_home_only(self):
        with tempfile.TemporaryDirectory() as folder:
            out, err, _ = self.run_main(folder, [], None, "192.168.1.20")
            self.assertIn("address=192.168.1.20", out)
            self.assertIn("home Wi-Fi only", err)

    def test_with_no_address_at_all_it_stops_and_says_how_to_give_one(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(SystemExit) as stopped:
                self.run_main(folder, [], None, None)
            self.assertIn("--address", str(stopped.exception.code))
            self.assertFalse(os.path.exists(os.path.join(folder, "pairing.html")))

    def test_lan_asks_only_for_the_home_address(self):
        with tempfile.TemporaryDirectory() as folder:
            out, err, asked_tailscale = self.run_main(folder, ["--lan"], "wss://pc.tail0example.ts.net", "192.168.1.20")
            self.assertIn("address=192.168.1.20", out)
            self.assertFalse(asked_tailscale)
            self.assertEqual(err, "")


class CodeFile(unittest.TestCase):
    def test_a_code_saved_again_by_notepad_loses_its_byte_order_mark(self):
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, "pairing-code")
            with open(path, "w", encoding="utf-8-sig", newline="") as f:
                f.write("example-code-123\r\n")
            self.assertEqual(pairing.read_code(path), "example-code-123")


if __name__ == "__main__":
    unittest.main()
