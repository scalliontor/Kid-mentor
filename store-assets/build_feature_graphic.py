#!/usr/bin/env python3
"""Build the KidMentor Google Play Feature Graphic (1024x500) as SVG, embedding the
app mascot + partner logos (PTIT, CTS Lab), then render to PNG via rsvg-convert.

Style: clean green-pastel. Bilingual VI/EN. Mascot hero on the right.
Partner logos sit top-left above the eyebrow line (transparent padding auto-trimmed).
"""
import base64
import io
import os
import subprocess
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = "/home/namnx/Ptalk_project/App/app/src/main/res/drawable"
MASCOT = f"{RES}/char_idle.png"
PTIT = f"{RES}/logo_ptit.png"
CTS = f"{RES}/logo_cts_main.png"
SVG_OUT = os.path.join(HERE, "feature_graphic.svg")
PNG_OUT = os.path.join(HERE, "feature_graphic_1024x500.png")

W, H = 1024, 500


def embed(path):
    with open(path, "rb") as f:
        return "data:image/png;base64," + base64.b64encode(f.read()).decode()


def embed_trimmed(path):
    """Embed PNG with its transparent border cropped; return (uri, w, h)."""
    im = Image.open(path).convert("RGBA")
    bbox = im.getbbox()
    if bbox:
        im = im.crop(bbox)
    buf = io.BytesIO()
    im.save(buf, format="PNG")
    uri = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
    return uri, im.width, im.height


mascot_uri = embed(MASCOT)
ptit_uri, ptit_w, ptit_h = embed_trimmed(PTIT)
cts_uri, cts_w, cts_h = embed_trimmed(CTS)


def plus(cx, cy, s, color, opacity=1.0, sw=None):
    sw = sw or s * 0.34
    return (
        f'<g stroke="{color}" stroke-width="{sw}" stroke-linecap="round" opacity="{opacity}">'
        f'<line x1="{cx-s}" y1="{cy}" x2="{cx+s}" y2="{cy}"/>'
        f'<line x1="{cx}" y1="{cy-s}" x2="{cx}" y2="{cy+s}"/></g>'
    )


# ── top-left partner-logo strip (PTIT | CTS Lab), aligned to a common height ──
LOGO_H = 60
lx, ly = 72, 28
ptit_dw = LOGO_H * ptit_w / ptit_h
cts_dw = LOGO_H * cts_w / cts_h
gap = 22
div_x = lx + ptit_dw + gap
cts_x = div_x + gap
logo_strip = (
    f'<image xlink:href="{ptit_uri}" x="{lx:.1f}" y="{ly}" width="{ptit_dw:.1f}" height="{LOGO_H}"/>'
    f'<line x1="{div_x:.1f}" y1="{ly+8}" x2="{div_x:.1f}" y2="{ly+LOGO_H-8}" '
    f'stroke="#8CC9AC" stroke-width="2" stroke-linecap="round"/>'
    f'<image xlink:href="{cts_uri}" x="{cts_x:.1f}" y="{ly}" width="{cts_dw:.1f}" height="{LOGO_H}"/>'
)

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
     width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#F2FBF7"/>
      <stop offset="0.55" stop-color="#DDF3E8"/>
      <stop offset="1" stop-color="#C7E8D8"/>
    </linearGradient>
    <radialGradient id="spot" cx="0.5" cy="0.45" r="0.6">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.95"/>
      <stop offset="0.7" stop-color="#FFFFFF" stop-opacity="0.55"/>
      <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="title" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#2E8B5B"/>
      <stop offset="1" stop-color="#1C6B43"/>
    </linearGradient>
  </defs>

  <!-- background -->
  <rect width="{W}" height="{H}" fill="url(#bg)"/>

  <!-- soft depth blobs -->
  <circle cx="120" cy="430" r="200" fill="#FFFFFF" opacity="0.28"/>
  <circle cx="960" cy="60"  r="170" fill="#BFE6D2" opacity="0.45"/>
  <circle cx="540" cy="-40" r="150" fill="#FFFFFF" opacity="0.20"/>

  <!-- decorative plus sparkles (mascot eye motif) -->
  {plus(610, 70, 10, "#7FBE9D", 0.8)}
  {plus(470, 430, 11, "#86C3A3", 0.7)}
  {plus(995, 330, 12, "#86C3A3", 0.6)}
  {plus(60, 250, 12, "#7FBE9D", 0.85)}

  <!-- ===== mascot hero (right) ===== -->
  <circle cx="812" cy="250" r="212" fill="url(#spot)"/>
  <circle cx="812" cy="250" r="196" fill="none" stroke="#9AD3B6" stroke-width="3" opacity="0.55"/>
  <image xlink:href="{mascot_uri}" x="648" y="34" width="328" height="492"
         preserveAspectRatio="xMidYMid meet"/>

  <!-- ===== text block (left) ===== -->
  <!-- partner logos -->
  {logo_strip}

  <!-- eyebrow pill -->
  <rect x="72" y="120" width="244" height="40" rx="20" fill="#2E7D52" opacity="0.12"/>
  <text x="94" y="146" font-family="DejaVu Sans" font-size="18" font-weight="bold"
        letter-spacing="1.5" fill="#2E7D52">TRỢ LÝ AI CHO TRẺ EM</text>

  <!-- title -->
  <text x="70" y="256" font-family="DejaVu Sans" font-size="88" font-weight="bold"
        fill="url(#title)" letter-spacing="-1">Kid Mentor</text>

  <!-- tagline VI -->
  <text x="72" y="310" font-family="DejaVu Sans" font-size="34" font-weight="bold"
        fill="#2E7D52">Người bạn AI học tập của bé</text>

  <!-- subtitle EN -->
  <text x="72" y="347" font-family="DejaVu Sans" font-size="21" font-style="italic"
        fill="#5FA384">Your child's friendly AI learning companion</text>
</svg>'''

with open(SVG_OUT, "w") as f:
    f.write(svg)

subprocess.run(
    ["rsvg-convert", "-w", str(W), "-h", str(H), SVG_OUT, "-o", PNG_OUT],
    check=True,
)
print("Wrote", PNG_OUT)
print(f"PTIT trimmed {ptit_w}x{ptit_h} -> {ptit_dw:.0f}x{LOGO_H} | CTS trimmed {cts_w}x{cts_h} -> {cts_dw:.0f}x{LOGO_H}")
