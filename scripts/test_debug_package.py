"""Source safeguards and negative tests for the compiled-APK CI gate; no SDK required."""

from pathlib import Path
import re
import unittest

from check_debug_package import ANDROID, DEBUG_ID, LABELS, RELEASE_ID, check_badging, check_manifest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
BADGING = f"""package: name='{DEBUG_ID}' versionCode='18' versionName='4.0.1'
application-label:'Return Fairy (Test)'
application-label-ko:'반납요정 (Test)'
"""
MANIFEST = f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{DEBUG_ID}">
    <permission android:name="{DEBUG_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:debuggable="true" android:allowBackup="false">
        <provider android:name="androidx.startup.InitializationProvider"
            android:authorities="{DEBUG_ID}.androidx-startup" android:exported="false" />
        <activity android:name="{RELEASE_ID}.ui.MainActivity">
            <intent-filter><action android:name="android.intent.action.MAIN" /></intent-filter>
        </activity>
    </application>
</manifest>"""


class DebugPackageTest(unittest.TestCase):
    def test_source_keeps_debug_suffix_out_of_release(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn(f'applicationId = "{RELEASE_ID}"', gradle)
        self.assertIn(f'namespace = "{RELEASE_ID}"', gradle)
        debug = re.search(r"        debug \{(.*?)\n        \}", gradle, re.DOTALL)[1]
        self.assertIn('applicationIdSuffix = ".debug"', debug)
        self.assertEqual(gradle.count("applicationIdSuffix"), 1)
        self.assertNotIn("signingConfig", debug)

    def test_debug_overrides_every_main_app_name_locale(self):
        main = ROOT / "app/src/main/res"
        locales = set()
        for source in main.glob("values*/strings.xml"):
            node = ET.parse(source).find("string[@name='app_name']")
            if node is None:
                continue
            locale = source.parent.name.removeprefix("values")
            locales.add(locale)
            debug = ROOT / "app/src/debug/res" / source.relative_to(main)
            self.assertEqual(ET.parse(debug).find("string[@name='app_name']").text, LABELS[locale])
            self.assertNotIn("(Test)", node.text)
        self.assertEqual(locales, set(LABELS))
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        self.assertEqual(manifest.find("application").get(ANDROID + "label"), "@string/app_name")

    def test_accepts_isolated_apk(self):
        check_badging(BADGING)
        check_manifest(MANIFEST)

    def test_rejects_release_id(self):
        with self.assertRaises(ValueError):
            check_badging(BADGING.replace(DEBUG_ID, RELEASE_ID))
        with self.assertRaises(ValueError):
            check_manifest(MANIFEST.replace(f'package="{DEBUG_ID}"', f'package="{RELEASE_ID}"'))

    def test_rejects_missing_or_non_test_localized_label(self):
        for bad in (BADGING.replace("반납요정 (Test)", "반납요정"),
                    BADGING.replace("application-label-ko:'반납요정 (Test)'\n", ""),
                    BADGING + "application-label-fr:'Return Fairy'\n"):
            with self.subTest(badging=bad), self.assertRaises(ValueError):
                check_badging(bad)

    def test_rejects_release_authority_or_permission(self):
        for suffix in (".androidx-startup", ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                check_manifest(MANIFEST.replace(DEBUG_ID + suffix, RELEASE_ID + suffix))

    def test_rejects_mixed_authorities(self):
        with self.assertRaises(ValueError):
            check_manifest(MANIFEST.replace(DEBUG_ID + ".androidx-startup",
                                           DEBUG_ID + ".androidx-startup;" + RELEASE_ID + ".files"))

    def test_rejects_shared_uid_or_release_process(self):
        with self.assertRaises(ValueError):
            check_manifest(MANIFEST.replace("<manifest ", f'<manifest android:sharedUserId="{RELEASE_ID}" '))
        with self.assertRaises(ValueError):
            check_manifest(MANIFEST.replace("<application ", f'<application android:process="{RELEASE_ID}" '))

    def test_rejects_incoming_links(self):
        with self.assertRaises(ValueError):
            check_manifest(MANIFEST.replace("<intent-filter>", '<intent-filter><data android:scheme="returnfairy" />'))

    def test_rejects_release_or_backup_enabled_apk(self):
        for before, after in (('debuggable="true"', 'debuggable="false"'),
                              ('allowBackup="false"', 'allowBackup="true"')):
            with self.subTest(before=before), self.assertRaises(ValueError):
                check_manifest(MANIFEST.replace(before, after))


if __name__ == "__main__":
    unittest.main()
