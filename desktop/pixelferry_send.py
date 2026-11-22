#!/usr/bin/env python3
"""
PixelFerry desktop sender (Windows / Linux / macOS). Python 3.8+, no extra packages.

Send every photo/video in one or more folders to your Pixel XL, full quality:

    python pixelferry_send.py send "D:\\Photos" --to 192.168.1.20 --code K7QM-2XHD-9F3B-WPRA --owner Shawn --device Laptop

Later, once Google Photos has backed them up, delete them from this computer:

    python pixelferry_send.py delete-backed-up "D:\\Photos" --to 192.168.1.20 --code K7QM-2XHD-9F3B-WPRA

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
    if len(c) != 16 or any(ch not in ALPHABET for ch in c):
        sys.exit("That pairing code doesn't look right. It's 16 characters like K7QM-2XHD-9F3B-WPRA.")
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


def item_key(path):
    return os.path.normcase(os.path.abspath(path))


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
        k = item_key(path)
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


class Pixel:
    def __init__(self, host, port, code, timeout=120):
        self.host, self.port, self.timeout = host, port, timeout
        self.token, self.pin = parse_code(code)
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
        got = base32(hashlib.sha256(der).digest())[:8]
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
            sys.exit("STOPPED: wrong pairing code. Turn 'Receive from computer' off and on in the Pixel app for a new one.")
        if r.status == 429:
            sys.exit("STOPPED: too many wrong codes; the Pixel locked receiving for a minute.")
        return r.status, data.decode("utf-8", "replace")

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


def cmd_send(a):
    px = Pixel(a.to, a.port, a.code)
    hello = with_retries(px.status, "connect")
    print(f"Connected to Pixel securely. Free space: {fmt_bytes(hello['free'])}")
    files, skipped = collect(a.folders, a.since, a.until)
    total = sum(st.st_size for _, st in files)
    print(f"Found {len(files)} photos/videos ({fmt_bytes(total)}). Ignored {skipped} non-media files.")

    done = set()
    for i in range(0, len(files), 500):
        body = "".join(f"{quote(item_key(p))}\t{st.st_size}\n" for p, st in files[i:i + 500]).encode()
        s, d = with_retries(lambda: px.request("POST", "/api/check", body=body), "check")
        for line in d.splitlines():
            if "\t" in line:
                done.add(unquote(line.split("\t")[0]))
    todo = [(p, st) for p, st in files if item_key(p) not in done]
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
                            "X-Key": quote(item_key(p)), "X-Name": quote(os.path.basename(p)),
                            "X-Size": str(st.st_size), "X-Sha256": sha, "X-Mtime": str(int(st.st_mtime * 1000)),
                            "X-Owner": quote(a.owner), "X-Device": quote(a.device),
                        }
                        return px.request("PUT", "/api/upload", body=f, headers=headers)
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
        print("\nStopped. Run the same command again to continue where you left off.")
    finally:
        cache.save()
        px.close()
    print(f"\nDone. Sent {sent}, duplicates skipped {dup}, failed {failed}.")
    if failed:
        print("Run the same command again to retry the failed ones.")


def cmd_delete(a):
    px = Pixel(a.to, a.port, a.code)
    files, _ = collect(a.folders)
    by_key = {item_key(p): (p, st) for p, st in files}
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
        print("Nothing is ready to delete yet. Files become deletable after Google Photos backs them up "
              "and the Pixel app's 'Free up space' step has run.")
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


def main(argv=None):
    ap = argparse.ArgumentParser(description="Send photos/videos to your Pixel at full quality.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("send", "delete-backed-up"):
        p = sub.add_parser(name)
        p.add_argument("folders", nargs="+")
        p.add_argument("--to", required=True, help="Pixel address shown in the app, e.g. 192.168.1.20")
        p.add_argument("--port", type=int, default=8443)
        p.add_argument("--code", required=True, help="pairing code shown in the app")
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
    main()
