#!/usr/bin/env python3
"""Render docs/ci-setup/CI_SETUP_GUIDE.md to CI_SETUP_GUIDE.pdf (headless Chrome/Edge, no other services).

    pip install markdown-it-py
    python docs/ci-setup/build_pdf.py            # writes docs/ci-setup/CI_SETUP_GUIDE.pdf
Re-run after every edit of the Markdown, and commit both files together.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from markdown_it import MarkdownIt

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "CI_SETUP_GUIDE.md"
TARGET = HERE / "CI_SETUP_GUIDE.pdf"

CSS = """
@page { size: A4; margin: 16mm 14mm 16mm 14mm; }
* { box-sizing: border-box; }
body { font-family: "Segoe UI", -apple-system, Helvetica, Arial, sans-serif; font-size: 10pt; line-height: 1.45;
       color: #1f2328; }
h1 { font-size: 21pt; margin: 0 0 2pt; color: #0b3d2e; }
h1 + h3 { margin-top: 0; color: #57606a; font-weight: 500; font-size: 10.5pt; }
h2 { font-size: 14pt; color: #0b3d2e; border-bottom: 2px solid #2da44e; padding-bottom: 3pt; margin-top: 18pt;
     page-break-after: avoid; }
h3 { font-size: 11.5pt; color: #0b3d2e; margin-top: 12pt; page-break-after: avoid; }
p, li { orphans: 3; widows: 3; }
a { color: #0969da; text-decoration: none; }
blockquote { margin: 8pt 0; padding: 6pt 10pt; background: #f0f7f2; border-left: 4px solid #2da44e; color: #24292f; }
blockquote p { margin: 2pt 0; }
code { font-family: Consolas, "Cascadia Mono", Menlo, monospace; font-size: 8.6pt; background: #f3f4f6;
       padding: 0.5pt 3pt; border-radius: 3px; }
pre { background: #0d1117; color: #e6edf3; padding: 8pt 10pt; border-radius: 5px; overflow-x: hidden;
      white-space: pre-wrap; word-break: break-word; page-break-inside: avoid; font-size: 8.4pt; line-height: 1.35; }
pre code { background: none; color: inherit; padding: 0; font-size: inherit; }
table { border-collapse: collapse; width: 100%; margin: 6pt 0 10pt; font-size: 8.8pt; page-break-inside: auto; }
tr { page-break-inside: avoid; }
th { background: #0b3d2e; color: #fff; text-align: left; font-weight: 600; }
th, td { border: 1px solid #d0d7de; padding: 3.5pt 5pt; vertical-align: top; }
tr:nth-child(even) td { background: #f6f8fa; }
hr { border: none; border-top: 1px solid #d0d7de; margin: 14pt 0; }
.cover-note { font-size: 8.5pt; color: #57606a; margin-top: 18pt; }
"""


def browser() -> str:
    candidates = [
        os.environ.get("CHROME"),
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        shutil.which("google-chrome"), shutil.which("chromium"), shutil.which("chrome"),
    ]
    for c in candidates:
        if c and Path(c).exists():
            return c
    sys.exit("No Chrome/Edge found; set CHROME=<path to chrome executable>")


def main() -> int:
    # CommonMark + GitHub tables (same rendering rules as GitHub, unlike Python-Markdown)
    body = MarkdownIt("commonmark", {"html": False, "typographer": False}).enable("table").render(
        SOURCE.read_text(encoding="utf-8"))
    html = (f"<!doctype html><html><head><meta charset='utf-8'><title>GrowerCare CI/CD setup</title>"
            f"<style>{CSS}</style></head><body>{body}"
            f"<p class='cover-note'>Generated from docs/ci-setup/CI_SETUP_GUIDE.md by docs/ci-setup/build_pdf.py</p>"
            f"</body></html>")
    with tempfile.TemporaryDirectory() as tmp:
        page = Path(tmp) / "guide.html"
        page.write_text(html, encoding="utf-8")
        cmd = [browser(), "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
               f"--user-data-dir={Path(tmp) / 'profile'}", f"--print-to-pdf={TARGET}", page.as_uri()]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    if not TARGET.exists() or TARGET.stat().st_size < 10_000:
        sys.exit("PDF was not produced")
    print(f"wrote {TARGET} ({TARGET.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
