#!/usr/bin/env python3
"""
PixelFerry desktop sender (Windows / Linux / macOS). Python 3.8+, no extra packages.

Easiest: double-click "PixelFerry Send" (Windows) or run ./pixelferry-send.sh (Linux).
It asks for the folder, the Pixel's address and code, and remembers your answers.

Or from a terminal, send every photo/video in one or more folders to your Pixel XL, full quality:

    python pixelferry_send.py send "D:\\Photos" --to 192.168.1.20 --code K7QM-2XHD-9F3B-WPRA-8C1N-ZT4E --owner Shawn --device Laptop

Later, once Google Photos has backed them up, delete them from this computer:

    python pixelferry_send.py delete-backed-up "D:\\Photos" --to 192.168.1.20 --code K7QM-2XHD-9F3B-WPRA-8C1N-ZT4E

Safe to stop (Ctrl+C) and re-run any time: finished files are skipped.
"""
import argparse
import hashlib
import http.client
import json
import os
import socket
import ssl
import sys
import time
import uuid
from datetime import datetime
from urllib.parse import quote, unquote

PHOTO = {"jpg", "jpeg", "heic", "heif", "png", "gif", "webp", "bmp", "avif", "dng", "raw", "cr2", "cr3",
         "nef", "arw", "orf", "rw2", "raf", "srw", "pef"}
VIDEO = {"mp4", "mov", "m4v", "3gp", "3g2", "mkv", "avi", "webm", "mts", "m2ts", "ts", "wmv", "mpg", "mpeg"}
WRAPPED = {"insv", "insp"}
ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
STATE_DIR = os.path.join(os.path.expanduser("~"), ".pixelferry")
CHUNK = 1 << 20
GUIDED = False  # double-click mode: talk about double-clicking, not commands
POLL_SECONDS = float(os.environ.get("PIXELFERRY_POLL_SECONDS", "60"))


def ext(name):
    return name.rsplit(".", 1)[-1].lower() if "." in name else ""


def is_media(name):
    return not name.startswith(".") and ext(name) in (PHOTO | VIDEO | WRAPPED)


def base32(data):
    out, buf, bits = [], 0, 0
    for b in data:
        buf = (buf << 8) | b
        bits += 8
        while bits >= 5:
            out.append(ALPHABET[(buf >> (bits - 5)) & 31])
            bits -= 5
    if bits:
        out.append(ALPHABET[(buf << (5 - bits)) & 31])
    return "".join(out)


def parse_code(code):
    c = code.replace("-", "").replace(" ", "").upper().replace("O", "0").replace("I", "1").replace("L", "1")
    if len(c) != 24 or any(ch not in ALPHABET for ch in c):
        sys.exit("That pairing code doesn't look right. It's 24 characters like K7QM-2XHD-9F3B-WPRA-8C1N-ZT4E.")
    return c[:8], c[8:]


def fmt_bytes(n):
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{n} B"
        n /= 1024


def device_id():
    os.makedirs(STATE_DIR, exist_ok=True)
    p = os.path.join(STATE_DIR, "device-id")
    if os.path.exists(p):
        with open(p) as f:
            return f.read().strip()
    i = "pc-" + uuid.uuid4().hex
    with open(p, "w") as f:
        f.write(i)
    return i


def item_key(path, st):
    """Path + size + modified time: an edited file at the same path is a new version and gets sent."""
    return f"{os.path.abspath(path)}|{st.st_size}|{int(st.st_mtime)}"


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


class HashCache:
    """Remembers hashes so a resumed run doesn't re-read 10k files."""

    def __init__(self):
        self.path = os.path.join(STATE_DIR, "hash-cache.json")
        try:
            with open(self.path) as f:
                self.d = json.load(f)
        except Exception:
            self.d = {}
        self.dirty = 0

    def get(self, path, st):
        k = os.path.abspath(path)
        e = self.d.get(k)
        if e and e[0] == st.st_size and e[1] == int(st.st_mtime):
            return e[2]
        h = sha256_file(path)
        self.d[k] = [st.st_size, int(st.st_mtime), h]
        self.dirty += 1
        if self.dirty >= 50:
            self.save()
        return h

    def save(self):
        if not self.dirty:
            return
        os.makedirs(STATE_DIR, exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.d, f)
        os.replace(tmp, self.path)
        self.dirty = 0


DISCOVERY_PORT = 8445


def discover(timeout=2.0):
    """Find PixelFerry on this Wi-Fi. Returns [(ip, https_port, pin)]. Answers carry no secrets:
    the certificate pin is still checked over TLS before anything is sent."""
    found = {}
    targets = [os.environ.get("PIXELFERRY_DISCOVERY_ADDR", "255.255.255.255")]
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(0.3)
        end = time.time() + timeout
        while time.time() < end:
            for t in targets:
                try:
                    s.sendto(b"PIXELFERRY-DISCOVER 1", (t, int(os.environ.get("PIXELFERRY_DISCOVERY_PORT", DISCOVERY_PORT))))
                except OSError:
                    pass
            try:
                while True:
                    data, (ip, _) = s.recvfrom(256)
                    parts = data.decode("ascii", "replace").split()
                    if len(parts) == 4 and parts[0] == "PIXELFERRY" and parts[1] == "1":
                        found[(ip, parts[2], parts[3])] = (ip, int(parts[2]), parts[3])
            except (socket.timeout, OSError):
                pass
            if found:
                break
        s.close()
    except OSError:
        pass
    return list(found.values())


def paired():
    """{certificate pin: computer key} for Pixels this computer has paired with."""
    return load_settings().get("pixels", {})


def remember_pairing(pin, token):
    st = load_settings()
    st.setdefault("pixels", {})[pin] = token
    save_settings(st)


def forget_pairing(pin):
    st = load_settings()
    st.get("pixels", {}).pop(pin, None)
    save_settings(st)


class Pixel:
    def __init__(self, host, port, code=None, timeout=120, token=None, pin=None):
        self.host, self.port, self.timeout = host, port, timeout
        self.saved_key = not code
        if code:
            self.token, self.pin = parse_code(code)
        else:
            self.token, self.pin = token, pin
        self.conn = None
        self.dev = device_id()

    def _connect(self):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # we verify by pinning the exact certificate below
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        conn = http.client.HTTPSConnection(self.host, self.port, context=ctx, timeout=self.timeout, blocksize=CHUNK)
        conn.connect()
        der = conn.sock.getpeercert(binary_form=True)
        got = base32(hashlib.sha256(der).digest())[:len(self.pin)]
        if got != self.pin:
            conn.close()
            sys.exit("STOPPED: the device at that address is NOT your Pixel (security check failed). "
                     "Check the address and pairing code on the Pixel.")
        self.conn = conn

    def close(self):
        if self.conn:
            try:
                self.conn.close()
            except Exception:
                pass
        self.conn = None

    def request(self, method, path, body=None, headers=None):
        h = {"Authorization": "Bearer " + self.token, "X-Device-Id": quote(self.dev)}
        h.update(headers or {})
        if self.conn is None:
            self._connect()
        try:
            self.conn.request(method, path, body=body, headers=h)
            r = self.conn.getresponse()
            data = r.read()
        except Exception:
            self.close()
            raise
        if r.getheader("Connection", "").lower() == "close":
            self.close()
        if r.status == 401:
            if self.saved_key:
                forget_pairing(self.pin)
                sys.exit("This computer was removed from the Pixel (\"Forget paired computers\"). Run again and enter the new code.")
            sys.exit("STOPPED: the Pixel didn't accept the code. Check the code shown in PixelFerry and try again.")
        if r.status == 429:
            sys.exit("STOPPED: too many wrong codes from this computer; the Pixel blocks it for 5 minutes.")
        return r.status, data.decode("utf-8", "replace")

    def pair(self):
        """After a successful code login: get a key so this computer never needs the code again."""
        s, d = self.request("POST", "/api/pair")
        if s == 200:
            remember_pairing(self.pin, json.loads(d)["token"])
            return True
        return False

    def status(self):
        s, d = self.request("GET", "/api/status")
        return json.loads(d)


def collect(folders, since=None, until=None):
    files, skipped = [], 0
    for root in folders:
        if not os.path.isdir(root):
            sys.exit(f"Folder not found: {root}")
        for dp, dns, fns in os.walk(root):
            dns[:] = [d for d in dns if not d.startswith(".") and d.lower() != "$recycle.bin"]
            for fn in fns:
                p = os.path.join(dp, fn)
                if not is_media(fn):
                    skipped += 1
                    continue
                try:
                    st = os.stat(p)
                except OSError:
                    continue
                if since and st.st_mtime < since:
                    continue
                if until and st.st_mtime >= until:
                    continue
                files.append((p, st))
    files.sort(key=lambda x: x[1].st_mtime)
    return files, skipped


def with_retries(fn, what, tries=8):
    delay = 3
    for i in range(tries):
        try:
            return fn()
        except (OSError, http.client.HTTPException, socket.timeout) as e:
            if i == tries - 1:
                raise
            print(f"\n  connection problem ({e.__class__.__name__}) during {what}; retrying in {delay}s...")
            time.sleep(delay)
            delay = min(delay * 2, 60)


def wait_for_room(px, size):
    shown = False
    while True:
        st = with_retries(px.status, "status")
        if st["free"] - st["reserve"] >= size:
            if shown:
                print("  Room is back. Continuing.")
            return
        if not shown:
            print(f"\n  Pixel is full ({fmt_bytes(max(st['free'], 0))} free). Waiting for Google Photos to back up "
                  "and free space... (leave this running)")
            shown = True
        print(f"  [{datetime.now():%H:%M}] {st.get('message', '')}")
        time.sleep(POLL_SECONDS)


def connect(a):
    """Address: given, or found automatically. Code: given (then remembered), or a saved pairing."""
    saved = paired()
    host, port, pin = a.to, a.port, None
    if not host:
        found = discover()
        want = parse_code(a.code)[1] if a.code else None
        if want:
            mine = [f for f in found if f[2] == want]   # the Pixel whose code was typed
        else:
            mine = [f for f in found if f[2] in saved]
        pick = (mine or ([] if want else found) or [None])[0]
        if pick is None:
            sys.exit("Couldn't find the Pixel on this Wi-Fi. In PixelFerry tap 'A computer', check both are on the same Wi-Fi, "
                     "or pass --to with the address shown on the Pixel.")
        host, port, pin = pick
        print(f"Found your Pixel at {host}")
    if a.code:
        px = Pixel(host, port, a.code)
        px.status()  # verifies certificate + code
        px.pair()
        return px
    if pin is None:
        # address given but no code: try every saved pairing (the TLS pin check picks the right one)
        for p_, t_ in saved.items():
            try:
                px = Pixel(host, port, token=t_, pin=p_)
                px._connect()
                return px
            except SystemExit:
                continue
            except OSError:
                sys.exit(f"Couldn't reach the Pixel at {host}. Is PixelFerry open on 'A computer', on the same Wi-Fi?")
        sys.exit("First time with this Pixel: add --code with the code shown in PixelFerry.")
    if pin not in saved:
        sys.exit("First time with this Pixel: add --code with the code shown in PixelFerry.")
    return Pixel(host, port, token=saved[pin], pin=pin)


def cmd_send(a):
    st = load_settings()
    st.update({"owner": a.owner, "device": a.device})
    save_settings(st)
    px = connect(a)
    hello = with_retries(px.status, "connect")
    print(f"Connected to Pixel securely. Free space: {fmt_bytes(hello['free'])}")
    files, skipped = collect(a.folders, a.since, a.until)
    total = sum(st.st_size for _, st in files)
    print(f"Found {len(files)} photos/videos ({fmt_bytes(total)}). Ignored {skipped} non-media files.")

    done = set()
    for i in range(0, len(files), 500):
        body = "".join(f"{quote(item_key(p, st))}\t{st.st_size}\n" for p, st in files[i:i + 500]).encode()
        s, d = with_retries(lambda: px.request("POST", "/api/check", body=body), "check")
        for line in d.splitlines():
            if "\t" in line:
                done.add(unquote(line.split("\t")[0]))
    todo = [(p, st) for p, st in files if item_key(p, st) not in done]
    print(f"Already on Pixel: {len(files) - len(todo)}. To send now: {len(todo)}.")
    if a.dry_run:
        return

    cache = HashCache()
    sent = dup = failed = 0
    sent_bytes = 0
    t0 = time.time()
    try:
        for n, (p, st) in enumerate(todo, 1):
            sha = cache.get(p, st)
            verify_fails = 0
            while True:
                def put():
                    with open(p, "rb") as f:
                        headers = {
                            "Content-Length": str(st.st_size),
                            "X-Key": quote(item_key(p, st)), "X-Name": quote(os.path.basename(p)),
                            "X-Size": str(st.st_size), "X-Sha256": sha, "X-Mtime": str(int(st.st_mtime * 1000)),
                            "X-Owner": quote(a.owner), "X-Device": quote(a.device),
                        }
                        return px.request("PUT", "/api/upload", body=f, headers=headers)
                st_now = with_retries(px.status, "status") if n == 1 or n % 25 == 0 or st.st_size > 200 * 1024 * 1024 else None
                if st_now and st_now["free"] - st_now["reserve"] < st.st_size:
                    wait_for_room(px, st.st_size)
                try:
                    s, d = put()
                except (OSError, http.client.HTTPException, socket.timeout):
                    # connection dropped mid-upload: usually the Pixel filled up and hung up, or Wi-Fi blipped
                    px.close()
                    time.sleep(3)
                    wait_for_room(px, st.st_size)
                    s, d = with_retries(put, os.path.basename(p))
                if s == 507:  # Pixel full: wait for the Google Photos wave, doesn't count as a failure
                    wait_for_room(px, st.st_size)
                    continue
                if s == 409 and verify_fails < 2:
                    verify_fails += 1
                    print(f"\n  checksum didn't match for {p}; resending")
                    sha = sha256_file(p)
                    continue
                break
            if s == 200:
                res = json.loads(d).get("status")
                if res == "duplicate":
                    dup += 1
                else:
                    sent += 1
                sent_bytes += st.st_size
            else:
                failed += 1
                print(f"\n  FAILED {p}: {s} {d}")
            el = max(time.time() - t0, 0.001)
            rate = sent_bytes / el
            left = sum(x[1].st_size for x in todo[n:]) if n % 50 == 0 or n == len(todo) else None
            eta = f" ETA {int(left / rate // 60)} min" if left and rate > 0 else ""
            print(f"\r  {n}/{len(todo)}  {fmt_bytes(sent_bytes)}  {fmt_bytes(rate)}/s{eta}      ", end="", flush=True)
    except KeyboardInterrupt:
        print("\nStopped. " + ("Double-click PixelFerry Send again" if GUIDED else "Run the same command again") + " to continue where you left off.")
    finally:
        cache.save()
        px.close()
    print(f"\nDone. Sent {sent}, duplicates skipped {dup}, failed {failed}.")
    if GUIDED and sent:
        print("Google Photos backs them up over the next while. Double-click PixelFerry Send again later to delete them from this computer.")
    if failed:
        print(("Double-click PixelFerry Send again" if GUIDED else "Run the same command again") + " to retry the failed ones.")


def count_deletable(folder, extra):
    """How many files in this folder the Pixel says are safely backed up (nothing is deleted here)."""
    ns = argparse.Namespace(to=None, port=int(os.environ.get("PIXELFERRY_PORT", "8443")), code=None)
    if "--to" in extra:
        ns.to = extra[extra.index("--to") + 1]
    px = connect(ns)
    files, _ = collect([folder])
    keys = {item_key(p, st) for p, st in files}
    s, d = with_retries(lambda: px.request("POST", "/api/deletable"), "deletable")
    px.close()
    return sum(1 for line in d.splitlines() if "\t" in line and unquote(line.split("\t")[0]) in keys)


def cmd_delete(a):
    px = connect(a)
    files, _ = collect(a.folders)
    by_key = {item_key(p, st): (p, st) for p, st in files}
    s, d = with_retries(lambda: px.request("POST", "/api/deletable"), "deletable")
    candidates = []
    for line in d.splitlines():
        if "\t" not in line:
            continue
        k, sha = line.split("\t", 1)
        k = unquote(k)
        if k in by_key:
            candidates.append((by_key[k][0], by_key[k][1], sha.strip(), k))
    if not candidates:
        print("Nothing is ready to delete yet. Files become deletable after Google Photos has backed them up.")
        return
    size = sum(st.st_size for _, st, _, _ in candidates)
    print(f"{len(candidates)} files ({fmt_bytes(size)}) in these folders are safely backed up by Google Photos.")
    if input("Type DELETE to remove them from this computer: ").strip() != "DELETE":
        print("Cancelled. Nothing deleted.")
        return
    if input(f"Last check: permanently delete {len(candidates)} files? (yes/no): ").strip().lower() != "yes":
        print("Cancelled. Nothing deleted.")
        return
    deleted, kept = [], 0
    for p, st, sha, k in candidates:
        try:
            if os.path.getsize(p) != st.st_size or sha256_file(p) != sha:
                kept += 1  # file changed since it was sent: keep it
                continue
            os.remove(p)
            deleted.append(k)
        except OSError as e:
            kept += 1
            print(f"  could not delete {p}: {e}")
    for i in range(0, len(deleted), 500):
        body = "".join(quote(k) + "\n" for k in deleted[i:i + 500]).encode()
        with_retries(lambda: px.request("POST", "/api/mark-deleted", body=body), "mark")
    px.close()
    print(f"Deleted {len(deleted)}. Kept {kept} (changed or locked).")


def day(s):
    return datetime.strptime(s, "%Y-%m-%d").timestamp()


SETTINGS = os.path.join(STATE_DIR, "settings.json")


def load_settings():
    try:
        with open(SETTINGS) as f:
            return json.load(f)
    except Exception:
        return {}


def save_settings(d):
    os.makedirs(STATE_DIR, exist_ok=True)
    tmp = SETTINGS + ".tmp"
    with open(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:  # keys: owner-only
        json.dump(d, f)
    os.replace(tmp, SETTINGS)


def ask(prompt, default=None):
    shown = f"{prompt} [{default}]: " if default else f"{prompt}: "
    v = input(shown).strip()
    return v or (default or "")


def pick_folder():
    """A normal folder-picker window if available (Windows / most Linux), else type the path."""
    try:
        import tkinter
        from tkinter import filedialog
        root = tkinter.Tk()
        root.withdraw()
        root.attributes("-topmost", True)
        path = filedialog.askdirectory(title="Choose the folder with your photos")
        root.destroy()
        if path:
            return path
    except Exception:
        pass
    return ask("Folder with your photos (you can drag it into this window)").strip('"').strip("'")


def bundled_code():
    """The 'Send to my computer' bundle from the Pixel includes the code, so nothing has to be typed."""
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pixelferry-pairing.txt")
    try:
        with open(p) as f:
            for line in f:
                c = line.strip()
                if len(c.replace("-", "")) == 24:
                    return c
    except OSError:
        pass
    return None


def guided(folder=None):
    """Double-click mode: asks a few questions, remembers the answers."""
    print("=" * 56)
    print("  PixelFerry: send photos to your Pixel, full quality")
    print("=" * 56)
    print("On the Pixel: open PixelFerry and tap 'A computer'.\n")
    st = load_settings()
    folder = folder or pick_folder()
    if not folder or not os.path.isdir(folder):
        print("No folder chosen. Nothing sent.")
        return
    print(f"Folder: {folder}")
    print("Looking for your Pixel on this Wi-Fi...")
    found = discover()
    mine = [f for f in found if f[2] in paired()]
    extra = []
    if mine:
        print(f"Found your Pixel at {mine[0][0]} (already paired, no code needed).")
    else:
        if found:
            print(f"Found a Pixel at {found[0][0]}.")
        else:
            extra += ["--to", ask("Couldn't find it automatically. Pixel address (shown in PixelFerry)", st.get("to"))]
        code = bundled_code()
        if code:
            print("Using the pairing code that came with this sender.")
        else:
            code = ask("Code shown in PixelFerry (only needed this first time)")
        extra += ["--code", code]
    owner = ask("Whose photos are these? (e.g. Shawn)", st.get("owner"))
    device = ask("Which computer? (e.g. Laptop)", st.get("device") or socket.gethostname()[:20])
    st.update({"owner": owner, "device": device})
    if "--to" in extra:
        st["to"] = extra[extra.index("--to") + 1]
    save_settings(st)
    global GUIDED
    GUIDED = True
    # Anything from this folder Google already has? Offer to delete it first (nothing can be ready right after sending).
    if mine:
        try:
            n = count_deletable(folder, extra)
            if n and ask(f"{n} photos/videos from this folder are already safe in Google Photos. Delete them from this computer? (yes/no)", "no").lower() == "yes":
                main(["delete-backed-up", folder] + [x for i, x in enumerate(extra) if x != "--code" and extra[i - 1:i] != ["--code"]])
        except SystemExit as e:
            if e.code not in (None, 0):
                print(e.code)
    args = ["send", folder, "--owner", owner, "--device", device] + extra
    try:
        main(args)
    except SystemExit as e:
        if e.code not in (None, 0):
            print(e.code)
            if "removed from the Pixel" in str(e.code):
                code = bundled_code() or ask("New code shown in PixelFerry")
                extra = [x for i, x in enumerate(extra) if x != "--code" and extra[i - 1:i] != ["--code"]] + ["--code", code]
                try:
                    main(["send", folder, "--owner", owner, "--device", device] + extra)
                except SystemExit as e2:
                    if e2.code not in (None, 0):
                        print(e2.code)



def main(argv=None):
    ap = argparse.ArgumentParser(description="Send photos/videos to your Pixel at full quality.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("send", "delete-backed-up"):
        p = sub.add_parser(name)
        p.add_argument("folders", nargs="+")
        p.add_argument("--to", help="Pixel address (optional: found automatically on the same Wi-Fi)")
        p.add_argument("--port", type=int, default=int(os.environ.get("PIXELFERRY_PORT", "8443")))
        p.add_argument("--code", help="code shown in the app (only needed the first time)")
        if name == "send":
            p.add_argument("--owner", required=True, help="whose files, e.g. Shawn")
            p.add_argument("--device", default=socket.gethostname()[:20], help="e.g. Laptop")
            p.add_argument("--since", type=day, help="only files from this date, YYYY-MM-DD")
            p.add_argument("--until", type=day, help="only files before this date, YYYY-MM-DD")
            p.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    if a.cmd == "send":
        cmd_send(a)
    else:
        cmd_delete(a)


if __name__ == "__main__":
    if len(sys.argv) == 1 or (len(sys.argv) == 2 and os.path.isdir(sys.argv[1])):
        # double-click (or a folder dropped on it / chosen by the Windows launcher)
        try:
            guided(sys.argv[1] if len(sys.argv) == 2 else None)
        except (KeyboardInterrupt, EOFError):
            print("\nStopped. Run it again any time: finished files are skipped.")
        try:
            input("\nPress Enter to close.")
        except EOFError:
            pass
    else:
        main()
