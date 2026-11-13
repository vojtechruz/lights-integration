"""Generuje notifikacni zvuky pro lights-integration (hotovo / ceka na tebe / chyba).

Synteza bez zavislosti (jen stdlib): zvonivy ton = zakladni sinus + par inharmonickych
alikvot, kazdy s exponencialnim doznivanim; kratky nabeh proti lupnuti, jemny dozvuk
(par zpozdenych kopii). Vystup: 44,1 kHz, 16 bit, mono WAV vedle skriptu.

Spusteni: python tools/make_sounds.py [vystupni-slozka]   (default: build/sounds)
Vybrane zvuky pak zkopirovat napr. jako success.wav / waiting.wav / error.wav a nastavit
v config/config.json -> "sounds": {"SUCCESS": "...", "WAITING_FOR_INPUT": "...", "ERROR": "..."}.
Pouzite: hotovo = fanfara-3-ta-daa, ceka = ceka-2-dvojity-tuk, chyba = hori-3-vyti-sireny.
"""
import math
import struct
import sys
import wave
from pathlib import Path

SR = 44100
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "build" / "sounds"
OUT.mkdir(parents=True, exist_ok=True)

# frekvence tonu (A4 = 440 Hz)
def n(name):
    names = {"C": -9, "C#": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3,
             "G": -2, "G#": -1, "A": 0, "A#": 1, "B": 2}
    note, octave = name[:-1], int(name[-1])
    return 440.0 * 2 ** ((names[note] + 12 * (octave - 4)) / 12)


def bell(freq, dur, amp=1.0, decay=4.0, partials=((1, 1.0), (2.0, 0.35), (3.01, 0.12), (4.2, 0.05))):
    """Zvonivy / marimbovy ton."""
    count = int(SR * dur)
    out = [0.0] * count
    attack = int(SR * 0.006)
    for i in range(count):
        t = i / SR
        env = math.exp(-decay * t)
        if i < attack:
            env *= i / attack
        s = 0.0
        for ratio, a in partials:
            s += a * math.sin(2 * math.pi * freq * ratio * t) * math.exp(-decay * (ratio - 1) * 0.6 * t)
        out[i] = amp * env * s
    return out


def mix(track, sound, start):
    i0 = int(start * SR)
    need = i0 + len(sound)
    if len(track) < need:
        track.extend([0.0] * (need - len(track)))
    for i, v in enumerate(sound):
        track[i0 + i] += v
    return track


def reverb(track, taps=((0.045, 0.28), (0.083, 0.18), (0.131, 0.11), (0.197, 0.06))):
    tail = int(SR * 0.25)
    out = track + [0.0] * tail
    for delay, gain in taps:
        d = int(SR * delay)
        for i, v in enumerate(track):
            out[i + d] += v * gain
    return out


def save(name, track, peak=0.7):
    track = reverb(track)
    m = max(abs(v) for v in track) or 1.0
    fade = int(SR * 0.05)
    for i in range(fade):
        track[-1 - i] *= i / fade
    with wave.open(str(OUT / f"{name}.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(v / m * peak * 32767)) for v in track))


def seq(notes, step, dur=0.9, decay=4.5, partials=None, amps=None):
    t = []
    for k, name in enumerate(notes):
        kw = {"decay": decay}
        if partials:
            kw["partials"] = partials
        mix(t, bell(n(name), dur, amp=(amps[k] if amps else 1.0), **kw), k * step)
    return t


SOFT = ((1, 1.0), (2.0, 0.12), (3.0, 0.03))           # mekci, skoro cisty ton
GLASS = ((1, 1.0), (2.76, 0.25), (5.4, 0.08))          # sklenene / celesta
MARIMBA = ((1, 1.0), (3.93, 0.18), (9.2, 0.04))

# HOTOVO: stoupajici, durove, uspokojive
save("hotovo-1-arpeggio", seq(["C5", "E5", "G5", "C6"], 0.09, dur=1.1))
save("hotovo-2-sklenene", seq(["G5", "D6"], 0.12, dur=1.4, decay=3.2, partials=GLASS))
save("hotovo-3-marimba", seq(["E5", "G5", "C6"], 0.11, dur=0.8, decay=6, partials=MARIMBA))

# CEKA NA TEBE: dva tony, otazka (konci nahore), jemne
save("ceka-1-ding-dong", seq(["E5", "A5"], 0.18, dur=1.2, decay=3.5, partials=SOFT))
save("ceka-2-dvojity-tuk", seq(["A5", "A5"], 0.16, dur=0.5, decay=9, partials=MARIMBA, amps=[0.8, 1.0]))
save("ceka-3-otazka", seq(["C5", "G5", "D6"], 0.13, dur=1.0, decay=4, partials=GLASS))

# CHYBA: klesajici, mol / tritonus, ale ne agresivni
save("chyba-1-klesajici", seq(["E5", "C5", "A4"], 0.14, dur=1.0, decay=4))
save("chyba-2-tritonus", seq(["F#4", "C4"], 0.2, dur=1.3, decay=3.2, partials=SOFT))
save("chyba-3-dvakrat-nizko", seq(["D4", "D4"], 0.22, dur=0.6, decay=7, partials=MARIMBA))

def brass(freq, dur, amp=1.0, vibrato=0.0):
    """Zesové / trubkové: harmonické 1..10, jas roste s náběhem, krátký release."""
    count = int(SR * dur)
    attack, release = 0.025, 0.08
    out = [0.0] * count
    phase = 0.0
    for i in range(count):
        t = i / SR
        if t < attack:
            env = t / attack
        elif t > dur - release:
            env = max(0.0, (dur - t) / release)
        else:
            env = 1.0 - 0.15 * (t - attack) / max(dur, 0.001)
        bright = min(1.0, t / 0.06)  # "nafouknutí" tónu
        f = freq * (1 + vibrato * math.sin(2 * math.pi * 5.5 * t) * min(1.0, t / 0.25))
        phase += 2 * math.pi * f / SR
        s = 0.0
        for k in range(1, 11):
            s += math.sin(k * phase) / k * (bright if k > 2 else 1.0) * (0.85 ** (k - 1))
        out[i] = amp * env * s
    return out


def fanfare(notes):
    """notes = [(nota nebo seznam not (akord), začátek s, délka s, vibrato)]"""
    t = []
    for names, start, dur, vib in notes:
        names = names if isinstance(names, list) else [names]
        for name in names:
            mix(t, brass(n(name), dur, amp=1.0 / len(names) ** 0.5, vibrato=vib), start)
    return t


# HOTOVO jako krátká fanfára
save("fanfara-1-tatata-taa", fanfare([("G4", 0.00, 0.11, 0), ("G4", 0.13, 0.11, 0),
                                       ("G4", 0.26, 0.11, 0), ("C5", 0.39, 0.55, 0.006)]))
save("fanfara-2-arpeggio", fanfare([("C5", 0.00, 0.10, 0), ("E5", 0.11, 0.10, 0),
                                     ("G5", 0.22, 0.10, 0), (["C5", "E5", "G5", "C6"], 0.34, 0.6, 0.005)]))
save("fanfara-3-ta-daa", fanfare([(["G4", "D5"], 0.00, 0.14, 0), (["C5", "E5", "G5"], 0.17, 0.65, 0.006)]))

def siren_tone(freq, dur, amp=1.0, harmonics=6):
    """Ostřejší tón (liché harmonické ~ čtverec), krátký náběh/doběh."""
    count = int(SR * dur)
    out = [0.0] * count
    edge = int(SR * 0.012)
    for i in range(count):
        t = i / SR
        env = min(1.0, i / edge, (count - i) / edge)
        s = sum(math.sin(2 * math.pi * freq * k * t) / k for k in range(1, 2 * harmonics, 2))
        out[i] = amp * env * s
    return out


def glide(f0, f1, dur, amp=1.0):
    """Houkačka: plynulý přechod frekvence (vytí sirény)."""
    count = int(SR * dur)
    out = [0.0] * count
    edge = int(SR * 0.02)
    phase = 0.0
    for i in range(count):
        x = i / count
        f = f0 + (f1 - f0) * (0.5 - 0.5 * math.cos(math.pi * x))
        phase += 2 * math.pi * f / SR
        env = min(1.0, i / edge, (count - i) / edge)
        s = sum(math.sin(k * phase) / k for k in (1, 3, 5, 7))
        out[i] = amp * env * s
    return out


# CHYBA: "hoří, hoří"
# 1) dvoutónová hasičská houkačka (ta-tü ta-tü), dvakrát
t = []
for k in range(2):
    mix(t, siren_tone(n("B5"), 0.26), k * 0.56)
    mix(t, siren_tone(n("F#5"), 0.26), k * 0.56 + 0.28)
save("hori-1-hasici-houkacka", t)

# 2) rytmus "ho-ří, ho-ří": krátká-dlouhá, klesající malá tercie, žesťově
save("hori-2-ho-ri-ho-ri", fanfare([("A5", 0.00, 0.12, 0), ("F#5", 0.14, 0.30, 0.01),
                                     ("A5", 0.50, 0.12, 0), ("F#5", 0.64, 0.42, 0.012)]))

# 3) krátké vytí sirény nahoru-dolů, dvakrát
t = []
mix(t, glide(n("E5"), n("B5"), 0.35), 0.0)
mix(t, glide(n("B5"), n("E5"), 0.35), 0.35)
mix(t, glide(n("E5"), n("B5"), 0.35), 0.75)
mix(t, glide(n("B5"), n("E5"), 0.35), 1.10)
save("hori-3-vyti-sireny", t)
print("hotovo:", sorted(p.name for p in OUT.glob("*.wav")))
