#!/usr/bin/env python3
"""Serial device acceptance; all resets apply only to this dedicated fixture package."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

PACKAGE = "com.ft.fixture.launch"
ACTIVITY = PACKAGE + "/com.ft.launchfixture.LaunchActivity"
URI = "content://com.ft.fixture.launch.control"
MIXED = "foreground_and_background"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--warm-only", action="store_true", help="Run the warm/cold/hot regression suite")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    apk = root / "ft-launch-fixture/build/outputs/apk/debug/ft-launch-fixture-debug.apk"
    args.output.mkdir(parents=True, exist_ok=True)
    adb = ["adb", "-s", args.serial]
    results = []

    def run(*command):
        process = subprocess.run(adb + list(command), capture_output=True, text=True, timeout=20)
        if process.returncode:
            raise RuntimeError((process.stdout + process.stderr)[-3000:])
        return process.stdout

    def control(method):
        output = run("shell", "content", "call", "--uri", URI, "--method", method)
        if "Exception" in output or "Error" in output:
            raise RuntimeError(output[-3000:])

    def status():
        control("status")
        return json.loads(run("shell", "run-as", PACKAGE, "cat", "files/launch-acceptance.json"))

    def wait_for(predicate, description):
        deadline = time.monotonic() + 18
        latest = None
        while time.monotonic() < deadline:
            latest = status()
            if predicate(latest):
                return latest
            time.sleep(.25)
        raise AssertionError(description + ": " + json.dumps(latest, ensure_ascii=False)[-3500:])

    def reset():
        run("shell", "am", "force-stop", PACKAGE)
        assert "Success" in run("shell", "pm", "clear", PACKAGE)

    def start(gated=False, wait_for_window=False):
        command = ["shell", "am", "start", "--activity-single-top", "--activity-reorder-to-front",
                   "-n", ACTIVITY]
        if wait_for_window:
            command.append("-W")
        if gated:
            command += ["--ez", "gate_first_frame", "true"]
        output = run(*command)
        if "Error" in output or "Exception" in output:
            raise RuntimeError(output[-3000:])
        # Querying the control provider before Activity resume can itself start the process.
        # Observe the system first so the probe cannot change the launch being measured.
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            activities = run("shell", "dumpsys", "activity", "activities")
            if any(PACKAGE in line and ("mResumedActivity:" in line or "topResumedActivity=" in line)
                   for line in activities.splitlines()):
                return
            time.sleep(.2)
        raise AssertionError("Activity did not resume without a provider probe")

    def completed(expected):
        report = wait_for(lambda s: s["first_draw_done"] and len(s["cold_actions"]) == 1,
                          "cold Action must complete and reach local storage")
        assert report["launch_type"] == expected, report
        action = report["cold_actions"][0]
        assert action["launch_type"] == expected, report
        duration = re.search(r"(?:[, ])duration=(\d+)(?:i)?(?:[, ]|$)", action["line_protocol"])
        assert duration, action
        action["duration_ns"] = int(duration.group(1))
        segments = sum(report[k] for k in ["pre_application_duration_ns", "application_duration_ns",
                                          "first_frame_duration_ns"])
        assert action["duration_ns"] > 0, report
        if report["api"] >= 29 and report["first_activity_ns"] > 0:
            assert action["duration_ns"] == segments, report
        elif report["api"] < 29:
            assert report["first_activity_ns"] == 0, report
            assert report["application_duration_ns"] == 0, report
            assert report["first_frame_duration_ns"] == 0, report
            assert "app_application_init_time=" not in action["line_protocol"], report
            assert "app_first_frame_init_time=" not in action["line_protocol"], report
            if report["api"] < 24:
                assert "app_pre_application_init_time=" not in action["line_protocol"], report
        return report

    def save(name, report, endpoint):
        report["scenario"] = name
        report["measurement_end"] = endpoint
        report["result"] = "PASS"
        (args.output / (name + ".json")).write_text(json.dumps(report, ensure_ascii=False, indent=2))
        results.append({"scenario": name, "result": "PASS", "launch_type": report["launch_type"],
                        "cold_action_count": len(report["cold_actions"]), "measurement_end": endpoint})
        print("PASS", name, report["launch_type"], "cold_actions=" + str(len(report["cold_actions"])),
              flush=True)

    def verify_warm_launches():
        def counts(report):
            return tuple(len(report[key]) for key in ("cold_actions", "warm_actions", "hot_actions"))

        def stored(expected, description):
            return wait_for(lambda s: counts(s) == expected, description)

        def save_result(name, report, **metrics):
            settled = status()
            assert counts(settled) == counts(report) and settled["pid"] == report["pid"], settled
            result = dict(scenario=name, result="PASS", pid=report["pid"],
                          cold=counts(report)[0], warm=counts(report)[1], hot=counts(report)[2],
                          **metrics)
            (args.output / (name + ".json")).write_text(
                json.dumps(dict(report, acceptance=result), ensure_ascii=False, indent=2))
            results.append(result)
            print("PASS", name, json.dumps(result), flush=True)

        def duration_ns(line):
            duration = re.search(r"(?:[, ])duration=(\d+)(?:i)?(?:[, ]|$)", line)
            assert duration, line
            return int(duration.group(1))

        def assert_warm(report):
            line = report["warm_actions"][0]
            assert "view_name=ApplicationLaunch" in line, line
            for field in ("app_pre_application_init_time", "app_application_init_time",
                          "app_first_frame_init_time", "app_launch_type"):
                assert field + "=" not in line, line
            duration = duration_ns(line)
            assert duration > 0, line
            return duration

        def assert_first_activity_warm(report):
            assert report["first_draw_done"] and counts(report) == (0, 1, 0), report
            assert sum(e["event"] == "created" for e in report["events"]) == 1, report
            activity_ns = report["first_activity_observed_ns"]
            gap = activity_ns - report["process_origin_ns"]
            assert gap > 10_000_000_000 and report["first_launch_origin_ns"] == activity_ns, report
            duration = assert_warm(report)
            drawn_ns = next(e["monotonic_ns"] for e in report["events"]
                            if e["event"] == "first_draw_observed")
            assert 0 <= activity_ns + duration - drawn_ns < 500_000_000, report
            return dict(process_to_activity_ns=gap, duration_ns=duration)

        reset()
        start(wait_for_window=True)
        cold = completed("foreground")
        assert counts(cold) == (1, 0, 0), cold
        cold_gap = cold["first_activity_observed_ns"] - cold["process_origin_ns"]
        assert 0 < cold_gap <= 10_000_000_000, cold
        save_result("cold_control", cold, process_to_activity_ns=cold_gap,
                    duration_ns=cold["cold_actions"][0]["duration_ns"])

        reset()
        run("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
        run("shell", "run-as", PACKAGE, "touch", "files/delay-before-application")
        start(wait_for_window=True)
        delayed = stored((0, 1, 0), "real pre-Application delay should produce one warm Action")
        pre_gap = delayed["application_ns"] - delayed["process_origin_ns"]
        assert delayed["pre_application_delay_ns"] >= 12_000_000_000 and pre_gap > 10_000_000_000, delayed
        save_result("pre_application_delay", delayed, process_to_application_ns=pre_gap,
                    **assert_first_activity_warm(delayed))

        reset()
        headless = wait_for(lambda s: s["application_ns"] > 0, "headless Application initialized")
        assert not headless["first_draw_done"] and headless["first_activity_observed_ns"] == 0, headless
        assert counts(headless) == (0, 0, 0), headless
        time.sleep(12)
        start(gated=True)
        held = wait_for(lambda s: s["listener_registered"] and s["foreground"],
                        "first Activity should await the real first draw")
        assert held["pid"] == headless["pid"] and held["application_ns"] == headless["application_ns"], held
        assert counts(held) == (0, 0, 0) and not held["first_draw_done"], held
        control("release")
        first = stored((0, 1, 0), "headless process should report warm after first draw")
        assert first["pid"] == headless["pid"], first
        save_result("headless_then_first_activity", first, **assert_first_activity_warm(first))

        reset()
        start(wait_for_window=True)
        initial = completed("foreground")
        control("finish_activity")
        wait_for(lambda s: not s["foreground"] and any(e["event"] == "destroyed" for e in s["events"]),
                 "initial Activity destroyed")
        start(gated=True)
        recreated = wait_for(lambda s: s["foreground"] and sum(e["event"] == "resumed"
                             for e in s["events"]) == 2, "replacement Activity resumed")
        assert recreated["pid"] == initial["pid"] and counts(recreated) == (1, 0, 0), recreated
        control("release")
        warm = stored((1, 1, 0), "recreating the Activity should report warm once")
        assert sum(e["event"] == "created" for e in warm["events"]) == 2, warm
        save_result("recreate_activity_in_same_process", warm, duration_ns=assert_warm(warm))

        run("shell", "input", "keyevent", "KEYCODE_HOME")
        wait_for(lambda s: not s["foreground"], "existing Activity stopped")
        time.sleep(11)
        start()
        hot = stored((1, 1, 1), "reusing the existing Activity should report hot once")
        assert hot["pid"] == initial["pid"], hot
        assert sum(e["event"] == "created" for e in hot["events"]) == 2, hot
        save_result("reuse_activity_after_background", hot, duration_ns=duration_ns(hot["hot_actions"][0]))

        reset()
        start(gated=True)
        before = wait_for(lambda s: s["foreground"] and s["listener_registered"],
                          "cold Activity is awaiting first draw")
        assert counts(before) == (0, 0, 0) and before["launch_type"] == "foreground", before
        run("shell", "input", "keyevent", "KEYCODE_HOME")
        wait_for(lambda s: not s["foreground"] and s["launch_type"] == MIXED,
                 "cold launch enters background while first draw is pending")
        time.sleep(61)
        start(gated=True)
        resumed = wait_for(lambda s: s["foreground"] and s["listener_registered"],
                           "same process returns with its first draw still pending")
        assert resumed["pid"] == before["pid"] and counts(resumed) == (0, 0, 0), resumed
        control("release")
        mixed = completed(MIXED)
        action = mixed["cold_actions"][0]
        assert mixed["pid"] == before["pid"] and counts(mixed) == (1, 0, 0), mixed
        assert sum(e["event"] == "created" for e in mixed["events"]) == 1, mixed
        assert mixed["first_frame_duration_ns"] > 60_000_000_000, mixed
        assert action["duration_ns"] > 60_000_000_000, action
        save_result("long_mixed_cold_launch", mixed, duration_ns=action["duration_ns"],
                    first_frame_duration_ns=mixed["first_frame_duration_ns"])

    try:
        print("Installing isolated fixture on", args.serial, flush=True)
        run("install", "-r", str(apk))
        if args.warm_only:
            verify_warm_launches()
            return

        reset()
        start()
        foreground = completed("foreground")
        run("shell", "input", "keyevent", "KEYCODE_HOME")
        wait_for(lambda s: not s["foreground"], "foreground control must enter background")
        assert status()["launch_type"] == "foreground"
        save("foreground_control", foreground, "automatic_first_draw")
        time.sleep(11)
        start()
        warm = wait_for(lambda s: len(s["hot_actions"]) == 1, "one hot Action after background")
        assert len(warm["cold_actions"]) == 1 and warm["current_activity"], warm
        assert warm["app_state"] == "run", warm
        assert re.search(r"(?:[, ])duration=[1-9]\d*(?:i)?(?:[, ]|$)", warm["hot_actions"][0]), warm
        control("repeat_hook")
        run("shell", "input", "keyevent", "KEYCODE_HOME")
        stopped = wait_for(lambda s: not s["foreground"], "repeat hook must not unbalance Activity count")
        assert stopped["app_state"] == "background", stopped
        assert len(stopped["cold_actions"]) == 1 and len(stopped["hot_actions"]) == 1, stopped
        save("hot_start_and_repeat_hook", stopped, "automatic_resume")

        reset()
        pending = wait_for(lambda s: s["application_ns"] != 0 and s["launch_type"] == "background",
                           "headless process must be classified background")
        time.sleep(1)
        pending = status()
        assert not pending["foreground"] and not pending["first_draw_done"]
        assert not pending["listener_registered"] and pending["first_activity_ns"] == 0
        assert pending["cold_actions"] == []
        save("background_pending", pending, "no_first_draw_no_automatic_action")

        control("finish_background_for_test")
        background = completed("background")
        assert not background["foreground"] and background["first_activity_ns"] == 0
        save("background_explicit_test_endpoint", background, "explicit_test_completion")
        start()
        frozen = wait_for(lambda s: s["foreground"], "post-completion foreground transition")
        assert frozen["launch_type"] == "background"
        assert len(frozen["cold_actions"]) == 1
        assert frozen["cold_actions"][0]["launch_type"] == "background"
        save("background_report_frozen_after_completion", frozen, "explicit_test_completion")

        reset()
        before = wait_for(lambda s: s["launch_type"] == "background", "background before foreground")
        control("repeat_hook")
        repeated = status()
        assert repeated["application_ns"] == before["application_ns"]
        assert repeated["launch_type"] == "background"
        start()
        background = completed("background")
        assert background["application_ns"] == before["application_ns"]
        save("background_repeat_hook_foreground", background, "automatic_first_draw")

        reset()
        wait_for(lambda s: s["launch_type"] == "background", "background-origin gated launch")
        start(gated=True)
        held = wait_for(lambda s: s["foreground"] and s["listener_registered"],
                        "background-origin first draw is held")
        assert held["launch_type"] == "background" and not held["first_draw_done"]
        run("shell", "input", "keyevent", "KEYCODE_HOME")
        stopped = wait_for(lambda s: not s["foreground"], "background-origin launch returns to background")
        assert stopped["launch_type"] == "background" and not stopped["first_draw_done"]
        start(gated=True)
        wait_for(lambda s: s["foreground"], "background-origin launch returns to foreground")
        control("release")
        save("background_origin_foreground_background_foreground", completed("background"),
             "automatic_first_draw")

        reset()
        start(gated=True)
        held = wait_for(lambda s: s["foreground"] and s["listener_registered"],
                        "foreground Activity must register a held first-draw listener")
        assert held["launch_type"] == "foreground" and not held["first_draw_done"]
        assert held["cold_actions"] == []
        run("shell", "input", "keyevent", "KEYCODE_HOME")
        stopped = wait_for(lambda s: not s["foreground"] and s["launch_type"] == MIXED,
                           "background while first draw is pending")
        assert not stopped["first_draw_done"] and stopped["cold_actions"] == []
        time.sleep(1)
        start(gated=True)
        resumed = wait_for(lambda s: s["foreground"], "return to held Activity")
        assert not resumed["first_draw_done"] and resumed["launch_type"] == MIXED
        assert sum(e["event"] == "created" for e in resumed["events"]) == 1
        control("release")
        save("foreground_background_foreground_before_first_draw", completed(MIXED),
             "automatic_first_draw")
        reset()
        start(gated=True)
        wait_for(lambda s: s["foreground"] and s["listener_registered"], "held startup before recreation")
        control("recreate")
        recreated = wait_for(lambda s: sum(e["event"] == "created" for e in s["events"]) == 2
                             and s["listener_registered"], "Activity recreated with pending draw")
        assert recreated["launch_type"] == "foreground" and not recreated["first_draw_done"], recreated
        assert recreated["current_activity"] and recreated["cold_actions"] == [], recreated
        control("release")
        save("configuration_recreation_before_first_draw", completed("foreground"), "automatic_first_draw")
    except Exception as exc:
        (args.output / "failure.txt").write_text(str(exc))
        raise
    finally:
        run("shell", "am", "force-stop", PACKAGE)
        (args.output / "summary.json").write_text(json.dumps({"device": args.serial,
            "api": run("shell", "getprop", "ro.build.version.sdk").strip(),
            "results": results}, ensure_ascii=False, indent=2))
    print("All", len(results), "acceptance checks passed; reports:", args.output, flush=True)


if __name__ == "__main__":
    main()
