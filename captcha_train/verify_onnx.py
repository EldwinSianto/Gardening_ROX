import numpy as np, os, re
import onnxruntime as ort
from PIL import Image

dict_chars = open('c:/RoxGarden/captcha_train/captcha_dict.txt', encoding='utf-8').read().split('\n')
dict_chars = [c for c in dict_chars if c != '']
# CTC blank at index 0, then chars
charset = ['blank'] + dict_chars

sess = ort.InferenceSession('c:/RoxGarden/captcha_train/captcha_rec.onnx', providers=['CPUExecutionProvider'])
inp = sess.get_inputs()[0]
out = sess.get_outputs()[0]
print('input', inp.name, inp.shape, '| output', out.name, out.shape)

def preprocess(path, H=48):
    im = Image.open(path).convert('RGB')
    w, h = im.size
    nw = max(1, int(w * H / h))
    im = im.resize((nw, H))
    arr = np.array(im).astype('float32')           # H,W,3 RGB
    arr = (arr / 255.0 - 0.5) / 0.5
    arr = arr.transpose(2, 0, 1)[None, ...]         # 1,3,H,W
    return arr

def ctc_decode(logits):
    # logits: 1,T,C
    idx = logits[0].argmax(axis=1)
    out = []
    last = 0
    for i in idx:
        if i != 0 and i != last:
            out.append(charset[i])
        last = i
    return ''.join(out)

labels = {}
for line in open('c:/captcha_data/16384/labels.tsv', encoding='utf-8').read().strip().split('\n'):
    fn, lab = line.split('\t')
    labels[fn] = lab
valset = [os.path.basename(l.split('\t')[0]) for l in open('c:/RoxGarden/captcha_train/rec_gt_val.txt', encoding='utf-8').read().strip().split('\n')]

ok = 0
for fn in valset:
    x = preprocess('c:/RoxGarden/captcha_train/images/' + fn)
    y = sess.run(None, {inp.name: x})[0]
    pred = ctc_decode(y)
    true = labels[fn]
    good = pred == true
    ok += good
    print(('OK ' if good else 'XX '), 'true=' + true, 'pred=' + pred, fn)
print(f'ONNX VAL acc: {ok}/{len(valset)} = {ok/len(valset):.1%}')
