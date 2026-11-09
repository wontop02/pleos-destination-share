#!/usr/bin/env python3
"""Prepare the two approved local Pleos vehicles without changing product data."""
from __future__ import annotations

import argparse
import contextlib
import ctypes
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ADB_PORT = 5041
API_PORT = 4192
USB_ALLOWLIST = "PLEOS12_VIRTUAL_ONLY_NO_USB"
PACKAGE = "ai.pleos.destinations.vehicle"
COMPONENT = PACKAGE + "/.MainActivity"
VEHICLES = (("Pleos12Leader", 5570), ("Pleos12Follower", 5572))
PAGES = {"home_page": "home", "compose_page": "compose", "settings_page": "settings",
         "welcome_page": "welcome", "room_form_page": "room_form"}


class Failure(Exception):
    def __init__(self, message, code=6):
        super().__init__(message)
        self.message = message
        self.code = code


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def read_json(path, purpose):
    messages = {
        "config": "실행 설정 파일을 읽을 수 없습니다. 실행 안내를 확인해 주세요.",
        "adb_metadata": "전용 ADB 환경 파일을 읽을 수 없습니다. 로컬 설정을 확인해 주세요.",
        "resources": "이전 실행 자원 기록을 읽을 수 없습니다. 기록을 보존하고 실행 안내를 확인해 주세요.",
    }
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        raise Failure(messages[purpose], 2) from None


def atomic_json(path, value):
    path = Path(path)
    fd, temporary = tempfile.mkstemp(dir=str(path.parent), prefix=".pending-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as target:
            json.dump(value, target, ensure_ascii=False, indent=2)
            target.write("\n")
        os.replace(temporary, path)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temporary)


def source_root():
    return Path(__file__).resolve().parents[3]


def demo_home(root):
    override = os.environ.get("PLEOS_DEMO_HOME")
    if override:
        return Path(override).expanduser().resolve()
    try:
        result = subprocess.run(["/usr/bin/git", "-C", str(root), "rev-parse", "--git-common-dir"],
                                capture_output=True, timeout=5, check=True)
        common = Path(result.stdout.decode().strip())
        if not common.is_absolute():
            common = root / common
        return common.resolve().parent / ".harness" / "demo"
    except (OSError, subprocess.SubprocessError, UnicodeError):
        raise Failure("저장소 위치를 확인할 수 없습니다. 저장소의 실행 파일을 다시 열어 주세요.", 2) from None


class RunLog:
    """Only launcher-owned status fields are written; never subprocess output or UI text."""
    def __init__(self, home):
        self.home = Path(home)
        self.home.mkdir(parents=True, exist_ok=True, mode=0o700)
        logs = self.home / "logs"
        logs.mkdir(mode=0o700, exist_ok=True)
        self.path = logs / (time.strftime("%Y%m%d-%H%M%S") + "-" + str(os.getpid()) + ".jsonl")
        self.stream = self.path.open("x", encoding="utf-8")

    def event(self, event, **fields):
        self.stream.write(json.dumps(dict(at=time.time(), event=event, **fields), ensure_ascii=False) + "\n")
        self.stream.flush()

    def status(self, text, event, **fields):
        print(text, flush=True)
        self.event(event, **fields)


@contextlib.contextmanager
def single_instance(home):
    with (Path(home) / "launcher.lock").open("a+") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Failure("이미 데모를 준비 중입니다. 먼저 열린 실행 창을 확인해 주세요.", 3) from None
        try:
            yield
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def absolute_file(value, executable=False):
    if not isinstance(value, str) or not Path(value).is_absolute():
        raise Failure("설정의 경로는 존재하는 절대 경로여야 합니다.", 2)
    path = Path(value).resolve()
    if not path.is_file() or (executable and not os.access(path, os.X_OK)):
        raise Failure("필요한 실행 파일이 없습니다. 로컬 설정의 경로를 확인해 주세요.", 2)
    return path


def same_location(first, second):
    """macOS may spell one existing path as NFC or NFD; compare filesystem identity."""
    try:
        return Path(first).samefile(Path(second))
    except (OSError, ValueError, TypeError):
        return False


def load_config(path, root):
    config = read_json(path, "config")
    if not isinstance(config, dict) or config.get("version") != 1:
        raise Failure("지원하지 않는 설정입니다. config.example.json을 확인해 주세요.", 2)
    required = {"version", "repo_root", "sdk_root", "adb_metadata", "node", "server", "apk_sha256", "emulators"}
    if set(config) - required - {"timeouts"} or not required.issubset(config):
        raise Failure("설정 항목이 누락되었거나 알 수 없는 항목이 있습니다.", 2)
    if not isinstance(config["repo_root"], str) or not Path(config["repo_root"]).is_absolute() or not same_location(config["repo_root"], root):
        raise Failure("실행 파일과 설정의 저장소가 다릅니다. repo_root를 확인해 주세요.", 2)
    # Keep the configured spelling for process argv and files after proving the same directory.
    config["repo_root"] = Path(config["repo_root"]).resolve()
    sdk = Path(config["sdk_root"]) if isinstance(config["sdk_root"], str) else Path(".")
    if not sdk.is_absolute():
        raise Failure("SDK 경로는 절대 경로여야 합니다.", 2)
    config["sdk_root"] = sdk.resolve()
    config["adb"] = absolute_file(str(sdk / "platform-tools" / "adb"), True)
    config["emulator"] = absolute_file(str(sdk / "emulator" / "emulator"), True)
    absolute_file(config["node"], True)
    config["node"] = Path(config["node"])  # Preserve argv[0] spelling; verify the resolved OS executable separately.
    config["adb_metadata"] = absolute_file(config["adb_metadata"])
    server = config["server"]
    if not isinstance(server, dict) or set(server) - {"script", "sha256", "adopt_pid", "adopt_script", "adopt_started"} or not {"script", "sha256"}.issubset(server):
        raise Failure("서버 설정을 확인해 주세요.", 2)
    server["script"] = absolute_file(server["script"])
    if not same_location(server["script"], config["repo_root"] / "prototype" / "server.mjs"):
        raise Failure("서버는 이 저장소의 prototype/server.mjs여야 합니다.", 2)
    for sha in (server["sha256"], config["apk_sha256"]):
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha):
            raise Failure("서버·설치 APK의 SHA-256 설정을 확인해 주세요.", 2)
    if digest(server["script"]) != server["sha256"]:
        raise Failure("서버 소스가 승인한 버전과 다릅니다. 설정과 소스를 확인해 주세요.", 2)
    adoption = {"adopt_pid", "adopt_script", "adopt_started"}
    if adoption.intersection(server) and not adoption.issubset(server):
        raise Failure("기존 서버의 PID·시작 시각·소스 경로를 함께 지정해 주세요.", 2)
    if "adopt_pid" in server:
        if type(server["adopt_pid"]) is not int or server["adopt_pid"] <= 1:
            raise Failure("기존 서버 PID 설정을 확인해 주세요.", 2)
        if not isinstance(server["adopt_started"], str) or not re.fullmatch(r"[A-Z][a-z]{2} [A-Z][a-z]{2} [ \d]\d \d{2}:\d{2}:\d{2} \d{4}", server["adopt_started"]):
            raise Failure("기존 서버의 시작 시각 설정을 확인해 주세요.", 2)
        server["adopt_script"] = absolute_file(server["adopt_script"])
        if digest(server["adopt_script"]) != server["sha256"]:
            raise Failure("기존 서버의 소스가 승인한 버전과 다릅니다.", 2)
    if not isinstance(config["emulators"], list) or len(config["emulators"]) != 2:
        raise Failure("승인된 가상 차량 두 대의 설정이 필요합니다.", 2)
    for vehicle, expected in zip(config["emulators"], VEHICLES):
        if not isinstance(vehicle, dict) or set(vehicle) != {"avd", "port", "user", "pane"} or (vehicle.get("avd"), vehicle.get("port")) != expected or vehicle.get("user") != 10:
            raise Failure("차량 이름·포트·사용자는 승인된 두 차량과 같아야 합니다.", 2)
        pane = vehicle["pane"]
        if not isinstance(pane, list) or len(pane) != 4 or any(type(n) is not int for n in pane) or not (0 < pane[0] < pane[2] and 0 <= pane[1] < pane[3]):
            raise Failure("차량 앱 pane의 경계 설정을 확인해 주세요.", 2)
    limits = {"command": 15, "server": 20, "adb": 20, "boot": 240, "ready": 60}
    supplied = config.get("timeouts", {})
    if not isinstance(supplied, dict) or set(supplied) - set(limits):
        raise Failure("대기 시간 설정을 확인해 주세요.", 2)
    for key, value in supplied.items():
        if type(value) not in (int, float) or not 1 <= value <= 600:
            raise Failure("대기 시간은 1–600초여야 합니다.", 2)
        limits[key] = value
    config["timeouts"] = limits
    return config


def owned_environment(config):
    meta = read_json(config["adb_metadata"], "adb_metadata")
    if not isinstance(meta, dict) or meta.get("serverPort") != ADB_PORT or meta.get("listenHost") != "127.0.0.1" or meta.get("USBAllowlistSerial") != USB_ALLOWLIST or meta.get("physicalUSBConnectionsDisabledByUnmatchedAllowlist") is not True:
        raise Failure("전용 ADB의 기존 소유권 설정이 올바르지 않습니다.", 2)
    private = meta.get("emulatorEnvironment")
    allowed = {"ADB_MDNS_AUTO_CONNECT", "ADB_SERVER_SOCKET", "ADB_VENDOR_KEYS", "ANDROID_ADB_SERVER_PORT",
               "ANDROID_AVD_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_USER_HOME", "JAVA_HOME"}
    if not isinstance(private, dict) or set(private) - allowed or any(not isinstance(v, str) for v in private.values()):
        raise Failure("전용 ADB 환경 설정을 확인해 주세요.", 2)
    if private.get("ADB_MDNS_AUTO_CONNECT") != "0" or private.get("ANDROID_ADB_SERVER_PORT") != str(ADB_PORT) or private.get("ADB_SERVER_SOCKET") not in {"tcp:127.0.0.1:5041", "tcp:localhost:5041", "tcp:5041"}:
        raise Failure("전용 ADB 포트·검색 차단 설정을 확인해 주세요.", 2)
    if private.get("ADB_VENDOR_KEYS") != meta.get("ownKeyPath") or not private.get("ADB_VENDOR_KEYS"):
        raise Failure("기존 전용 ADB 키 경로 설정을 확인해 주세요.", 2)
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if Path(private.get(key, "")).resolve() != config["sdk_root"]:
            raise Failure("전용 SDK 환경과 로컬 설정이 다릅니다.", 2)
    env = {key: value for key, value in os.environ.items() if not key.startswith(("ADB_", "ANDROID_"))}
    env.update(private)
    return env


class Host:
    def __init__(self, timeout=15):
        self.timeout = timeout

    def run(self, args, env=None, timeout=None, ok=(0,)):
        try:
            completed = subprocess.run([str(arg) for arg in args], env=env, capture_output=True,
                                       timeout=self.timeout if timeout is None else max(0.1, timeout))
        except subprocess.TimeoutExpired:
            raise Failure("명령 대기 시간이 지났습니다. 로그의 실패 단계를 확인해 주세요.", 5) from None
        except OSError:
            raise Failure("필요한 명령을 실행할 수 없습니다. 로컬 설정을 확인해 주세요.", 6) from None
        if completed.returncode not in ok:
            raise Failure("준비 명령이 실패했습니다. 로그의 실패 단계를 확인해 주세요.", 6)
        return completed.stdout.decode("utf-8", errors="replace")

    def listeners(self, port):
        output = self.run(["/usr/sbin/lsof", "-nP", "-a", "-iTCP:" + str(port), "-sTCP:LISTEN", "-Fpn"], ok=(0, 1))
        result = {}
        pid = None
        for line in output.splitlines():
            if line.startswith("p") and line[1:].isdigit():
                pid = int(line[1:])
                result.setdefault(pid, [])
            elif line.startswith("n") and pid is not None:
                result[pid].append(line[1:])
        return result

    def process(self, pid):
        command = self.run(["/bin/ps", "-ww", "-p", str(pid), "-o", "command="], ok=(0, 1)).strip()
        clock_env = dict(os.environ, LC_ALL="C")
        clock_env.pop("TZ", None)  # Keep the Mac's local zone, independent of Terminal/Finder overrides.
        started = self.run(["/bin/ps", "-p", str(pid), "-o", "lstart="], env=clock_env, ok=(0, 1)).strip()
        if not command or not started:
            raise Failure("확인 중 실행 자원이 종료되었습니다. 실행 파일을 다시 열어 주세요.", 4)
        try:
            library = ctypes.CDLL("/usr/lib/libproc.dylib")
            function = library.proc_pidpath
            function.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32]
            function.restype = ctypes.c_int
            buffer = ctypes.create_string_buffer(4096)
            if function(pid, buffer, len(buffer)) <= 0:
                raise OSError()
            executable = buffer.value.decode("utf-8")
        except (OSError, UnicodeError, AttributeError):
            raise Failure("실행 자원의 실제 프로그램을 확인할 수 없습니다. 기존 실행은 그대로 두었습니다.", 4) from None
        return {"pid": pid, "started": started, "command": command, "executable": executable}

    def spawn(self, args, env):
        try:
            child = subprocess.Popen([str(arg) for arg in args], env=env, stdin=subprocess.DEVNULL,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                     start_new_session=True, close_fds=True)
        except OSError:
            raise Failure("실행 자원을 시작할 수 없습니다. 로컬 설정을 확인해 주세요.", 6) from None
        return child


def loopback_listener(listeners, port):
    return len(listeners) == 1 and all(address in {"127.0.0.1:" + str(port), "[::1]:" + str(port)}
                                        for addresses in listeners.values() for address in addresses) and all(listeners.values())


def fingerprint(process):
    return {"pid": process["pid"], "started": process["started"],
            "command_sha256": hashlib.sha256(process["command"].encode()).hexdigest(),
            "executable": str(Path(process["executable"]).resolve())}


def same_process(record, process):
    return isinstance(record, dict) and all(record.get(key) == value for key, value in fingerprint(process).items())


def ui_observation(xml, pane):
    """Inspect public resource IDs and geometry in memory; discard all text attributes."""
    try:
        start = xml.index("<?xml")
        end = xml.index("</hierarchy>", start) + len("</hierarchy>")
        tree = ET.fromstring(xml[start:end])
    except (ValueError, ET.ParseError):
        return None
    hits = []
    for node in tree.iter("node"):
        if node.get("package") != PACKAGE:
            continue
        name = node.get("resource-id", "").removeprefix(PACKAGE + ":id/")
        if name not in PAGES:
            continue
        numbers = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if not numbers:
            continue
        x1, y1, x2, y2 = map(int, numbers.groups())
        if pane[0] <= x1 < x2 <= pane[2] and pane[1] <= y1 < y2 <= pane[3] and x2 - x1 >= 400 and y2 - y1 >= 200:
            hits.append(PAGES[name])
    if len(hits) != 1:
        return None
    return {"page": hits[0], "pane_verified": True}


def activity_observation(output):
    histories = re.findall(r"(?m)^\s*\*\s+Hist\s+#\d+:\s+ActivityRecord\{([^\s}]+)\s+u(\d+)\s+" + re.escape(COMPONENT) + r"(?:\s|})", output)
    records = {(record, int(user)) for record, user in histories}
    resumed = any(re.search(r"(?:mResumedActivity:|topResumedActivity=)\s*ActivityRecord\{[^\n]*\bu10\s+" + re.escape(COMPONENT) + r"(?:\s|})", line)
                  for line in output.splitlines())
    return {"count": len(records), "user_ok": all(user == 10 for _, user in records), "resumed": resumed}


class Launcher:
    def __init__(self, config, home, log, host=None, api_port=API_PORT):
        self.c = config
        self.home = Path(home)
        self.log = log
        self.host = host or Host(config["timeouts"]["command"])
        self.env = owned_environment(config)
        self.api_port = api_port  # Tests use a real Node process on an isolated free port.
        self.state_path = self.home / "resources.json"
        self.state = read_json(self.state_path, "resources") if self.state_path.exists() else {"version": 1}
        if not isinstance(self.state, dict) or self.state.get("version") != 1:
            raise Failure("실행 자원 기록을 읽을 수 없습니다. 기록을 보존하고 로컬 설정을 확인해 주세요.", 2)
        self.phase = "configuration"

    def save(self):
        atomic_json(self.state_path, self.state)

    def wait(self, check, seconds, message):
        deadline = time.monotonic() + seconds
        while True:
            result = check(max(0.1, deadline - time.monotonic()))
            if result:
                return result
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise Failure(message, 5)
            time.sleep(min(1, remaining))

    def server_identity(self, listeners):
        if not loopback_listener(listeners, self.api_port):
            raise Failure("서버 포트를 다른 실행이 사용 중입니다. 기존 실행은 그대로 두었습니다.", 4)
        pid = next(iter(listeners))
        process = self.host.process(pid)
        server = self.c["server"]
        saved = self.state.get("server")
        candidate = None
        if same_process(saved, process):
            candidate = Path(saved.get("script", ""))
        elif pid == server.get("adopt_pid") and process["started"] == server.get("adopt_started"):
            candidate = server["adopt_script"]
        if candidate not in {server["script"], server.get("adopt_script")} or candidate is None or Path(process["executable"]).resolve() != self.c["node"].resolve() or process["command"] != str(self.c["node"]) + " " + str(candidate) or digest(candidate) != server["sha256"]:
            raise Failure("기존 서버의 소유권을 확인할 수 없습니다. 종료하거나 덮어쓰지 않았습니다.", 4)
        return dict(fingerprint(process), script=str(candidate), sha256=server["sha256"])

    def server_http_ready(self):
        try:
            request = urllib.request.Request("http://127.0.0.1:" + str(self.api_port) + "/", method="GET")
            # Ignore proxy environment; only loopback is contacted, with no redirects.
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
            with opener.open(request, timeout=2) as response:
                body = response.read(2 * 1024 * 1024)
                return response.status == 200 and hashlib.sha256(body).hexdigest() == digest(self.c["repo_root"] / "prototype" / "public" / "index.html")
        except (OSError, urllib.error.URLError):
            return False

    def ensure_server(self):
        self.phase = "server"
        self.log.status("공유 서버를 확인하고 있어요.", self.phase)
        listeners = self.host.listeners(self.api_port)
        if listeners:
            record = self.server_identity(listeners)
            reused = True
        else:
            child = self.host.spawn([self.c["node"], self.c["server"]["script"]],
                                    dict(self.env, PORT=str(self.api_port)))
            process = self.host.process(child.pid)
            if Path(process["executable"]).resolve() != self.c["node"].resolve() or process["command"] != str(self.c["node"]) + " " + str(self.c["server"]["script"]):
                raise Failure("시작한 서버의 실행 정보를 확인할 수 없습니다.", 4)
            record = dict(fingerprint(process), script=str(self.c["server"]["script"]), sha256=self.c["server"]["sha256"])
            self.state["server"] = record
            self.save()
            reused = False
        self.state["server"] = record
        self.save()
        def ready(_remaining):
            listeners = self.host.listeners(self.api_port)
            if not listeners:
                return False
            current = self.server_identity(listeners)
            if current != record:
                raise Failure("확인 중 공유 서버가 바뀌었습니다. 기존 실행을 확인해 주세요.", 4)
            return self.server_http_ready()
        self.wait(ready, self.c["timeouts"]["server"], "공유 서버 준비 시간이 지났습니다. 실행 파일을 다시 열어 주세요.")
        self.log.event("server_ready", pid=record["pid"], reused=reused, source_sha256=record["sha256"])

    def adb_identity(self, listeners=None):
        listeners = self.host.listeners(ADB_PORT) if listeners is None else listeners
        if not loopback_listener(listeners, ADB_PORT):
            raise Failure("전용 연결 포트의 소유권을 확인할 수 없습니다. 다른 연결은 건드리지 않았습니다.", 4)
        process = self.host.process(next(iter(listeners)))
        command = process["command"]
        # adb's daemon argv differs from the start-server client argv.
        if Path(process["executable"]).resolve() != self.c["adb"].resolve() or not re.search(r"(?:^| )-L tcp:(?:127\.0\.0\.1:)?5041(?: |$)", command) or " fork-server server " not in command or not command.endswith(" --one-device " + USB_ALLOWLIST):
            raise Failure("전용 ADB의 실행 정보가 다릅니다. 다른 연결은 건드리지 않았습니다.", 4)
        return fingerprint(process)

    def ensure_adb(self):
        self.phase = "adb"
        self.log.status("두 차량의 전용 연결을 확인하고 있어요.", self.phase)
        listeners = self.host.listeners(ADB_PORT)
        reused = bool(listeners)
        if not listeners:
            # ADB_SERVER_SOCKET with an IP is a remote-client address: it forbids daemon bootstrap.
            # Use only the explicit local -L socket while starting; keep the normal client environment intact.
            starter_env = dict(self.env)
            starter_env.pop("ADB_SERVER_SOCKET", None)
            starter_env.pop("ANDROID_ADB_SERVER_PORT", None)
            self.host.run([self.c["adb"], "-L", "tcp:5041", "--one-device", USB_ALLOWLIST, "start-server"], env=starter_env,
                          timeout=self.c["timeouts"]["adb"])
        record = self.adb_identity()
        old = self.state.get("adb")
        if old and reused and not same_process(old, self.host.process(record["pid"])):
            # A correctly isolated replacement daemon can be adopted after a previous one exited.
            self.log.event("adb_replaced", pid=record["pid"])
        self.state["adb"] = record
        self.save()
        self.log.event("adb_ready", pid=record["pid"], reused=reused)

    def adb(self, vehicle, *args, timeout=None):
        self.adb_identity()  # Refuse client access if the isolated daemon disappeared or changed.
        if (vehicle["avd"], vehicle["port"]) not in VEHICLES or vehicle["user"] != 10:
            raise Failure("승인되지 않은 차량 요청을 거절했습니다.", 4)
        # -H prevents a missing local daemon from silently auto-starting a default server.
        return self.host.run([self.c["adb"], "-H", "127.0.0.1", "-P", str(ADB_PORT),
                              "-s", "emulator-" + str(vehicle["port"]), *args], env=self.env, timeout=timeout)

    def shell(self, vehicle, *args, timeout=None):
        return self.adb(vehicle, "shell", shlex.join(str(arg) for arg in args), timeout=timeout)

    def emulator_identity(self, vehicle):
        listeners = self.host.listeners(vehicle["port"])
        if len(listeners) != 1:
            raise Failure("차량 포트를 다른 실행이 사용 중입니다. 기존 실행은 그대로 두었습니다.", 4)
        pid = next(iter(listeners))
        transport = self.host.listeners(vehicle["port"] + 1)
        if transport and set(transport) != {pid}:
            raise Failure("차량 연결 포트가 다른 실행과 겹칩니다. 기존 실행은 그대로 두었습니다.", 4)
        process = self.host.process(pid)
        command = process["command"]
        approved = {self.c["emulator"].resolve(), (self.c["sdk_root"] / "emulator/qemu/darwin-aarch64/qemu-system-aarch64").resolve()}
        executable_ok = Path(process["executable"]).resolve() in approved
        avds = re.findall(r"(?:^| )-avd ([^ ]+)(?= |$)", command)
        ports = re.findall(r"(?:^| )-port ([^ ]+)(?= |$)", command)
        if not executable_ok or avds != [vehicle["avd"]] or ports != [str(vehicle["port"])]:
            raise Failure("차량 포트의 AVD가 승인한 차량과 다릅니다. 기존 실행은 그대로 두었습니다.", 4)
        return fingerprint(process)

    def avd_name(self, vehicle, timeout=None):
        output = self.adb(vehicle, "emu", "avd", "name", timeout=timeout)
        lines = [line.strip() for line in output.splitlines() if line.strip() and line.strip() != "OK"]
        if lines != [vehicle["avd"]]:
            raise Failure("실제 가상 차량 이름이 설정과 다릅니다. 기존 실행은 그대로 두었습니다.", 4)

    def ensure_emulator(self, vehicle):
        self.phase = "boot_" + str(vehicle["port"])
        label = "대표" if vehicle["port"] == 5570 else "참여"
        self.log.status(label + " 차량을 준비하고 있어요.", self.phase)
        listeners = self.host.listeners(vehicle["port"])
        cold = not listeners
        if cold:
            if self.host.listeners(vehicle["port"] + 1):
                raise Failure("차량 연결 포트가 이미 사용 중입니다. 기존 실행은 그대로 두었습니다.", 4)
            # Do not launch another copy of this AVD on an unexpected port.
            matching_pids = self.host.run(["/usr/bin/pgrep", "-f", "(^|[[:space:]])-avd[[:space:]]+" + vehicle["avd"] + "([[:space:]]|$)"], ok=(0, 1))
            if matching_pids.strip():
                raise Failure("같은 가상 차량이 다른 포트에서 실행 중입니다. 기존 실행은 그대로 두었습니다.", 4)
            child = self.host.spawn([self.c["emulator"], "-avd", vehicle["avd"], "-port", str(vehicle["port"]),
                                     "-no-snapshot", "-no-boot-anim", "-audio", "coreaudio", "-gpu", "host",
                                     "-crash-report-mode", "disabled"], self.env)
            self.state.setdefault("emulators", {})[vehicle["avd"]] = {"spawn_pid": child.pid, "status": "starting"}
            self.save()
        else:
            self.emulator_identity(vehicle)
        def boot(remaining):
            if not self.host.listeners(vehicle["port"]):
                return False
            self.emulator_identity(vehicle)
            try:
                self.avd_name(vehicle, min(5, remaining))
                if self.shell(vehicle, "getprop", "sys.boot_completed", timeout=min(5, remaining)).strip() != "1":
                    return False
            except Failure as error:
                if error.code in (5, 6):
                    return False
                raise
            user = self.shell(vehicle, "am", "get-current-user", timeout=min(5, remaining)).strip()
            if cold and user == "0":
                return False  # The approved image resumes user10 after initial system-user boot.
            if user != "10":
                raise Failure("차량의 전경 사용자가 10이 아닙니다. 사용자 변경 없이 중단했습니다.", 4)
            if self.shell(vehicle, "am", "get-started-user-state", "10", timeout=min(5, remaining)).strip() != "RUNNING_UNLOCKED":
                return False
            return True
        self.wait(boot, self.c["timeouts"]["boot"], label + " 차량의 부팅 대기 시간이 지났습니다. 실행 파일을 다시 열어 주세요.")
        record = self.emulator_identity(vehicle)
        self.state.setdefault("emulators", {})[vehicle["avd"]] = dict(record, status="booted")
        self.save()
        if cold:
            self.adb(vehicle, "emu", "avd", "hostmicon")
        self.log.event("vehicle_booted", avd=vehicle["avd"], pid=record["pid"], reused=not cold, user=10)

    def ensure_routes(self, vehicle):
        self.phase = "routes_" + str(vehicle["port"])
        self.avd_name(vehicle)
        if self.shell(vehicle, "am", "get-current-user").strip() != "10":
            raise Failure("차량 사용자가 바뀌었습니다. 연결을 변경하지 않았습니다.", 4)
        def mappings():
            output = self.adb(vehicle, "reverse", "--list")
            result = {}
            for line in output.splitlines():
                fields = line.split()
                if len(fields) == 3:
                    result.setdefault(fields[1], []).append(fields[2])
            return result
        before = mappings()
        for port in (4182, 4192):
            actual = before.get("tcp:" + str(port), [])
            if actual and actual != ["tcp:4192"]:
                raise Failure("기존 차량 주소 연결이 다른 서버를 가리킵니다. 덮어쓰지 않았습니다.", 4)
        repaired = []
        for port in (4182, 4192):
            if "tcp:" + str(port) not in before:
                self.adb(vehicle, "reverse", "--no-rebind", "tcp:" + str(port), "tcp:4192")
                repaired.append(port)
        after = mappings()
        if any(after.get("tcp:" + str(port)) != ["tcp:4192"] for port in (4182, 4192)):
            raise Failure("차량 기본 주소의 연결 복구를 확인할 수 없습니다.", 7)
        self.log.event("routes_ready", avd=vehicle["avd"], repaired=repaired)

    def installed_apk(self, vehicle):
        paths = self.shell(vehicle, "pm", "path", "--user", "10", PACKAGE).strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:/data/app/"):
            raise Failure("승인된 차량 앱이 설치되어 있지 않습니다. 승인 APK를 먼저 준비해 주세요.", 7)
        path = paths[0][len("package:"):]
        if not re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+/base\.apk", path):
            raise Failure("차량 앱의 설치 경로를 확인할 수 없습니다.", 7)
        value = self.shell(vehicle, "sha256sum", path).split()
        if not value or value[0] != self.c["apk_sha256"]:
            raise Failure("설치된 차량 앱이 승인 APK와 다릅니다. 자동 설치 없이 중단했습니다.", 7)
        self.log.event("apk_verified", avd=vehicle["avd"], sha256=value[0])

    def activity(self, vehicle, timeout=None):
        result = activity_observation(self.shell(vehicle, "dumpsys", "activity", "activities", timeout=timeout))
        if result["count"] > 1 or not result["user_ok"]:
            raise Failure("앱 화면이 중복되어 있거나 다른 사용자에 있습니다. 기존 화면을 보존하고 중단했습니다.", 4)
        return result

    def public_ui(self, vehicle, timeout=None):
        xml = self.adb(vehicle, "exec-out", "uiautomator", "dump", "/proc/self/fd/1", timeout=timeout)
        return ui_observation(xml, vehicle["pane"])

    def ensure_app(self, vehicle):
        self.phase = "app_" + str(vehicle["port"])
        self.installed_apk(vehicle)
        activity = self.activity(vehicle)
        observation = self.public_ui(vehicle) if activity["count"] == 1 and activity["resumed"] else None
        open_count = 0
        last_open = float("-inf")
        def open_own_app(remaining=None):
            nonlocal open_count, last_open
            self.shell(vehicle, "am", "start", "--user", "10", "-a", "android.intent.action.MAIN",
                       "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", COMPONENT,
                       "-W", "--windowingMode", "6", "--display", "0",
                       timeout=None if remaining is None else min(self.c["timeouts"]["command"], remaining))
            open_count += 1
            last_open = time.monotonic()
            self.log.event("app_open" if open_count == 1 else "app_reopen", avd=vehicle["avd"], attempt=open_count)
        if not observation and not (activity["count"] == 1 and activity["resumed"]):
            open_own_app()
        def ready(remaining):
            if self.shell(vehicle, "am", "get-current-user", timeout=min(5, remaining)).strip() != "10":
                raise Failure("앱 확인 중 차량 사용자가 바뀌었습니다.", 4)
            try:
                current = self.activity(vehicle, min(5, remaining))
                if current["count"] != 1:
                    return False
                if not current["resumed"]:
                    # Cold native startup may take focus after our first launch. Reuse the same
                    # verified task at most twice more, without extending the readiness deadline.
                    if open_count < 3 and time.monotonic() - last_open >= 3:
                        open_own_app(remaining)
                    return False
                # An active own Activity with no public page may have a modal; preserve it.
                return self.public_ui(vehicle, min(12, remaining))
            except Failure as error:
                if error.code in (5, 6):
                    return False
                raise
        observation = observation or self.wait(ready, self.c["timeouts"]["ready"], "차량 앱 화면의 준비 시간이 지났습니다. 권한·초대 창과 차량 pane을 확인해 주세요.")
        self.log.event("app_ready", avd=vehicle["avd"], page=observation["page"], pane_verified=True,
                       main_activity_count=1, launched=open_count > 0)
        return observation

    def run(self):
        self.state["last_run"] = {"status": "preparing", "at": time.time()}
        self.save()
        commit = self.host.run(["/usr/bin/git", "-C", str(self.c["repo_root"]), "rev-parse", "HEAD"]).strip()
        if not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise Failure("실행 소스의 버전을 확인할 수 없습니다.", 2)
        self.state["source"] = {"commit": commit, "launcher_sha256": digest(__file__),
                                "server_sha256": self.c["server"]["sha256"], "apk_sha256": self.c["apk_sha256"]}
        self.save()
        self.log.event("source_verified", **self.state["source"])
        self.ensure_server()
        self.ensure_adb()
        observations = []
        for vehicle in self.c["emulators"]:
            self.ensure_emulator(vehicle)
            self.ensure_routes(vehicle)
            observations.append(self.ensure_app(vehicle))
        self.phase = "final_check"
        self.server_identity(self.host.listeners(self.api_port))
        if not self.server_http_ready():
            raise Failure("마지막 확인에서 공유 서버 연결이 끊겼습니다.", 7)
        for vehicle in self.c["emulators"]:
            self.emulator_identity(vehicle)
            self.ensure_routes(vehicle)
            current = self.activity(vehicle)
            if not current["resumed"] or current["count"] != 1 or not self.public_ui(vehicle):
                raise Failure("마지막 확인에서 차량 앱 화면이 준비되지 않았습니다.", 7)
        self.state["last_run"] = {"status": "ready", "at": time.time(), "vehicles": observations}
        self.save()
        self.log.status("두 차량이 준비됐어요. 열려 있는 앱에서 이어서 사용하세요.", "ready")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="Pleos 두 차량 데모 실행")
    parser.add_argument("--config", type=Path)
    parser.add_argument("--check-config", action="store_true")
    arguments = parser.parse_args(argv)
    log = None
    launcher = None
    phase = "entry"
    try:
        root = source_root()
        home = demo_home(root)
        log = RunLog(home)
        print("실행 기록: " + str(log.path), flush=True)
        config_path = arguments.config or home / "config.json"
        log.event("entry", phase=phase, launcher_sha256=digest(__file__),
                  config_exists=config_path.is_file(), configuration_only=arguments.check_config)
        phase = "lock"
        with single_instance(home):
            phase = "configuration"
            log.event("phase", phase=phase)
            config = load_config(config_path, root)
            phase = "adb_metadata"
            log.event("phase", phase=phase)
            owned_environment(config)
            if arguments.check_config:
                log.status("로컬 설정을 확인했어요.", "configuration_valid")
                return 0
            phase = "runtime_state"
            log.event("phase", phase=phase)
            launcher = Launcher(config, home, log)
            launcher.run()
        return 0
    except Failure as error:
        print(str(error), file=sys.stderr, flush=True)
        if log:
            phase = launcher.phase if launcher else phase
            log.event("failed", phase=phase, exit_code=error.code, message=error.message)
            if launcher:
                launcher.state["last_run"] = {"status": "failed", "at": time.time(), "phase": phase, "exit_code": error.code}
                with contextlib.suppress(OSError):
                    launcher.save()
        return error.code
    except (OSError, ValueError, TypeError, KeyError) as error:
        print("실행 설정·기록을 처리하지 못했습니다. 로컬 설정과 실행 기록을 확인해 주세요.", file=sys.stderr, flush=True)
        if log:
            log.event("failed", phase=launcher.phase if launcher else phase, exit_code=2,
                      exception_type=type(error).__name__)
        return 2
    finally:
        if log:
            log.stream.close()


if __name__ == "__main__":
    sys.exit(main())
