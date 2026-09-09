"""Regenerate flite-android/CMakeLists.txt with correct source globs:
- flite core: src/**  minus audio/* except au_none.c, minus siod (unused)
- lang modules: cmulex, usenglish, cmu_indic_lang, cmu_indic_lex, cmu_us_kal16,
  cmu_time_awb
- happyalu JNI service/engine/voices/string (.cc)
Output: libttsflite.so for app ABIs. Gradle drives; no NDK gcc needed."""
import io
import os

D = os.path.dirname(os.path.abspath(__file__))
FLITE = os.path.join(D, "flite-src")
JNI = os.path.join(D, "flite-android")


def cfiles(rel):
    base = os.path.join(FLITE, rel)
    out = []
    for root, dirs, files in os.walk(base):
        for f in sorted(files):
            if f.endswith(".c"):
                out.append(os.path.relpath(os.path.join(root, f), FLITE).replace("\\", "/"))
    return out


core = cfiles("src")
# audio: keep the neutral layer (auclient/audio/au_none/au_streaming),
# drop only platform device backends:
core = [f for f in core if not (
    f.startswith("src/audio/") and os.path.basename(f) not in
    ("auclient.c", "audio.c", "au_none.c", "au_streaming.c"))]
core = [f for f in core if not f.startswith("src/siod/")]
# platform-specific variants that don't compile under Android:
core = [f for f in core if not any(
    x in f for x in ("palmos", "win32", "wince", "au_alsa", "au_oss", "au_pulse",
                     "au_sun", "au_mac", "au_esd"))]
print("core:", len(core))

lex = cfiles("lang/cmulex")
# raw data fragments are #included by the *_tables/*.c master files, not compiled:
def looks_like_data(fpath):
    try:
        with open(fpath, encoding="utf-8", errors="replace") as fh:
            head = "".join(fh.readlines(3)[:3]).strip()
    except Exception:
        return True
    # data blobs start with numbers, bare strings, or commas — not C decls
    return head[:1] in ("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", '"', ",", "-") and "{" not in head[:80]

def drop_data(files):
    return [f for f in files if not looks_like_data(os.path.join(FLITE, f))]

# .c files that are #included by other .c files (data fragments) must not be
# compiled directly.
INCLUDED_C = set()
import re as _re
for rel in cfiles("src") + cfiles("lang"):
    try:
        t = io.open(os.path.join(FLITE, rel), encoding="utf-8", errors="replace").read()
    except Exception:
        continue
    for m in _re.finditer(r'#include\s+"([^"]+\.c)"', t):
        INCLUDED_C.add(m.group(1))
print("c-included fragments:", sorted(INCLUDED_C))

def drop_included(files):
    return [f for f in files if os.path.basename(f) not in INCLUDED_C]

lex = drop_included(drop_data(cfiles("lang/cmulex")))
use = drop_included(drop_data(cfiles("lang/usenglish")))
indlang = drop_included(drop_data(cfiles("lang/cmu_indic_lang")))
indlex = drop_included(drop_data(cfiles("lang/cmu_indic_lex")))
kal16 = drop_included(drop_data(cfiles("lang/cmu_us_kal16")))
timewb = drop_included(drop_data(cfiles("lang/cmu_time_awb")))
core = drop_included(drop_data(core))
print("lex", len(lex), "useng", len(use), "indic_lang", len(indlang),
      "indic_lex", len(indlex), "kal16", len(kal16), "time", len(timewb), "core", len(core))

all_c = "\n".join(f"    ${{FLITE_DIR}}/{f}" for f in core + lex + use + indlang + indlex + kal16 + timewb)

cmake = f"""cmake_minimum_required(VERSION 3.21)
project(flite_tts C CXX)

set(FLITE_DIR ${{CMAKE_CURRENT_SOURCE_DIR}}/../flite-src)
set(CMAKE_C_STANDARD 99)
add_compile_options(-O2 -w)

# flite build-time defines (mirror config/config android defaults)
add_compile_definitions(
    FLITE_PARAMETER_FIXED_POINT=0
    WORDS_BIGENDIAN=0
    NO_AUDIO
)

include_directories(
    ${{FLITE_DIR}}/include
    ${{FLITE_DIR}}/src/audio
    ${{FLITE_DIR}}/src/utils
    ${{FLITE_DIR}}/src/regex
    ${{FLITE_DIR}}/src/hrg
    ${{FLITE_DIR}}/src/lexicon
    ${{FLITE_DIR}}/src/lang
    ${{FLITE_DIR}}/src/cg
    ${{FLITE_DIR}}/src/speech
    ${{FLITE_DIR}}/src/wcart
    ${{FLITE_DIR}}/src/shared
    ${{FLITE_DIR}}/lang/usenglish
    ${{FLITE_DIR}}/lang/cmulex
    ${{FLITE_DIR}}/lang/cmu_indic_lang
    ${{FLITE_DIR}}/lang/cmu_indic_lex
)

add_library(flite_all STATIC
{all_c}
)

add_library(ttsflite SHARED
    ${{CMAKE_CURRENT_SOURCE_DIR}}/itantra_flite_jni.cc
)
target_include_directories(ttsflite PRIVATE
    ${{FLITE_DIR}}/include
    ${{CMAKE_CURRENT_SOURCE_DIR}}/jni
)
target_link_libraries(ttsflite PRIVATE flite_all log)
"""

out = os.path.join(JNI, "CMakeLists.txt")
io.open(out, "w", encoding="utf-8").write(cmake)
print("written", out, len(cmake), "chars")
