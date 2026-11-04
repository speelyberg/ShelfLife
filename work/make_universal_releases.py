#!/usr/bin/env python3
"""Build assets/universal_releases.tsv from Wikipedia's "List of Universal Pictures films (decade)" pages and
"List of Focus Features films" (parse-API JSON saved in the directory given as argv[1]).

Keys match Bumpers.releaseKey in AntiPiracy.kt: year|lowercase ascii alphanumerics, & -> and.
Rows whose notes say Universal only handled some non-US territories are skipped (US release keys only)."""
import html, json, pathlib, re, sys, unicodedata
from html.parser import HTMLParser

US = re.compile(r"north america|u\.s\.|united states|\bus\b|domestic|worldwide|american")


class Tables(HTMLParser):
    """Rows of every wikitable as lists of (text, has_italic) cells; rowspans are copied down."""
    def __init__(self):
        super().__init__()
        self.tables, self.depth, self.cell, self.row, self.span = [], 0, None, None, {}

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "table":
            self.depth += 1
            if self.depth == 1 and "wikitable" in (a.get("class") or ""):
                self.tables.append([]); self.span = {}
        elif not self.tables or self.depth != 1:
            return
        elif tag == "tr":
            self.row = []
        elif tag in ("td", "th") and self.row is not None:
            self.cell = [[], False, int(re.sub(r"\D", "", a.get("rowspan", "1")) or 1)]
        elif tag == "i" and self.cell:
            self.cell[1] = True
        elif tag == "sup" and self.cell:
            self.cell.append("sup")
        elif tag == "br" and self.cell:
            self.cell[0].append(";")

    def handle_endtag(self, tag):
        if tag == "table":
            self.depth -= 1
        elif not self.tables or self.depth != 1:
            return
        elif tag == "sup" and self.cell and self.cell[-1] == "sup":
            self.cell.pop()
        elif tag in ("td", "th") and self.cell is not None:
            # fill any columns held by rowspans from earlier rows before this one
            while len(self.row) in self.span:
                c, left = self.span[len(self.row)]
                self.row.append(c)
                self.span[len(self.row) - 1] = (c, left - 1) if left > 1 else None
                if self.span[len(self.row) - 1] is None: del self.span[len(self.row) - 1]
            c = (" ".join("".join(self.cell[0]).split()), self.cell[1])
            if self.cell[2] > 1: self.span[len(self.row)] = (c, self.cell[2] - 1)
            self.row.append(c); self.cell = None
        elif tag == "tr" and self.row is not None:
            while len(self.row) in self.span:
                c, left = self.span.pop(len(self.row)); self.row.append(c)
                if left > 1: self.span[len(self.row) - 1] = (c, left - 1)
            if self.row: self.tables[-1].append(self.row)
            self.row = None

    def handle_data(self, data):
        if self.cell is not None and self.cell[-1] != "sup":
            self.cell[0].append(data)


def key(title, year):
    plain = unicodedata.normalize("NFKD", title.lower().replace("&", "and").replace("½", "12"))
    plain = re.sub(r"[^a-z0-9]+", "", "".join(ch for ch in plain if not unicodedata.combining(ch)))
    return f"{year}|{plain}"


def foreign_only(notes):
    return any(map(foreign_clause, notes.lower().split(";")))


def foreign_clause(clause):
    clause = clause.strip()
    if re.search(r"outside (of )?(the )?(north america|u\.s|united states|us\b|canada)", clause): return True
    if clause.startswith("international") and "only" in clause: return True
    if "outside" in clause: return False   # e.g. "outside French-speaking territories" still covers the US
    return "only" in clause and "distribution" in clause and clause.strip() != "distribution only" \
        and not US.search(clause)


def rows(path):
    p = Tables(); p.feed(json.loads(path.read_text())["parse"]["text"])
    for table in p.tables:
        header = [c[0].lower() for c in table[0]]
        ti = next((i for i, h in enumerate(header) if "title" in h), None)
        di = next((i for i, h in enumerate(header) if "date" in h or h == "year"), None)
        ni = next((i for i, h in enumerate(header) if h.startswith("note")), None)
        if ti is None or di is None: continue
        for r in table[1:]:
            if len(r) <= max(ti, di): continue
            year = re.search(r"\b(19|20)\d\d\b", r[di][0])
            title = re.sub(r"\s*\(.*?\)$", "", html.unescape(r[ti][0])).strip(" †‡*")
            if year and title:
                yield title, int(year.group()), r[ni][0] if ni is not None and ni < len(r) else ""


src = pathlib.Path(sys.argv[1])
keys, skipped = set(), 0
for f in sorted(src.glob("*.json")):
    for title, year, notes in rows(f):
        if foreign_only(notes): skipped += 1; continue
        keys.add(key(title, year))
out = pathlib.Path(__file__).resolve().parents[1] / "android/app/src/main/assets/universal_releases.tsv"
out.write_text("# Universal Pictures and Focus Features US release keys: year|normalized title (Wikipedia lists)\n"
               + "".join(k + "\n" for k in sorted(keys)))
print(f"{len(keys)} keys, {skipped} foreign-only rows skipped -> {out}")
