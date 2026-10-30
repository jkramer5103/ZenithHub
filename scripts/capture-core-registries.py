# Captures the registry data of vanilla's "core" pack for src/main/resources/mcdata/core-registries.bin.gz.
# Run a vanilla server of the native version (offline mode, no whitelist), then:
#   python3 scripts/capture-core-registries.py src/main/resources/mcdata/core-registries.bin.gz [host] [port] [protocol]
# It logs in, answers the known-packs offer with an empty list so the server sends every entry, and writes the
# format CoreRegistryData reads. Packet ids are those of 26.3: check them when the native version moves.
import socket, struct, zlib, uuid, gzip, io, sys
HOST = sys.argv[2] if len(sys.argv) > 2 else '127.0.0.1'
PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 25565
PROTO = int(sys.argv[4]) if len(sys.argv) > 4 else 777
s = socket.create_connection((HOST, PORT)); buf = b''
def vi(n):
    o = b''
    while True:
        b = n & 0x7f; n >>= 7
        o += bytes([b | (0x80 if n else 0)])
        if not n: return o
def rvi(b, i):
    n = sh = 0
    while True:
        x = b[i]; i += 1; n |= (x & 0x7f) << sh; sh += 7
        if not x & 0x80: return n, i
comp = -1
def send(pid, data):
    body = vi(pid) + data
    if comp >= 0: body = vi(0) + body
    s.sendall(vi(len(body)) + body)
def recv():
    global buf
    while True:
        try:
            n, i = rvi(buf, 0)
            if len(buf) - i >= n:
                pkt = buf[i:i+n]; buf = buf[i+n:]; break
        except IndexError: pass
        d = s.recv(65536)
        if not d: raise SystemExit('closed')
        buf += d
    if comp >= 0:
        dl, j = rvi(pkt, 0); pkt = zlib.decompress(pkt[j:]) if dl else pkt[j:]
    pid, j = rvi(pkt, 0); return pid, pkt[j:]
def string(x): b = x.encode(); return vi(len(b)) + b
def rstr(b, i):
    n, i = rvi(b, i); return b[i:i+n].decode(), i + n
def skip_nbt(b, i, t):
    if t == 1: return i + 1
    if t == 2: return i + 2
    if t in (3, 5): return i + 4
    if t in (4, 6): return i + 8
    if t == 7: n = struct.unpack('>i', b[i:i+4])[0]; return i + 4 + n
    if t == 8: n = struct.unpack('>H', b[i:i+2])[0]; return i + 2 + n
    if t == 9:
        et = b[i]; n = struct.unpack('>i', b[i+1:i+5])[0]; i += 5
        for _ in range(n): i = skip_nbt(b, i, et)
        return i
    if t == 10:
        while True:
            et = b[i]; i += 1
            if et == 0: return i
            nl = struct.unpack('>H', b[i:i+2])[0]; i += 2 + nl
            i = skip_nbt(b, i, et)
    if t == 11: n = struct.unpack('>i', b[i:i+4])[0]; return i + 4 + 4 * n
    if t == 12: n = struct.unpack('>i', b[i:i+4])[0]; return i + 4 + 8 * n
    raise ValueError(t)
send(0x00, vi(PROTO) + string(HOST) + struct.pack('>H', PORT) + vi(2))
send(0x00, string('Capture') + uuid.uuid3(uuid.NAMESPACE_DNS, 'cap').bytes)
while True:
    pid, d = recv()
    if pid == 0x03: comp, _ = rvi(d, 0)
    elif pid == 0x02: send(0x03, b''); break
    elif pid == 0x00: raise SystemExit('kicked at login: ' + d[:200].decode('latin1'))
regs = {}
while True:
    pid, d = recv()
    if pid == 15: send(7, vi(0))
    elif pid == 7:
        name, i = rstr(d, 0); n, i = rvi(d, i); ent = {}
        for _ in range(n):
            eid, i = rstr(d, i); has = d[i]; i += 1
            if has:
                t = d[i]; end = skip_nbt(d, i + 1, t); ent[eid] = d[i:end]; i = end
            else: ent[eid] = None
        regs[name] = ent
    elif pid == 3: break
missing = [(r, e) for r, es in regs.items() for e, v in es.items() if v is None]
print(len(regs), 'registries,', sum(len(v) for v in regs.values()), 'entries, missing data:', len(missing))
out = io.BytesIO(); w = out.write
def utf(x): b = x.encode(); w(struct.pack('>H', len(b)) + b)
w(struct.pack('>i', len(regs)))
for r, es in regs.items():
    utf(r); w(struct.pack('>i', len(es)))
    for e, v in es.items(): utf(e); w(v)
with open(sys.argv[1], 'wb') as f: f.write(gzip.compress(out.getvalue(), mtime=0))
