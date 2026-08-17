import re, os

# true labels
labels = {}
for line in open('c:/captcha_data/16384/labels.tsv', encoding='utf-8').read().strip().split('\n'):
    fn, lab = line.split('\t')
    labels[fn] = lab

# val set filenames
valset = set()
for line in open('c:/RoxGarden/captcha_train/rec_gt_val.txt', encoding='utf-8').read().strip().split('\n'):
    p = line.split('\t')[0]
    valset.add(os.path.basename(p))

# parse infer.log: pairs of infer_img + result
pred = {}
cur = None
for line in open('c:/RoxGarden/captcha_train/infer.log', encoding='utf-8', errors='ignore'):
    m = re.search(r'infer_img:\s*(.+\.png)', line)
    if m:
        cur = os.path.basename(m.group(1).strip())
    m2 = re.search(r'result:\s*(\S*)', line)
    if m2 and cur:
        pred[cur] = m2.group(1).strip()
        cur = None

tr_ok = tr_n = va_ok = va_n = 0
wrong = []
for fn, true in labels.items():
    p = pred.get(fn, '<none>')
    ok = (p == true)
    if fn in valset:
        va_n += 1; va_ok += ok
    else:
        tr_n += 1; tr_ok += ok
    if not ok:
        wrong.append((fn in valset, fn, true, p))

print(f'TRAIN acc: {tr_ok}/{tr_n} = {tr_ok/tr_n:.1%}')
print(f'VAL   acc: {va_ok}/{va_n} = {va_ok/va_n:.1%}')
print(f'TOTAL acc: {tr_ok+va_ok}/{tr_n+va_n} = {(tr_ok+va_ok)/(tr_n+va_n):.1%}')
print('--- WRONG (V=val) ---')
for isval, fn, true, p in wrong:
    print(('V' if isval else 'T'), 'true=' + true, 'pred=' + p, fn)
