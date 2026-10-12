"""Regenerates engine/decisions/src/jvmTest/resources/laya_golden.json from the Python reference (laya 0.4.6).
Usage: python golden.py CHECKPOINT_ROOT model.onnx OUT.json   (tokenizer ids, reference sequences, raw logits, answers)"""
import json, numpy as np, onnxruntime as ort
from transformers import AutoTokenizer
from laya.common import build_sequence, render_options, QTYPES
from laya.onnx_agent import ONNXAgent
import sys
CKPT, ONNX, OUT = sys.argv[1], sys.argv[2], sys.argv[3]  # checkpoint root, exported .onnx, output json
D=CKPT+"/multilingual"
tok=AutoTokenizer.from_pretrained(D+"/tokenizer")
texts=["poista edellinen lause","Tänään kirkossa laulettiin virsi 123.","delete the last sentence","Hän sanoi että poista se ei ole hyvä idea.",
 " DICTATION: plain text to be written","choice question: Is the user dictating?","Ääkköset: äöå ÄÖÅ — ”lainaus”","  multiple   spaces\tand\nnewlines",
 "日本語のテキスト","emoji 🙂 test","Johannes 3:16 — Jumala on rakastanut maailmaa.","","a","<mask> inside text","Hyvää huomenta, rakkaat seurakuntalaiset!",
 "ex­tra soft hyphen","12345678901234567890","https://example.org/a?b=c&d=e","Ev.-lut. kirkko","tabs\t\t\tend"]
tcases=[{"text":t,"ids":tok(t,add_special_tokens=False)["input_ids"]} for t in texts]
qs={
 "intent": {"type":"choice","instructions":"Is the user dictating text or giving an editing command?",
            "criteria":{"DICTATION":"plain text to be written","COMMAND":"an instruction to edit or control the app"}},
 "explicit": {"type":"noul","instructions":"Is the user explicitly asking for an action to be performed?"},
 "strength": {"type":"score","instructions":"How strongly is this a command?","criteria":["not at all","weakly","clearly"]},
}
ag=ONNXAgent(CKPT,subfolder="multilingual",onnx_path=ONNX)
sess=ort.InferenceSession(ONNX,providers=["CPUExecutionProvider"])
states=["poista edellinen lause","Tänään kirkossa laulettiin virsi 123.","delete the last sentence","Hän sanoi että poista se ei ole hyvä idea."]
seqs=[]
for s in states:
    res=ag.system_one(s,qs)
    for qid,q in qs.items():
        iq=ag._to_internal(q)
        ids,markers=build_sequence(tok,s,iq,1024,256)
        qt=QTYPES[iq["t"]]
        mp=np.array([markers+[0]*max(0,2-len(markers))],dtype=np.int64); mm=np.array([[True]*len(markers)+[False]*max(0,2-len(markers))])
        lg,act=sess.run(None,{"input_ids":np.array([ids],dtype=np.int64),"attention_mask":np.ones((1,len(ids)),dtype=np.int64),"marker_pos":mp,"marker_mask":mm,"qtype":np.array([qt],dtype=np.int64)})
        seqs.append({"state":s,"qid":qid,"question":q,"ids":ids,"markers":markers,"qtype":qt,
                     "logits":[round(float(x),5) for x in lg[0][:len(markers)]],"act":round(float(act[0,0]),5),"answer":res["answers"][qid]})
json.dump({"tokenizer_cases":tcases,"sequences":seqs,"max_len":1024,"head_max_len":256,"mask_id":tok.mask_token_id,"cls_id":tok.cls_token_id,"sep_id":tok.sep_token_id,"pad_id":tok.pad_token_id},open(OUT,"w"),ensure_ascii=False)
print(len(tcases),len(seqs),tok.mask_token_id,tok.cls_token_id,tok.sep_token_id,tok.pad_token_id)
print(tcases[2], tcases[13])
