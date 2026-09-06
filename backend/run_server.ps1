# Launch FastAPI Sync Backend with auto-reload
Set-Location -Path $PSScriptRoot
Write-Host "Starting LabelAudit Sync Backend on http://0.0.0.0:8000..." -ForegroundColor Cyan
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
