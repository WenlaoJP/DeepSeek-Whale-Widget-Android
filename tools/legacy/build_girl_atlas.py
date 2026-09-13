#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 dsh-whale-girl-pet 的透明 WebM 动画转成 Android 可用的横向帧条 webp。

每个动画 -> 一张 <name>.webp，横向 N 帧，每帧 SIZE x SIZE，RGBA 带 alpha。
Android 侧按 atlas.getHeight() 当格宽切帧（正方形），帧数 = 宽/高 运行时推导。
"""
import os
import shutil
import subprocess

from PIL import Image

SRC = '/root/girl-src/assets/thumb'
TMP = '/root/petframes'
OUT = '/root/whale-android/app/src/main/res/drawable-nodpi'
FPS = 12
SIZE = 360

# 输出名 -> (源 webm, 截取秒数)
ANIMS = [
    ('girl_idle',  '待机呼吸休闲.webm',           3.0),
    ('girl_think', '工作思考.webm',               3.0),
    ('girl_busy',  '工作被打扰.webm',             3.0),
    ('girl_look',  '东张西望.webm',               3.0),
    ('girl_tap',   '点击回应 - 开心跃动.webm',     3.0),
    ('girl_done',  '工作结束.webm',               3.5),
    ('girl_greet', '女仆屈膝礼仪.webm',            3.0),
    ('girl_jump',  '原地跳跃抓碎头顶物品.webm',     3.0),
    ('girl_sleep', '原地小憩沉眠.webm',             3.0),
    ('girl_walk',  '螃蟹走路.webm',               3.0),
]


def build(name, src_file, seconds):
    src = os.path.join(SRC, src_file)
    tmpdir = os.path.join(TMP, name)
    shutil.rmtree(tmpdir, ignore_errors=True)
    os.makedirs(tmpdir, exist_ok=True)
    cmd = [
        'ffmpeg', '-v', 'error', '-y',
        '-c:v', 'libvpx-vp9',
        '-i', src,
        '-t', str(seconds),
        '-vf', 'fps=%d,scale=%d:%d:flags=lanczos' % (FPS, SIZE, SIZE),
        '-pix_fmt', 'rgba',
        '-an',
        os.path.join(tmpdir, 'f_%03d.png'),
    ]
    subprocess.run(cmd, check=True)
    files = sorted(f for f in os.listdir(tmpdir) if f.endswith('.png'))
    if not files:
        raise SystemExit('no frames decoded: ' + src_file)
    first = Image.open(os.path.join(tmpdir, files[0])).convert('RGBA')
    w, h = first.size
    sheet = Image.new('RGBA', (w * len(files), h), (0, 0, 0, 0))
    for i, f in enumerate(files):
        sheet.paste(Image.open(os.path.join(tmpdir, f)).convert('RGBA'), (i * w, 0))
    out = os.path.join(OUT, name + '.webp')
    sheet.save(out, 'WEBP', quality=82, method=4)
    print('%-12s frames=%3d sheet=%dx%d %5dKB' % (name, len(files), sheet.size[0], sheet.size[1], os.path.getsize(out) // 1024))
    shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == '__main__':
    os.makedirs(TMP, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    old = os.path.join(OUT, 'whale_atlas.webp')
    if os.path.exists(old):
        os.remove(old)
        print('removed whale_atlas.webp')
    for name, f, s in ANIMS:
        build(name, f, s)
