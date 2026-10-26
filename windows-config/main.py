"""M6GH AS-Built Config — isolated Windows stage-1 ABT viewer.

Python 3.10+, standard library only. No vehicle writes or network publishing.
"""
from __future__ import annotations

import re
import hashlib
import hmac
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from pathlib import Path
import json
import os
import threading
import logging
import urllib.error
from snapshot_mapper import parse_snapshot
from public_connection import PUBLISHABLE_KEY
from abt_codec import parse_checked, serialize_changes
from license_gate import device_id, saved_license_valid, store_license
from supabase_publication import download_verified_row
from publication_cache import load_verified_cache, save_verified_cache
from feature_engine import Feature, FeatureEngine, checksum_valid

MODULES = {"IC": "720", "BCM": "726", "RKE": "731", "ABS": "760", "AFS": "737"}
ROW_PATTERN = re.compile(r"(?<![A-Za-z0-9])([0-9A-Fa-f]{3}-[0-9A-Fa-f]{2}-[0-9A-Fa-f]{2})\s+((?:[0-9A-Fa-f]{2,4}[ \t]+)*[0-9A-Fa-f]{2,4})(?![A-Za-z0-9])")



class HexTable(tk.Frame):
    """Compact bordered HEX rows with character-level active-feature highlighting."""
    def __init__(self, parent):
        super().__init__(parent, bg="#c4c4cb", bd=1, relief="solid")
        self.highlights = {}
        self.items = []
        header = tk.Frame(self, bg="#dedee3")
        header.pack(fill="x")
        for title, width in (("Адрес строки", 16), ("Значение HEX", 30)):
            tk.Label(header, text=title, width=width, bg="#dedee3",
                     font=("Segoe UI", 10, "bold"), anchor="center").pack(
                         side="left", fill="x", expand=True, pady=3)
        self.body = tk.Frame(self, bg="#c4c4cb")
        self.body.pack(fill="both", expand=True)

    def heading(self, *args, **kwargs):
        pass

    def column(self, *args, **kwargs):
        pass

    def tag_configure(self, *args, **kwargs):
        pass

    def get_children(self):
        return tuple(range(len(self.items)))

    def delete(self, *items):
        for child in self.body.winfo_children():
            child.destroy()
        self.items.clear()

    def insert(self, parent, position, values, tags=()):
        address, value = values
        self.items.append(values)
        row = tk.Frame(self.body, bg="#c4c4cb")
        row.pack(fill="x", pady=(0, 1))
        address_cell = tk.Frame(row, bg="#f0f0f2", bd=1, relief="solid")
        address_cell.pack(side="left", fill="both", expand=True, padx=(0, 1))
        tk.Label(address_cell, text=address, width=16, bg="#f0f0f2",
                 fg="#222222", font=("Consolas", 11, "bold"),
                 anchor="center", pady=1).pack(fill="x")
        value_cell = tk.Frame(row, bg="#f0f0f2", bd=1, relief="solid")
        value_cell.pack(side="left", fill="both", expand=True)
        text = tk.Text(value_cell, height=1, width=30, bg="#f0f0f2",
                       fg="#3b3b3b", font=("Consolas", 11), relief="flat",
                       bd=0, padx=5, pady=1, highlightthickness=0,
                       wrap="none", cursor="arrow")
        text.pack(fill="x")
        text.insert("1.0", value)
        text.tag_configure("active", foreground="#c81930",
                           font=("Consolas", 11, "bold"))
        raw_index = 0
        for column, char in enumerate(value):
            if char != " ":
                if raw_index in self.highlights.get(address, set()):
                    text.tag_add("active", f"1.{column}", f"1.{column + 1}")
                raw_index += 1
        text.configure(state="disabled")
        return len(self.items) - 1


def active_hex_positions(features, address, value):
    """Highlight enabled feature bits/nibbles only; never highlight checksum."""
    from feature_engine import indices, norm
    active = set()
    raw = norm(value)
    for feature in features:
        if feature.address != address or not FeatureEngine.state(feature, value):
            continue
        try:
            if feature.mode.upper() == "HEX":
                active.update(i for i in indices(feature.indices) if i < len(raw) - 2)
            elif feature.mode.upper() == "BITS":
                p = feature.byte_index * 2
                active.update(i for i in (p, p + 1) if i < len(raw) - 2)
        except (ValueError, IndexError):
            continue
    return active


class WindowsConfig(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("M6GH AS-Built Config — Windows")
        # Fit low-resolution Windows tablets while preserving desktop usability.
        self.update_idletasks()
        screen_w, screen_h = self.winfo_screenwidth(), self.winfo_screenheight()
        width = min(850, max(480, screen_w - 36))
        height = min(720, max(420, screen_h - 100))
        self.geometry(f"{width}x{height}")
        self.minsize(min(600, width), min(440, height))
        self.after_idle(lambda: self.center_dialog(self, width, height, make_transient=False))
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
        self.faq_entries = []
        self.admin_unlocked = False
        self.admin_authenticated = False
        self.admin_extra_rows = set()
        self.admin_visible_modules = set(MODULES)
        self.admin_module_info = {m: {"name": m, "version": ""} for m in MODULES}
        self.admin_heading = "M6GH AS-BUILT CONFIG"
        self.admin_open_caption = "Открыть ABT"
        self.admin_save_caption = "Сохранить ABT"
        self.admin_about_description = "M6GH AS-Built Config — для редактирования заводских конфигураций электронных блоков Mazda 6 GH. Приложение предназначено для активации и деактивации функций оборудования. После изменения функций сохраните новый ABT и проверьте его перед программированием в FORScan."
        self.admin_warning_title = "⚠ WARNING ⚠"
        self.admin_warning_text = "НЕ ЗАБЫВАЙТЕ ДЕЛАТЬ КОПИЮ ИСХОДНОГО ФАЙЛА НАСТРОЕК ИЗ FORSCAN."
        self.admin_authors = [["Разработчик", "Dim304", "https://www.drive2.ru/users/dim304/"], ["Соавтор", "Wolis11", "https://www.drive2.ru/users/wolis11/"]]
        self.catalog_version = None
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
            self.after(200, self.restore_admin_draft)
            # Windows admin operates locally; no remote refresh can overwrite edits.
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
        root = tk.Frame(self, bg="#d7d7d7", padx=9, pady=7)
        root.pack(fill="both", expand=True)

        top = tk.Frame(root, bg="#d7d7d7", height=46)
        top.pack(fill="x", pady=(0, 5))
        heading = tk.Label(top, text="M6GH AS-BUILT CONFIG", bg="#d7d7d7",
                           fg="#202020", font=("Segoe UI", 14, "bold"))
        heading.pack(side="left", anchor="w", padx=(2, 0), pady=(4, 4))
        self.heading_label = heading
        helpbar = tk.Frame(top, bg="#d7d7d7")
        helpbar.pack(side="right")
        self.helpbar = helpbar
        for caption, callback in (("FAQ", self.show_faq), ("О программе", self.show_about)):
            tk.Button(helpbar, text=caption, command=callback, bg="#f4f4f6",
                      highlightbackground="#b61d31", highlightthickness=2,
                      font=("Segoe UI", 10, "bold"), padx=12, pady=3).pack(side="left", padx=4)

        self.admin_button = tk.Button(helpbar, text="Админ-панель", command=self.show_admin_login, bg="#c81930", fg="white", font=("Segoe UI", 10, "bold"))
        tabbar = self.panel(root)
        tabbar.pack(fill="x", pady=(0, 6))
        for module in MODULES:
            button = tk.Button(tabbar, text=module, font=("Segoe UI", 10, "bold"),
                               relief="raised", bd=1, padx=5, pady=2,
                               command=lambda m=module: self.select_module(m))
            button.pack(side="left", fill="x", expand=True, padx=2, pady=2)
            self.tabs[module] = button

        info = self.panel(root)
        info.pack(fill="x", pady=(0, 6))
        self.module_label = tk.Label(info, bg="#f4f4f6", fg="#222222",
                                     font=("Segoe UI", 12, "bold"), anchor="w")
        self.module_label.pack(fill="x", padx=8, pady=(3, 0))
        self.module_details = tk.Label(info, bg="#f4f4f6", fg="#666666",
                                       font=("Segoe UI", 11), anchor="w")
        self.module_details.pack(fill="x", padx=8, pady=(0, 3))

        actions = tk.Frame(root, bg="#d7d7d7")
        actions.pack(side="bottom", fill="x", pady=(4, 0))
        self.action_buttons = []
        for label, command in ((self.admin_open_caption, self.open_abt),
                               (self.admin_save_caption, self.save_abt)):
            button = tk.Button(actions, text=label, command=command,
                      font=("Segoe UI", 10, "bold"), padx=7, pady=3,
                      bg="#f4f4f6", activebackground="#e8e8ec")
            button.pack(side="left", fill="x", expand=True, padx=4)
            self.action_buttons.append(button)
        self.status = tk.Label(root, wraplength=650, text="Перед изменением сохраняйте резервную копию исходного ABT",
                               bg="#f4f4f6", fg="#202020", font=("Segoe UI", 9),
                               anchor="w", padx=5, pady=2,
                               highlightbackground="#b61d31", highlightthickness=2)
        self.status.pack(side="bottom", fill="x", pady=(0, 5))
        main = self.panel(root)
        main.pack(fill="both", expand=True, pady=(0, 3))
        tk.Label(main, text="ФУНКЦИИ БЛОКА", font=("Segoe UI", 11, "bold"),
                 bg="#f4f4f6", anchor="w").pack(fill="x", padx=8, pady=(5, 1))
        self.features_hint = tk.Label(main, text="Загрузите локальный JSON с функциями для предварительной проверки",
                 font=("Segoe UI", 10), bg="#f4f4f6", fg="#555555",
                 anchor="w")
        self.features_hint.pack(fill="x", padx=8, pady=(0, 2))
        self.feature_panel = tk.Frame(main, bg="#f4f4f6")
        self.feature_panel.pack(fill="x", padx=8, pady=(0, 2))
        tk.Label(main, text="AS-BUILT / HEX", font=("Segoe UI", 11, "bold"),
                 bg="#f4f4f6", anchor="w").pack(fill="x", padx=8, pady=(0, 3))

        grid = tk.Frame(main, bg="#f4f4f6")
        grid.pack(fill="both", expand=True, padx=8, pady=(0, 4))
        self.table = HexTable(grid)
        self.table.pack(fill="both", expand=True)

        self.select_module("IC")

    def select_module(self, module):
        self.active_module = module
        for name, button in self.tabs.items():
            selected = name == module
            button.configure(bg="#c81930" if selected else "#c5c5cb",
                             fg="white" if selected else "#161616",
                             activebackground="#a81629" if selected else "#e8e8ec")
        info = self.admin_module_info.get(module, {})
        self.module_label.configure(text=f"{module}: {info.get('name') or module}")
        prefix = MODULES[module]
        self.module_details.configure(text=f"ID: {prefix or '—'}  |  Ver: {info.get('version') or ('ABT загружен' if self.raw_file else 'Образец')}")
        self.table.delete(*self.table.get_children())
        self.table.highlights = {}
        shown = 0
        for address, value in self.rows:
            if prefix is not None and address.startswith(prefix + "-"):
                current = self.preview_rows.get(address, value)
                relevant = [f for f in self.features if f.module == module]
                self.table.highlights[address] = active_hex_positions(relevant, address, current)
                self.table.insert("", "end", values=(address, current))
                shown += 1
        self.render_features()
        self.status.configure(text=f"{module}: строк {shown}. HEX — просмотр изменений; сохраняйте новый ABT.")

    def open_abt(self):
        name = filedialog.askopenfilename(title="Открыть AS-Built",
                                          filetypes=[("AS-Built", "*.abt *.txt *.asbuilt"), ("Все файлы", "*.*")])
        if not name:
            return
        try:
            raw = Path(name).read_bytes()
            if Path(name).suffix.lower() == ".key":
                self.unlock_admin_key(raw)
                return
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
            self.status.configure(text=f"Загружен {self.source_path.name}: {len(matches)} строк. Изменения можно сохранить в новый ABT.")
        except (OSError, UnicodeError, ValueError) as exc:
            messagebox.showerror("Ошибка открытия ABT", str(exc))

    def unlock_admin_key(self, raw):
        # Compatibility with the owner's historical admin key. This is an
        # exact-file compatibility check, not a cryptographic signature scheme.
        expected = "73c58455bbe288de332228b8169a630d6254a9c129930c73268b9e42d6d313ea"
        valid = raw.strip().startswith(b"MAZDA6GH-ADMIN-V1\n") and hmac.compare_digest(
            hashlib.sha256(raw.strip()).hexdigest(), expected)
        if not valid:
            messagebox.showerror("Админ-ключ", "Ключ администратора не распознан.")
            return
        self.admin_unlocked = True
        self.show_admin_login()

    def show_admin_login(self):
        if not self.admin_unlocked:
            return
        if self.admin_authenticated:
            self.show_admin_panel()
            return
        dialog = tk.Toplevel(self)
        dialog.title("Вход в админ-панель")
        self.center_dialog(dialog, 390, 240)
        dialog.resizable(False, False)
        tk.Label(dialog, text="Авторизация администратора", font=("Segoe UI", 14, "bold")).pack(pady=15)
        tk.Label(dialog, text="Логин").pack()
        login = tk.Entry(dialog, width=35)
        login.pack()
        tk.Label(dialog, text="Пароль").pack()
        password = tk.Entry(dialog, width=35, show="*")
        password.pack()
        def submit():
            # Historical Windows V7.1 credential hash; no credentials in server calls.
            old_hash = "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918"
            if login.get() == "admin" and hmac.compare_digest(hashlib.sha256(password.get().encode()).hexdigest(), old_hash):
                self.admin_authenticated = True
                self.admin_button.pack(side="left", padx=4)
                dialog.destroy()
                self.show_admin_panel()
            else:
                messagebox.showerror("Админ-панель", "Неверный логин или пароль", parent=dialog)
        tk.Button(dialog, text="Войти", command=submit, bg="#c81930", fg="white").pack(pady=12)
        password.bind("<Return>", lambda event: submit())
        login.focus_set()
        dialog.grab_set()

    def save_admin_draft_file(self):
        """Persist edits immediately, not only when the admin window closes."""
        folder = Path.home() / ".m6gh_asbuilt_config"
        folder.mkdir(parents=True, exist_ok=True)
        destination = folder / "windows_admin_draft.json"
        data = {
            "format": "m6gh-windows-admin-draft-v1",
            "features": [dict(id=f.id, module=f.module, address=f.address,
                              mode=f.mode, indices=f.indices, on=f.on, off=f.off,
                              byte_index=f.byte_index, label=f.label) for f in self.features],
            "extra_rows": [list(item) for item in sorted(self.admin_extra_rows)],
            "visible_modules": sorted(self.admin_visible_modules),
            "module_info": self.admin_module_info,
            "heading": self.admin_heading,
            "faq": self.faq_entries,
            "open_caption": self.admin_open_caption,
            "save_caption": self.admin_save_caption,
            "about_description": self.admin_about_description,
            "warning_title": self.admin_warning_title,
            "warning_text": self.admin_warning_text,
            "authors": self.admin_authors,
        }
        temporary = destination.with_suffix(".tmp")
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(destination)

    def restore_admin_draft(self):
        """Restore the admin-only local draft after online catalog loading."""
        path = Path.home() / ".m6gh_asbuilt_config" / "windows_admin_draft.json"
        if not path.exists():
            return
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if data.get("format") != "m6gh-windows-admin-draft-v1":
                raise ValueError("Неверный формат")
            features = [Feature(**item) for item in data["features"]]
            visible = set(data["visible_modules"]) & set(MODULES)
            heading = data["heading"]
            faq = data["faq"]
            extra = {(m, a) for m, a in data["extra_rows"]
                     if m in MODULES and re.fullmatch(r"[0-9A-F]{3}-[0-9]{2}-[0-9]{2}", a)
                     and a.startswith(MODULES[m] + "-")}
            if not visible or not isinstance(heading, str) or not 1 <= len(heading) <= 45 or not isinstance(faq, list):
                raise ValueError("Некорректные настройки")
        except (OSError, ValueError, TypeError, KeyError, AttributeError) as exc:
            messagebox.showwarning("Черновик админки", "Не удалось восстановить настройки: " + str(exc))
            return
        self.features = features
        self.admin_visible_modules = visible
        saved_info = data.get("module_info", {})
        if isinstance(saved_info, dict):
            for module in MODULES:
                item = saved_info.get(module)
                if isinstance(item, dict):
                    self.admin_module_info[module] = {
                        "name": str(item.get("name", module))[:70],
                        "version": str(item.get("version", ""))[:40],
                    }
        self.admin_extra_rows = extra
        self.admin_heading = heading
        self.admin_open_caption = str(data.get("open_caption", self.admin_open_caption))
        self.admin_save_caption = str(data.get("save_caption", self.admin_save_caption))
        for button, caption in zip(self.action_buttons, (self.admin_open_caption, self.admin_save_caption)):
            button.configure(text=caption)
        self.faq_entries = faq
        self.admin_about_description = str(data.get("about_description", self.admin_about_description))
        self.admin_warning_title = str(data.get("warning_title", self.admin_warning_title))
        self.admin_warning_text = str(data.get("warning_text", self.admin_warning_text))
        authors = data.get("authors", self.admin_authors)
        if isinstance(authors, list) and len(authors) == 2 and all(isinstance(x, list) and len(x) == 3 for x in authors):
            self.admin_authors = authors
        self.admin_open_caption = str(data.get("open_caption", self.admin_open_caption))
        self.admin_save_caption = str(data.get("save_caption", self.admin_save_caption))
        for button, caption in zip(self.action_buttons, (self.admin_open_caption, self.admin_save_caption)):
            button.configure(text=caption)
        self.heading_label.configure(text=heading)
        for module, button in self.tabs.items():
            if module in visible:
                button.pack(side="left", fill="x", expand=True, padx=3, pady=3)
            else:
                button.pack_forget()
        self.select_module(self.active_module if self.active_module in visible else next(m for m in MODULES if m in visible))

    def show_admin_panel(self):
        if not self.admin_authenticated:
            return
        self.restore_admin_draft()
        dialog = tk.Toplevel(self)
        dialog.title("Администрирование интерфейса — Windows")
        self.center_dialog(dialog, 790, 610)
        dialog.resizable(True, True)
        dialog.minsize(900, 680)
        dialog.geometry("1040x780")
        tabs = ttk.Notebook(dialog)
        tabs.pack(fill="both", expand=True, padx=12, pady=12)
        pages = {}
        for title in ("Функции и биты", "Строки AS-Built", "Блоки и доступ", "Оформление", "FAQ"):
            page = tk.Frame(tabs, bg="#f4f4f6")
            tabs.add(page, text=title)
            pages[title] = page
        features_page = pages["Функции и биты"]
        columns = ("Блок", "Функция", "Адрес", "Режим", "ON", "OFF")
        tree = ttk.Treeview(features_page, columns=columns, show="headings", height=8)
        for column in columns:
            tree.heading(column, text=column)
            tree.column(column, width=100 if column != "Функция" else 180)
        tree.pack(fill="both", expand=True, padx=8, pady=8)
        tree.tag_configure("odd", background="#e7e7e7")
        tree.tag_configure("even", background="#ffffff")
        for feature in self.features:
            tree.insert("", "end", values=(feature.module, feature.label, feature.address, feature.mode, feature.on, feature.off))
        tk.Label(features_page, text="Каталог из подписанной публикации. Редактирование и отправка на сервер пока не включены.",
                 bg="#f4f4f6", fg="#8a2534", wraplength=700).pack(pady=10)
        editor = tk.Frame(features_page, bg="#f4f4f6")
        editor.pack(fill="x", padx=8)
        fields = ("module", "label", "address", "mode", "indices", "on", "off", "byte_index")
        field_labels = ("Модуль", "Надпись / функция", "Строка", "Режим HEX/BITS", "HEX индексы / биты", "ВКЛ", "ВЫКЛ", "Номер байта")
        inputs = {}
        captions = {"module": "Модуль", "label": "Надпись / функция",
                    "address": "Строка AS-Built", "mode": "Режим HEX/BITS",
                    "indices": "HEX индексы / биты", "on": "ВКЛ",
                    "off": "ВЫКЛ", "byte_index": "Номер байта"}
        for i, field in enumerate(fields):
            tk.Label(editor, text=captions[field], bg="#f4f4f6").grid(
                row=(i // 2) * 2, column=i % 2, sticky="w", padx=12, pady=(5, 0))
            entry = tk.Entry(editor, width=35)
            entry.grid(row=(i // 2) * 2 + 1, column=i % 2, padx=12, pady=3)
            inputs[field] = entry
        selected = [None]
        def refresh_editor():
            tree.delete(*tree.get_children())
            for number, f in enumerate(self.features):
                tree.insert("", "end", values=(f.module, f.label, f.address, f.mode, f.on, f.off), tags=(f.id, "odd" if number % 2 else "even"))
            self.engine = FeatureEngine()
            for f in self.features:
                original = next((v for a, v in self.rows if a == f.address), None)
                if original is not None:
                    self.engine.seed(f, self.engine.state(f, original))
            self.select_module(self.active_module)
        def pick(event=None):
            selection = tree.selection()
            if not selection:
                return
            tags = tree.item(selection[0], "tags")
            if not tags:
                return
            selected[0] = tags[0]
            f = next((f for f in self.features if f.id == selected[0]), None)
            if f:
                for field in fields:
                    inputs[field].delete(0, "end")
                    inputs[field].insert(0, str(getattr(f, field)))
        tree.bind("<<TreeviewSelect>>", pick)
        def save_local():
            from dataclasses import replace
            from feature_engine import indices, norm
            import uuid
            try:
                values = {field: inputs[field].get().strip() for field in fields}
                values["module"] = values["module"].upper()
                values["address"] = values["address"].upper()
                values["mode"] = values["mode"].upper()
                values["byte_index"] = int(values["byte_index"] or "0")
                if values["module"] not in MODULES or not MODULES[values["module"]]:
                    raise ValueError("Неизвестный блок")
                if not re.fullmatch(r"[0-9A-F]{3}-[0-9]{2}-[0-9]{2}", values["address"]):
                    raise ValueError("Неверный адрес AS-Built")
                if not values["address"].startswith(MODULES[values["module"]] + "-"):
                    raise ValueError("Адрес не соответствует блоку")
                positions = indices(values["indices"])
                if not positions or values["byte_index"] < 0:
                    raise ValueError("Неверные позиции")
                if values["mode"] == "HEX":
                    if not values["off"]:
                        values["off"] = "0" * len(positions)
                    if len(positions) != len(norm(values["on"])) or len(positions) != len(norm(values["off"])):
                        raise ValueError("Количество HEX-позиций должно совпадать с ON и OFF")
                elif values["mode"] != "BITS" or any(p > 7 for p in positions):
                    raise ValueError("Неверные позиции BITS")
                previous = next((f for f in self.features if f.id == selected[0]), None)
                candidate = replace(previous, **values) if previous else Feature(
                    id="local-" + uuid.uuid4().hex[:12], **values)
                if previous:
                    self.features[self.features.index(previous)] = candidate
                else:
                    self.features.append(candidate)
                selected[0] = candidate.id
                refresh_editor()
                self.save_admin_draft_file()
                messagebox.showinfo("Функции и биты", "Функция сохранена: " + candidate.label, parent=dialog)
                self.status.configure(text="Админка: изменения применены локально, сервер не затронут.")
            except Exception as exc:
                messagebox.showerror("Редактор", "Не удалось сохранить функцию: " + str(exc), parent=dialog)
        def new_local():
            selected[0] = None
            tree.selection_remove(*tree.selection())
            for field in fields:
                inputs[field].delete(0, "end")
            inputs["module"].insert(0, self.active_module)
            inputs["mode"].insert(0, "HEX")
            inputs["byte_index"].insert(0, "0")
        def delete_local():
            if selected[0] and messagebox.askyesno("Удалить", "Удалить функцию локально?", parent=dialog):
                self.features = [f for f in self.features if f.id != selected[0]]
                selected[0] = None
                refresh_editor()
                self.save_admin_draft_file()
        controls = tk.Frame(features_page, bg="#f4f4f6")
        controls.pack()
        for label, callback in (("Добавить новую", new_local), ("Добавить / изменить", save_local), ("Удалить", delete_local)):
            tk.Button(controls, text=label, command=callback).pack(side="left", padx=4)
        refresh_editor()
        def export_catalog():
            destination = filedialog.asksaveasfilename(parent=dialog, title="Сохранить каталог функций",
                defaultextension=".json", filetypes=[("JSON", "*.json")])
            if not destination:
                return
            from dataclasses import asdict
            payload = {"features": [asdict(feature) for feature in self.features]}
            try:
                Path(destination).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
            except OSError as exc:
                messagebox.showerror("Экспорт", str(exc), parent=dialog)
                return
            messagebox.showinfo("Экспорт", "Каталог сохранён локально. На сервер ничего не отправлено.", parent=dialog)
        tk.Button(features_page, text="Экспорт функций в JSON", command=export_catalog).pack(pady=5)
        rows_page = pages["Строки AS-Built"]
        tk.Label(rows_page, text="Каталог адресов AS-Built", bg="#f4f4f6",
                 font=("Segoe UI", 12, "bold")).pack(pady=10)
        row_columns = ("Блок", "Адрес", "HEX из ABT", "Функций")
        row_tree = ttk.Treeview(rows_page, columns=row_columns, show="headings", height=13)
        for column, width in zip(row_columns, (100, 160, 260, 100)):
            row_tree.heading(column, text=column)
            row_tree.column(column, width=width, anchor="center")
        row_tree.pack(fill="both", expand=True, padx=14, pady=8)
        def refresh_rows():
            row_tree.delete(*row_tree.get_children())
            addresses = set((f.module, f.address) for f in self.features)
            addresses.update((self.active_module, address) for address, value in self.rows)
            addresses.update(self.admin_extra_rows)
            for module, address in sorted(addresses):
                original = next((v for a, v in self.rows if a == address), "")
                shown = self.preview_rows.get(address, original) if original else "—"
                count = sum(f.module == module and f.address == address for f in self.features)
                row_tree.insert("", "end", values=(module, address, shown, count))
        controls = tk.Frame(rows_page, bg="#f4f4f6")
        controls.pack(pady=5)
        tk.Label(controls, text="Блок", bg="#f4f4f6").pack(side="left")
        module_var = tk.StringVar(value=self.active_module)
        ttk.Combobox(controls, textvariable=module_var, values=list(MODULES), state="readonly",
                     width=7).pack(side="left", padx=6)
        tk.Label(controls, text="Адрес", bg="#f4f4f6").pack(side="left")
        address_entry = tk.Entry(controls, width=15)
        address_entry.pack(side="left", padx=6)
        def add_address():
            module = module_var.get()
            address = address_entry.get().strip().upper()
            if not re.fullmatch(r"[0-9A-F]{3}-[0-9]{2}-[0-9]{2}", address) or not address.startswith(MODULES[module] + "-"):
                messagebox.showerror("Адрес", "Укажите адрес выбранного блока, например 720-01-01", parent=dialog)
                return
            self.admin_extra_rows.add((module, address))
            refresh_rows()
        def remove_address():
            selection = row_tree.selection()
            if not selection:
                return
            module, address = row_tree.item(selection[0], "values")[:2]
            if (module, address) not in self.admin_extra_rows:
                messagebox.showinfo("Адрес", "Строки из ABT или каталога функций нельзя удалить здесь.", parent=dialog)
                return
            self.admin_extra_rows.discard((module, address))
            refresh_rows()
        tk.Button(controls, text="Добавить адрес", command=add_address).pack(side="left", padx=5)
        tk.Button(controls, text="Удалить добавленный", command=remove_address).pack(side="left", padx=5)
        tk.Label(rows_page, text="Изменяются только дополнительные адреса каталога в текущем сеансе; исходный ABT не затрагивается.",
                 bg="#f4f4f6", fg="#8a2534").pack(pady=5)
        refresh_rows()
        access_page = pages["Блоки и доступ"]
        tk.Label(access_page, text="Блоки и доступ", font=("Segoe UI", 16, "bold"),
                 bg="#f4f4f6").pack(pady=(12, 4))
        tk.Label(access_page, text="Настройки блоков. HEX-префиксы защищены от случайного изменения.",
                 bg="#f4f4f6", fg="#555555").pack(pady=(0, 6))
        holder = tk.Frame(access_page, bg="#f4f4f6")
        holder.pack(fill="both", expand=True, padx=14)
        canvas = tk.Canvas(holder, bg="#f4f4f6", highlightthickness=0)
        scroll = ttk.Scrollbar(holder, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        cards = tk.Frame(canvas, bg="#f4f4f6")
        canvas_window = canvas.create_window((0, 0), window=cards, anchor="nw")
        cards.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(canvas_window, width=e.width))
        module_vars, name_entries, version_entries = {}, {}, {}
        for module, prefix in MODULES.items():
            info = self.admin_module_info.get(module, {})
            card = tk.Frame(cards, bg="#ffffff", highlightbackground="#c81930",
                            highlightthickness=1, padx=12, pady=8)
            card.pack(fill="x", padx=5, pady=5)
            header = tk.Frame(card, bg="#ffffff")
            header.pack(fill="x")
            tk.Label(header, text=module, bg="#ffffff", fg="#b71930",
                     font=("Segoe UI", 13, "bold")).pack(side="left")
            enabled = tk.BooleanVar(value=module in self.admin_visible_modules)
            module_vars[module] = enabled
            tk.Checkbutton(header, text="Показывать в Config", variable=enabled,
                           bg="#ffffff").pack(side="right")
            form = tk.Frame(card, bg="#ffffff")
            form.pack(fill="x", pady=(6, 0))
            for col, (caption, value, editable) in enumerate((
                ("Название", info.get("name", module), True),
                ("ID / HEX-адрес", prefix, False),
                ("Версия", info.get("version", ""), True),
            )):
                tk.Label(form, text=caption, bg="#ffffff").grid(row=0, column=col, sticky="w", padx=5)
                entry = tk.Entry(form, width=24 if col == 0 else 16)
                entry.insert(0, value)
                entry.grid(row=1, column=col, sticky="ew", padx=5, pady=3)
                form.columnconfigure(col, weight=1)
                if not editable:
                    entry.configure(state="readonly")
                elif col == 0:
                    name_entries[module] = entry
                else:
                    version_entries[module] = entry
        def apply_access():
            enabled = {module for module, variable in module_vars.items() if variable.get()}
            if not enabled:
                messagebox.showerror("Блоки", "Оставьте хотя бы один блок.", parent=dialog)
                return
            new_info = {}
            for module in MODULES:
                name = name_entries[module].get().strip()
                version = version_entries[module].get().strip()
                if not name or len(name) > 70 or len(version) > 40:
                    messagebox.showerror("Блоки", "Проверьте название и версию блока.", parent=dialog)
                    return
                new_info[module] = {"name": name, "version": version}
            self.admin_module_info = new_info
            self.admin_visible_modules = enabled
            for module, button in self.tabs.items():
                if module in enabled:
                    button.pack(side="left", fill="x", expand=True, padx=3, pady=3)
                else:
                    button.pack_forget()
            self.select_module(self.active_module if self.active_module in enabled else next(m for m in MODULES if m in enabled))
            self.save_admin_draft_file()
            self.status.configure(text="Карточки блоков сохранены локально.")
            messagebox.showinfo("Блоки", "Изменения сохранены и восстановятся после перезапуска.", parent=dialog)
        tk.Button(access_page, text="Сохранить блоки и доступ", command=apply_access,
                  bg="#c81930", fg="white", font=("Segoe UI", 11, "bold")).pack(pady=12)
        appearance = pages["Оформление"]
        tk.Label(appearance, text="Оформление приложения", font=("Segoe UI", 15, "bold"),
                 bg="#f4f4f6").pack(pady=14)
        appearance_fields = tk.Frame(appearance, bg="#f4f4f6")
        appearance_fields.pack(padx=22, fill="x")
        appearance_entries = {}
        for row, (key, label, value) in enumerate((
            ("heading", "Название окна", self.admin_heading),
            ("open", "Кнопка открытия", self.admin_open_caption),
            ("save", "Кнопка сохранения", self.admin_save_caption),
        )):
            tk.Label(appearance_fields, text=label, bg="#f4f4f6", anchor="w",
                     font=("Segoe UI", 11)).grid(row=row, column=0, sticky="w", padx=10, pady=10)
            entry = tk.Entry(appearance_fields, width=55, font=("Segoe UI", 11))
            entry.insert(0, value)
            entry.grid(row=row, column=1, padx=10, pady=10, sticky="ew")
            appearance_entries[key] = entry
        appearance_fields.columnconfigure(1, weight=1)
        def apply_appearance():
            values = {key: entry.get().strip() for key, entry in appearance_entries.items()}
            if any(not value or len(value) > 45 for value in values.values()):
                messagebox.showerror("Оформление", "Заполните все поля (не более 45 символов).", parent=dialog)
                return
            self.admin_heading = values["heading"]
            self.admin_open_caption = values["open"]
            self.admin_save_caption = values["save"]
            self.heading_label.configure(text=self.admin_heading)
            for button, caption in zip(self.action_buttons, (self.admin_open_caption, self.admin_save_caption)):
                button.configure(text=caption)
            self.save_admin_draft_file()
            messagebox.showinfo("Оформление", "Надписи сохранены локально.", parent=dialog)
        tk.Button(appearance, text="Сохранить оформление", command=apply_appearance).pack(pady=8)
        about_editor = tk.Frame(appearance, bg="#f4f4f6")
        about_editor.pack(fill="both", expand=True, padx=22, pady=8)
        about_canvas = tk.Canvas(about_editor, bg="#f4f4f6", highlightthickness=0)
        about_scroll = ttk.Scrollbar(about_editor, orient="vertical", command=about_canvas.yview)
        about_canvas.configure(yscrollcommand=about_scroll.set)
        about_scroll.pack(side="right", fill="y")
        about_canvas.pack(side="left", fill="both", expand=True)
        about_content = tk.Frame(about_canvas, bg="#f4f4f6")
        about_window = about_canvas.create_window((0, 0), window=about_content, anchor="nw")
        about_content.bind("<Configure>", lambda e: about_canvas.configure(scrollregion=about_canvas.bbox("all")))
        about_canvas.bind("<Configure>", lambda e: about_canvas.itemconfigure(about_window, width=e.width))
        def labeled_text(label, initial, height):
            tk.Label(about_content, text=label, bg="#f4f4f6", anchor="w").pack(fill="x", pady=(8, 2))
            widget = tk.Text(about_content, height=height, wrap="word", font=("Segoe UI", 10))
            widget.insert("1.0", initial)
            widget.pack(fill="x")
            return widget
        description_editor = labeled_text("Описание программы", self.admin_about_description, 5)
        warning_title_editor = labeled_text("Заголовок предупреждения (красный)", self.admin_warning_title, 1)
        warning_text_editor = labeled_text("Текст предупреждения (красный)", self.admin_warning_text, 3)
        author_inputs = []
        for number, author in enumerate(self.admin_authors, 1):
            group = []
            for caption, value in zip(("Подпись", "Имя", "Ссылка Drive2"), author):
                tk.Label(about_content, text=f"Автор {number} — {caption}", bg="#f4f4f6").pack(anchor="w", pady=(5, 1))
                field = tk.Entry(about_content)
                field.insert(0, value)
                field.pack(fill="x")
                group.append(field)
            author_inputs.append(group)
        def save_about():
            self.admin_about_description = description_editor.get("1.0", "end-1c").strip()
            self.admin_warning_title = warning_title_editor.get("1.0", "end-1c").strip()
            self.admin_warning_text = warning_text_editor.get("1.0", "end-1c").strip()
            self.admin_authors = [[entry.get().strip() for entry in group] for group in author_inputs]
            self.save_admin_draft_file()
            messagebox.showinfo("Оформление", "Описание, предупреждение и авторы сохранены.", parent=dialog)
        tk.Button(about_content, text="Сохранить описание и авторов", command=save_about).pack(pady=12)

        faq_page = pages["FAQ"]
        tk.Label(faq_page, text="Редактор FAQ (локально)", font=("Segoe UI", 14, "bold"),
                 bg="#f4f4f6").pack(pady=8)
        faq_list = tk.Listbox(faq_page, height=8)
        faq_list.pack(fill="x", padx=18)
        tk.Label(faq_page, text="Заголовок", bg="#f4f4f6").pack()
        faq_name = tk.Entry(faq_page, width=75)
        faq_name.pack()
        tk.Label(faq_page, text="Описание", bg="#f4f4f6").pack()
        faq_description = tk.Text(faq_page, height=6, width=75)
        faq_description.pack()
        def refresh_faq():
            faq_list.delete(0, "end")
            for item in self.faq_entries:
                faq_list.insert("end", str(item.get("name", "")) if isinstance(item, dict) else "")
        def select_faq(event=None):
            selected = faq_list.curselection()
            if not selected:
                return
            item = self.faq_entries[selected[0]]
            if not isinstance(item, dict):
                return
            faq_name.delete(0, "end")
            faq_name.insert(0, str(item.get("name", "")))
            faq_description.delete("1.0", "end")
            faq_description.insert("1.0", str(item.get("description", "")))
        faq_list.bind("<<ListboxSelect>>", select_faq)
        def save_faq():
            name = faq_name.get().strip()
            description = faq_description.get("1.0", "end-1c").strip()
            if not name:
                messagebox.showerror("FAQ", "Введите заголовок.", parent=dialog)
                return
            selected = faq_list.curselection()
            item = {"name": name, "description": description}
            if selected:
                self.faq_entries[selected[0]] = item
            else:
                self.faq_entries.append(item)
            refresh_faq()
        def new_faq():
            faq_list.selection_clear(0, "end")
            faq_name.delete(0, "end")
            faq_description.delete("1.0", "end")
        def delete_faq():
            selected = faq_list.curselection()
            if selected and messagebox.askyesno("FAQ", "Удалить выбранную запись?", parent=dialog):
                self.faq_entries.pop(selected[0])
                new_faq()
                refresh_faq()
        faq_buttons = tk.Frame(faq_page, bg="#f4f4f6")
        faq_buttons.pack(pady=8)
        for caption, action in (("Новая", new_faq), ("Сохранить", save_faq),
                                ("Удалить", delete_faq), ("Просмотр FAQ", self.show_faq)):
            tk.Button(faq_buttons, text=caption, command=action).pack(side="left", padx=5)
        tk.Label(faq_page, text="Изменения видны в FAQ до перезапуска; публикации на сервер нет.",
                 bg="#f4f4f6").pack()
        refresh_faq()
        def save_admin_draft():
            path = filedialog.asksaveasfilename(parent=dialog, title="Сохранить настройки админки",
                                                defaultextension=".json", filetypes=[("JSON", "*.json")])
            if not path:
                return
            data = {
                "format": "m6gh-windows-admin-draft-v1",
                "features": [dict(id=f.id, module=f.module, address=f.address,
                                  mode=f.mode, indices=f.indices, on=f.on, off=f.off,
                                  byte_index=f.byte_index, label=f.label) for f in self.features],
                "extra_rows": [list(item) for item in sorted(self.admin_extra_rows)],
                "visible_modules": sorted(self.admin_visible_modules),
                "heading": self.admin_heading,
                "open_caption": self.admin_open_caption,
                "save_caption": self.admin_save_caption,
                "faq": self.faq_entries,
            }
            try:
                Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
            except OSError as exc:
                messagebox.showerror("Сохранение", str(exc), parent=dialog)
                return
            messagebox.showinfo("Админка", "Локальный файл настроек сохранён.", parent=dialog)

        def load_admin_draft():
            path = filedialog.askopenfilename(parent=dialog, title="Открыть настройки админки",
                                              filetypes=[("JSON", "*.json")])
            if not path:
                return
            try:
                data = json.loads(Path(path).read_text(encoding="utf-8"))
                if data.get("format") != "m6gh-windows-admin-draft-v1":
                    raise ValueError("Неверный формат настроек")
                features = [Feature(**item) for item in data["features"]]
                extra_rows = {(module, address) for module, address in data["extra_rows"]
                              if module in MODULES and re.fullmatch(r"[0-9A-F]{3}-[0-9]{2}-[0-9]{2}", address)
                              and address.startswith(MODULES[module] + "-")}
                visible = set(data["visible_modules"]) & set(MODULES)
                heading = str(data["heading"])
                faq = data["faq"]
                if not visible or not 1 <= len(heading) <= 45 or not isinstance(faq, list):
                    raise ValueError("Некорректные настройки")
            except (OSError, ValueError, TypeError, KeyError, AttributeError) as exc:
                messagebox.showerror("Загрузка", str(exc), parent=dialog)
                return
            self.features = features
            self.admin_extra_rows = extra_rows
            self.admin_visible_modules = visible
            self.admin_heading = heading
            self.faq_entries = faq
            self.heading_label.configure(text=heading)
            self.select_module(self.active_module if self.active_module in visible else next(iter(sorted(visible))))
            refresh_rows()
            refresh_faq()
            messagebox.showinfo("Админка", "Черновик загружен. Для обновления вкладок перезапустите админку.", parent=dialog)

        def autosave_admin():
            try:
                folder = Path.home() / ".m6gh_asbuilt_config"
                folder.mkdir(parents=True, exist_ok=True)
                destination = folder / "windows_admin_draft.json"
                data = {
                    "format": "m6gh-windows-admin-draft-v1",
                    "features": [dict(id=f.id, module=f.module, address=f.address,
                                      mode=f.mode, indices=f.indices, on=f.on, off=f.off,
                                      byte_index=f.byte_index, label=f.label) for f in self.features],
                    "extra_rows": [list(item) for item in sorted(self.admin_extra_rows)],
                    "visible_modules": sorted(self.admin_visible_modules),
                    "heading": self.admin_heading,
                    "open_caption": self.admin_open_caption,
                    "save_caption": self.admin_save_caption,
                    "faq": self.faq_entries,
                }
                temporary = destination.with_suffix(".tmp")
                temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
                temporary.replace(destination)
                return True
            except (OSError, TypeError, ValueError) as exc:
                messagebox.showerror("Автосохранение", str(exc), parent=dialog)
                return False

        def close_admin():
            if autosave_admin():
                dialog.destroy()

        dialog.protocol("WM_DELETE_WINDOW", close_admin)
        draft_buttons = tk.Frame(dialog)
        draft_buttons.pack(pady=(0, 5))
        tk.Button(draft_buttons, text="Сохранить настройки JSON", command=save_admin_draft).pack(side="left", padx=5)
        tk.Button(draft_buttons, text="Загрузить настройки JSON", command=load_admin_draft).pack(side="left", padx=5)
        tk.Button(dialog, text="Закрыть и сохранить", command=close_admin).pack(pady=(0, 10))

    def load_cached_settings(self):
        try:
            publication = load_verified_cache()
            if publication is None:
                return
            features = parse_snapshot(publication.snapshot)
            self.features = features
            self.apply_publication_metadata(publication.snapshot, publication.version)
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
            except Exception as exc:
                # Never expose credentials, payloads or server response bodies.
                if isinstance(exc, urllib.error.HTTPError):
                    detail = f"HTTP {exc.code}"
                elif isinstance(exc, urllib.error.URLError):
                    detail = "Нет соединения с сервером"
                elif isinstance(exc, (ValueError, KeyError, TypeError)):
                    detail = ("Подпись публикации не совпадает с ключом приложения" if "Invalid publisher RSA signature" in str(exc) else "Ошибка проверки публикации: " + str(exc)[:120])
                else:
                    detail = type(exc).__name__
                self.after(0, lambda d=detail: self.status.configure(
                    text="Автообновление не удалось: " + d + ". Настройки не изменены."))
                return
            def apply():
                self.features = features
                self.apply_publication_metadata(publication.snapshot, publication.version)
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

    def apply_publication_metadata(self, snapshot, version):
        self.catalog_version = version
        try:
            raw = snapshot.get("stores", {}).get("studio_faq", {}).get("entries", "[]")
            entries = json.loads(raw)
            self.faq_entries = entries if isinstance(entries, list) else []
        except (ValueError, TypeError, AttributeError):
            self.faq_entries = []

    def show_faq(self):
        dialog = tk.Toplevel(self)
        dialog.title("FAQ — M6GH AS-Built Config")
        self.center_dialog(dialog, 700, 540)
        dialog.resizable(False, False)
        frame = tk.Frame(dialog, bg="#f4f4f6", padx=16, pady=16)
        frame.pack(fill="both", expand=True)
        view = tk.Text(frame, wrap="word", font=("Segoe UI", 11), bg="#f4f4f6")
        view.pack(side="left", fill="both", expand=True)
        bar = ttk.Scrollbar(frame, orient="vertical", command=view.yview)
        bar.pack(side="right", fill="y")
        view.configure(yscrollcommand=bar.set)
        view.tag_configure("heading", foreground="#b61d31", font=("Segoe UI", 12, "bold"))
        if not self.faq_entries:
            view.insert("end", "Пока нет опубликованных записей FAQ.")
        for item in self.faq_entries:
            if not isinstance(item, dict):
                continue
            view.insert("end", str(item.get("name", "")) + chr(10), "heading")
            view.insert("end", str(item.get("description", "")) + chr(10))
            if item.get("extra_text"):
                view.insert("end", str(item["extra_text"]) + chr(10))
            view.insert("end", chr(10) + "─" * 32 + chr(10) * 2)
        view.configure(state="disabled")

    def center_dialog(self, dialog, width, height, make_transient=True):
        self.update_idletasks()
        x = self.winfo_rootx() + max(0, (self.winfo_width() - width) // 2)
        y = self.winfo_rooty() + max(0, (self.winfo_height() - height) // 2)
        dialog.geometry(f"{width}x{height}+{x}+{y}")
        if make_transient:
            dialog.transient(self)
            dialog.lift()
            dialog.focus_force()

    def show_about(self):
        dialog = tk.Toplevel(self)
        dialog.title("О программе — M6GH AS-Built Config")
        self.center_dialog(dialog, 640, 650)
        dialog.resizable(False, False)
        dialog.configure(bg="#f4f4f6")
        tk.Label(dialog, text="О программе", font=("Segoe UI", 18, "bold"),
                 bg="#f4f4f6").pack(fill="x", pady=(18, 10))
        tk.Label(dialog, text="M6GH AS-BUILT CONFIG", font=("Segoe UI", 17, "bold"),
                 bg="#f4f4f6").pack(pady=(0, 10))
        tk.Frame(dialog, bg="#c81930", height=4).pack(fill="x", padx=36, pady=(0, 12))
        body = tk.Frame(dialog, bg="#f4f4f6")
        body.pack(fill="both", expand=True, padx=30)
        text = tk.Text(body, wrap="word", font=("Segoe UI", 11), bg="#f4f4f6",
                       relief="flat", padx=8, pady=4)
        text.pack(side="left", fill="both", expand=True)
        bar = ttk.Scrollbar(body, command=text.yview)
        bar.pack(side="right", fill="y")
        text.configure(yscrollcommand=bar.set)
        text.tag_configure("warning", foreground="#b61d31", font=("Segoe UI", 11, "bold"), justify="center")
        text.insert("end", self.admin_about_description + "\n\n")
        text.insert("end", self.admin_warning_title + "\n" + self.admin_warning_text + "\n\n", "warning")
        for role, name, link in self.admin_authors:
            text.insert("end", role + ": " + name + "\n")
            if link:
                text.insert("end", link + "\n")
        text.configure(state="disabled")
        tk.Button(dialog, text="Закрыть", command=dialog.destroy, bg="#c81930", fg="white",
                  font=("Segoe UI", 12, "bold"), pady=10).pack(fill="x", padx=36, pady=16)

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
        self.features_hint.configure(text="Функции каталога: изменения сохраняются в новый ABT")
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
            box.grid(row=i // 3, column=i % 3, sticky="w", padx=6, pady=1)
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
        if self.raw_file is None or self.loaded_module != self.active_module:
            messagebox.showinfo("Нет файла", "Откройте ABT выбранного блока.")
            return
        changes = {address: value for address, value in self.preview_rows.items()
                   if any(a == address and original != value for a, original in self.rows)}
        if not changes and not messagebox.askyesno("Без изменений", "Изменений нет. Сохранить копию исходного ABT?"):
            return
        name = filedialog.asksaveasfilename(title="Сохранить изменённый ABT",
                    defaultextension=".abt",
                    initialfile=(self.source_path.stem + "_modified.abt" if self.source_path else "modified.abt"))
        if not name:
            return
        try:
            destination = Path(name)
            if self.source_path and destination.resolve() == self.source_path.resolve():
                raise ValueError("Исходный ABT нельзя перезаписывать. Выберите другое имя.")
            encoding = ("utf-16" if self.raw_file.startswith((bytes.fromhex("FFFE"), bytes.fromhex("FEFF")))
                        else ("utf-8-sig" if self.raw_file.startswith(bytes.fromhex("EFBBBF")) else "utf-8"))
            updated = serialize_changes(self.raw_file.decode(encoding), changes,
                                        {v: k for k, v in MODULES.items()})
            destination.write_bytes(updated.encode(encoding))
            self.status.configure(text=f"Сохранено: {destination.name}. Изменённых строк: {len(changes)}.")
            messagebox.showinfo("ABT сохранён", f"Сохранено: {destination.name}. Изменённых строк: {len(changes)}. Исходный файл не изменён.")
        except (OSError, ValueError, UnicodeError) as exc:
            messagebox.showerror("Ошибка сохранения ABT", str(exc))


if __name__ == "__main__":
    WindowsConfig().mainloop()
