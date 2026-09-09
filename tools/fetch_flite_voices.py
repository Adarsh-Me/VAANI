"""Download festvox flitevox Indic voices into app assets with clean names."""
import os
import urllib.request

ASSETS = r"C:\Users\prabhat\OneDrive\Desktop\codingbase\Projects\iTantra\app\src\main\assets\flite"
BASE = "http://festvox.org/flite/voices/cg/voxdata-v2.0.0/"
VOICES = {
    # app lang code -> (server file, local asset name)
    "ta": ("tam-IND-female;sxv.flitevox", "tamil_sxv.flitevox"),
    "gu": ("guj-IND-female;axb.flitevox", "gujarati_axb.flitevox"),
    "mr": ("mar-IND-female;slp.flitevox", "marathi_slp.flitevox"),
    "te": ("tel-IND-female;knr.flitevox", "telugu_knr.flitevox"),
    "hi2": ("hin-IND-female;axb.flitevox", "hindi_axb.flitevox"),
}

os.makedirs(ASSETS, exist_ok=True)
for lang, (remote, local) in VOICES.items():
    dest = os.path.join(ASSETS, local)
    if os.path.exists(dest) and os.path.getsize(dest) > 5_000_000:
        print("skip", local, os.path.getsize(dest))
        continue
    url = BASE + remote.replace(";", "%3B")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=180) as r, open(dest + ".tmp", "wb") as f:
        f.write(r.read())
    sz = os.path.getsize(dest + ".tmp")
    if sz < 1_000_000:
        os.remove(dest + ".tmp")
        print("FAIL (too small):", url)
    else:
        if os.path.exists(dest):
            os.remove(dest)
        os.rename(dest + ".tmp", dest)
        print("OK", local, round(sz / 1e6, 1), "MB")
print("DONE")
