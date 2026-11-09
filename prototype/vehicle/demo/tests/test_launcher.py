"""Isolated launcher checks. Never connect to ADB5041 or the live API4192."""
import contextlib
import fcntl
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest
import unicodedata
import urllib.request
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "launcher.py"
spec = importlib.util.spec_from_file_location("pleos_launcher", MODULE_PATH)
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)
ROOT = MODULE_PATH.parents[3]
NODE = Path("/opt/homebrew/bin/node")


def process(pid=100, command="owned command", executable="/usr/bin/true", started="Mon Nov 9 00:00:00 2026"):
    return dict(pid=pid, command=command, executable=executable, started=started)


class TrackingHost(launcher.Host):
    def __init__(self):
        super().__init__()
        self.children = []

    def spawn(self, args, env):
        child = super().spawn(args, env)
        self.children.append(child)
        return child


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="pleos launcher isolated ")
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.sdk = self.home / "sdk"
        for name in ("platform-tools/adb", "emulator/emulator"):
            target = self.sdk / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("#!/bin/sh\nexit 99\n")
            target.chmod(0o700)
        self.meta = self.home / "owned-adb-server.json"
        environment = {"ADB_MDNS_AUTO_CONNECT": "0", "ADB_SERVER_SOCKET": "tcp:127.0.0.1:5041",
                       "ADB_VENDOR_KEYS": str(self.home / "existing-owned-key"), "ANDROID_ADB_SERVER_PORT": "5041",
                       "ANDROID_HOME": str(self.sdk), "ANDROID_SDK_ROOT": str(self.sdk),
                       "ANDROID_AVD_HOME": str(self.home / "avd"), "ANDROID_USER_HOME": str(self.home / "android")}
        self.meta.write_text(json.dumps(dict(serverPort=5041, listenHost="127.0.0.1", USBAllowlistSerial=launcher.USB_ALLOWLIST,
                                            physicalUSBConnectionsDisabledByUnmatchedAllowlist=True,
                                            ownKeyPath=environment["ADB_VENDOR_KEYS"], emulatorEnvironment=environment)))
        self.raw_config = dict(version=1, repo_root=str(ROOT), sdk_root=str(self.sdk), adb_metadata=str(self.meta),
                               node=str(NODE), server=dict(script=str(ROOT / "prototype/server.mjs"),
                                                          sha256=launcher.digest(ROOT / "prototype/server.mjs")),
                               apk_sha256="5" * 64,
                               emulators=[dict(avd=avd, port=port, user=10, pane=[853, 90, 2535, 1300]) for avd, port in launcher.VEHICLES],
                               timeouts=dict(command=2, boot=1, ready=1, adb=1, server=5))
        self.config_path = self.home / "config.json"
        self.write_config()
        self.log = launcher.RunLog(self.home)
        self.addCleanup(self.log.stream.close)

    def write_config(self):
        self.config_path.write_text(json.dumps(self.raw_config))

    def config(self):
        return launcher.load_config(self.config_path, ROOT)

    def instance(self, host=None, api_port=4192):
        return launcher.Launcher(self.config(), self.home, self.log, host=host, api_port=api_port)

    def invoke(self, *args, entry=None):
        env = dict(os.environ, PLEOS_DEMO_HOME=str(self.home))
        return subprocess.run([str(entry or ROOT / "Pleos 데모 실행.command"), *args], cwd=self.home, env=env,
                              capture_output=True, timeout=10, text=True)

    def test_real_entry_supports_symlink_spaces_and_other_cwd(self):
        entry = self.home / "바로 가기 데모.command"
        entry.symlink_to(ROOT / "Pleos 데모 실행.command")
        result = self.invoke("--check-config", entry=entry)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("로컬 설정을 확인했어요", result.stdout)

    def test_real_target_entry_accepts_mac_nfd_spelling_of_same_file(self):
        canonical = ROOT / "Pleos 데모 실행.command"
        decomposed = Path(unicodedata.normalize("NFD", str(canonical)))
        if not decomposed.exists():
            self.skipTest("filesystem does not equate NFC and NFD names")
        self.assertNotEqual(str(canonical), str(decomposed))
        self.assertTrue(canonical.samefile(decomposed))
        result = self.invoke("--check-config", entry=decomposed)
        self.assertEqual(result.returncode, 0, result.stderr)
        config = launcher.load_config(self.config_path, Path(unicodedata.normalize("NFD", str(ROOT))))
        self.assertEqual(str(config["repo_root"]), str(ROOT))
        self.assertEqual(str(config["server"]["script"]), str(ROOT / "prototype/server.mjs"))

    def test_foreign_directory_and_foreign_server_still_fail_identity_guard(self):
        self.raw_config["repo_root"] = str(self.home)
        self.write_config()
        with self.assertRaises(launcher.Failure) as caught:
            self.config()
        self.assertEqual(caught.exception.code, 2)
        self.raw_config["repo_root"] = str(ROOT)
        foreign = self.home / "server.mjs"
        foreign.write_bytes((ROOT / "prototype/server.mjs").read_bytes())
        self.raw_config["server"]["script"] = str(foreign)
        self.write_config()
        with self.assertRaises(launcher.Failure) as caught:
            self.config()
        self.assertEqual(caught.exception.code, 2)

    def test_missing_and_malformed_configuration_are_actionable_and_private(self):
        self.config_path.unlink()
        result = self.invoke("--check-config")
        self.assertEqual(result.returncode, 2)
        self.assertIn("설정", result.stderr)
        self.config_path.write_text('{"secret":"NEVER_ECHO_PRIVATE_VALUE",')
        result = self.invoke("--check-config")
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("NEVER_ECHO_PRIVATE_VALUE", result.stdout + result.stderr)
        self.assertNotIn("NEVER_ECHO_PRIVATE_VALUE", "".join(p.read_text() for p in (self.home / "logs").glob("*")))

    def test_diagnostics_keep_curated_failure_and_purpose_without_private_values(self):
        self.config_path.unlink()
        result = self.invoke("--check-config")
        self.assertEqual(result.returncode, 2)
        newest = max((self.home / "logs").glob("*"), key=lambda path: path.stat().st_mtime_ns)
        rows = [json.loads(line) for line in newest.read_text().splitlines()]
        self.assertEqual(rows[0]["event"], "entry")
        self.assertFalse(rows[0]["config_exists"])
        self.assertEqual(rows[0]["launcher_sha256"], launcher.digest(MODULE_PATH))
        self.assertEqual(rows[-1]["phase"], "configuration")
        self.assertIn("실행 설정 파일", rows[-1]["message"])
        self.assertNotIn("exception_type", rows[-1])
        for purpose, expected in [("adb_metadata", "전용 ADB 환경 파일"), ("resources", "이전 실행 자원 기록")]:
            with self.assertRaises(launcher.Failure) as caught:
                launcher.read_json(self.home / "missing-private-path", purpose)
            self.assertIn(expected, caught.exception.message)
            self.assertNotIn("missing-private-path", caught.exception.message)

    def test_unexpected_exception_logs_only_type_and_phase(self):
        self.log.stream.close()
        self.log.path.unlink()
        with mock.patch.object(launcher, "source_root", return_value=ROOT), \
                mock.patch.object(launcher, "demo_home", return_value=self.home), \
                mock.patch.object(launcher, "load_config", side_effect=ValueError("NEVER_LOG_RAW_EXCEPTION")), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(launcher.main(["--check-config"]), 2)
        newest = max((self.home / "logs").glob("*"), key=lambda path: path.stat().st_mtime_ns)
        rows = [json.loads(line) for line in newest.read_text().splitlines()]
        self.assertEqual(rows[-1]["exception_type"], "ValueError")
        self.assertEqual(rows[-1]["phase"], "configuration")
        self.assertNotIn("NEVER_LOG_RAW_EXCEPTION", newest.read_text())

    def test_wrong_avd_port_and_wrong_source_are_rejected(self):
        self.raw_config["emulators"][0]["port"] = 5554
        self.write_config()
        with self.assertRaises(launcher.Failure) as caught:
            self.config()
        self.assertEqual(caught.exception.code, 2)
        self.raw_config["emulators"][0]["port"] = 5570
        self.raw_config["server"]["sha256"] = "0" * 64
        self.write_config()
        with self.assertRaises(launcher.Failure):
            self.config()

    def test_concurrent_entry_returns_distinct_code_before_any_resource_actions(self):
        with launcher.single_instance(self.home):
            result = self.invoke()
        self.assertEqual(result.returncode, 3)
        self.assertIn("이미 데모를 준비 중", result.stderr)
        self.assertFalse((self.home / "resources.json").exists())

    def test_environment_keeps_owned_key_but_blocks_inherited_adb_settings(self):
        with mock.patch.dict(os.environ, {"ANDROID_SERIAL": "physical-private", "ADB_TRACE": "all", "ADB_MDNS_AUTO_CONNECT": "1"}):
            env = launcher.owned_environment(self.config())
        self.assertNotIn("ANDROID_SERIAL", env)
        self.assertNotIn("ADB_TRACE", env)
        self.assertEqual(env["ADB_MDNS_AUTO_CONNECT"], "0")
        self.assertEqual(env["ADB_VENDOR_KEYS"], str(self.home / "existing-owned-key"))

    def test_foreign_server_port_never_starts_or_stops_process(self):
        host = mock.Mock()
        host.listeners.return_value = {101: ["127.0.0.1:4192"]}
        host.process.return_value = process(pid=101)
        app = self.instance(host)
        with self.assertRaises(launcher.Failure) as caught:
            app.ensure_server()
        self.assertEqual(caught.exception.code, 4)
        host.spawn.assert_not_called()
        host.run.assert_not_called()

    def test_fresh_adoption_requires_exact_pid_start_time_and_os_executable(self):
        self.raw_config["server"].update(adopt_pid=101, adopt_started="Sun Nov  8 19:17:48 2026",
                                         adopt_script=self.raw_config["server"]["script"])
        self.write_config()
        host = mock.Mock()
        command = str(NODE) + " " + str(ROOT / "prototype/server.mjs")
        host.process.return_value = process(pid=101, command=command, executable=str(NODE.resolve()),
                                            started="Sun Nov  8 19:17:48 2026")
        app = self.instance(host)
        listeners = {101: ["127.0.0.1:4192"]}
        self.assertEqual(app.server_identity(listeners)["pid"], 101)
        # A saved fingerprint mismatch must not fall back to a reused PID with a new start time.
        app.state["server"] = dict(launcher.fingerprint(host.process.return_value),
                                   script=str(ROOT / "prototype/server.mjs"))
        host.process.return_value = process(pid=101, command=command, executable=str(NODE.resolve()),
                                            started="Sun Nov  8 19:17:49 2026")
        with self.assertRaises(launcher.Failure) as caught:
            app.server_identity(listeners)
        self.assertEqual(caught.exception.code, 4)
        host.spawn.assert_not_called()
        host.run.assert_not_called()

    def test_partial_adoption_configuration_is_rejected(self):
        self.raw_config["server"].update(adopt_pid=101, adopt_script=self.raw_config["server"]["script"])
        self.write_config()
        with self.assertRaises(launcher.Failure) as caught:
            self.config()
        self.assertEqual(caught.exception.code, 2)

    def test_pid_reuse_changes_process_fingerprint(self):
        original = process(pid=123)
        self.assertTrue(launcher.same_process(launcher.fingerprint(original), original))
        replacement = dict(original, started="Mon Nov 9 01:00:00 2026")
        self.assertFalse(launcher.same_process(launcher.fingerprint(original), replacement))
        replacement = dict(original, executable="/foreign/program")
        self.assertFalse(launcher.same_process(launcher.fingerprint(original), replacement))

    def test_existing_adb_basename_argv_requires_approved_os_executable(self):
        host = mock.Mock()
        host.listeners.return_value = {100: ["127.0.0.1:5041"]}
        command = "adb -L tcp:5041 fork-server server --reply-fd 4 --one-device " + launcher.USB_ALLOWLIST
        host.process.return_value = process(command=command, executable=str(self.sdk / "platform-tools/adb"))
        app = self.instance(host)
        self.assertEqual(app.adb_identity()["pid"], 100)
        host.process.return_value = process(command=command, executable="/foreign/adb")
        with self.assertRaises(launcher.Failure):
            app.adb_identity()

    def test_missing_adb_is_started_only_with_explicit_loopback_and_usb_isolation(self):
        host = mock.Mock()
        app = self.instance(host)
        host.listeners.return_value = {}
        app.adb_identity = mock.Mock(return_value=launcher.fingerprint(process()))
        app.ensure_adb()
        args = host.run.call_args.args[0]
        self.assertEqual(args[1:], ["-L", "tcp:5041", "--one-device", launcher.USB_ALLOWLIST, "start-server"])
        starter = host.run.call_args.kwargs["env"]
        self.assertEqual(starter["ADB_MDNS_AUTO_CONNECT"], "0")
        self.assertNotIn("ADB_SERVER_SOCKET", starter)
        self.assertNotIn("ANDROID_ADB_SERVER_PORT", starter)
        self.assertEqual(starter["ADB_VENDOR_KEYS"], app.env["ADB_VENDOR_KEYS"])
        self.assertEqual(app.env["ADB_SERVER_SOCKET"], "tcp:127.0.0.1:5041")
        self.assertEqual(app.env["ANDROID_ADB_SERVER_PORT"], "5041")

    def test_client_scopes_remote_host_and_exact_serial(self):
        host = mock.Mock()
        host.run.return_value = "ok"
        app = self.instance(host)
        app.adb_identity = mock.Mock()
        vehicle = self.config()["emulators"][0]
        app.adb(vehicle, "get-state")
        args = host.run.call_args.args[0]
        self.assertEqual(args[1:7], ["-H", "127.0.0.1", "-P", "5041", "-s", "emulator-5570"])
        self.assertNotIn("5037", args)

    def test_unknown_emulator_identity_and_wrong_transport_owner_are_rejected(self):
        host = mock.Mock()
        host.listeners.side_effect = [{100: ["127.0.0.1:5570"]}, {101: ["127.0.0.1:5571"]}]
        app = self.instance(host)
        with self.assertRaises(launcher.Failure) as caught:
            app.emulator_identity(self.config()["emulators"][0])
        self.assertEqual(caught.exception.code, 4)
        host.spawn.assert_not_called()

    def test_restore_both_default_routes_and_preserve_existing_mappings(self):
        app = self.instance(mock.Mock())
        vehicle = self.config()["emulators"][0]
        app.avd_name = mock.Mock()
        app.shell = mock.Mock(return_value="10\n")
        mappings = ["", "emulator-5570 tcp:4182 tcp:4192\nemulator-5570 tcp:4192 tcp:4192\n"]
        app.adb = mock.Mock(side_effect=[mappings[0], "", "", mappings[1]])
        app.ensure_routes(vehicle)
        self.assertEqual(app.adb.call_args_list[1].args[1:], ("reverse", "--no-rebind", "tcp:4182", "tcp:4192"))
        self.assertEqual(app.adb.call_args_list[2].args[1:], ("reverse", "--no-rebind", "tcp:4192", "tcp:4192"))
        app.adb = mock.Mock(side_effect=[mappings[1], mappings[1]])
        app.ensure_routes(vehicle)
        self.assertEqual(app.adb.call_count, 2)

    def test_conflicting_reverse_route_is_not_overwritten(self):
        app = self.instance(mock.Mock())
        app.avd_name = mock.Mock()
        app.shell = mock.Mock(return_value="10")
        app.adb = mock.Mock(return_value="emulator-5570 tcp:4182 tcp:4190\n")
        with self.assertRaises(launcher.Failure) as caught:
            app.ensure_routes(self.config()["emulators"][0])
        self.assertEqual(caught.exception.code, 4)
        self.assertEqual(app.adb.call_count, 1)

    def test_boot_timeout_is_bounded_and_never_grants_or_changes_user(self):
        app = self.instance(mock.Mock())
        app.host.listeners.return_value = {100: ["127.0.0.1:5570"]}
        app.emulator_identity = mock.Mock(return_value=launcher.fingerprint(process()))
        app.avd_name = mock.Mock()
        app.shell = mock.Mock(return_value="0")
        with contextlib.redirect_stdout(io.StringIO()), mock.patch.object(launcher.time, "sleep"):
            with self.assertRaises(launcher.Failure) as caught:
                app.ensure_emulator(self.config()["emulators"][0])
        self.assertEqual(caught.exception.code, 5)
        self.assertTrue(all(call.args[1:] == ("getprop", "sys.boot_completed") for call in app.shell.call_args_list))
        app.host.spawn.assert_not_called()

    def test_cold_boot_waits_for_user10_and_restores_only_approved_host_mic(self):
        host = mock.Mock()
        host.listeners.side_effect = [{}, {}, {200: ["127.0.0.1:5570"]}, {200: ["127.0.0.1:5570"]}]
        host.run.return_value = ""
        host.spawn.return_value.pid = 200
        app = self.instance(host)
        app.emulator_identity = mock.Mock(return_value=launcher.fingerprint(process(pid=200)))
        app.avd_name = mock.Mock()
        app.shell = mock.Mock(side_effect=["1", "0", "1", "10", "RUNNING_UNLOCKED\n"])
        app.adb = mock.Mock()
        with contextlib.redirect_stdout(io.StringIO()), mock.patch.object(launcher.time, "sleep"):
            app.ensure_emulator(self.config()["emulators"][0])
        argv = host.spawn.call_args.args[0]
        self.assertEqual(argv[1:], ["-avd", "Pleos12Leader", "-port", "5570", "-no-snapshot", "-no-boot-anim",
                                   "-audio", "coreaudio", "-gpu", "host", "-crash-report-mode", "disabled"])
        self.assertEqual(app.adb.call_args.args[1:], ("emu", "avd", "hostmicon"))
        self.assertEqual(app.shell.call_args.args[1:], ("am", "get-started-user-state", "10"))
        self.assertEqual(host.run.call_args.args[0][:2], ["/usr/bin/pgrep", "-f"])
        self.assertFalse(any("switch-user" in call.args or "grant" in call.args for call in app.shell.call_args_list))

    def test_warm_wrong_foreground_user_refuses_without_switch_or_spawn(self):
        host = mock.Mock()
        host.listeners.return_value = {200: ["127.0.0.1:5570"]}
        app = self.instance(host)
        app.emulator_identity = mock.Mock(return_value=launcher.fingerprint(process(pid=200)))
        app.avd_name = mock.Mock()
        app.shell = mock.Mock(side_effect=["1", "0"])
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(launcher.Failure) as caught:
                app.ensure_emulator(self.config()["emulators"][0])
        self.assertEqual(caught.exception.code, 4)
        host.spawn.assert_not_called()
        self.assertEqual(app.shell.call_count, 2)

    def test_missing_public_product_ui_times_out_without_ready_event(self):
        app = self.instance(mock.Mock())
        app.installed_apk = mock.Mock()
        app.activity = mock.Mock(return_value=dict(count=1, user_ok=True, resumed=True))
        app.public_ui = mock.Mock(return_value=None)
        app.shell = mock.Mock(return_value="10")
        with mock.patch.object(launcher.time, "monotonic", side_effect=[100, 100, 102]):
            with self.assertRaises(launcher.Failure) as caught:
                app.ensure_app(self.config()["emulators"][0])
        self.assertEqual(caught.exception.code, 5)
        self.assertNotIn('"event": "app_ready"', self.log.path.read_text())

    def test_public_ui_uses_resource_id_and_correct_pane_without_recording_text(self):
        xml = '<?xml version="1.0"?><hierarchy><node package="' + launcher.PACKAGE + '" resource-id="' + launcher.PACKAGE + ':id/compose_page" bounds="[853,240][2535,1300]" text="NEVER_STORE_INVITATION_OR_DRAFT"/></hierarchy>'
        result = launcher.ui_observation(xml, [853, 90, 2535, 1300])
        self.assertEqual(result, dict(page="compose", pane_verified=True))
        self.assertNotIn("NEVER_STORE", json.dumps(result))
        self.assertIsNone(launcher.ui_observation(xml.replace("[853,240]", "[0,0]"), [853, 90, 2535, 1300]))

    def test_duplicate_main_and_non_resumed_product_cannot_be_ready(self):
        history = '* Hist  #0: ActivityRecord{abc u10 ' + launcher.COMPONENT + ' t100}\n'
        resumed = 'mResumedActivity: ActivityRecord{abc u10 ' + launcher.COMPONENT + ' t100}\n'
        self.assertEqual(launcher.activity_observation(history + resumed), dict(count=1, user_ok=True, resumed=True))
        duplicate = '* Hist #1: ActivityRecord{def u10 ' + launcher.COMPONENT + ' t101}\n'
        self.assertEqual(launcher.activity_observation(history + duplicate + resumed)["count"], 2)
        self.assertFalse(launcher.activity_observation(history)["resumed"])

    def test_warm_product_page_is_preserved_without_activity_start(self):
        app = self.instance(mock.Mock())
        app.installed_apk = mock.Mock()
        app.activity = mock.Mock(return_value=dict(count=1, user_ok=True, resumed=True))
        app.public_ui = mock.Mock(return_value=dict(page="compose", pane_verified=True))
        app.shell = mock.Mock()
        self.assertEqual(app.ensure_app(self.config()["emulators"][0])["page"], "compose")
        app.shell.assert_not_called()

    def test_cold_vendor_foreground_displacement_reuses_the_same_own_main_task(self):
        app = self.instance(mock.Mock())
        vehicle = self.config()["emulators"][0]
        history = '* Hist  #0: ActivityRecord{abc u10 ' + launcher.COMPONENT + ' t100}\n'
        displaced = launcher.activity_observation(history + 'mResumedActivity: ActivityRecord{vendor u10 ai.umos.maps.android.navigation.app/.MainActivity t90}\n')
        own = launcher.activity_observation(history + 'mResumedActivity: ActivityRecord{abc u10 ' + launcher.COMPONENT + ' t100}\n')
        app.installed_apk = mock.Mock()
        app.activity = mock.Mock(side_effect=[dict(count=0, user_ok=True, resumed=False), displaced, own])
        app.public_ui = mock.Mock(return_value=dict(page="home", pane_verified=True))
        app.shell = mock.Mock(side_effect=lambda _vehicle, *args, **kwargs: "10" if args == ("am", "get-current-user") else "")
        app.c["timeouts"]["ready"] = 10
        with mock.patch.object(launcher.time, "monotonic", side_effect=[0, 0, 3, 3, 3, 3, 4]), mock.patch.object(launcher.time, "sleep"):
            result = app.ensure_app(vehicle)
        starts = [call for call in app.shell.call_args_list if call.args[1:3] == ("am", "start")]
        self.assertEqual(len(starts), 2)
        self.assertEqual(starts[0].args, starts[1].args)
        self.assertEqual(result["page"], "home")
        rows = [json.loads(line) for line in self.log.path.read_text().splitlines()]
        self.assertEqual([row["attempt"] for row in rows if row["event"] == "app_reopen"], [2])
        self.assertTrue(any(row["event"] == "app_ready" and row["main_activity_count"] == 1 for row in rows))

    def test_still_displaced_own_main_has_three_open_bound_and_original_timeout(self):
        app = self.instance(mock.Mock())
        app.installed_apk = mock.Mock()
        count = 0
        def activity(*_args):
            nonlocal count
            count += 1
            return dict(count=0 if count == 1 else 1, user_ok=True, resumed=False)
        app.activity = mock.Mock(side_effect=activity)
        app.public_ui = mock.Mock()
        app.shell = mock.Mock(side_effect=lambda _vehicle, *args, **kwargs: "10" if args == ("am", "get-current-user") else "")
        app.c["timeouts"]["ready"] = 15
        with mock.patch.object(launcher.time, "monotonic", side_effect=iter(range(100))), mock.patch.object(launcher.time, "sleep"):
            with self.assertRaises(launcher.Failure) as caught:
                app.ensure_app(self.config()["emulators"][0])
        self.assertEqual(caught.exception.code, 5)
        starts = [call for call in app.shell.call_args_list if call.args[1:3] == ("am", "start")]
        self.assertEqual(len(starts), 3)
        self.assertTrue(all(call.args == starts[0].args for call in starts))
        app.public_ui.assert_not_called()
        rows = [json.loads(line) for line in self.log.path.read_text().splitlines()]
        self.assertEqual([row["attempt"] for row in rows if row["event"] == "app_reopen"], [2, 3])
        self.assertFalse(any(row["event"] == "app_ready" for row in rows))

    def test_resumed_own_main_without_public_ui_is_not_reopened(self):
        app = self.instance(mock.Mock())
        app.installed_apk = mock.Mock()
        app.activity = mock.Mock(return_value=dict(count=1, user_ok=True, resumed=True))
        app.public_ui = mock.Mock(return_value=None)
        app.shell = mock.Mock(return_value="10")
        with mock.patch.object(launcher.time, "monotonic", side_effect=[100, 100, 102]):
            with self.assertRaises(launcher.Failure) as caught:
                app.ensure_app(self.config()["emulators"][0])
        self.assertEqual(caught.exception.code, 5)
        self.assertFalse(any(call.args[1:3] == ("am", "start") for call in app.shell.call_args_list))
        self.assertNotIn('"event": "app_reopen"', self.log.path.read_text())

    def test_app_open_uses_launcher_intent_and_never_clear_top_or_force_stop(self):
        app = self.instance(mock.Mock())
        app.installed_apk = mock.Mock()
        app.activity = mock.Mock(return_value=dict(count=0, user_ok=True, resumed=False))
        app.public_ui = mock.Mock(return_value=None)
        app.shell = mock.Mock()
        app.wait = mock.Mock(return_value=dict(page="home", pane_verified=True))
        app.ensure_app(self.config()["emulators"][0])
        args = app.shell.call_args.args[1:]
        self.assertIn("android.intent.action.MAIN", args)
        self.assertIn("android.intent.category.LAUNCHER", args)
        self.assertIn("0x10200000", args)
        self.assertIn("--windowingMode", args)
        self.assertNotIn("force-stop", args)
        self.assertNotIn("clear", args)

    def test_partial_failure_cannot_publish_ready(self):
        host = mock.Mock()
        host.run.return_value = "4a9865f1b1584cb13a32f3a7022b08928ebad158\n"
        app = self.instance(host)
        app.ensure_server = mock.Mock()
        app.ensure_adb = mock.Mock()
        app.ensure_emulator = mock.Mock(side_effect=launcher.Failure("boot failed", 5))
        with self.assertRaises(launcher.Failure):
            app.run()
        state = json.loads(app.state_path.read_text())
        self.assertEqual(state["last_run"]["status"], "preparing")
        self.assertNotIn('"event": "ready"', self.log.path.read_text())

    @unittest.skipUnless(NODE.is_file(), "approved Node is unavailable")
    def test_real_node_cold_start_and_warm_reuse_on_isolated_port(self):
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            port = reserved.getsockname()[1]
        self.assertNotIn(port, (4190, 4192, 5041, 5570, 5572))
        host = TrackingHost()
        app = self.instance(host, port)
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                app.ensure_server()
            first = app.state["server"].copy()
            self.assertEqual(len(host.children), 1)
            self.assertTrue(app.server_http_ready())
            # An isolated test room proves a warm run preserves the same in-memory server.
            base = "http://127.0.0.1:" + str(port)
            create = urllib.request.Request(base + "/api/rooms", data=b'{"name":"launcher-isolated-check"}',
                                            headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(create, timeout=2) as response:
                self.assertEqual(response.status, 201)
                room = json.loads(response.read())
            # A second Launcher reads the on-disk process identity and reuses it.
            again = self.instance(host, port)
            with contextlib.redirect_stdout(io.StringIO()):
                again.ensure_server()
            self.assertEqual(first, again.state["server"])
            self.assertEqual(len(host.children), 1)
            request = urllib.request.Request(base + "/api/rooms/" + room["roomId"],
                                             headers={"Authorization": "Bearer " + room["token"]})
            with urllib.request.urlopen(request, timeout=2) as response:
                self.assertEqual(response.status, 200)
            request.method = "DELETE"
            with urllib.request.urlopen(request, timeout=2) as response:
                self.assertEqual(response.status, 200)
                self.assertTrue(json.loads(response.read()).get("closed"))
            self.assertNotIn(room["token"], self.log.path.read_text())
            self.assertNotIn(room["inviteSecret"], self.log.path.read_text())
        finally:
            for child in host.children:
                current = host.process(child.pid)
                self.assertTrue(launcher.same_process(app.state["server"], current))
                self.assertEqual(Path(current["executable"]).resolve(), NODE.resolve())
                child.terminate()
                child.wait(timeout=10)


if __name__ == "__main__":
    unittest.main(verbosity=2)
