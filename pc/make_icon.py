# -*- coding: utf-8 -*-
"""生成 KeyVoice 专属图标 keyvoice.ico（麦克风造型 + 品牌蓝）"""
from PIL import Image, ImageDraw

BG_DARK = (14, 17, 22, 255)      # #0E1116 窗口底色
PRIMARY = (79, 140, 255, 255)    # #4F8CFF 品牌蓝
WHITE = (230, 234, 240, 255)     # #E6EAF0 文字色

SIZE = 256


def draw_icon(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size / 256.0

    # 圆角方形底（品牌蓝）
    pad = int(18 * s)
    radius = int(52 * s)
    d.rounded_rectangle([pad, pad, size - pad, size - pad], radius=radius, fill=PRIMARY)

    cx = size / 2.0

    # ---- 麦克风主体（圆角竖条）----
    mic_w = int(58 * s)
    mic_h = int(96 * s)
    mic_top = int(56 * s)
    mic_left = cx - mic_w / 2.0
    mic_right = cx + mic_w / 2.0
    mic_bottom = mic_top + mic_h
    d.rounded_rectangle(
        [mic_left, mic_top, mic_right, mic_bottom],
        radius=int(mic_w / 2), fill=WHITE,
    )

    # ---- 麦克风下方 U 形托架（描边弧）----
    arc_w = int(104 * s)
    arc_top = int(118 * s)
    arc_bottom = int(168 * s)
    d.arc(
        [cx - arc_w / 2.0, arc_top, cx + arc_w / 2.0, arc_bottom],
        start=0, end=180, fill=WHITE, width=int(14 * s),
    )

    # ---- 立柱 + 底座 ----
    stand_top = arc_bottom - int(6 * s)
    stand_bottom = int(196 * s)
    d.line([cx, stand_top, cx, stand_bottom], fill=WHITE, width=int(14 * s))

    base_w = int(72 * s)
    d.rounded_rectangle(
        [cx - base_w / 2.0, stand_bottom, cx + base_w / 2.0, stand_bottom + int(14 * s)],
        radius=int(7 * s), fill=WHITE,
    )

    return img


def main():
    # ICO 内嵌多尺寸，保证任务栏/标题栏/Alt-Tab 都清晰。
    # ⚠ 坑：ICO 的基图必须是【最大】那张，PIL 才会把 sizes 全部写进去；
    #    基图用 16x16 的话只会存一个尺寸（实测只出 (16,16)，图标在高分屏上发虚）。
    sizes = [16, 24, 32, 48, 64, 128, 256]
    imgs = [draw_icon(s) for s in sizes]
    base = imgs[-1]                       # 256x256 作基图
    rest = list(reversed(imgs[:-1]))      # 其余尺寸作为附加帧
    icon_path = r"D:\1\typetext\pc\keyvoice.ico"
    base.save(
        icon_path, format="ICO",
        sizes=[(s, s) for s in sizes],
        append_images=rest,
    )
    print("icon saved:", icon_path)

    # 自检：确认所有尺寸都写进去了
    from PIL import Image
    chk = Image.open(icon_path)
    got = chk.info.get("sizes")
    print("embedded sizes:", got)
    ok = got and len(got) == len(sizes)
    print("[OK] all sizes embedded" if ok else "[WARN] sizes missing: %s" % (got,))


if __name__ == "__main__":
    main()
