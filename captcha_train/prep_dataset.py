import glob, os, random
random.seed(42)

labels = {}
for line in open('c:/captcha_data/16384/labels.tsv', encoding='utf-8').read().strip().split('\n'):
    fn, lab = line.split('\t')
    labels[fn] = lab

imgs = sorted(glob.glob('images/*.png'))
data = []
for p in imgs:
    fn = os.path.basename(p)
    rel = p.replace(os.sep, '/')
    data.append((rel, labels[fn]))

# stratify by operator
byop = {'+': [], '-': [], '*': []}
for p, l in data:
    op = [c for c in l if c in '+-*'][0]
    byop[op].append((p, l))

train, val = [], []
for op, items in byop.items():
    random.shuffle(items)
    nval = max(3, len(items) // 8)
    val += items[:nval]
    train += items[nval:]

random.shuffle(train)
random.shuffle(val)

with open('rec_gt_train.txt', 'w', encoding='utf-8') as f:
    for p, l in train:
        f.write(f'{p}\t{l}\n')
with open('rec_gt_val.txt', 'w', encoding='utf-8') as f:
    for p, l in val:
        f.write(f'{p}\t{l}\n')
with open('captcha_dict.txt', 'w', encoding='utf-8') as f:
    for c in list('0123456789') + ['+', '-', '*']:
        f.write(c + '\n')

print('train', len(train), 'val', len(val), 'dict 13 chars')
print('val sample:', val[:5])