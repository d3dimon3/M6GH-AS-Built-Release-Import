"""FORScan ABT parser compatible with the Android AbtCodec row formats."""
import re
from dataclasses import dataclass
from feature_engine import norm, spaced

ROW = re.compile(r"^([0-9A-F]{3})[- ]?([0-9A-Z]{2})[- ]?([0-9A-Z]{2})\s+([0-9A-F][0-9A-F ]*)$")
COMPACT = re.compile(r"^([0-9A-F]{3})([0-9A-Z]{2})([0-9A-Z]{2})([0-9A-F]+)$")
BLOCK = re.compile(r"^;\s*Block\s+(\d+)", re.I)

@dataclass(frozen=True)
class Row:
    module: str
    address: str
    value: str
    block: int

def decode_index(value):
    value = value.upper()
    if re.fullmatch(r"[0-9]{2}", value):
        return int(value)
    if not re.fullmatch(r"[G-V][0-9A-F]", value):
        raise ValueError("Invalid ABT index")
    return ((ord(value[0]) - ord("G")) << 4) + int(value[1], 16)

def parse_checked(content, prefix_to_module):
    rows = []
    block = -1
    for number, source in enumerate(content.splitlines(), 1):
        line = source.strip().replace("\ufeff", "").upper()
        header = BLOCK.match(line)
        if header:
            block = int(header.group(1))
            continue
        if not line or line.startswith((";", "#", "//")):
            continue
        clean = re.sub(r"\s+", " ", re.sub(r"[,:=\t]+", " ", line)).strip()
        match = ROW.fullmatch(clean) or COMPACT.fullmatch(clean)
        if not match:
            raise ValueError(f"Неверная строка ABT: {number}")
        prefix, first, second, payload = match.groups()
        a, b = decode_index(first), decode_index(second)
        data = norm(payload)
        if len(data) < 4 or len(data) % 2:
            raise ValueError(f"Неверное HEX-значение в строке {number}")
        rows.append(Row(prefix_to_module.get(prefix, "OTHER"),
                        f"{prefix}-{a:02d}-{b:02d}", spaced(data),
                        block if block >= 0 else a))
    if not rows:
        raise ValueError("Формат ABT не распознан")
    return rows
