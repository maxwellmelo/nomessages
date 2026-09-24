import sys
from PIL import Image
import zxingcpp

src = sys.argv[1]
dst = sys.argv[2]
# region hints (optional): left top right bottom of the search area, else full image
img = Image.open(src).convert("RGB")
w, h = img.size

# Find the QR by locating the white card region (approx observed: full-width white
# card with the black/white matrix centered). We search for the largest contiguous
# near-white rectangle in the vertical band where the QR typically sits, then find
# the actual black pixel bounding box inside it for a tight crop with margin.
gray = img.convert("L")
px = gray.load()

# Scan for bounding box of "busy" (non-uniform, noisy black/white) area which is the QR.
# Heuristic: QR area has many isolated black pixels surrounded by white; background text
# areas are mostly uniform light gray. We threshold on pure black pixels density per row/col.
import numpy as np
arr = np.array(gray)
black = arr < 100
# row/col sums
row_sums = black.sum(axis=1)
col_sums = black.sum(axis=0)
col_thresh = max(150, int(col_sums.max() * 0.35))
cols = np.where(col_sums > col_thresh)[0]
if len(cols) == 0:
    print("NO_QR_REGION_FOUND")
    sys.exit(1)
left, right = cols.min(), cols.max()
qr_width = right - left

# Restrict row search to the column band of the QR only, so button/text rows
# elsewhere on screen (same width threshold) don't pollute the row range.
band = black[:, left:right + 1]
row_sums_band = band.sum(axis=1)
row_thresh = max(100, int(row_sums_band.max() * 0.35))
rows = np.where(row_sums_band > row_thresh)[0]
# Keep only the largest contiguous run of qualifying rows (the QR itself).
splits = np.where(np.diff(rows) > 5)[0]
groups = np.split(rows, splits + 1)
best = max(groups, key=len)
top, bottom = best.min(), best.max()
margin = 20
top = max(0, top - margin)
left = max(0, left - margin)
bottom = min(h, bottom + margin)
right = min(w, right + margin)
crop = img.crop((left, top, right, bottom))
crop.save(dst)
print(f"cropped to {crop.size} at box=({left},{top},{right},{bottom})")

results = zxingcpp.read_barcodes(crop)
if not results:
    print("DECODE_FAILED")
    sys.exit(2)
for r in results:
    print("DECODED_FORMAT:", r.format)
    print("DECODED_TEXT_LEN:", len(r.text))
    print("DECODED_TEXT_PREFIX:", r.text[:40])
