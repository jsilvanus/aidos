#!/usr/bin/env python3
"""Zero-shot dictation-routing evaluation for Laya (RFC-0022 D45 / Dictator IntentRouter).

Utterances are synthetic and hand-labelled by the author (no real recordings, no personal data); they
estimate feasibility, not production accuracy. Run with the laya ONNX package:

    python dictation_eval.py CHECKPOINT_DIR laya_multilingual.onnx [--subfolder multilingual]
"""
import argparse, json, time
from collections import Counter

D, C, A = "DICTATION", "COMMAND", "AI_REQUEST"
DATA = [
 # Finnish
 ("fi", C, "poista edellinen lause"), ("fi", C, "kumoa"), ("fi", C, "lihavoi tämä sana"), ("fi", C, "uusi kappale"),
 ("fi", C, "tallenna asiakirja"), ("fi", C, "siirry dokumentin alkuun"), ("fi", C, "poista viimeinen sana"),
 ("fi", C, "avaa uusi dokumentti"), ("fi", C, "tee uudelleen"), ("fi", C, "valitse koko teksti"), ("fi", C, "kursivoi lause"), ("fi", C, "uusi rivi"),
 ("fi", D, "Tänään kirkossa laulettiin virsi sata kaksikymmentäkolme."), ("fi", D, "Rakkaat seurakuntalaiset, tervetuloa jumalanpalvelukseen."),
 ("fi", D, "Kokous pidetään torstaina kello kahdeksantoista seurakuntatalolla."), ("fi", D, "Hän sanoi että poista se ei ole hyvä idea."),
 ("fi", D, "Johannes kolme kuusitoista kertoo Jumalan rakkaudesta maailmaa kohtaan."), ("fi", D, "Muistan kun tallensin vanhat valokuvat levylle."),
 ("fi", D, "Kiitos kaikille vapaaehtoisille tämän vuoden talkoista."), ("fi", D, "Uusi kirkkoherra aloittaa tehtävässään ensi kuussa."),
 ("fi", D, "Lapset lauloivat kauniisti ja vanhemmat kuuntelivat hiljaa."), ("fi", D, "Seurakunnan talous on vakaa ja menot ovat pysyneet ennallaan."),
 ("fi", D, "Pappi poisti pöydältä kynttilät ja kumosi viinilasin vahingossa."), ("fi", D, "Sunnuntain saarnateksti on Matteuksen evankeliumista."),
 ("fi", A, "kirjoita tästä lyhyt yhteenveto"), ("fi", A, "voisitko tehdä tästä lyhyemmän?"), ("fi", A, "käännä tämä kappale englanniksi"),
 ("fi", A, "keksi otsikko tälle tekstille"), ("fi", A, "korjaa kirjoitusvirheet ja muotoile teksti virallisemmaksi"), ("fi", A, "selitä mitä tämä tarkoittaa yksinkertaisesti"),
 ("fi", A, "luo tästä luettelo tärkeimmistä kohdista"), ("fi", A, "kirjoita kiitoskirje vapaaehtoisille"), ("fi", A, "tiivistä edellinen kappale yhteen lauseeseen"),
 ("fi", A, "ehdota parempaa sanamuotoa tälle lauseelle"), ("fi", A, "mitä Raamattu sanoo anteeksiannosta?"), ("fi", A, "muuta sävy lämpimämmäksi"),
 # English
 ("en", C, "delete the last sentence"), ("en", C, "undo"), ("en", C, "make this word bold"), ("en", C, "new paragraph"),
 ("en", C, "save the document"), ("en", C, "go to the start of the document"), ("en", C, "delete the last word"),
 ("en", C, "open a new document"), ("en", C, "redo that"), ("en", C, "select all text"), ("en", C, "italicize the sentence"), ("en", C, "new line"),
 ("en", D, "Today in church we sang hymn number one hundred twenty three."), ("en", D, "Dear congregation, welcome to the service."),
 ("en", D, "The meeting will be held on Thursday at six in the evening."), ("en", D, "He said that delete it is not a good idea."),
 ("en", D, "John three sixteen speaks of God's love for the world."), ("en", D, "I remember when I saved the old photos to a disk."),
 ("en", D, "Thank you to all the volunteers for this year's work."), ("en", D, "The new vicar begins her duties next month."),
 ("en", D, "The children sang beautifully and the parents listened quietly."), ("en", D, "The parish finances are stable and expenses have stayed the same."),
 ("en", D, "The priest removed the candles from the table and knocked over the wine glass."), ("en", D, "Sunday's sermon text is from the Gospel of Matthew."),
 ("en", A, "write a short summary of this"), ("en", A, "could you make this shorter?"), ("en", A, "translate this paragraph into Finnish"),
 ("en", A, "come up with a title for this text"), ("en", A, "fix the typos and make the tone more formal"), ("en", A, "explain what this means in simple terms"),
 ("en", A, "turn this into a bullet list of the main points"), ("en", A, "write a thank you letter to the volunteers"), ("en", A, "condense the previous paragraph into one sentence"),
 ("en", A, "suggest a better wording for this sentence"), ("en", A, "what does the Bible say about forgiveness?"), ("en", A, "make the tone warmer"),
]

Q = {
 "route": {"type": "choice", "instructions": "The user is dictating into a text editor by voice. Decide what this utterance is.",
           "criteria": {D: "text the user wants written into the document",
                        C: "an instruction to edit, format, navigate or save the document",
                        A: "a request for an AI assistant to write, rewrite, summarize, translate or answer"}},
}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("checkpoint"); ap.add_argument("onnx"); ap.add_argument("--subfolder", default="multilingual")
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    from laya.onnx_agent import ONNXAgent
    ag = ONNXAgent(a.checkpoint, subfolder=a.subfolder, onnx_path=a.onnx)
    rows, lat = [], []
    for lang, gold, text in DATA:
        t = time.perf_counter(); r = ag.system_one(text, Q); lat.append((time.perf_counter() - t) * 1000)
        ans = r["answers"]["route"]
        rows.append({"lang": lang, "gold": gold, "text": text, "pred": ans["choice"], "p": ans["answer_confidence"]})
    n = len(rows); ok = sum(r["gold"] == r["pred"] for r in rows)
    print(f"n={n} accuracy={ok/n:.3f}  median latency {sorted(lat)[n//2]:.0f} ms (CPU, 1 question, incl. tokenization)")
    for g in (D, C, A):
        sub = [r for r in rows if r["gold"] == g]
        print(f"  {g:11s} recall {sum(r['pred']==g for r in sub)/len(sub):.2f}  confusions {dict(Counter(r['pred'] for r in sub if r['pred']!=g))}")
    for lang in ("fi", "en"):
        sub = [r for r in rows if r["lang"] == lang]
        print(f"  {lang}: accuracy {sum(r['gold']==r['pred'] for r in sub)/len(sub):.2f}")
    print("selective accuracy (answer only when p >= threshold):")
    for th in (0.5, 0.6, 0.7, 0.8, 0.9):
        kept = [r for r in rows if r["p"] >= th]
        if kept: print(f"  p>={th}: coverage {len(kept)/n:.2f} accuracy {sum(r['gold']==r['pred'] for r in kept)/len(kept):.3f}")
    # The costly error for Dictator: dictation executed as a command/AI request (data loss or surprise).
    bad = [r for r in rows if r["gold"] == D and r["pred"] != D]
    print(f"dictation misrouted as command/AI: {len(bad)}/{sum(r['gold']==D for r in rows)}")
    for r in bad: print("   ", r["lang"], round(r["p"], 2), r["pred"], "<-", r["text"])
    if a.out: json.dump(rows, open(a.out, "w"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    main()
