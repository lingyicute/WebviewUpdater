#!/usr/bin/env python3
"""子集化 Nebulove 字体并内嵌进 web/index.html"""
import re, base64, urllib.request, os, sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_URL = "https://raw.githubusercontent.com/lingyicute/Nebulove/main/Nebulove.ttf"
FALLBACK_URL = "https://cdn.jsdelivr.net/gh/lingyicute/Nebulove@main/Nebulove.woff2"
INDEX_PATH = os.path.join(REPO_ROOT, "web", "index.html")
TMP_FONT_PATH = "/tmp/Nebulove.ttf"

def main():
    if not os.path.exists(INDEX_PATH):
        print(f"Error: {INDEX_PATH} not found.", file=sys.stderr)
        sys.exit(1)

    print("Reading web/index.html...")
    with open(INDEX_PATH, "r", encoding="utf-8") as f:
        html = f.read()

    # 1. Collect all characters needed
    chars = set(html)
    # Ensure full ASCII printable set (32 to 126)
    for c in range(32, 127):
        chars.add(chr(c))
    # Common punctuation
    chars.update(['：', '，', '。', '！', '？', '；', '“', '”', '‘', '’', '（', '）', '【', '】', '—', '…', '·', '《', '》', '×', '＝', '÷', '＋', '－'])

    print(f"Total unique characters needed: {len(chars)}")

    # 2. Download full Nebulove font
    print(f"Downloading font from {FONT_URL}...")
    try:
        urllib.request.urlretrieve(FONT_URL, TMP_FONT_PATH)
    except Exception as e:
        print(f"Failed to download font: {e}", file=sys.stderr)
        sys.exit(1)

    # 3. Subset font using fontTools
    from fontTools.ttLib import TTFont
    from fontTools.subset import Subsetter, Options

    print("Subsetting font...")
    font = TTFont(TMP_FONT_PATH)
    subsetter = Subsetter(options=Options())
    subsetter.populate(text="".join(chars))
    subsetter.subset(font)

    # 固定时间戳，保证同样输入产出字节一致的 WOFF2，
    # 否则每次运行都会因为 head.modified 变化而产生无意义的 commit
    font["head"].modified = 0

    font.flavor = "woff2"
    tmp_woff2 = "/tmp/Nebulove-Subset.woff2"
    font.save(tmp_woff2)

    woff2_size = os.path.getsize(tmp_woff2)
    print(f"Subsetted WOFF2 size: {woff2_size} bytes ({woff2_size / 1024:.2f} KB)")

    # 4. Convert to base64
    with open(tmp_woff2, "rb") as f:
        b64_font = base64.b64encode(f.read()).decode("utf-8")

    # 5. Replace font-face in web/index.html
    font_css = f'''@font-face{{
  font-family:"Nebulove";
  src:url("data:font/woff2;charset=utf-8;base64,{b64_font}") format("woff2"),
      url("{FALLBACK_URL}") format("woff2");
  font-weight:100 900;
  font-display:swap;
}}'''

    new_html = re.sub(r'@font-face\s*\{[^}]*\}', font_css, html, flags=re.DOTALL)

    if new_html == html:
        print("web/index.html is already up to date. No changes made.")
        return

    with open(INDEX_PATH, "w", encoding="utf-8") as f:
        f.write(new_html)

    print("web/index.html updated successfully!")

if __name__ == "__main__":
    main()
