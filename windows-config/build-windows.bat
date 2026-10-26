@echo off
setlocal
cd /d "%~dp0"
py -3 -m pip install -r requirements.txt
if errorlevel 1 exit /b 1
py -3 -m unittest discover -s . -p "test_*.py" -v
if errorlevel 1 exit /b 1
py -3 -m PyInstaller --noconfirm --clean --onefile --windowed --name M6GH-AS-Built-Config main.py
if errorlevel 1 exit /b 1
echo EXE ready: dist\M6GH-AS-Built-Config.exe
