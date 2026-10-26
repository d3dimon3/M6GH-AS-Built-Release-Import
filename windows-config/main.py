"""M6GH AS-Built Config — isolated Windows stage-1 ABT viewer.

Python 3.10+, standard library only. No vehicle writes or network publishing.
"""
from __future__ import annotations

import re
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from pathlib import Path

MODULES = {"IC": "720", "BCM": "726", "RKE": None, "ABS": None, "AFS": None}
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
        self._build()

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
        tk.Label(main, text="Функции и галочки появятся после переноса и проверки движка Android",
                 font=("Segoe UI", 10), bg="#f4f4f6", fg="#555555",
                 anchor="w").pack(fill="x", padx=14, pady=(0, 16))
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
        for label, command in (("Открыть ABT", self.open_abt), ("Сохранить копию ABT", self.save_abt)):
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
            if prefix is None or address.startswith(prefix + "-"):
                self.table.insert("", "end", values=(address, value))
                shown += 1
        self.status.configure(text=f"{module}: строк показано {shown}. Редактирование отключено на этапе 1.")

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
            matches = [(m.group(1).upper(), " ".join(m.group(2).upper().split()))
                       for m in ROW_PATTERN.finditer(content)]
            if not matches:
                raise ValueError("Строки формата 720-01-01 и HEX-значения не обнаружены")
            self.raw_file, self.source_path, self.rows = raw, Path(name), matches
            self.select_module(self.active_module)
            self.status.configure(text=f"Загружен {self.source_path.name}: {len(matches)} строк. Только просмотр.")
        except (OSError, UnicodeError, ValueError) as exc:
            messagebox.showerror("Ошибка открытия ABT", str(exc))

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
