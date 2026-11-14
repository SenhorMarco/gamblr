import contextlib
import ctypes
import io
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dev import ROOT, Service, StartError, check_ports, services, supervise


WORKER = '''import json,subprocess,sys,time
from http.server import BaseHTTPRequestHandler,HTTPServer
from pathlib import Path
mode,port,marker=sys.argv[1:]
marker=Path(marker)
if mode=='fail':
    until=time.monotonic()+10
    while not marker.exists() and time.monotonic()<until: time.sleep(.02)
    sys.exit(3)
child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(120)'])
marker.write_text(json.dumps([os_pid:=__import__('os').getpid(),child.pid]))
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200);self.end_headers();self.wfile.write(b'OK')
    def log_message(self,*args): pass
HTTPServer(('127.0.0.1',int(port)),Handler).serve_forever()
'''


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1',0))
        return probe.getsockname()[1]


def running_windows(pid):
    from ctypes import wintypes
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD]
    kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.GetExitCodeProcess.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=kernel.OpenProcess(0x1000,False,pid)
    if not handle:
        return False
    try:
        code=wintypes.DWORD()
        return bool(kernel.GetExitCodeProcess(handle,ctypes.byref(code))) and code.value==259
    finally:
        kernel.CloseHandle(handle)


def running(pid):
    if os.name == 'nt':
        return running_windows(pid)
    try:
        # Um zombie ja encerrou e aguarda apenas coleta pelo pai do sistema.
        stat = Path(f'/proc/{pid}/stat').read_text()
        return stat.rsplit(')', 1)[1].split()[0] != 'Z'
    except FileNotFoundError:
        return False


class DevTests(unittest.TestCase):
    def test_port_conflict_does_not_interrupt_existing_server(self):
        with socket.socket() as existing:
            existing.bind(('127.0.0.1',0));existing.listen()
            port=existing.getsockname()[1]
            with self.assertRaisesRegex(StartError,str(port)):
                check_ports([Service('Existente',(),port,'/')],'127.0.0.1')
            self.assertNotEqual(-1,existing.fileno())

    def test_configured_ports_and_backend_only(self):
        with patch.dict(os.environ,{},clear=True):
            items=services({'GAMBLR_WRITER_PORT':'8180','GAMBLR_READER_PORT':'5100'},backend_only=True)
            self.assertEqual([8180,5100],[item.port for item in items])
            self.assertEqual([8080,5000,5174],[item.port for item in services({},web_port=5174)])
            with self.assertRaises(StartError): services({'GAMBLR_WRITER_PORT':'5000'})
            with self.assertRaises(StartError): services({'GAMBLR_WRITER_PORT':'invalid'})

    @unittest.skipUnless(os.name == 'nt' or sys.platform == 'linux', 'Windows ou Linux')
    def test_interrupt_stops_child_and_grandchild(self):
        with tempfile.TemporaryDirectory() as directory:
            worker=Path(directory)/'worker.py';worker.write_text(WORKER,encoding='utf-8')
            marker=Path(directory)/'pids.json'
            item=Service('Teste',(str(worker),'server',str(port:=free_port()),str(marker)),port,'/')
            def interrupt(): raise KeyboardInterrupt
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(KeyboardInterrupt):supervise([item],timeout=15,on_ready=interrupt)
            for pid in json.loads(marker.read_text()):self.assertFalse(running(pid),f'Processo {pid} ficou ativo')
            check_ports([item],'127.0.0.1')

    @unittest.skipUnless(os.name == 'nt' or sys.platform == 'linux', 'Windows ou Linux')
    def test_failed_service_stops_other_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            worker=Path(directory)/'worker.py';worker.write_text(WORKER,encoding='utf-8')
            marker=Path(directory)/'pids.json'
            server=Service('Servidor',(str(worker),'server',str(port:=free_port()),str(marker)),port,'/')
            failing=Service('Falha',(str(worker),'fail','0',str(marker)),free_port(),'/')
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(StartError,'Falha encerrou'):
                    supervise([server,failing],timeout=15)
            for pid in json.loads(marker.read_text()):self.assertFalse(running(pid),f'Processo {pid} ficou ativo')
            check_ports([server],'127.0.0.1')


if __name__=='__main__':unittest.main()
