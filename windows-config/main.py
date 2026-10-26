"""M6GH AS-Built Config — isolated Windows stage-1 ABT viewer.

Python 3.10+, standard library only. No vehicle writes or network publishing.
"""
from __future__ import annotations

import re
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from pathlib import Path
import json
import os
import threading
from snapshot_mapper import parse_snapshot
from public_connection import PUBLISHABLE_KEY
from abt_codec import parse_checked
from license_gate import device_id, saved_license_valid, store_license
from supabase_publication import download_verified_row
from publication_cache import load_verified_cache, save_verified_cache
from feature_engine import Feature, FeatureEngine, checksum_valid

MODULES = {"IC": "720", "BCM": "726", "RKE": "731", "ABS": "760", "AFS": "737"}
ROW_PATTERN = re.compile(r"(?<![A-Za-z0-9])([0-9A-Fa-f]{3}-[0-9A-Fa-f]{2}-[0-9A-Fa-f]{2})\s+((?:[0-9A-Fa-f]{2,4}[ \t]+)*[0-9A-Fa-f]{2,4})(?![A-Za-z0-9])")


class WindowsConfig(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("M6GH AS-Built Config — Windows")
        self.geometry("1060x760")
        self.minsize(800, 580)
        self.configure(bg="#d7d7d7")
        self.active_module = "IC"
        self.raw_file: bytes | None = None
        self.source_path: Path | None = None
        self.rows: list[tuple[str, str]] = []
        self.tabs: dict[str, tk.Button] = {}
        self.features: list[Feature] = []
        self.engine = FeatureEngine()
        self.feature_vars: list[tk.BooleanVar] = []
        self.preview_rows: dict[str, str] = {}
        self.loaded_module: str | None = None
        self.withdraw()
        self.after(100, self.enforce_license)

    def enforce_license(self):
        try:
            identifier = device_id()
        except Exception as exc:
            messagebox.showerror("Лицензия", "Не удалось получить Device ID: " + str(exc))
            self.destroy()
            return
        if saved_license_valid(identifier):
            self._build()
            self.deiconify()
            self.after(100, self.load_cached_settings)
            self.after(500, self.refresh_online_silent)
            return
        dialog = tk.Toplevel(self)
        dialog.title("Активация M6GH AS-Built Config")
        dialog.geometry("520x320")
        dialog.resizable(False, False)
        dialog.protocol("WM_DELETE_WINDOW", self.destroy)
        tk.Label(dialog, text="Активация Windows Config", font=("Segoe UI", 16, "bold")).pack(pady=(18, 8))
        tk.Label(dialog, text="Отправьте Device ID продавцу и введите полученный ключ:").pack(pady=4)
        tk.Label(dialog, text=identifier, font=("Consolas", 18, "bold")).pack(pady=8)
        dialog.clipboard_clear()
        def copy_id():
            dialog.clipboard_clear()
            dialog.clipboard_append(identifier)
            messagebox.showinfo("Device ID", "Device ID скопирован", parent=dialog)
        tk.Button(dialog, text="Копировать Device ID", command=copy_id).pack(pady=5)
        entry = tk.Text(dialog, height=3, width=55, wrap="word")
        entry.pack(pady=8)
        def activate():
            try:
                store_license(entry.get("1.0", "end"), identifier)
            except Exception as exc:
                messagebox.showerror("Активация", str(exc), parent=dialog)
                return
            dialog.destroy()
            self._build()
            self.deiconify()
            self.after(100, self.load_cached_settings)
            self.after(500, self.refresh_online_silent)
        tk.Button(dialog, text="АКТИВИРОВАТЬ", command=activate,
                  bg="#c81930", fg="white", font=("Segoe UI", 11, "bold")).pack(pady=4)
        dialog.grab_set()
        dialog.focus_force()

    def panel(self, parent, **kwargs):
        return tk.Frame(parent, bg="#f4f4f6", highlightbackground="#b61d31",
                        highlightcolor="#b61d31", highlightthickness=2, **kwargs)

    def _build(self):
        root = tk.Frame(self, bg="#d7d7d7", padx=18, pady=16)
        root.pack(fill="both", expand=True)

        heading = tk.Label(root, text="M6GH AS-BUILT CONFIG", bg="#d7d7d7",
                           fg="#202020", font=("Segoe UI", 20, "bold"))
        heading.pack(anchor="w", pady=(0, 12))

        tabbar = self.panel(root)
        tabbar.pack(fill="x", pady=(0, 10))
        for module in MODULES:
            button = tk.Button(tabbar, text=module, font=("Segoe UI", 12, "bold"),
                               relief="flat", padx=23, pady=12,
                               command=lambda m=module: self.select_module(m))
            button.pack(side="left", fill="x", expand=True, padx=3, pady=3)
            self.tabs[module] = button

        info = self.panel(root)
        info.pack(fill="x", pady=(0, 10))
        self.module_label = tk.Label(info, bg="#f4f4f6", fg="#222222",
                                     font=("Segoe UI", 15, "bold"), anchor="w")
        self.module_label.pack(fill="x", padx=14, pady=(12, 4))
        self.module_details = tk.Label(info, bg="#f4f4f6", fg="#666666",
                                       font=("Segoe UI", 11), anchor="w")
        self.module_details.pack(fill="x", padx=14, pady=(0, 12))

        main = self.panel(root)
        main.pack(fill="both", expand=True, pady=(0, 10))
        tk.Label(main, text="ФУНКЦИИ БЛОКА", font=("Segoe UI", 13, "bold"),
                 bg="#f4f4f6", anchor="w").pack(fill="x", padx=14, pady=(12, 3))
        self.features_hint = tk.Label(main, text="Загрузите локальный JSON с функциями для предварительной проверки",
                 font=("Segoe UI", 10), bg="#f4f4f6", fg="#555555",
                 anchor="w")
        self.features_hint.pack(fill="x", padx=14, pady=(0, 4))
        self.feature_panel = tk.Frame(main, bg="#f4f4f6")
        self.feature_panel.pack(fill="x", padx=14, pady=(0, 12))
        tk.Label(main, text="AS-BUILT / HEX", font=("Segoe UI", 13, "bold"),
                 bg="#f4f4f6", anchor="w").pack(fill="x", padx=14, pady=(0, 6))

        grid = tk.Frame(main, bg="#f4f4f6")
        grid.pack(fill="both", expand=True, padx=14, pady=(0, 12))
        self.table = ttk.Treeview(grid, columns=("address", "value"), show="headings")
        self.table.heading("address", text="Адрес строки")
        self.table.heading("value", text="Значение HEX")
        self.table.column("address", width=210, stretch=False)
        self.table.column("value", width=650, stretch=True)
        scrollbar = ttk.Scrollbar(grid, orient="vertical", command=self.table.yview)
        self.table.configure(yscrollcommand=scrollbar.set)
        self.table.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        actions = tk.Frame(root, bg="#d7d7d7")
        actions.pack(fill="x", pady=(0, 10))
        for label, command in (("Открыть ABT", self.open_abt),
                               ("Сохранить копию ABT", self.save_abt)):
            tk.Button(actions, text=label, command=command,
                      font=("Segoe UI", 12, "bold"), padx=16, pady=12,
                      bg="#f4f4f6", activebackground="#e8e8ec").pack(
                          side="left", fill="x", expand=True, padx=4)
        self.status = tk.Label(root, text="Первый этап Windows: просмотр ABT без изменения файла",
                               bg="#f4f4f6", fg="#202020", font=("Segoe UI", 11, "bold"),
                               anchor="w", padx=12, pady=12,
                               highlightbackground="#b61d31", highlightthickness=2)
        self.status.pack(fill="x")
        self.select_module("IC")

    def select_module(self, module):
        self.active_module = module
        for name, button in self.tabs.items():
            selected = name == module
            button.configure(bg="#c81930" if selected else "#f4f4f6",
                             fg="white" if selected else "#161616",
                             activebackground="#a81629" if selected else "#e8e8ec")
        self.module_label.configure(text=f"{module}: {('Instrument Cluster' if module == 'IC' else module)}")
        prefix = MODULES[module]
        self.module_details.configure(text=f"ID: {prefix or '—'}  |  Ver: {'ABT загружен' if self.raw_file else 'Образец'}")
        self.table.delete(*self.table.get_children())
        shown = 0
        for address, value in self.rows:
            if prefix is not None and address.startswith(prefix + "-"):
                self.table.insert("", "end", values=(address, self.preview_rows.get(address, value)))
                shown += 1
        self.render_features()
        self.status.configure(text=f"{module}: строк {shown}. HEX — предварительный просмотр; запись отключена.")

    def open_abt(self):
        name = filedialog.askopenfilename(title="Открыть AS-Built",
                                          filetypes=[("AS-Built", "*.abt *.txt *.asbuilt"), ("Все файлы", "*.*")])
        if not name:
            return
        try:
            raw = Path(name).read_bytes()
            if len(raw) > 2_000_000:
                raise ValueError("Файл слишком большой")
            content = raw.decode("utf-8-sig") if not raw.startswith(b"\xff\xfe") else raw.decode("utf-16")
            parsed = parse_checked(content, {p: m for m, p in MODULES.items() if p})
            if any(row.module != self.active_module for row in parsed):
                raise ValueError("ABT относится к другому блоку. Выберите соответствующую вкладку.")
            matches = [(row.address, row.value) for row in parsed]
            self.raw_file, self.source_path, self.rows = raw, Path(name), matches
            self.preview_rows.clear()
            self.engine = FeatureEngine()
            self.loaded_module = self.active_module
            for feature in self.features:
                row = next((value for address, value in matches if address == feature.address), None)
                if feature.module == self.active_module and row is not None:
                    self.engine.seed(feature, self.engine.state(feature, row))
            self.select_module(self.active_module)
            self.status.configure(text=f"Загружен {self.source_path.name}: {len(matches)} строк. Только просмотр.")
        except (OSError, UnicodeError, ValueError) as exc:
            messagebox.showerror("Ошибка открытия ABT", str(exc))

    def load_cached_settings(self):
        try:
            publication = load_verified_cache()
            if publication is None:
                return
            features = parse_snapshot(publication.snapshot)
            self.features = features
            self.engine = FeatureEngine()
            for feature in features:
                original = next((v for a, v in self.rows if a == feature.address), None)
                if original is not None:
                    self.engine.seed(feature, self.engine.state(feature, original))
            self.select_module(self.active_module)
            self.status.configure(text=f"Офлайн: проверенная публикация v{publication.version}.")
        except Exception as exc:
            self.status.configure(text="Офлайн-кэш отклонён: " + str(exc)[:120])

    def refresh_online_silent(self):
        # Automatic only; no credentials or network prompts in customer UI.
        key = PUBLISHABLE_KEY or os.environ.get("M6GH_SUPABASE_PUBLISHABLE_KEY", "")
        if not key:
            self.status.configure(text="Онлайн-каталог не настроен в сборке; используется офлайн-кэш.")
            return
        url = "https://zyksvoauffhexketcyrq.supabase.co"
        self.status.configure(text="Автоматическая проверка обновлений...")
        def worker():
            try:
                row = download_verified_row(url, key)
                publication = save_verified_cache(row)
                features = parse_snapshot(publication.snapshot)
            except Exception:
                self.after(0, lambda: self.status.configure(
                    text="Сервер недоступен или публикация отклонена. Используются сохранённые настройки."))
                return
            def apply():
                self.features = features
                self.preview_rows.clear()
                self.engine = FeatureEngine()
                for feature in features:
                    original = next((v for a, v in self.rows if a == feature.address), None)
                    if original is not None:
                        self.engine.seed(feature, self.engine.state(feature, original))
                self.select_module(self.active_module)
                self.status.configure(text=f"Настройки обновлены автоматически: версия {publication.version}, функций {len(features)}.")
            self.after(0, apply)
        threading.Thread(target=worker, daemon=True).start()

    def open_features(self):
        name = filedialog.askopenfilename(title="Локальные функции (без Supabase)",
                                          filetypes=[("JSON", "*.json"), ("Все файлы", "*.*")])
        if not name:
            return
        try:
            if Path(name).stat().st_size > 1_000_000:
                raise ValueError("Слишком большой файл")
            data = json.loads(Path(name).read_text(encoding="utf-8-sig"))
            items = data.get("features", []) if isinstance(data, dict) else data
            if not isinstance(items, list):
                raise ValueError("Ожидался массив features")
            result = []
            for item in items:
                if not isinstance(item, dict):
                    raise ValueError("Неверный элемент features")
                feature = Feature(
                    id=str(item["id"]), module=str(item["module"]),
                    address=str(item["address"]).upper(), mode=str(item["mode"]).upper(),
                    indices=str(item.get("indices", "")), on=str(item.get("on", "")),
                    off=str(item.get("off", "")), byte_index=int(item.get("byteIndex", 0)),
                    label=str(item.get("label", item["id"])))
                if feature.mode not in ("HEX", "BITS"):
                    raise ValueError("Неизвестный режим функции")
                result.append(feature)
            self.features = result
            self.preview_rows.clear()
            self.engine = FeatureEngine()
            for feature in self.features:
                original = next((v for a, v in self.rows if a == feature.address), None)
                if original is not None:
                    self.engine.seed(feature, self.engine.state(feature, original))
            self.select_module(self.active_module)
            self.status.configure(text=f"Загружено функций: {len(result)}. Только локальный просмотр.")
        except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
            messagebox.showerror("Ошибка JSON", str(exc))

    def render_features(self):
        for widget in self.feature_panel.winfo_children():
            widget.destroy()
        self.feature_vars.clear()
        relevant = [f for f in self.features if f.module == self.active_module]
        if not relevant:
            self.features_hint.configure(text="Нет загруженных функций этого блока")
            return
        self.features_hint.configure(text="Локальные функции: изменения только в памяти (не сохраняются в ABT)")
        for i, feature in enumerate(relevant):
            original = next((v for a, v in self.rows if a == feature.address), None)
            value = self.preview_rows.get(feature.address, original) if original else None
            var = tk.BooleanVar(value=bool(value and self.engine.state(feature, value)))
            self.feature_vars.append(var)
            box = tk.Checkbutton(self.feature_panel, text=feature.label or feature.id, variable=var,
                                 bg="#f4f4f6", activebackground="#f4f4f6",
                                 fg="#222222", selectcolor="#f4f4f6",
                                 font=("Segoe UI", 10),
                                 command=lambda f=feature, v=var: self.toggle_feature(f, v))
            box.grid(row=i // 3, column=i % 3, sticky="w", padx=8, pady=3)
            if value is None:
                box.configure(state="disabled")

    def toggle_feature(self, feature, var):
        original = next((v for a, v in self.rows if a == feature.address), None)
        if original is None or self.loaded_module != self.active_module:
            var.set(not var.get())
            messagebox.showwarning("ABT", "Откройте ABT выбранного блока перед изменением.")
            return
        previous = self.preview_rows.get(feature.address, original)
        try:
            siblings = [f for f in self.features if f.module == feature.module and f.address == feature.address]
            new_value = self.engine.apply(feature.module, feature.address, previous,
                                          feature, var.get(), siblings)
            if not checksum_valid(feature.address, new_value):
                raise ValueError("Контрольная сумма не прошла проверку")
            self.preview_rows[feature.address] = new_value
            self.select_module(self.active_module)
            self.status.configure(text=f"Предпросмотр: {feature.label or feature.id} — {'ON' if var.get() else 'OFF'}. Файл не изменён.")
        except (ValueError, IndexError) as exc:
            var.set(not var.get())
            messagebox.showerror("Изменение отклонено", str(exc))

    def save_abt(self):
        if self.raw_file is None:
            messagebox.showinfo("Нет файла", "Сначала откройте ABT.")
            return
        name = filedialog.asksaveasfilename(title="Сохранить исходную копию ABT",
                                             defaultextension=".abt",
                                             initialfile=(self.source_path.stem + "_copy.abt"
                                                          if self.source_path else "copy.abt"))
        if name:
            try:
                Path(name).write_bytes(self.raw_file)
                self.status.configure(text="Сохранена точная копия исходного файла. Данные не изменялись.")
            except OSError as exc:
                messagebox.showerror("Ошибка сохранения", str(exc))


if __name__ == "__main__":
    WindowsConfig().mainloop()
