English-only PaddleOCR Override
================================

Letakkan 4 file berikut di folder ini untuk mengganti PP-OCRv5 default
dengan variant English-only (lebih akurat untuk ASCII / operator math):

  1. det.onnx       — text detection model (English-trained)
  2. rec.onnx       — text recognition model (English-only)
  3. cls.onnx       — text angle classification (language-agnostic, boleh
                       pakai cls.onnx dari PP-OCRv5)
  4. dict.txt       — character dictionary yang match dengan rec.onnx

Semua file harus ada. Kalau ada satu yang hilang, app fallback ke
PP-OCRv5 default (di-download dari Internet).

────────────────────────────────────────────────────────────────────────
SUMBER MODEL English-only PP-OCRv4 mobile:
────────────────────────────────────────────────────────────────────────

OPSI 1 — Dari RapidOCR GitHub releases (paling stabil)
   https://github.com/RapidAI/RapidOCR/releases
   Cari file: en_PP-OCRv4_mobile_det.onnx, en_PP-OCRv4_mobile_rec.onnx,
              en_dict.txt
   Rename masing-masing jadi: det.onnx, rec.onnx, dict.txt

OPSI 2 — Dari ModelScope mirror RapidAI
   https://www.modelscope.cn/models/RapidAI/RapidOCR/files
   Path: onnx/PP-OCRv4/en_PP-OCRv4_mobile_*.onnx
   Plus: en_dict.txt

OPSI 3 — Convert sendiri dari paddle:
   - Download en_PP-OCRv4_mobile_*_infer.tar dari:
     https://paddleocr.bj.bcebos.com/PP-OCRv4/english/
   - Convert pakai paddle2onnx CLI

Untuk cls.onnx:
   - Pakai cls.onnx dari cache PP-OCRv5 (setelah app run sekali):
     /data/data/com.roxgarden/files/assets/mobile_ocr/cls.onnx
   - Atau download fresh dari sumber yang sama dengan det/rec
   - cls model bahasa-agnostic, bebas pakai yang mana

────────────────────────────────────────────────────────────────────────
SETELAH MENARUH FILE:
────────────────────────────────────────────────────────────────────────

1. Pastikan nama file persis: det.onnx, rec.onnx, cls.onnx, dict.txt
2. Rebuild APK: gradle assembleDebug
3. Install ulang: adb install -r ...
4. Cek logcat saat startup, harus muncul:
   "Using English OCR models from assets/ocr_english"

Kalau ingin balik ke PP-OCRv5 default, hapus salah satu file (atau
rename folder ini), lalu rebuild.