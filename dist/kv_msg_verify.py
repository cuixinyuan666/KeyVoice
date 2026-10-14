from PIL import Image, ImageChops

a = Image.open(r"C:\Users\Administrator\AppData\Local\Temp\kv_before.png").convert("RGB")
b = Image.open(r"C:\Users\Administrator\AppData\Local\Temp\kv_after.png").convert("RGB")
print("size:", a.size, b.size)

def diff_ratio(box):
    da = a.crop(box); db = b.crop(box)
    d = ImageChops.difference(da, db).convert("L")
    px = list(d.getdata())
    changed = sum(1 for v in px if v > 30)
    return changed, len(px), round(changed / max(1, len(px)), 3)

# 输入框区域（顶部）
print("输入框区域 变化像素:", diff_ratio((60, 60, 1020, 640)))
# 左侧提示条区域（垂直居中）
print("左侧提示条区域 变化像素:", diff_ratio((40, 700, 250, 1650)))
# 底部按钮区（应基本无变化，作对照）
print("底部对照区域 变化像素:", diff_ratio((60, 2000, 1020, 2280)))
