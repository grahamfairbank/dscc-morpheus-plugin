#!/usr/bin/env python3
"""Draws docs/images/how-it-works.svg, the "How it works" infographic for dscc-morpheus-plugin.

    python3 tools/draw-how-it-works.py

Edit the text below and run it again; the layout (wrapping, card heights) is computed.
Adapted from tools/draw-how-it-works.py in com-morpheus-plugin (same author, MIT).
"""
from html import escape
from pathlib import Path

W = 1400
FONT = "-apple-system, 'Segoe UI', Helvetica, Arial, sans-serif"
INK, MUTED, LINE = "#1b1f24", "#57606a", "#d0d7de"
BLUE = ("#eaf2fc", "#2a6bb8")     # browser
TEAL = ("#ecf6f2", "#1c7a5c")     # Morpheus appliance / plugin
GREEN = ("#eef7e6", "#3f7a1d")    # HVM VMs
AMBER = ("#fff5e1", "#a35f00")    # guest agent check / gates
GRAY = ("#f6f8fa", "#57606a")

out: list[str] = []
MARKERS: set[str] = set()


def t(x, y, s, size=14, weight="normal", fill=INK, anchor="start", italic=False):
    st = ' font-style="italic"' if italic else ""
    out.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" fill="{fill}" '
               f'text-anchor="{anchor}"{st}>{escape(s)}</text>')


def rect(x, y, w, h, fill, stroke, rx=10, sw=1.5, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>')


def wrap(s, width, size):
    per = max(8, int(width / (size * 0.53)))
    words, lines, cur = s.split(), [], ""
    for w in words:
        if cur and len(cur) + 1 + len(w) > per:
            lines.append(cur)
            cur = w
        else:
            cur = f"{cur} {w}".strip()
    if cur:
        lines.append(cur)
    return lines


def para(x, y, w, s, size=13, fill=INK, gap=4, bullet=None, weight="normal"):
    """Writes wrapped text; returns the y below it."""
    indent = 14 if bullet else 0
    lines = wrap(s, w - indent, size)
    for i, ln in enumerate(lines):
        if i == 0 and bullet:
            t(x, y + size, bullet, size, "bold", fill)
        t(x + indent, y + size, ln, size, weight, fill)
        y += size + gap
    return y


def card(x, y, w, title, items, colors, size=13, title_size=15, bullet="•", draw=True):
    """A rounded card with a title and wrapped bullet items; returns its height."""
    global out
    saved = out
    out = []
    cy = y + 14
    t(x + 14, cy + title_size, title, title_size, "bold", colors[1])
    cy += title_size + 10
    for it in items:
        if isinstance(it, tuple):           # (text, style)
            text, style = it
            if style == "note":
                cy = para(x + 14, cy, w - 28, text, size - 1, MUTED) + 4
            elif style == "plain":
                cy = para(x + 14, cy, w - 28, text, size) + 4
            elif style == "code":
                cy = para(x + 14, cy, w - 28, text, size - 1, colors[1], weight="bold") + 4
            else:                           # custom bullet
                cy = para(x + 14, cy, w - 28, text, size, bullet=style) + 4
        else:
            cy = para(x + 14, cy, w - 28, it, size, bullet=bullet) + 4
    h = cy - y + 8
    body = out
    out = saved
    if draw:
        rect(x, y, w, h, "#ffffff", colors[1], rx=8, sw=1.2)
        out.extend(body)
    return h


def arrow(x1, y1, x2, y2, color=INK, label=None, lx=None, ly=None, both=False):
    mid = "a" + color.lstrip("#")
    MARKERS.add(color)
    m = f' marker-start="url(#{mid})"' if both else ""
    out.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="2" marker-end="url(#{mid})"{m}/>')
    if label:
        for i, ln in enumerate(label.split("\n")):
            t(lx if lx is not None else (x1 + x2) / 2, (ly if ly is not None else (y1 + y2) / 2 - 8) + i * 15,
              ln, 12, "bold", color, "middle")


def zone(x, y, w, h, title, sub, colors):
    rect(x, y, w, h, colors[0], colors[1], rx=14, sw=2)
    t(x + 18, y + 30, title, 19, "bold", colors[1])
    t(x + 18, y + 50, sub, 13, "normal", MUTED)




def stack(x, y, w, cards, gap=12):
    for title, items, colors in cards:
        y += card(x, y, w, title, items, colors) + gap
    return y


# ---------------------------------------------------------------- architecture
TOP = 108
COL1_X, COL1_W = 24, 306
COL2_X, COL2_W = 400, 600
COL3_X, COL3_W = 1070, 306

col1 = [
    ("Cluster page › HPE Storage", [
        "Arrays serving the cluster: model, health, capacity used, data reduction",
        "GFS2 datastores and Alletra B10000 Integration disks, with their array volumes",
        "LUNs not seen by every host; HPE LUNs DSCC doesn't list",
    ], BLUE),
    ("Host page › HPE Storage", [
        "Every HPE array LUN on the host, what uses it, how many hosts see it",
    ], BLUE),
    ("VM / instance › HPE Storage", [
        "Every disk, how it's stored, and the array volume behind it",
        "Open in DSCC links; copy button on every LUN id",
    ], BLUE),
]
col2_left = [
    ("Tab providers", [
        "Show the tab only where a page uses an HPE array LUN (a diagnostics setting shows it everywhere)",
        "Optional: only for roles with View HPE Storage tab",
        "Render HTML on the appliance: the browser never talks to GreenLake",
    ], TEAL),
    ("MorpheusReader", [
        "A cluster's hosts, datastores and VMs, read at most once a minute and shared by its tabs",
    ], TEAL),
]
col2_right = [
    ("DsccIndex (background)", [
        "Every storage system, its capacity and health, and every volume",
        "500 per page; refreshed every 5 minutes while tabs are in use",
        "Tabs never wait on DSCC; on rate limits it waits as DSCC asks",
    ], TEAL),
    ("StorageMap + WwnMatcher", [
        "Reduce each LUN to one device id and join datastores, disks and hosts to array volumes",
    ], TEAL),
]
col3 = [
    ("Sign-in (OAuth2)", [
        "Workspace token issuer first; legacy GreenLake sign-in as fallback",
        "Token reused until a minute before it expires",
    ], AMBER),
    ("Data Services (DSCC) API", [
        "Storage systems, capacity, health, volumes: GET only",
        ("3PAR · Primera · Alletra 9000 · Alletra MP B10000 · Nimble · Alletra 5000/6000", "note"),
    ], GREEN),
]


def measure(cards, w):
    return sum(card(0, 0, w, a, b, c, draw=False) + 12 for a, b, c in cards)


inner = 2 * 14
half = (COL2_W - 3 * 14) // 2
h1 = measure(col1, COL1_W - inner)
h2 = max(measure(col2_left, half), measure(col2_right, half))
h3 = measure(col3, COL3_W - inner)
DATA_H = 112
ZONE_H = 66 + max(h1 + 54, h2 + DATA_H + 18, h3 + 54)

zone(COL1_X, TOP, COL1_W, ZONE_H, "① Your browser", "Morpheus web UI: nothing to install", BLUE)
zone(COL2_X, TOP, COL2_W, ZONE_H, "② Morpheus appliance", "dscc-morpheus-plugin jar, running inside Morpheus", TEAL)
zone(COL3_X, TOP, COL3_W, ZONE_H, "③ HPE GreenLake", "Your workspace, with arrays onboarded", GREEN)

stack(COL1_X + 14, TOP + 66, COL1_W - inner, col1)
stack(COL2_X + 14, TOP + 66, half, col2_left)
stack(COL2_X + 28 + half, TOP + 66, half, col2_right)
stack(COL3_X + 14, TOP + 66, COL3_W - inner, col3)

dy = TOP + ZONE_H - DATA_H - 14
rect(COL2_X + 14, dy, COL2_W - 28, DATA_H, "#ffffff", GRAY[1], rx=8, sw=1.2, dash="5 4")
t(COL2_X + 28, dy + 24, "Plugin settings (stored on the appliance)", 15, "bold", GRAY[1])
para(COL2_X + 28, dy + 34, COL2_W - 56,
     "GreenLake region · API client ID and secret · workspace ID · optional proxy · "
     "\"Only show to roles with the dscc-morpheus permission\". The secret never goes to a browser; "
     "Morpheus admins who can edit plugins can see it.", 13)

para(COL1_X + 14, TOP + ZONE_H - 46, COL1_W - 28,
     "Each page shows only the arrays and volumes behind its own LUNs.", 12, MUTED)
para(COL3_X + 14, TOP + ZONE_H - 46, COL3_W - 28,
     "Use a dedicated GreenLake account with a read-only Data Services role.", 12, MUTED)

ay = TOP + 140
arrow(COL1_X + COL1_W + 4, ay, COL2_X - 4, ay, BLUE[1], "tab\nHTML", ly=ay - 26, both=True)
arrow(COL2_X + COL2_W + 4, ay, COL3_X - 4, ay, AMBER[1], "token\n(HTTPS)", ly=ay - 26)
arrow(COL2_X + COL2_W + 4, ay + 170, COL3_X - 4, ay + 170, GREEN[1], "GET,\n5 min", ly=ay + 144, both=True)

# ---------------------------------------------------------------- the flows
FY = TOP + ZONE_H + 44
t(W / 2, FY, "What happens, step by step", 22, "bold", INK, "middle")
FY += 22
GAP = 16
PW = (W - 2 * 24 - 3 * GAP) / 4
flows = [
    ("A.  Reading DSCC", GREEN, [
        ("The appliance signs in to the workspace's token issuer with the API client.", "1"),
        ("It reads every storage system and volume, page by page, into an in-memory index.", "2"),
        ("Every 5 minutes, while someone uses the tabs, it reads again.", "3"),
        ("If one array's volumes can't be read, the last good read stays, with a note.", "note"),
    ]),
    ("B.  Opening a tab", BLUE, [
        ("Morpheus asks whether to show the tab: does this page use an HPE array LUN?", "1"),
        ("The plugin reads the Morpheus records once (shared for a minute per cluster).", "2"),
        ("It joins them to the index and renders the tab. It never waits on DSCC.", "3"),
        ("Right after a restart the tab says it's still reading.", "note"),
    ]),
    ("C.  Matching a LUN", TEAL, [
        ("Host multipath device or GFS2 block device: /dev/mapper/3<WWN> or /dev/mapper/2<serial>.", "1"),
        ("Alletra B10000 Integration disk: the WWN itself.", "2"),
        ("DSCC volume: wwn (3PAR family, Alletra MP) or serial_number (Nimble family).", "3"),
        ("All reduce to one device id. Disks on NFS or local datastores are never shown as array disks.", "4"),
    ]),
    ("D.  What it never does", AMBER, [
        ("No writes to DSCC or Morpheus", "✗"),
        ("No agents, SSH or commands on hosts", "✗"),
        ("No array passwords", "✗"),
        ("No web routes; the browser gets HTML only", "✗"),
        ("Sizes are binary (GiB, TiB); \"used on array\" is physical space after thin provisioning and data reduction.", "note"),
    ]),
]


def flow_items(items):
    return [(s, b if b in ("plain", "note") else (f"{b}." if b.isdigit() else b)) for s, b in items]


fh = max(card(0, 0, PW, title, flow_items(items), col, size=13, title_size=15, draw=False)
         for title, col, items in flows)
for i, (title, col, items) in enumerate(flows):
    x = 24 + i * (PW + GAP)
    rect(x, FY, PW, fh, col[0], col[1], rx=12, sw=2)
    saved = len(out)
    card(x, FY, PW, title, flow_items(items), col, size=13, title_size=15)
    del out[saved]              # drop the white rect, keep the text

FOOT = FY + fh + 30
t(24, FOOT, "Unofficial community project: not an HPE or Morpheus Data product, and not endorsed or supported by HPE. "
  "HPE, GreenLake, Alletra, Primera, 3PAR, Nimble and Morpheus are HPE trademarks. See NOTICE.md.",
  12, "normal", MUTED)
H = FOOT + 22

markers = "\n".join(f'<marker id="a{c.lstrip("#")}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{c}"/></marker>' for c in sorted(MARKERS))
head = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">
<title>How dscc-morpheus-plugin works</title>
<desc>Architecture and flows of dscc-morpheus-plugin: the browser, the plugin inside the Morpheus appliance, and HPE GreenLake Data Services.</desc>
<defs>
{markers}
</defs>
<rect width="{W}" height="{H}" fill="#ffffff"/>
<text x="24" y="48" font-size="30" font-weight="bold" fill="{INK}">How dscc-morpheus-plugin works</text>
<text x="24" y="76" font-size="15" fill="{MUTED}">The HPE array, volume and LUN under every HPE Morpheus datastore, host and VM, with health and capacity from HPE GreenLake Data Services. Read-only.</text>
'''
out_path = Path(__file__).resolve().parent.parent / "docs/images/how-it-works.svg"
out_path.write_text(head + "\n".join(out) + "\n</svg>\n")
print(out_path, W, H)
