import argparse
import html
import io
import ipaddress
import json
import os
import socket
import subprocess
import sys
import urllib.parse
import webbrowser

import qrcode
import qrcode.image.svg

import lan_tls

TAILSCALE_PATHS = [
    "tailscale",
    os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Tailscale", "tailscale.exe"),
]


TLS_PORT = 8766
DEFAULT_CERT_DIR = os.path.dirname(os.path.abspath(__file__))
PRIVATE_NETWORKS = ["10.0.0.0/8", "127.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12", "100.64.0.0/10", "169.254.0.0/16",
                    "::1/128", "fe80::/10", "fc00::/7"]


def tailscale_address():
    for exe in TAILSCALE_PATHS:
        try:
            out = subprocess.run([exe, "status", "--json"], capture_output=True, text=True, timeout=20, check=False,
                                 creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout
            name = json.loads(out)["Self"]["DNSName"].rstrip(".")
        except (OSError, ValueError, KeyError, subprocess.SubprocessError):
            continue
        if name:
            return f"wss://{name}"
    return None


def lan_address():
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("192.0.2.1", 9))
        return probe.getsockname()[0]
    except OSError:
        return None
    finally:
        probe.close()


def phone_address(address):
    if address.lower().startswith("https://"):
        return "wss://" + address[len("https://"):]
    return address


def home_link(address, folder, port=TLS_PORT):
    """A home-network address becomes the encrypted port with the pin of
    this computer's certificate; anything with a real certificate stays,
    and so does everything when the server's encrypted port is off (0)."""
    if not port:
        return address, None
    scheme, separator, rest = address.partition("://")
    if not separator:
        scheme, rest = "ws", address
    if scheme.lower() != "ws":
        return address, None
    host = urllib.parse.urlsplit("//" + rest).hostname or ""
    if not is_private_host(host):
        return address, None
    cert_path, _ = lan_tls.ensure_certificate(folder)
    shown = f"[{host}]" if ":" in host else host
    return f"wss://{shown}:{port}", lan_tls.certificate_pin(cert_path)


def is_private_host(host):
    name = host.lower()
    if name == "localhost" or name.endswith((".local", ".lan", ".home.arpa", ".ts.net")):
        return True
    try:
        ip = ipaddress.ip_address(name)
    except ValueError:
        return "." not in name and bool(name)
    return any(ip in ipaddress.ip_network(net) for net in PRIVATE_NETWORKS)


def read_code(path):
    with open(path, encoding="utf-8-sig") as f:
        return f.read().strip()


write_private = lan_tls.write_private


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description="Makes the QR code the phone scans to pair with this computer.")
    p.add_argument("--address", help="what the phone connects to, e.g. wss://pc.tailnet.ts.net or 192.168.1.20")
    p.add_argument("--code-file", default=os.path.join(here, "pairing-code"))
    p.add_argument("--out", default=os.path.join(here, "pairing.html"))
    p.add_argument("--no-open", action="store_true")
    p.add_argument("--lan", action="store_true", help="use this computer's address on the home network")
    p.add_argument("--cert-dir", default=DEFAULT_CERT_DIR, help="the server's --cert-dir, where lan-cert.pem is kept")
    p.add_argument("--tls-port", type=int, default=TLS_PORT, help="the server's --tls-port; 0 when it is off")
    args = p.parse_args()

    address = args.address or (lan_address() if args.lan else tailscale_address())
    if not address and not args.lan:
        address = lan_address()
        if address:
            print("Tailscale gave no address; this QR code works on the home Wi-Fi only.", file=sys.stderr)
    if not address:
        sys.exit("Couldn't work out this computer's address; pass --address wss://... or --address 192.168.1.20")
    address = phone_address(address)
    code = read_code(args.code_file)
    address, pin = home_link(address, args.cert_dir, args.tls_port)
    fields = {"address": address, "code": code}
    if pin:
        fields["pin"] = pin
    link = "ozen://pair?" + urllib.parse.urlencode(fields)

    svg = io.BytesIO()
    qrcode.make(link, image_factory=qrcode.image.svg.SvgPathImage, box_size=12, border=2).save(svg)
    page = f"""<!doctype html><meta charset="utf-8"><title>Ozen pairing</title>
<style>body{{font:18px system-ui;text-align:center;margin:40px;background:#fff;color:#111}}svg{{width:360px;height:360px}}code{{font-size:16px}}</style>
<h1>Ozen: pair a phone with this computer</h1>
<p>Open the iPhone's Camera, point it at the code, and tap the Ozen link. Ozen asks before connecting.</p>
{svg.getvalue().decode()}
<p>Or type it into Ozen's Settings: Transcription engine, Home computer:</p>
<p>Address: <code>{html.escape(address)}</code><br>Pairing code: <code>{html.escape(code)}</code></p>
<p>Anyone with this code can use this computer for captions. Keep it in the family.</p>"""
    write_private(args.out, page)
    print(f"Pairing page: {args.out}")
    print(f"Link: {link}")
    if not args.no_open:
        webbrowser.open("file://" + os.path.abspath(args.out))


if __name__ == "__main__":
    main()
