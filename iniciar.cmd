@echo off
setlocal
if not exist "%~dp0.venv\Scripts\python.exe" (
    echo Ambiente Python nao encontrado. Prepare o .venv conforme a documentacao do projeto.
    exit /b 1
)
"%~dp0.venv\Scripts\python.exe" "%~dp0scripts\dev.py" %*
exit /b %errorlevel%
