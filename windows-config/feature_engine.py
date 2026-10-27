"""Pure-Python port of Android FeatureEngine and AbtCodec arithmetic.

No network, no database mutations. Character indices are zero-based.
"""
from __future__ import annotations

from dataclasses import dataclass, field
import re


def norm(value: str) -> str:
    return re.sub(r"[^0-9A-Fa-f]", "", value or "").upper()


def spaced(value: str) -> str:
    raw = norm(value)
    return " ".join(raw[i:i + 4] for i in range(0, len(raw), 4))


def indices(spec: str) -> list[int]:
    out = []
    for part in re.split(r"[,;\s]+", spec.strip()):
        if not part:
            continue
        if "-" in part:
            a, b = (int(x) for x in part.split("-", 1))
            values = range(min(a, b), max(a, b) + 1)
        else:
            values = [int(part)]
        for n in values:
            if n < 0 or n > 4095 or len(out) >= 4096:
                raise ValueError("Index out of range")
            out.append(n)
    return out


def checksum(address: str, data: str) -> str:
    p = address.split("-")
    total = int(p[0][0], 16) + int(p[0][1:], 16) + int(p[1]) + int(p[2])
    raw = norm(data)
    total += sum(int(raw[i:i + 2], 16) for i in range(0, len(raw) - 1, 2))
    return f"{total & 255:02X}"


def recalc(address: str, value: str) -> str:
    raw = norm(value)
    if len(raw) < 4 or len(raw) % 2:
        return spaced(raw)
    return spaced(raw[:-2] + checksum(address, raw[:-2]))


def checksum_valid(address: str, value: str) -> bool:
    raw = norm(value)
    return len(raw) >= 4 and len(raw) % 2 == 0 and raw[-2:] == checksum(address, raw[:-2])


@dataclass(frozen=True)
class Feature:
    id: str
    module: str
    address: str
    mode: str
    indices: str
    on: str
    off: str = ""
    byte_index: int = 0
    label: str = ""


@dataclass
class FeatureEngine:
    baselines: dict[str, str] = field(default_factory=dict)
    enabled: set[str] = field(default_factory=set)
    initially_enabled: set[str] = field(default_factory=set)
    explicitly_disabled: set[str] = field(default_factory=set)

    @staticmethod
    def key(feature: Feature) -> str:
        return f"{feature.module}|{feature.address}|{feature.id}"

    def seed(self, feature: Feature, active: bool):
        if active:
            self.initially_enabled.add(self.key(feature))
            self.enabled.add(self.key(feature))

    @staticmethod
    def state(feature: Feature, value: str) -> bool:
        raw = norm(value)
        try:
            if feature.mode.upper() == "BITS":
                b = int(raw[feature.byte_index * 2:feature.byte_index * 2 + 2], 16)
                bits = indices(feature.indices)
                return bool(bits) and all(b & (1 << bit) for bit in bits)
            idx = indices(feature.indices)
            target = norm(feature.on)
            return bool(idx) and len(idx) == len(target) and all(raw[p] == t for p, t in zip(idx, target))
        except (ValueError, IndexError):
            return False

    @staticmethod
    def _patch(raw: list[str], feature: Feature, active: bool):
        if feature.mode.upper() == "BITS":
            p = feature.byte_index * 2
            if p < 0 or p + 2 > len(raw):
                raise ValueError("Byte out of range")
            value = int("".join(raw[p:p + 2]), 16)
            for bit in indices(feature.indices):
                if bit < 0 or bit > 7:
                    raise ValueError("Bit out of range")
                value = (value | (1 << bit)) if active else (value & ~(1 << bit))
            raw[p:p + 2] = list(f"{value:02X}")
        else:
            idx = indices(feature.indices)
            target = norm(feature.on if active else feature.off)
            if len(idx) != len(target):
                raise ValueError("HEX target/index mismatch")
            for p, digit in zip(idx, target):
                if p >= len(raw):
                    raise ValueError("HEX index out of range")
                raw[p] = digit

    @staticmethod
    def conflicts(first: Feature, second: Feature) -> bool:
        """True only for competing values at the same HEX position.

        Independent functions sharing an AS-Built row remain independent.
        """
        if first.id == second.id or first.module != second.module or first.address != second.address:
            return False
        if first.mode.upper() != "HEX" or second.mode.upper() != "HEX":
            return False
        a = dict(zip(indices(first.indices), norm(first.on)))
        b = dict(zip(indices(second.indices), norm(second.on)))
        return any(position in b and digit != b[position] for position, digit in a.items())

    def apply(self, module: str, address: str, value: str,
              changed: Feature, active: bool, features: list[Feature]) -> str:
        if changed.module != module or changed.address != address:
            raise ValueError("Feature address/module mismatch")
        row_key = f"{module}|{address}"
        baseline = self.baselines.get(row_key, norm(value))
        key = self.key(changed)
        enabled = set(self.enabled)
        disabled = set(self.explicitly_disabled)
        if active:
            # Selecting one HEX value deselects incompatible alternatives.
            # Never apply their OFF values: those could overwrite the chosen ON.
            for other in features:
                if self.conflicts(changed, other):
                    enabled.discard(self.key(other))
                    disabled.discard(self.key(other))
            enabled.add(key)
            disabled.discard(key)
        else:
            enabled.discard(key)
            if key in self.initially_enabled:
                if not changed.off.strip():
                    raise ValueError(f"Для функции {changed.label or changed.id} не задано значение OFF в админке")
                disabled.add(key)
        raw = list(baseline)
        touched = False
        for f in features:
            if f.module == module and f.address == address and self.key(f) in disabled and f.off:
                self._patch(raw, f, False)
                touched = True
        for f in features:
            if f.module == module and f.address == address and self.key(f) in enabled:
                self._patch(raw, f, True)
                touched = True
        result = recalc(address, "".join(raw)) if touched else spaced(baseline)
        self.baselines.setdefault(row_key, baseline)
        self.enabled = enabled
        self.explicitly_disabled = disabled
        return result


def changed_positions(original: str, current: str) -> set[int]:
    a, b = norm(original), norm(current)
    return {i for i in range(max(len(a), len(b))) if i >= len(a) or i >= len(b) or a[i] != b[i]}
