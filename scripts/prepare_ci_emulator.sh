#!/usr/bin/env bash
set -euo pipefail

# On the headless runner Android should close an unresponsive process instead of
# waiting for someone to tap its system dialog. Instrumentation still reports failures.
adb shell settings put global hide_error_dialogs 1
setting=$(adb shell settings get global hide_error_dialogs)
if [[ "${setting//$'\r'/}" != 1 ]]; then
    echo "Android did not enable automatic dismissal of error dialogs." >&2
    exit 1
fi

# This setting only applies to new dialogs. A cold boot can already have a Launcher ANR.
launcher_dialog="Application Not Responding: com.android.launcher3"
windows=$(adb shell dumpsys window windows)
if [[ "$windows" == *"$launcher_dialog"* ]]; then
    echo "Closing the launcher's startup ANR before UI tests."
    adb shell am force-stop com.android.launcher3
    for ((attempt = 0; attempt < 10; attempt++)); do
        windows=$(adb shell dumpsys window windows)
        if [[ "$windows" != *"$launcher_dialog"* ]]; then
            exit 0
        fi
        sleep 1
    done
    echo "The Android Launcher ANR is still blocking input." >&2
    exit 1
fi
