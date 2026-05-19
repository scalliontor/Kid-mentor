"""
workers/smart_chunker.py — Basic Punctuation Chunker
"""

# Phân loại dấu câu
TERMINAL = {".", "?", "!", "\n"}
SOFT = {",", ";", ":", "—"}

def should_flush(buffer: str, chunk_index: int, llm_done: bool, now_ms: float, last_flush_ms: float) -> tuple[bool, int | None]:
    """
    Decide whether to flush the buffer to TTS.
    """
    if not buffer:
        return False, None

    if llm_done:
        return True, len(buffer)

    text = buffer.rstrip(' \t')
    if not text:
        return False, None
        
    length = len(text)
    last_char = text[-1]

    # Để tránh việc gọi TTS quá nhiều lần cho các câu rất ngắn (overhead lớn, sinh ra chậm hơn thời gian đọc),
    # ta phải bắt buộc buffer gom đủ dài rồi mới cắt.
    # Chunk đầu tiên: threshold thấp hơn để giảm thời gian chờ audio đầu tiên
    if chunk_index == 0:
        if last_char in TERMINAL and length >= 15:
            return True, len(buffer)
        if last_char in SOFT and length >= 40:
            return True, len(buffer)
        if length >= 80:
            last_space = buffer.rfind(' ', 0, len(buffer))
            if last_space > 0:
                return True, last_space + 1
            return True, len(buffer)
    else:
        # Với dấu chấm/xuống dòng: Gom ít nhất 40 ký tự
        if last_char in TERMINAL and length >= 40:
            return True, len(buffer)
        # Với dấu phẩy: Gom ít nhất 80 ký tự
        if last_char in SOFT and length >= 80:
            return True, len(buffer)
        # Ép cắt nếu câu quá dài mà không có dấu câu (tránh TTS render quá lâu)
        if length >= 160:
            last_space = buffer.rfind(' ', 0, len(buffer))
            if last_space > 0:
                return True, last_space + 1
            return True, len(buffer)

    return False, None
