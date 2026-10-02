#!/usr/bin/env python3
"""纯 Python 版 zipalign（替代在 aarch64 proot 下无法执行的 x86_64 zipalign）。

用法:
    zipalign.py <alignment> <in.apk> <out.apk>

规则:
    - 未压缩（STORED）条目对齐到 alignment 边界，供 mmap 直接映射；
    - resources.arsc 强制以 STORED 写入（Android 11+ 要求不许压缩）；
    - 对齐通过往 local header 的 extra 字段插入 padding 实现，
      extra id 与官方 zipalign 一致（0xd935），便于校验。
"""
import struct
import sys
import zipfile

PAD_EXTRA_ID = 0xD935


def _pad_extra(offset, name_len, align):
    """返回对齐所需的 extra 字段字节；offset 为当前输出流位置。"""
    k = (-(offset + 30 + name_len + 4)) % align
    return struct.pack('<HH', PAD_EXTRA_ID, k) + b'\x00' * k


def align(src, dst, align_n=4):
    zin = zipfile.ZipFile(src, 'r')
    with zipfile.ZipFile(dst, 'w') as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            zi = zipfile.ZipInfo(info.filename, info.date_time)
            zi.external_attr = info.external_attr
            zi.compress_type = info.compress_type
            if info.filename == 'resources.arsc':
                zi.compress_type = zipfile.ZIP_STORED
            if zi.compress_type == zipfile.ZIP_STORED:
                zi.extra = _pad_extra(zout.fp.tell(),
                                      len(zi.filename.encode('utf-8')), align_n)
            else:
                zi.extra = info.extra
            zout.writestr(zi, data)
    zin.close()


def verify(path, align_n=4):
    """校验未压缩条目的数据偏移是否对齐，返回 (ok, rows)。"""
    ok, rows = True, []
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            stored = info.compress_type == zipfile.ZIP_STORED
            off = (info.header_offset + 30
                   + len(info.filename.encode('utf-8')) + len(info.extra))
            mis = off % align_n if stored else 0
            if mis:
                ok = False
            rows.append((info.filename, stored, off, mis))
    return ok, rows


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    align_n, src, dst = int(sys.argv[1]), sys.argv[2], sys.argv[3]
    align(src, dst, align_n)
    ok, rows = verify(dst, align_n)
    for name, stored, off, mis in rows:
        if stored:
            print('  %s  %s @ %d' % ('OK ' if not mis else 'BAD', name, off))
    print('zipalign: ' + ('OK' if ok else 'ERROR'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
