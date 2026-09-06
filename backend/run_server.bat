@echo off
cd /d "%~dp0"
echo Starting LabelAudit Sync Backend on http://0.0.0.0:8000...
call .venv\Scripts\activate.bat
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
