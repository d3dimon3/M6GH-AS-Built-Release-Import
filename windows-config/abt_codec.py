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

def serialize_changes(content, changes, prefix_to_module):
    """Write changed rows only and validate a full round trip."""
    from feature_engine import checksum_valid
    original = parse_checked(content, prefix_to_module)
    addresses = [r.address for r in original]
    if len(addresses) != len(set(addresses)):
        raise ValueError("Повторяющиеся адреса ABT")
    if set(changes) - set(addresses):
        raise ValueError("Изменённые адреса отсутствуют в ABT")
    replacements = {}
    for row in original:
        if row.address in changes:
            value = spaced(changes[row.address])
            if len(norm(value)) != len(norm(row.value)) or not checksum_valid(row.address, value):
                raise ValueError("Некорректная контрольная сумма или длина: " + row.address)
            replacements[row.address] = value
    output, seen = [], set()
    for source in content.splitlines(keepends=True):
        clean = re.sub(r"\\s+", " ", re.sub(r"[,:=\\t]+", " ", source.strip().replace("\ufeff", "").upper())).strip()
        match = ROW.fullmatch(clean) or COMPACT.fullmatch(clean)
        if not match:
            output.append(source)
            continue
        prefix, first, second, payload = match.groups()
        address = f"{prefix}-{decode_index(first):02d}-{decode_index(second):02d}"
        if address not in replacements:
            output.append(source)
            continue
        if address in seen:
            raise ValueError("Повтор адреса " + address)
        seen.add(address)
        data = norm(replacements[address])
        ending = "\r\n" if source.endswith("\r\n") else ("\n" if source.endswith("\n") else "")
        if COMPACT.fullmatch(clean):
            line = prefix + first + second + data
        else:
            line = f"{prefix}-{first}-{second} {spaced(data)}"
        output.append(line + ending)
    if seen != set(replacements):
        raise ValueError("Не удалось заменить все строки")
    result = "".join(output)
    checked = parse_checked(result, prefix_to_module)
    if [(x.address, x.value) for x in checked] != [(x.address, replacements.get(x.address, x.value)) for x in original]:
        raise ValueError("Проверка сохранённого ABT не пройдена")
    return result
