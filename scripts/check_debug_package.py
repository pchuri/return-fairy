#!/usr/bin/env python3
"""Fail CI before publishing a debug APK that could collide with the release app."""

import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


RELEASE_ID = "com.pchuri.returnfairy"
DEBUG_ID = RELEASE_ID + ".debug"
ANDROID = "{http://schemas.android.com/apk/res/android}"
LABELS = {"": "Return Fairy (Test)", "-ko": "반납요정 (Test)"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_badging(text):
    package = re.search(r"^package: name='([^']+)'", text, re.MULTILINE)
    require(package is not None and package[1] == DEBUG_ID, "Unexpected debug application ID")
    labels = dict(re.findall(r"^application-label([^:]*):'([^']*)'$", text, re.MULTILINE))
    for locale, expected in LABELS.items():
        require(labels.get(locale) == expected, f"Missing Test label for locale {locale or 'default'}")
    require(all("(Test)" in label for label in labels.values()), "A locale hides the Test label")


def check_manifest(text):
    root = ET.fromstring(text)
    require(root.get("package") == DEBUG_ID, "Unexpected manifest package")
    require(root.get(ANDROID + "sharedUserId") is None, "Debug must not share a UID")
    app = root.find("application")
    require(app is not None, "Missing application")
    require(app.get(ANDROID + "debuggable") == "true", "Expected a debug APK")
    require(app.get(ANDROID + "allowBackup") == "false", "App data backup must remain disabled")

    for provider in app.findall("provider"):
        authorities = provider.get(ANDROID + "authorities", "").split(";")
        require(all(value.startswith(DEBUG_ID + ".") for value in authorities),
                "Provider authorities must belong to the debug application ID")
    for tag in ("permission", "permission-tree", "permission-group", "uses-permission"):
        for permission in root.findall(tag):
            name = permission.get(ANDROID + "name", "")
            require(not name.startswith(RELEASE_ID + ".") or name.startswith(DEBUG_ID + "."),
                    "A permission still uses the release application ID")
    # There are no incoming links today. Require a deliberate audit if one is added.
    require(not root.findall(".//intent-filter/data"), "Incoming links need a debug isolation audit")
    for component in app.iter():
        process = component.get(ANDROID + "process", "")
        require(not process or process.startswith(":") or process == DEBUG_ID,
                "Unexpected non-private process")


def inspect_apk(apk, aapt2, apkanalyzer):
    def output(*command):
        return subprocess.check_output(command, text=True, encoding="utf-8")

    badging = output(aapt2, "dump", "badging", str(apk))
    manifest = output(apkanalyzer, "manifest", "print", str(apk))
    check_badging(badging)
    check_manifest(manifest)
    for line in badging.splitlines():
        if line.startswith(("package:", "application-label:", "application-label-ko:")):
            print(line)
    for provider in ET.fromstring(manifest).findall("application/provider"):
        print("Provider authority:", provider.get(ANDROID + "authorities"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt2", required=True, help="Android SDK build-tools aapt2 path")
    parser.add_argument("--apkanalyzer", required=True, help="Android SDK cmdline-tools apkanalyzer path")
    args = parser.parse_args()
    inspect_apk(args.apk, args.aapt2, args.apkanalyzer)
    print(f"Verified {DEBUG_ID}: Test labels, isolated providers/permissions, no shared UID or links")
