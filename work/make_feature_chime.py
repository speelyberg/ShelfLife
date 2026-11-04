"""ShelfLife's own 'feature presentation' chime, synthesized (nothing sampled or copied).
A soft Cmaj9 swell, a rising harp arpeggio, a bell as the title lands, a glint during the shine,
light reverb. Writes work/feature_chime.wav (6.6 s, 22.05 kHz mono); convert to .ogg for res/raw.
Run: python3 work/make_feature_chime.py"""
import numpy as np, wave, os
SR = 22050; LEN = 6.6
t = np.arange(int(SR * LEN)) / SR
out = np.zeros_like(t)
def note(name):
    names = {'C':0,'D':2,'E':4,'F':5,'G':7,'A':9,'B':11}
    n, octave = name[0], int(name[1:])
    return 440.0 * 2 ** ((names[n] + 12 * (octave + 1) - 69) / 12)
def env(start, attack, hold, release):
    e = np.zeros_like(t)
    a = (t >= start) & (t < start + attack); e[a] = (t[a] - start) / attack
    h = (t >= start + attack) & (t < start + attack + hold); e[h] = 1
    r = (t >= start + attack + hold) & (t < start + attack + hold + release); e[r] = 1 - (t[r] - start - attack - hold) / release
    return np.sin(e * np.pi / 2) ** 2  # smooth edges
# Pad: detuned sine pairs with a little 2nd harmonic, slow swell.
pad_env = env(0.0, 1.4, 3.6, 1.5)
for n in ['C3', 'G3', 'B3', 'D4', 'E4']:
    f = note(n)
    for cents in (-4, 4):
        ff = f * 2 ** (cents / 1200)
        out += 0.045 * pad_env * (np.sin(2 * np.pi * ff * t) + 0.25 * np.sin(4 * np.pi * ff * t))
# Harp: plucked partials with exponential decay, rising.
def pluck(start, f, amp, decay=1.3):
    m = t >= start; x = t[m] - start
    s = sum(a * np.sin(2 * np.pi * f * k * x) * np.exp(-x * k * 1.1 / decay) for k, a in ((1, 1.0), (2, 0.45), (3, 0.2), (4, 0.08)))
    out[m] += amp * s * np.minimum(1, x / 0.004)
for i, n in enumerate(['C4', 'E4', 'G4', 'B4', 'D5', 'E5', 'G5', 'B5', 'C6']):
    pluck(0.25 + i * 0.085, note(n), 0.10)
# Bell as the title lands: inharmonic partials, long decay.
def bell(start, f, amp, decay=2.6):
    m = t >= start; x = t[m] - start
    s = sum(a * np.sin(2 * np.pi * f * r * x) * np.exp(-x / (decay / r ** 0.5)) for r, a in ((1, 1), (2.0, 0.5), (2.76, 0.35), (5.4, 0.15), (8.93, 0.08)))
    out[m] += amp * s * np.minimum(1, x / 0.002)
bell(0.95, note('C5'), 0.13); bell(0.95, note('G5'), 0.07)
# Glint during the shine sweep.
for i, n in enumerate(['G6', 'C7', 'E7']):
    bell(2.05 + i * 0.07, note(n), 0.035, decay=0.9)
# Reverb: convolution with decaying noise.
rng = np.random.default_rng(7)
ir_t = np.arange(int(SR * 1.8)) / SR
ir = rng.standard_normal(ir_t.size) * np.exp(-ir_t / 0.45); ir[0] = 0
n = out.size + ir.size
wet = np.fft.irfft(np.fft.rfft(out, n) * np.fft.rfft(ir, n), n)[:out.size]
mix = out + 0.35 * wet / np.max(np.abs(wet)) * np.max(np.abs(out))
mix *= env(0.0, 0.01, LEN - 0.8, 0.78)  # tail fades out with the picture
mix = mix / np.max(np.abs(mix)) * 0.85
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'feature_chime.wav')
with wave.open(path, 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((mix * 32767).astype(np.int16).tobytes())
print('written', path)
