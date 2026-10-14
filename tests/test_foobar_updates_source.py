"""Pin the manual foobar update checker to the rolling-release contract."""

from pathlib import Path
from unittest import TestCase

ROOT = Path(__file__).parents[1]
MENU = ROOT / "foobar" / "wam_menu.cpp"
UPDATER = ROOT / "foobar" / "wam_updates.cpp"
PROJECT = ROOT / "foobar" / "foo_out_wam.vcxproj"
BUILD = ROOT / ".github" / "workflows" / "build.yml"
RELEASE = ROOT / ".github" / "workflows" / "release.yml"


class FoobarUpdateCheckerTests(TestCase):
    def test_menu_checks_updates_without_entering_speaker_control_queue(self) -> None:
        menu = MENU.read_text(encoding="utf-8")
        self.assertIn('"Check for updates"', menu)
        self.assertIn('L"check-updates"', menu)
        self.assertIn("wam::check_for_updates();\n            return;", menu)

    def test_release_identity_uses_commit_not_mutable_version(self) -> None:
        updater = UPDATER.read_text(encoding="utf-8")
        build = BUILD.read_text(encoding="utf-8")
        release = RELEASE.read_text(encoding="utf-8")
        self.assertIn("WAMBRIDGE_BUILD_SHA", updater)
        self.assertIn('"target_commitish"', updater)
        self.assertIn("/releases/tags/latest", updater)
        self.assertIn("WAMBRIDGE_BUILD_SHA", build)
        self.assertIn("$env:GITHUB_SHA", build)
        self.assertIn('gh release edit "$TAG"', release)
        self.assertIn('gh release upload "$TAG"', release)

    def test_checker_never_self_installs(self) -> None:
        updater = UPDATER.read_text(encoding="utf-8")
        self.assertIn("std::thread(check_worker).detach()", updater)
        self.assertIn("WINHTTP_FLAG_SECURE", updater)
        self.assertIn("winhttp", PROJECT.read_text(encoding="utf-8"))
        self.assertIn("ShellExecuteW", updater)
        self.assertIn("MB_YESNO", updater)
        self.assertIn("does not modify or install files automatically", updater)
        self.assertNotIn("CreateFileW(", updater)
        self.assertNotIn("MoveFileExW(", updater)

    def test_missing_local_build_id_cannot_be_called_current(self) -> None:
        updater = UPDATER.read_text(encoding="utf-8")
        self.assertIn('valid_sha(installed) && installed == latest.sha', updater)
        self.assertIn('L"unknown (older or local build)"', updater)
        self.assertIn("foobar/wam_build_sha.h", (ROOT / ".gitignore").read_text())
