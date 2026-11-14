"""Inicia os servicos locais e encerra toda a arvore de processos com Ctrl+C."""
import argparse
import ctypes
from dataclasses import dataclass
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

from dotenv import dotenv_values


ROOT = Path(__file__).resolve().parents[1]


class StartError(Exception):
    pass


@dataclass(frozen=True)
class Service:
    name: str
    command: tuple
    port: int
    health_path: str


def services(config, *, backend_only=False, web_port=5173):
    def port(key, default):
        try:
            value = int(config.get(key) or os.environ.get(key) or default)
        except ValueError:
            raise StartError(f"Porta invalida em {key}.") from None
        if not 1 <= value <= 65535:
            raise StartError(f"Porta invalida em {key}.")
        return value

    result = [
        Service("Spring", (str(ROOT / "scripts/writer.py"), "bootRun"), port("GAMBLR_WRITER_PORT", 8080), "/health"),
        Service("Ranking", (str(ROOT / "scripts/reader.py"), "run"), port("GAMBLR_READER_PORT", 5000), "/health"),
    ]
    if not backend_only:
        result.append(Service("Site", (str(ROOT / "scripts/frontend.py"), "--port", str(web_port)), web_port, "/"))
    if len({service.port for service in result}) != len(result):
        raise StartError("Cada servico deve usar uma porta diferente. Confira o .env e --port.")
    return result


def check_ports(items, host):
    for service in items:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            if os.name == "nt":
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            else:
                # Permite reiniciar apos conexoes HTTP em TIME_WAIT no Linux.
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind((host, service.port))
            except OSError:
                raise StartError(
                    f"{service.name}: nao foi possivel reservar a porta {service.port} em {host}. "
                    "Confira o endereco e pare a instancia anterior com Ctrl+C antes de iniciar."
                ) from None


class WindowsJob:
    """O Windows mata somente os processos deste job quando o handle fecha.

    Os filhos aguardam um sinal em stdin antes de executar os scripts, garantindo
    que o Gradle/Java so nascam depois da associacao ao job.
    """
    def __init__(self):
        from ctypes import wintypes

        class BasicLimits(ctypes.Structure):
            _fields_ = [
                ("PerProcessUserTimeLimit", ctypes.c_longlong),
                ("PerJobUserTimeLimit", ctypes.c_longlong), ("LimitFlags", wintypes.DWORD),
                ("MinimumWorkingSetSize", ctypes.c_size_t), ("MaximumWorkingSetSize", ctypes.c_size_t),
                ("ActiveProcessLimit", wintypes.DWORD), ("Affinity", ctypes.c_size_t),
                ("PriorityClass", wintypes.DWORD), ("SchedulingClass", wintypes.DWORD),
            ]

        class IoCounters(ctypes.Structure):
            _fields_ = [(field, ctypes.c_ulonglong) for field in (
                "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                "ReadTransferCount", "WriteTransferCount", "OtherTransferCount",
            )]

        class ExtendedLimits(ctypes.Structure):
            _fields_ = [
                ("BasicLimitInformation", BasicLimits), ("IoInfo", IoCounters),
                ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t),
            ]

        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self.kernel.CreateJobObjectW.restype = wintypes.HANDLE
        self.kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        self.kernel.SetInformationJobObject.restype = wintypes.BOOL
        self.kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self.kernel.AssignProcessToJobObject.restype = wintypes.BOOL
        self.kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.kernel.OpenProcess.restype = wintypes.HANDLE
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.kernel.CloseHandle.restype = wintypes.BOOL
        self.handle = self.kernel.CreateJobObjectW(None, None)
        if not self.handle:
            raise StartError("Nao foi possivel criar o supervisor de processos do Windows.")
        limits = ExtendedLimits()
        limits.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.kernel.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.close()
            raise StartError("Nao foi possivel configurar o encerramento dos processos.")

    def add(self, process):
        handle = self.kernel.OpenProcess(0x0101, False, process.pid)  # SET_QUOTA | TERMINATE
        if not handle:
            raise StartError("Nao foi possivel supervisionar o processo iniciado.")
        try:
            if not self.kernel.AssignProcessToJobObject(self.handle, handle):
                raise StartError("O Windows nao permitiu associar o processo ao supervisor.")
        finally:
            self.kernel.CloseHandle(handle)

    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None


# Se o supervisor morrer antes de liberar stdin, o filho sai sem iniciar servicos.
GATE = """import runpy,sys
from pathlib import Path
if sys.stdin.buffer.read(1) != b'1':
    sys.exit(1)
sys.argv=sys.argv[1:]
sys.path.insert(0,str(Path(sys.argv[0]).resolve().parent))
runpy.run_path(sys.argv[0],run_name='__main__')
"""


def relay(service, pipe):
    try:
        for line in pipe:
            print(f"[{service.name}] {line.rstrip()}", flush=True)
    finally:
        pipe.close()


def available(service):
    # Nao usa proxy HTTP do sistema para consultar processos desta maquina.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(f"http://127.0.0.1:{service.port}{service.health_path}", timeout=0.5) as response:
            return response.status == 200
    except (OSError, urllib.error.URLError):
        return False


def stop(children, job):
    if job is not None:
        job.close()
    else:
        for _, process in children:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
    deadline = time.monotonic() + 5
    for _, process in children:
        try:
            process.wait(timeout=max(0.01, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            if job is None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.kill()
            process.wait()
    if job is None:
        # Um pai pode sair antes de seus descendentes.
        for _, process in children:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass


def supervise(items, *, timeout=180, on_ready=None):
    children, threads = [], []
    job = WindowsJob() if os.name == "nt" else None
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"
    try:
        for service in items:
            print(f"Iniciando {service.name} (porta {service.port})...", flush=True)
            process = subprocess.Popen(
                [sys.executable, "-u", "-c", GATE, *service.command], cwd=ROOT, env=env,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace",
                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
                start_new_session=os.name != "nt",
            )
            children.append((service, process))
            try:
                if job is not None:
                    job.add(process)
                process.stdin.write("1")
                process.stdin.flush()
            except BaseException:
                process.kill()  # Ainda bloqueado no gate; nao criou descendentes.
                raise
            finally:
                process.stdin.close()
            thread = threading.Thread(target=relay, args=(service, process.stdout), daemon=True)
            thread.start()
            threads.append(thread)

        deadline = time.monotonic() + timeout
        pending = {service.name for service in items}
        announced = False
        while True:
            for service, process in children:
                code = process.poll()
                if code is not None:
                    raise StartError(f"{service.name} encerrou (codigo {code}). Os demais servicos serao encerrados; confira o log acima.")
            if pending:
                for service in items:
                    if service.name in pending and available(service):
                        pending.remove(service.name)
                        print(f"{service.name} disponivel na porta {service.port}.", flush=True)
                if pending and time.monotonic() >= deadline:
                    raise StartError(f"Tempo de inicializacao esgotado: {', '.join(sorted(pending))}. Confira os logs ou aumente --timeout.")
            if not pending and not announced:
                announced = True
                print("Todos os servicos estao prontos. Use Ctrl+C para encerrar.", flush=True)
                if on_ready:
                    on_ready()
            time.sleep(0.25)
    finally:
        print("Encerrando os servicos iniciados por este comando...", flush=True)
        previous = signal.signal(signal.SIGINT, signal.SIG_IGN)
        try:
            stop(children, job)
            for thread in threads:
                thread.join(timeout=1)
        finally:
            signal.signal(signal.SIGINT, previous)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-only", action="store_true", help="Inicia somente Spring e Flask, sem o site.")
    parser.add_argument("--port", type=int, default=5173, help="Porta do site (padrao: 5173).")
    parser.add_argument("--timeout", type=float, default=180, help="Tempo maximo de inicializacao em segundos.")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 0 < args.timeout < float("inf"):
        parser.error("Informe uma porta entre 1 e 65535 e um timeout positivo e finito.")
    try:
        config = dotenv_values(ROOT / ".env", interpolate=False, encoding="utf-8-sig")
        items = services(config, backend_only=args.backend_only, web_port=args.port)
        host = config.get("GAMBLR_BIND_ADDRESS") or "127.0.0.1"
        check_ports(items, host)
        if not args.backend_only:
            print(f"Site: http://127.0.0.1:{args.port}", flush=True)
            if host == "0.0.0.0":
                print(f"Na rede local, use http://IP_DESTE_COMPUTADOR:{args.port}", flush=True)
        supervise(items, timeout=args.timeout)
        return 0
    except KeyboardInterrupt:
        return 0
    except (StartError, OSError) as error:
        print(f"Nao foi possivel manter os servicos em execucao: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
