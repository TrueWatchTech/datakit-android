#!/usr/bin/env python3
"""Exercise an installed debug Sample's router/first-frame lifecycle on one device."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--scenario", choices=("foreground", "background", "after_draw", "background_origin"), required=True)
    parser.add_argument("--delay-ms", type=int, default=3000)
    parser.add_argument("--home-at", choices=("home_resumed", "home_created", "router_created"),
                        default="home_resumed", help="Lifecycle event after which Home is sent")
    parser.add_argument("--channel-page", action="store_true", help="Visit a sibling-process UI before Home")
    visibility = parser.add_mutually_exclusive_group()
    visibility.add_argument("--expect-process-state", action="store_true",
                            help="Require the main process to enter background when its own UI stops")
    visibility.add_argument("--expect-shared-state", action="store_true",
                            help="Check the older SDK behavior that combined visibility across processes")
    parser.add_argument("--verify-upload", action="store_true",
                        help="Check actual launch fields and a successful upload from this process")
    parser.add_argument("--legacy-log-path", action="store_true",
                        help="Read files/LogInner.log when validating an SDK built before the debug log migration")
    parser.add_argument("--expected", choices=("foreground", "background", "foreground_and_background"))
    parser.add_argument("--output", type=Path, required=True, help="Output prefix for .log/.json")
    args = parser.parse_args()
    if args.channel_page and (args.scenario != "background" or args.home_at != "home_resumed"):
        parser.error("--channel-page requires --scenario background and --home-at home_resumed")
    adb = [args.adb, "-s", args.serial]

    def run(*command):
        return subprocess.run(adb + list(command), capture_output=True, text=True,
                              check=True, timeout=20).stdout.strip()

    policy = run("shell", "dumpsys", "window", "policy")
    if "showing=true" in policy or "screenState=SCREEN_STATE_ON" not in policy:
        raise RuntimeError("Device must be unlocked with its screen on before testing")
    run("shell", "am", "force-stop", "com.ft")
    tags = ["LaunchTypeSample:I", "[FT-SDK]AppStartCounter:D", "[FT-SDK]AppStateMonitor:D", "AndroidRuntime:E",
            "am_anr:I", "am_crash:I", "wm_on_create_called:I", "wm_on_start_called:I",
            "wm_on_resume_called:I", "wm_on_paused_called:I", "wm_on_stop_called:I",
            "wm_on_destroy_called:I", "*:S"]
    capture = subprocess.Popen(adb + ["logcat", "-T", "1", "-v", "threadtime",
                                     "-b", "main", "-b", "system", "-b", "events"] + tags,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    lines = []
    condition = threading.Condition()

    def read_logs():
        for line in capture.stdout:
            if any(tag in line for tag in ("LaunchTypeSample", "AppStartCounter", "AppStateMonitor", "AndroidRuntime", "com.ft")):
                with condition:
                    lines.append(line.rstrip())
                    condition.notify_all()

    reader = threading.Thread(target=read_logs, daemon=True)
    reader.start()

    def wait_for(pattern, timeout=15, start=0):
        deadline = time.monotonic() + timeout
        with condition:
            while True:
                for line in lines[start:]:
                    if re.search(pattern, line):
                        return line
                if capture.poll() is not None:
                    raise RuntimeError("logcat stopped before the expected event")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RuntimeError("Timed out waiting for " + pattern)
                condition.wait(min(remaining, 0.2))

    launch = ["shell", "am", "start", "-a", "android.intent.action.MAIN",
              "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000",
              "-n", "com.ft/.SplashActivity"]
    result = {"scenario": args.scenario, "delay_ms": args.delay_ms, "serial": args.serial,
              "home_at": args.home_at, "channel_page": args.channel_page}
    marker_index = 0
    try:
        marker = "router-scenario-" + uuid.uuid4().hex
        run("shell", "log", "-t", "LaunchTypeSample", marker)
        wait_for(marker)
        marker_index = len(lines)
        if args.scenario == "background_origin":
            run("shell", "am", "broadcast", "-n", "com.ft/.BackgroundLaunchReceiver",
                "-a", "com.ft.action.SIMULATE_BACKGROUND_BROADCAST_LAUNCH")
            wait_for("AppStateMonitor: state:background", start=marker_index)
        run(*(launch + ["--ei", "launch_test_first_frame_delay_ms", str(args.delay_ms),
                        "--ez", "launch_test_channel_page", str(args.channel_page).lower()]))
        trigger = args.home_at.replace("_", " ") if args.scenario == "background" else "home resumed"
        trigger_line = wait_for("LaunchTypeSample: " + trigger, start=marker_index)
        pid = trigger_line.split()[2]
        result["pid"] = pid
        main_background = r"\s" + re.escape(pid) + r"\s+\d+\s+D .*AppStateMonitor: state:background"

        def main_cold_starts():
            return [s for s in lines[marker_index:]
                    if "AppStartCounter: coldStart:" in s and s.split()[2] == pid]

        if args.channel_page:
            wait_for("LaunchTypeSample: channel resumed", start=marker_index)
            wait_for("LaunchTypeSample: home stopped", start=marker_index)
            if args.expect_process_state:
                wait_for(main_background, start=lines.index(trigger_line, marker_index))
            if args.expect_shared_state and any(
                    "AppStateMonitor: state:background" in s and s.split()[2] == pid
                    for s in lines[marker_index:]):
                raise RuntimeError("App was marked background during visible sibling navigation")

        if args.scenario == "after_draw":
            wait_for("AppStartCounter: coldStart:", start=marker_index)
        if args.scenario in ("background", "after_draw"):
            if args.scenario == "background" and main_cold_starts():
                raise RuntimeError("First frame completed before Home; this is not the requested path")
            pause_index = len(lines)
            run("shell", "cmd", "input", "keyevent", "KEYCODE_HOME")
            if args.channel_page:
                wait_for("LaunchTypeSample: channel stopped", start=pause_index)
            elif args.home_at == "home_resumed" or args.scenario == "after_draw":
                wait_for("LaunchTypeSample: home stopped", start=pause_index)
            else:
                # A created-only Activity may have no Stop. Confirm the launcher is now resumed.
                time.sleep(1)
                activity_state = run("shell", "dumpsys", "activity", "activities")
                resumed = [s.strip() for s in activity_state.splitlines()
                           if "topResumedActivity=" in s or "mResumedActivity:" in s]
                result["resumed_after_home"] = resumed
                if not resumed or any("com.ft/" in s for s in resumed):
                    raise RuntimeError("Home did not put the Sample behind the launcher")
            time.sleep(5)
            if args.expect_shared_state or (args.expect_process_state and not args.channel_page):
                wait_for(main_background, start=pause_index)
            if run("shell", "pidof", "com.ft") != pid:
                raise RuntimeError("App process changed while backgrounded")
            if args.scenario == "background" and main_cold_starts():
                raise RuntimeError("Cold start completed while backgrounded, before returning")
            return_index = len(lines)
            run(*launch)
            wait_for("LaunchTypeSample: home resumed", start=return_index)
            if run("shell", "pidof", "com.ft") != pid:
                raise RuntimeError("Returning created a new process")

        wait_for(r"\s" + re.escape(pid) + r"\s+\d+\s+D .*AppStartCounter: coldStart:", start=marker_index)
        time.sleep(1)
        events = lines[marker_index:]
        result["shared_state_events"] = [s for s in events
                                         if "AppStateMonitor:" in s and s.split()[2] == pid]
        cold = main_cold_starts()
        result["process_snapshots"] = [s for s in events if "home stop snapshot:" in s]
        version = next((re.search(r"sdk=([^,\s]+)", s) for s in events if "router created, sdk=" in s), None)
        if version: result["sdk_version"] = version[1]
        result["cold_start_count"] = len(cold)
        if len(cold) != 1:
            raise RuntimeError("Expected exactly one completed cold start")
        match = re.search(r"coldStart:(\d+).*launchFromBackground:(true|false),appLaunchType:(\w+)", cold[0])
        if not match:
            raise RuntimeError("Cold-start log has an unexpected format")
        result.update(duration_ns=int(match[1]), launch_from_background=match[2] == "true",
                      app_launch_type=match[3])
        if any("router started" in s or "router resumed" in s for s in events):
            raise RuntimeError("Splash unexpectedly reached Start/Resume")
        if any("FATAL EXCEPTION" in s or ("am_anr" in s and "com.ft" in s) for s in events):
            raise RuntimeError("Crash/ANR observed during the scenario")
        if args.expected and result["app_launch_type"] != args.expected:
            raise AssertionError("Expected " + args.expected + ", got " + result["app_launch_type"])
        if args.verify_upload:
            # Filter on the device before exporting: never copy the full private log, user
            # metadata or intake URLs. Duplicate log entries are deduplicated by Action ID.
            log_path = "files/LogInner.log" if args.legacy_log_path else "cache/ft_sdk/logs/LogInner.log"
            field_command = (
                f"run-as com.ft sh -c 'grep \"action_type=launch_cold\" {log_path}"
                " | tail -n 12 | grep -Eo \"app_launch_type=\\\"[^\\\"]*\\\"|"
                "app_state=\\\"[^\\\"]*\\\"|duration=[0-9]+|"
                "action_id=[a-z0-9]+|[0-9]{19}$\"' || true")
            status_command = (
                "logcat -d --pid=" + pid + " -v brief '[FT-SDK]SyncTaskManager:D' '*:S'"
                " | grep -o 'pkg_id:rum-[0-9]* Sync Success-\\[code:200' || true")
            upload_deadline = time.monotonic() + 30
            uploaded = {}
            statuses = ""
            while time.monotonic() < upload_deadline:
                fields = run("shell", field_command)
                current = {}
                for line in fields.splitlines():
                    if "=" in line:
                        key, value = line.split("=", 1)
                        current[key] = value.strip('"')
                    elif re.fullmatch(r"\d{19}", line):
                        current["timestamp"] = int(line)
                        if (current.get("duration") == str(result["duration_ns"])
                                and current.get("app_launch_type") == result["app_launch_type"]):
                            uploaded[current["action_id"]] = current
                        current = {}
                statuses = run("shell", status_command)
                if uploaded and statuses:
                    break
                time.sleep(1)
            if len(uploaded) != 1 or not statuses:
                raise RuntimeError("Expected one matching launch payload and HTTP 200 from this process")
            payload = next(iter(uploaded.values()))
            if args.scenario in ("background", "background_origin"):
                if payload.get("app_state") != "foreground_and_background":
                    raise AssertionError("Actual launch interval lost proven foreground/background: "
                                         + str(payload.get("app_state")))
            elif payload.get("app_state") not in ("foreground", "unknown"):
                raise AssertionError("Foreground launch was changed by post-frame lifecycle: "
                                     + str(payload.get("app_state")))
            result["uploaded_launch"] = payload
            result["upload_http_status"] = 200
            result["successful_rum_batches"] = len(statuses.splitlines())
        result["status"] = "passed"
    except Exception as error:
        result.update(status="failed", error=str(error))
        raise
    finally:
        capture.terminate()
        try:
            capture.wait(timeout=5)
        except subprocess.TimeoutExpired:
            capture.kill()
            capture.wait(timeout=5)
        reader.join(timeout=2)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        Path(str(args.output) + ".log").write_text("\n".join(lines[marker_index:]) + "\n")
        Path(str(args.output) + ".json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
