#!/bin/sh
# ============================================================
# 重建 App 内嵌的 Python 运行时（py_runtime.zip + jniLibs 里的二进制）
#
# 为什么需要这个脚本：Android 10+ 禁止 exec App 私有目录里的文件，
# 所以要把 python 主程序放进 APK 的 native lib 目录；而 Termux 的二进制
# 又把 rpath 写死成 /data/data/com.termux/files/usr/lib，必须用 patchelf 改掉。
# 这些操作一次性做完并固化成包，App 首启解压即可用。
#
# 用法：sh tools/build_python_runtime.sh <b50-android 项目目录>
# 依赖：curl ar/xz tar patchelf python3
# 产物：
#   app/src/main/assets/py_runtime.zip          （约 29 MB，解压 63 MB）
#   app/src/main/jniLibs/arm64-v8a/libpy_home.so      （python3 主程序，4.6 KB）
#   app/src/main/jniLibs/arm64-v8a/libpython3.14.so   （5.9 MB）
#   app/src/main/jniLibs/arm64-v8a/libandroid-support.so, libc++_shared.so
# ============================================================
set -e
PROJ=${1:-/storage/emulated/0/Tools/LSPilot/projects/b50-android}
WORK=/tmp/b50pybuild
REPO=https://packages.termux.dev/apt/termux-main

# 需要的包（Pillow 13 的 _imaging / _imagingft / _webp 依赖链，已裁剪到最小）
PKGS="python python-pillow libjpeg-turbo libpng freetype zlib libffi libandroid-support \
libbz2 liblzma libsqlite openssl libandroid-glob libtiff libwebp libx11 littlecms giflib \
libraqm libfribidi harfbuzz glib libgraphite pcre2 libxau libxdmcp brotli zstd libc++"

# 要删掉的（体积大头 / 用不到）：libicu 31MB、cairo/pixman/fontconfig、avif/aom/dav1d…
DROP_LIBS="libicu libcairo libpixman* libfontconfig libxml2 libgettext* libunistring libattr \
liblzo2 libncursesw libXext libXrender libintl libasprintf libavif libaom libdav1d librav1e \
libSvtAv1Enc libgio-2.0 libgobject-2.0 libgmodule-2.0 libgirepository libgthread-2.0 libtextstyle"
DROP_PIL="_avif _imagingtk"
DROP_PYDIRS="test idlelib tkinter turtledemo lib2to3 ensurepip venv pydoc_data"
DROP_DYNLOAD="_curses* _curses_panel* _dbm* _gdbm* readline* _multiprocessing* _tkinter*"

rm -rf "$WORK"; mkdir -p "$WORK/debs" "$WORK/prefix"
cd "$WORK"

echo "[1/6] 拉包索引"
curl -sL -o Packages "$REPO/dists/stable/main/binary-aarch64/Packages"

echo "[2/6] 下载 .deb"
python3 - "$PKGS" << 'PY'
import re, sys, os, urllib.request
want = set(sys.argv[1].split())
txt = open('Packages', encoding='utf-8', errors='ignore').read()
urls = {}
for b in txt.split('\n\n'):
    m = re.search(r'^Package: (.+)$', b, re.M)
    if m and m.group(1).strip() in want:
        urls[m.group(1).strip()] = 'https://packages.termux.dev/apt/termux-main/' + \
            re.search(r'^Filename: (.+)$', b, re.M).group(1).strip()
for n, u in urls.items():
    f = os.path.join('debs', os.path.basename(u))
    if not os.path.exists(f):
        urllib.request.urlretrieve(u, f)
    print('  +', n)
PY

echo "[3/6] 解包到 prefix/"
python3 - << 'PY'
import glob, io, os, tarfile
for deb in sorted(glob.glob('debs/*.deb')):
    with open(deb, 'rb') as f:
        f.read(8)
        while True:
            h = f.read(60)
            if len(h) < 60:
                break
            name = h[0:16].decode().strip().rstrip('/')
            size = int(h[48:58].decode().strip())
            data = f.read(size)
            if size % 2:
                f.read(1)
            if name.startswith('data.tar'):
                tarfile.open(fileobj=io.BytesIO(data)).extractall('prefix')
                break
PY
USR="$WORK/prefix/data/data/com.termux/files/usr"

echo "[4/6] 裁剪：删 ICU/cairo/avif 等重库 + 无用标准库"
cd "$USR"
for k in $DROP_LIBS; do rm -f lib/${k}*.so* 2>/dev/null || true; done
for k in $DROP_PIL; do rm -f lib/python3.14/site-packages/PIL/${k}*.so 2>/dev/null || true; done
for d in $DROP_PYDIRS; do rm -rf lib/python3.14/$d 2>/dev/null || true; done
for d in $DROP_DYNLOAD; do rm -f lib/python3.14/lib-dynload/$d 2>/dev/null || true; done
rm -rf include share/gir-1.0 share/gettext share/locale share/man share/doc share/gir-1.0 2>/dev/null || true
rm -f bin/xgettext bin/msgfmt bin/msgmerge bin/rav1e bin/c_rehash bin/openssl 2>/dev/null || true

echo "[5/6] patchelf：把 rpath 从 /data/data/com.termux/... 改成 \$ORIGIN"
for f in bin/* lib/*.so*; do
  [ -e "$f" ] || continue
  if file "$f" 2>/dev/null | grep -q ELF; then
    case "$f" in
      bin/*) patchelf --set-rpath '$ORIGIN/../lib' "$f" 2>/dev/null || true ;;
      lib/*) patchelf --set-rpath '$ORIGIN'        "$f" 2>/dev/null || true ;;
    esac
  fi
done
# Pillow 扩展 + lib-dynload 的依赖都在 usr/lib，运行时靠 LD_LIBRARY_PATH
for f in lib/python3.14/site-packages/PIL/*.so lib/python3.14/lib-dynload/*.so; do
  [ -e "$f" ] && patchelf --set-rpath '$ORIGIN' "$f" 2>/dev/null || true
done

echo "[6/6] 打包 + 放二进制到 jniLibs"
cd "$WORK/prefix/data/data/com.termux/files"
mkdir -p "$PROJ/app/src/main/assets" "$PROJ/app/src/main/jniLibs/arm64-v8a"
rm -f "$PROJ/app/src/main/assets/py_runtime.zip"
# ★ zip 内顶层目录必须是 usr（PythonRunner 解压到 filesDir/py/ 下）
#   同时排除 python 主程序（它走 jniLibs，不占两份体积）
zip -qr -9 "$PROJ/app/src/main/assets/py_runtime.zip" usr \
    -x "usr/bin/python3*" "usr/bin/python" "usr/bin/py3*"
JL="$PROJ/app/src/main/jniLibs/arm64-v8a"
cp "$USR/bin/python3.14"                "$JL/libpy_home.so"
cp "$USR/lib/libpython3.14.so"          "$JL/libpython3.14.so"
cp "$USR/lib/libandroid-support.so"     "$JL/libandroid-support.so"
cp "$USR/lib/libc++_shared.so"          "$JL/libc++_shared.so" 2>/dev/null || true
patchelf --set-rpath '$ORIGIN' "$JL/libpy_home.so"
patchelf --set-rpath '$ORIGIN' "$JL/libpython3.14.so"
chmod 644 "$JL"/*.so

# 注意：zip 内的顶层目录必须叫 usr（PythonRunner 解压到 filesDir/py/ 下）
python3 -c "import zipfile,sys; z=zipfile.ZipFile('$PROJ/app/src/main/assets/py_runtime.zip'); print('py_runtime.zip 条目', len(z.namelist()))"
echo "完成。别忘了把 ResourceInstaller.PACK_VERSION / PythonRunner.PY_VERSION 各 +1"
