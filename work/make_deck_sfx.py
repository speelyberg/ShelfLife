"""Cuts the DVD player's sounds for the disc going in from two CC0 recordings (freesound previews, kept in
work/sfx-sources):
  594043 "DVD spinup/down" by SamsterBirdies (freesound.org/s/594043): tray out, tray in, the spin-up
  351378 "DVD player disc in tray" by thaighaudio (freesound.org/s/351378): the disc dropping onto the tray
Writes android/app/src/main/res/raw/tray_open.wav, tray_close.wav, disc_drop.wav, disc_spin.wav (mono,
44.1 kHz, 16-bit), each levelled to a target loudest-50 ms RMS so they sit under the case sounds.
Needs ffmpeg and numpy:  python3 work/make_deck_sfx.py"""
import os, subprocess, wave
import numpy as np

here = os.path.dirname(os.path.abspath(__file__))
raw = os.path.join(here, '..', 'android', 'app', 'src', 'main', 'res', 'raw')
SR = 44100

def load(name):
    pcm = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', os.path.join(here, 'sfx-sources', name), '-ac', '1', '-ar', str(SR), '-f', 's16le', '-'],
                         check=True, capture_output=True).stdout
    return np.frombuffer(pcm, np.int16).astype(float) / 32768

def cut(a, start, end, fade_in=0.01, fade_out=0.05, target_db=-28.0):
    s = a[int(start * SR):int(end * SR)].copy()
    s[:int(fade_in * SR)] *= np.linspace(0, 1, int(fade_in * SR))
    s[len(s) - int(fade_out * SR):] *= np.linspace(1, 0, int(fade_out * SR))
    win = int(0.05 * SR)
    loudest = max(np.sqrt(np.mean(s[i:i + win] ** 2)) for i in range(0, len(s) - win, win // 4))
    s *= 10 ** (target_db / 20) / loudest
    peak = np.abs(s).max()
    if peak > 0.89: s *= 0.89 / peak  # keep 1 dB of headroom
    return s

def save(name, s):
    with wave.open(os.path.join(raw, name), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((s * 32767).astype(np.int16).tobytes())
    print(name, '%.2f s' % (len(s) / SR))

drive, drop = load('594043.mp3'), load('351378.mp3')
save('tray_open.wav', cut(drive, 1.72, 2.32, target_db=-30))     # the tray motor, 0.5 s
save('tray_close.wav', cut(drive, 3.97, 4.57, target_db=-30))
save('disc_drop.wav', cut(drop, 15.87, 16.20, fade_in=0.003, target_db=-22))  # lands, rattles, settles
save('disc_spin.wav', cut(drive, 4.50, 8.50, fade_in=0.15, fade_out=1.2, target_db=-34))  # the spin-up whine, faded out
