"""Offline Windows license verification compatible with Android M6L1 signing format.

Private signing key is NEVER included in this application.
Windows Device ID is SHA256(MachineGuid), first eight bytes uppercase HEX.
"""
import base64
import hashlib
import json
import os
import re
from pathlib import Path

PUBLIC_KEY_B64 = (
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnvIOfc7yWryj6RAP2tNG+K5wbh74J+Kpq7YxnBQFnypdR8sUXIqgfgDQka/jLidtiW4SIDlnoe7zgc7yeiQsxGGzTTLGSbuHigLxMNBSVqTPwtGLxs4PWX3uLbyjMX0zlScatwuuzbausW8kRcS20kg3fdXKGJnKE+zzckKwyN2OrT+vkLFUGGlvI+GaVqHa76N3+6VE08FrA9u8am4rF9wLG8lw4yjEx8euNYI2sWAfHjVS/Yb3G28gwnScGPhUVk1EK9retTDI1Yo8pMDnCbINcG39yZLI5l5yCiZHIWqoNX6224RtWEjYOb9rJkqrOkLGcPpt6vR5YP6uub/iswIDAQAB"
)


def device_id():
    if os.name != "nt":
        raise RuntimeError("Windows Device ID available on Windows only")
    import winreg
    with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                        r"SOFTWARE\Microsoft\Cryptography",
                        0, winreg.KEY_READ | winreg.KEY_WOW64_64KEY) as key:
        machine_guid, _ = winreg.QueryValueEx(key, "MachineGuid")
    if not machine_guid:
        raise ValueError("Windows MachineGuid is missing")
    return hashlib.sha256(machine_guid.encode("utf-8")).digest()[:8].hex().upper()


def _b64url(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def verify_license(code, device):
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    try:
        parts = re.sub(r"\s+", "", code).split(".")
        if len(parts) != 3 or parts[0] != "M6L1" or len(parts[1]) > 2048 or len(parts[2]) > 1024:
            return False
        data = json.loads(_b64url(parts[1]).decode("utf-8"))
        if data.get("v") != 1 or data.get("type") != "lifetime" or data.get("device") != device:
            return False
        key = serialization.load_der_public_key(base64.b64decode(PUBLIC_KEY_B64))
        key.verify(_b64url(parts[2]), parts[1].encode("ascii"),
                   padding.PKCS1v15(), hashes.SHA256())
        return True
    except (Exception):
        return False


def license_path():
    root = os.environ.get("LOCALAPPDATA", str(Path.home() / "AppData" / "Local"))
    return Path(root) / "M6GH-AS-Built-Config" / "license.txt"


def saved_license_valid(device):
    path = license_path()
    try:
        if path.stat().st_size > 4096:
            return False
        return verify_license(path.read_text(encoding="utf-8"), device)
    except (OSError, UnicodeError):
        return False


def store_license(code, device):
    if not verify_license(code, device):
        raise ValueError("Неверный ключ или лицензия для другого компьютера")
    path = license_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(re.sub(r"\s+", "", code), encoding="utf-8")
