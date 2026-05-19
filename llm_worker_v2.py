"""
workers/llm_worker.py — Stateless LLM Redis consumer.
Reads from jobs:llm → uses llm_config from job → pushes to jobs:tts.
Services embed full system_prompt + config in the job payload.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import sys
import traceback
import uuid
import time
from dotenv import load_dotenv

load_dotenv(override=True)

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from shared.redis_bus import (
    get_redis, xadd_json, ensure_group, safe_read_group,
    is_cancelled, STREAM_LLM, STREAM_TTS,
)
from shared.llm_engine import chat_completion

GROUP = os.getenv("LLM_GROUP", "g_llm")
CONSUMER = os.getenv("LLM_CONSUMER", f"c_{uuid.uuid4().hex[:6]}")

# Redis Keys
def session_key(sid: str) -> str:
    return f"chat:{sid}:turns"

def estimate_tokens(text: str) -> int:
    return int(len(str(text).split()) * 1.3)

def build_context(full_system: str, user_text: str, history: list, max_tokens=6000) -> list:
    """Token-budgeting prompt assembly"""
    budget = max_tokens
    messages = []
    
    # 1. System
    budget -= estimate_tokens(full_system)
    messages.append({"role": "system", "content": full_system})
    
    # 2. History
    safe_budget = budget - 1000 - estimate_tokens(user_text)
    selected_turns = []
    for turn in reversed(history):
        turn_len = estimate_tokens(turn["content"])
        if safe_budget - turn_len > 0:
            selected_turns.insert(0, turn)
            safe_budget -= turn_len
        else:
            break
            
    messages.extend(selected_turns)
    
    # 3. User
    messages.append({"role": "user", "content": user_text})
    return messages


# ── Vietnamese number reading for chemical formulas ──
_NUM_VI = {
    0: "không", 1: "một", 2: "hai", 3: "ba", 4: "bốn",
    5: "năm", 6: "sáu", 7: "bảy", 8: "tám", 9: "chín", 10: "mười",
}

def _num_to_vi(n: int) -> str:
    if n <= 10:
        return _NUM_VI.get(n, str(n))
    if n < 20:
        return "mười " + _NUM_VI.get(n - 10, str(n - 10))
    return str(n)

# Vietnamese TTS-friendly element pronunciation
_ELEM_READ = {
    "H": "hát", "O": "ô", "C": "xê", "N": "nít", "S": "sờ",
    "Fe": "ép-e", "Ca": "can-xi", "Na": "na-tri", "Cl": "clo",
    "K": "ka-li", "Mg": "magiê", "Al": "a-li", "Zn": "kẽm",
    "Cu": "đồng", "Ag": "bạc", "Au": "vàng", "Pb": "chì",
    "Mn": "man-gan", "Si": "si-li", "P": "phốt", "Br": "brôm",
    "I": "i-ốt", "F": "flo", "Li": "li-ti", "Ba": "ba-ri",
    "Ti": "ti-tan", "Cr": "crom", "Ni": "ni-ken", "Sn": "thiếc",
    "Pt": "pla-tin", "He": "hê-li", "Ne": "nê-on", "Ar": "ac-gon",
}

# English chemistry/physics term → Vietnamese phonetic pronunciation
# Used by _replace_english_terms() for TTS readability
_ENG_TERM_MAP = {
    # ── Elements & particles ──
    "hydrogen": "hi-drô-gen", "oxygen": "óc-si-gen", "nitrogen": "ni-tơ-gen",
    "carbon": "các-bon", "sulfur": "sul-fua", "sulphur": "sul-fua",
    "iron": "sắt", "copper": "đồng", "zinc": "kẽm", "aluminum": "a-lu-mi-num",
    "silicon": "si-li-còn", "calcium": "can-xi", "sodium": "na-tri",
    "potassium": "ka-li", "magnesium": "ma-giê", "titanium": "ti-ta-ni",
    "chromium": "crom", "manganese": "man-gan", "nickel": "ni-ken",
    "platinum": "pla-tin", "silver": "bạc", "gold": "vàng", "lead": "chì",
    "tin": "thiếc", "mercury": "thuỷ ngân", "phosphorus": "phốt-pho",
    "fluorine": "flo", "chlorine": "clo", "bromine": "brôm",
    "helium": "hê-li", "neon": "nê-on", "argon": "ac-gon",
    "electron": "e-léc-tron", "proton": "prô-ton", "neutron": "nơ-tron",
    "photon": "phô-ton", "ion": "ai-on", "cation": "cat-ion",
    "anion": "an-ion", "isotope": "i-zô-tốp",
    # ── Chemical concepts ──
    "atom": "a-tom", "molecule": "mô-lê-cun", "compound": "côm-pao-nờ",
    "mixture": "hỗn hợp", "solution": "dung dịch", "solvent": "dung môi",
    "solute": "chất tan", "concentration": "nồng độ",
    "reaction": "phản ứng", "reagent": "phản ứng thử",
    "catalyst": "chất xúc tác", "enzyme": "en-zim",
    "acid": "a-xit", "base": "bazơ", "alkali": "an-ca-li",
    "salt": "muối", "oxide": "ô-xít", "hydroxide": "hi-drô-ô-xít",
    "sulfate": "sun-fat", "sulfide": "sun-fua", "sulfite": "sun-fit",
    "nitrate": "ni-trat", "nitrite": "ni-trit",
    "carbonate": "các-bon-nat", "bicarbonate": "bai-các-bon-nat",
    "chloride": "clo-rùa", "chlorate": "clo-rat",
    "phosphate": "phốt-phat", "acetate": "a-xe-tat",
    "permanganate": "pem-man-ga-nat",
    "anode": "a-nốt", "cathode": "ca-tốt",
    "electrode": "e-léc-trốt", "electrolyte": "e-léc-tơ-lít",
    "electrolysis": "e-léc-tơ-lai-sít",
    "oxidation": "ốc-si-hoá", "reduction": "rê-duc-sion",
    "corrosion": "ăn mòn", "precipitate": "kết tủa", "filtrate": "dịch lọc",
    "titration": "ti-trơ", "indicator": "chỉ thị",
    "valence": "hoá trị", "bond": "liên kết", "covalent": "cô-valent",
    "ionic": "ai-on-nic",
    "exothermic": "thoát nhiệt", "endothermic": "thu nhiệt",
    "combustion": "đốt cháy", "synthesis": "tổng hợp",
    "decomposition": "phân huỷ", "displacement": "thế",
    "solubility": "độ tan", "pH": "pi-ách",
    # ── Physics concepts ──
    "velocity": "véc-tô", "acceleration": "gia tốc",
    "force": "lực", "momentum": "động lượng",
    "energy": "năng lượng", "kinetic": "động năng",
    "potential": "thế năng", "power": "công suất",
    "frequency": "tần số", "amplitude": "biên độ", "wavelength": "bước sóng",
    "friction": "ma sát", "gravity": "trọng lực", "inertia": "quán tính",
    "mass": "khối lượng", "weight": "trọng lượng", "density": "mật độ",
    "pressure": "áp suất", "temperature": "nhiệt độ",
    "heat": "nhiệt", "work": "công", "torque": "mô-men",
    "joule": "jun", "watt": "oát", "newton": "niu-tơn",
    "pascal": "pát-can", "hertz": "héc",
    "volt": "vôn", "ampere": "am-pe", "ohm": "ôm",
    "circuit": "mạch", "resistor": "điện trở", "capacitor": "tụ điện",
    "inductor": "cuộn cảm", "conductor": "chất dẫn",
    "semiconductor": "bán dẫn", "insulator": "chất cách điện",
    "magnetic": "từ", "electric": "điện", "current": "dòng điện",
    "voltage": "điện áp", "resistance": "điện trở",
    "refraction": "khúc xạ", "reflection": "phản xạ",
    "diffraction": "nhiễu xạ", "interference": "giao thoa",
    "radiation": "bức xạ", "spectrum": "quang phổ",
    "nuclear": "hạt nhân", "fission": "phân hạch", "fusion": "nhiệt hạch",
    "radioactivity": "phóng xạ", "half-life": "thời gian bán huỷ",
    "entropy": "en-trô-pi", "thermodynamics": "nhiệt động học",
    "pendulum": "con lắc", "spring": "lò xo",
    # ── Common English words in Vietnamese education ──
    "experiment": "thí nghiệm", "theory": "lý thuyết",
    "formula": "công thức", "equation": "phương trình",
    "coefficient": "hệ số", "variable": "biến",
    "graph": "đồ thị", "table": "bảng",
    "example": "ví dụ", "exercise": "bài tập",
    "chapter": "chương", "section": "mục",
    "figure": "hình", "diagram": "sơ đồ",
}

def _replace_english_terms(text: str) -> str:
    """Replace English chemistry/physics terms with Vietnamese phonetic pronunciation."""
    for eng, vi in _ENG_TERM_MAP.items():
        # Case-insensitive replacement, preserve surrounding context
        text = re.sub(rf"\b{re.escape(eng)}\b", vi, text, flags=re.IGNORECASE)
    return text

# Roman numeral conversion (ordinal context)
_ROMAN_MAP = {
    "III": "3", "II": "2", "IV": "4", "IX": "9",
    "VIII": "8", "VII": "7", "VI": "6", "X": "10",
    "V": "5", "I": "1",
}
_ORDINAL_WORDS = r"(?:định luật|phần|bước|lần|thứ|mục|bài|chương|điều|câu|hạng)"

def _convert_roman(m):
    prefix = m.group(1)
    roman = m.group(2)
    num = _ROMAN_MAP.get(roman, roman)
    return prefix + " " + num

# Unit readings (Vietnamese)
_UNIT_MAP = {
    "km/h": "ki-lô-mét trên giờ",
    "m/s2": "mét trên giây bình",
    "m/s": "mét trên giây",
    "kg/m3": "ki-lô-gam trên mét khối",
    "N/m2": "niu-tơn trên mét vuông",
    "J/kg": "jun trên ki-lô-gam",
    "kWh": "ki-lô-oát-giờ",
    "oC": "độ xê",
    "°C": "độ xê",
}

# Chemical formula regex: multi-element formulas with digits.
# Matches: H2O, CO2, CaCO3, Fe2O3, 2H2O, etc.
# (?<![A-Za-z]) prevents matching inside a larger word.
_CHEM_RE = re.compile(r"(?<![A-Za-z])([A-Z][a-z]?\d*(?:[A-Z][a-z]?\d*)+)")
# Single element + digit: H2, O2, N2 (not matched by _CHEM_RE)
_CHEM_SINGLE = re.compile(r"(?<![A-Za-z])([A-Z][a-z]?)(\d+)(?![a-zA-Z\d])")


def _read_chem(m):
    """Read chemical formula with Vietnamese pronunciation: H2O -> hát hai ô, Fe2O3 -> ép-e hai ô ba."""
    s = m.group(0)
    if not any(c.isdigit() for c in s):
        return s  # no digits → not a formula (e.g. NaOH), leave unchanged
    result = []
    elem = ""
    for ch in s:
        if ch.isupper():
            if elem:
                result.append(_ELEM_READ.get(elem, elem))
                elem = ""
            elem = ch
        elif ch.islower():
            elem += ch
        elif ch.isdigit():
            if elem:
                result.append(_ELEM_READ.get(elem, elem))
                elem = ""
            result.append(_num_to_vi(int(ch)))
    if elem:
        result.append(_ELEM_READ.get(elem, elem))
    return " ".join(result)


def _sanitize_for_tts(text: str) -> str:
    """Strip markdown and LaTeX symbols that break TTS pronunciation."""
    if not text:
        return text

    # ── 1. LaTeX ──
    text = re.sub(r"\$\$(.+?)\$\$", r"\1", text, flags=re.DOTALL)
    text = re.sub(r"\$(.+?)\$", r"\1", text)
    text = text.replace("\\times", " nhân ")
    text = text.replace("\\Delta", "biệt thức")
    text = text.replace("\\cdot", " nhân ")
    text = text.replace("\\leq", " nhỏ hơn hoặc bằng ")
    text = text.replace("\\geq", " lớn hơn hoặc bằng ")
    text = text.replace("\\approx", " xấp xỉ ")
    text = text.replace("\\pm", " cộng trừ ")
    text = re.sub(r"\\frac\{(.+?)\}\{(.+?)\}", r"\1 chia \2", text)
    text = re.sub(r"\^2", " bình", text)
    text = re.sub(r"\^3", " khối", text)
    text = re.sub(r"\^\{(.+?)\}", r" mũ \1", text)
    text = re.sub(r"\\sqrt\{(.+?)\}", r"căn \1", text)
    text = text.replace("\\sqrt", "căn")
    text = re.sub(r"\\[a-zA-Z]+", "", text)
    text = re.sub(r"[{}\\]", "", text)

    # ── 2. Markdown + HTML ──
    text = re.sub(r"<[^>]+>", "", text)
    # Strip markdown bold/italic markers BEFORE math operator handling
    # so that "*Lục Vân Tiên*" doesn't become "nhân Lục Vân Tiên nhân"
    text = re.sub(r"\*{1,3}([^*]+)\*{1,3}", r"\1", text)
    text = re.sub(r"\*+", "", text)

    # ── 3. Emoji ──
    text = re.sub(
        "[\U0001F600-\U0001F64F"
        "\U0001F300-\U0001F5FF"
        "\U0001F680-\U0001F6FF"
        "\U0001F900-\U0001F9FF"
        "\U0001FA00-\U0001FA6F"
        "\U0001FA70-\U0001FAFF"
        "\U00002702-\U000027B0"
        "\U000024C2-\U0001F251"
        "\U0000FE0F"
        "\U0000200D"
        "\U00002600-\U000026FF"
        "\U00002700-\U000027BF"
        "]+", "", text
    )

    # ── 4. Whitespace & structure ──
    text = text.replace("\r\n", " . ").replace("\r", " . ").replace("\n", " . ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"-{3,}", ". ", text)
    # Em dash / en dash → pause (TTS may choke on these Unicode chars)
    text = text.replace("—", " . ").replace("–", " . ")

    # ── 5. Chemical formulas: read BEFORE number-letter split ──
    text = _CHEM_RE.sub(_read_chem, text)
    # Single element + digit: H2 -> hát hai, O2 -> ô hai
    text = _CHEM_SINGLE.sub(lambda m: _ELEM_READ.get(m.group(1), m.group(1)) + " " + _num_to_vi(int(m.group(2))), text)

    # ── 5b. English chemistry/physics terms → Vietnamese phonetic ──
    text = _replace_english_terms(text)

    # ── 5c. Roman numerals in ordinal context: "Định luật III" -> "Định luật 3" ──
    text = re.sub(
        rf"({_ORDINAL_WORDS})\s+(III|II|IV|IX|VIII|VII|VI|X|V|I)\b",
        _convert_roman, text, flags=re.IGNORECASE,
    )

    # ── 6. Chemical equation arrows ──
    text = text.replace("->", " cho ra ")
    text = text.replace("→", " cho ra ")

    # ── 7. Hyphen handling ──
    # Pure math subtraction: "3 - 2" -> "3 trừ 2" (both sides digits)
    text = re.sub(r"(\d)\s+-\s+(\d)", r"\1 trừ \2", text)
    # Variable math: "x - 3" -> "x trừ 3" (single letter - digit)
    text = re.sub(r"([a-zA-Z])\s+-\s+(\d)", r"\1 trừ \2", text)

    # ── 8. Unit notation (before "/" -> "chia") ──
    for unit, reading in _UNIT_MAP.items():
        text = text.replace(unit, reading)
    # Number/number fraction: "1/2" -> "1 phần 2", "3/4" -> "3 phần 4"
    text = re.sub(r"(\d+)\s*/\s*(\d+)", r"\1 phần \2", text)
    # Vietnamese text slash: "luận/Lời" -> "luận hoặc Lời" (before catch-all "chia")
    _VI_WORD = r"[a-zA-ZÀ-ỹ]{2,}"
    text = re.sub(rf"({_VI_WORD})/({_VI_WORD})", r"\1 hoặc \2", text)

    # ── 9. Math operators ──
    text = re.sub(r"(?<=[\w\d])\s*\*\s*(?=[\w\d])", " nhân ", text)
    text = re.sub(r"[*_#`]", "", text)
    text = text.replace("/", " chia ")
    text = text.replace("+", " cộng ")
    text = re.sub(r"(?:(?<=^)|(?<=[=+*/(]))\s*-\s*(\d)", r" âm \1", text)

    # ── 10. Number-letter separation ──
    text = re.sub(r"(\d)([a-zA-Z])", r"\1 \2", text)
    text = re.sub(r"x(\d)", r"x \1", text)

    # ── 11. Equality, zero ──
    text = re.sub(r"(\w)\s*=\s*(\S)", r"\1 bằng \2", text)
    text = re.sub(r"(?<![\d])0(?![\d])", "không", text)

    # ── 12. Parentheses ──
    def _convert_parens(m):
        inner = m.group(1)
        if re.search(r"\d\s*(bằng|cộng|trừ|nhân|chia)\s*\d", inner):
            return " mở ngoặc " + inner + " đóng ngoặc "
        return " " + inner + " "
    text = re.sub(r"\(([^)]+)\)", _convert_parens, text)
    text = text.replace("$", "")

    # ── 13. Interjections ──
    text = re.sub(r"[Ww]ao", "Oa", text)
    text = re.sub(r"[Ww]ow", "Oa", text)
    text = re.sub(r"[Hh]a+ha+", "ha ha", text)
    text = re.sub(r"[Hh]i+hi+", "hì hì", text)
    text = re.sub(r"[Hh]e+he+", "hê hê", text)
    text = re.sub(r"[Hh]u+hu+", "hu hu", text)

    # ── 14. Cleanup ──
    text = re.sub(r"^\s*[)\]]+\s*", "", text)
    text = re.sub(r"\s*[)\]]+\s*$", "", text)
    text = re.sub(r"\(\s*\)", "", text)
    text = re.sub(r"\[\s*\]", "", text)
    text = re.sub(r"(?:\s*\.\s*){2,}", ". ", text)
    text = re.sub(r"\s+\.", ".", text)
    text = text.strip()

    # If no printable alphanumeric characters, return empty
    if not any(c.isalnum() for c in text):
        return ""

    return text



def _infer_emotion(text: str) -> str:
    t = text.lower()
    if any(w in t for w in ["vui", "haha", "yêu", "cười", "hạnh phúc", "tuyệt vời", "hihi"]):
        return "01"
    if any(w in t for w in ["buồn", "xin lỗi", "huhu", "khóc", "chán", "mệt", "đau"]):
        return "10"
    if any(w in t for w in ["giận", "ghét", "cáu", "tức", "bực"]):
        return "02"
    return "00"


async def main():
    r = await get_redis()
    await ensure_group(r, STREAM_LLM, GROUP)
    print(f"[LLM Worker] up | group={GROUP} consumer={CONSUMER}")

    while True:
        msg = await safe_read_group(r, STREAM_LLM, GROUP, CONSUMER)
        if not msg:
            await asyncio.sleep(0.05)
            continue

        msg_id, fields = msg
        job = {}
        request_id = ""
        session_id = ""
        resp_stream = ""
        try:
            job = json.loads(fields.get("json", "{}"))
            request_id = job.get("request_id", "")
            session_id = job.get("session_id", "")
            text = job.get("text", "")
            resp_stream = job.get("resp_stream", "")
            llm_config = job.get("llm_config", {})

            if await is_cancelled(r, request_id):
                await r.xack(STREAM_LLM, GROUP, msg_id)
                continue

            print(f"🤖 [LLM] Query [{session_id}]: {text}")

            # Build system prompt from service config
            system_prompt = llm_config.get("system_prompt", "Bạn là trợ lý AI hữu ích.")
            safety_prompt = llm_config.get("safety_prompt", "")
            user_name = llm_config.get("user_name", "Người dùng")
            location_name = llm_config.get("location_name", "Việt Nam")

            full_system = (
                system_prompt
                .replace("{USER_NAME}", user_name)
                .replace("{LOCATION_NAME}", location_name)
                .replace("{RAG_CONTEXT}", "[NO_RAG]")
            )
            if safety_prompt:
                full_system += "\n" + safety_prompt

            # Load history from Redis
            raw_history = await r.get(session_key(session_id))
            history = json.loads(raw_history) if raw_history else []

            # Token-budgeting assembly
            messages = build_context(full_system, text, history, max_tokens=6000)

            # Call LLM streaming
            buffer = ""
            full_response = ""
            chunk_idx = 0
            sent_audio = False
            # Sentence end markers
            sentence_enders = re.compile(r'[.!?\n]')
            prev_emotion = "00"

            from shared.llm_engine import chat_completion_stream
            stream = chat_completion_stream(
                messages,
                model=llm_config.get("model"),
                temperature=llm_config.get("temperature", 0.7),
                max_tokens=llm_config.get("max_tokens", 1024),
                top_p=llm_config.get("top_p", 0.95),
                frequency_penalty=llm_config.get("frequency_penalty", 0.4),
                presence_penalty=llm_config.get("presence_penalty", 0.0),
            )

            from workers.smart_chunker import should_flush
            last_flush_ms = time.time() * 1000
            tokens_since_check = 0
            is_aborted = False

            for token in stream:
                if not token: continue
                buffer += token
                full_response += token
                
                tokens_since_check += 1
                if tokens_since_check >= 10:
                    tokens_since_check = 0
                    if await is_cancelled(r, request_id):
                        print(f"🛑 [LLM] Cancelled by user {request_id}")
                        is_aborted = True
                        break
                
                now_ms = time.time() * 1000
                do_flush, cut_idx = should_flush(buffer, chunk_idx, False, now_ms, last_flush_ms)
                
                if do_flush and cut_idx:
                    chunk = buffer[:cut_idx].strip()
                    remainder = buffer[cut_idx:].lstrip()

                    sentence = _sanitize_for_tts(chunk)
                    if not sentence:
                        print(f"⚠️ [LLM] Chunk {chunk_idx} sanitized EMPTY, dropping: {repr(chunk[:80])}")
                        buffer = remainder
                        continue
                        
                    emotion = _infer_emotion(sentence) if chunk_idx == 0 else prev_emotion
                    prev_emotion = emotion
                    
                    tts_job = {
                        "request_id": request_id,
                        "session_id": session_id,
                        "text": sentence,
                        "emotion": emotion,
                        "chunk_index": chunk_idx,
                        "is_last": False,
                        "resp_stream": resp_stream,
                        "input_text": text if chunk_idx == 0 else "",
                    }
                    print(f"🤖 [LLM] Chunk {chunk_idx}: {sentence}")
                    await xadd_json(r, STREAM_TTS, tts_job)
                    sent_audio = True
                    
                    buffer = remainder
                    chunk_idx += 1
                    last_flush_ms = now_ms

            if is_aborted:
                await r.xack(STREAM_LLM, GROUP, msg_id)
                continue

            # Flush remainder iteratively
            while buffer.strip():
                now_ms = time.time() * 1000
                do_flush, cut_idx = should_flush(buffer, chunk_idx, True, now_ms, last_flush_ms)
                
                if cut_idx is None: 
                    cut_idx = len(buffer)
                
                chunk = buffer[:cut_idx].strip()
                remainder = buffer[cut_idx:].lstrip()

                if not chunk:
                    break

                is_final_part = not bool(remainder.strip())
                sentence = _sanitize_for_tts(chunk)
                
                if sentence:
                    emotion = _infer_emotion(sentence) if chunk_idx == 0 else prev_emotion
                    prev_emotion = emotion
                    tts_job = {
                        "request_id": request_id,
                        "session_id": session_id,
                        "text": sentence,
                        "emotion": emotion,
                        "chunk_index": chunk_idx,
                        "is_last": is_final_part,
                        "resp_stream": resp_stream,
                        "input_text": text if chunk_idx == 0 else "",
                    }
                    print(f"🤖 [LLM] Chunk {chunk_idx} (flush): {sentence}")
                    await xadd_json(r, STREAM_TTS, tts_job)
                    sent_audio = True
                elif is_final_part:
                    await xadd_json(r, STREAM_TTS, {
                        "request_id": request_id, "session_id": session_id,
                        "text": "", "emotion": prev_emotion,
                        "chunk_index": chunk_idx, "is_last": True,
                        "resp_stream": resp_stream, "input_text": ""
                    })

                buffer = remainder
                chunk_idx += 1
                last_flush_ms = now_ms

            # Fallback: if no TTS chunk was sent, send a default response
            if not sent_audio:
                fallback_text = "Xin lỗi cậu, tớ chưa hiểu rõ câu hỏi. Cậu nói lại được không?"
                print(f"⚠️ [LLM] No audio chunks sent for {request_id}, sending fallback")
                await xadd_json(r, STREAM_TTS, {
                    "request_id": request_id, "session_id": session_id,
                    "text": fallback_text, "emotion": "00",
                    "chunk_index": 0, "is_last": True,
                    "resp_stream": resp_stream, "input_text": ""
                })

            # Trigger final is_last marker if buffer was completely empty upon termination
            elif not buffer.strip() and chunk_idx == 0:
                await xadd_json(r, STREAM_TTS, {
                    "request_id": request_id, "session_id": session_id,
                    "text": "", "emotion": prev_emotion,
                    "chunk_index": chunk_idx, "is_last": True,
                    "resp_stream": resp_stream, "input_text": ""
                })
            elif not buffer.strip() and chunk_idx > 0:
                 # If we already sent chunks natively and buffer is clean, 
                 # we should have already sent `is_last=True`?
                 # Wait, `is_last=True` only happens during flush remainder!
                 # If stream ended on a perfect cut_idx during the normal loop, 
                 # the buffer is perfectly empty here. We MUST send an empty final event!
                 await xadd_json(r, STREAM_TTS, {
                    "request_id": request_id, "session_id": session_id,
                    "text": "", "emotion": prev_emotion,
                    "chunk_index": chunk_idx, "is_last": True,
                    "resp_stream": resp_stream, "input_text": ""
                })

            # Update history with full response
            history.append({"role": "user", "content": text})
            history.append({"role": "assistant", "content": full_response})
            
            # Keep max 20 messages in DB to avoid huge JSONs, budget takes care of the rest
            if len(history) > 20:
                history = history[-20:]
            await r.setex(session_key(session_id), 86400, json.dumps(history, ensure_ascii=False))

            await r.xack(STREAM_LLM, GROUP, msg_id)

        except Exception as e:
            print(f"[LLM] error: {e}")
            traceback.print_exc()
            
            # Reset conversation or context if context window overflow occurs
            err_str = str(e).lower()
            if "context length" in err_str or "context_length_exceeded" in err_str or "400" in err_str:
                retry_count = job.get("retry_count", 0)
                if retry_count == 0:
                    print(f"⚠️ [LLM] Over context limit. Retry 1: Reducing history to last turn for {session_id}")
                    # Reload, trim to last 2 messages (1 turn), save to Redis
                    raw_hist = await r.get(session_key(session_id))
                    if raw_hist:
                        hist = json.loads(raw_hist)
                        if len(hist) > 2:
                            await r.setex(session_key(session_id), 86400, json.dumps(hist[-2:], ensure_ascii=False))
                    job["retry_count"] = 1
                    await xadd_json(r, STREAM_LLM, job)
                    await r.xack(STREAM_LLM, GROUP, msg_id)
                    continue
                elif retry_count == 1:
                    print(f"⚠️ [LLM] Over context limit. Retry 2: Stripping RAG context for {session_id}")
                    if "llm_config" in job and "system_prompt" in job["llm_config"]:
                        sys_prompt = job["llm_config"]["system_prompt"]
                        clean_prompt = re.sub(r'\n*<DỮ_LIỆU_HỆ_THỐNG_RAG>.*?</DỮ_LIỆU_HỆ_THỐNG_RAG>\n*', '', sys_prompt, flags=re.DOTALL)
                        job["llm_config"]["system_prompt"] = clean_prompt
                    job["retry_count"] = 2
                    await xadd_json(r, STREAM_LLM, job)
                    await r.xack(STREAM_LLM, GROUP, msg_id)
                    continue
                    
            # Try to notify user instead of hanging the client pipeline if retries exhausted
            if resp_stream and request_id:
                try:
                    fallback_text = "Xin lỗi cậu, dữ liệu tải về quá lớn khiến tớ bị nghẽn mạch rồi. Cậu thử hỏi lại ngắn gọn hơn một chút nhé."
                    if "connection" in err_str or "connect" in err_str or "timeout" in err_str or "refused" in err_str:
                        fallback_text = "Tớ đang bị mất kết nối với bộ não máy chủ rồi. Cậu đợi một chút xíu cho tớ thức dậy nha."
                        
                    await xadd_json(r, STREAM_TTS, {
                        "request_id": request_id,
                        "session_id": session_id,
                        "text": fallback_text,
                        "emotion": "10",
                        "chunk_index": 0,
                        "is_last": True,
                        "resp_stream": resp_stream,
                        "input_text": ""
                    })
                except Exception:
                    pass

            await r.xack(STREAM_LLM, GROUP, msg_id)
            await asyncio.sleep(0.2)


if __name__ == "__main__":
    asyncio.run(main())
