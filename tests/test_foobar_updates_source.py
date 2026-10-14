"""Guard the manual foobar update checker and rolling-release publication contract."""

import re
from pathlib import Path
from unittest import TestCase

ROOT = Path(__file__).parents[1]
MENU = ROOT / "foobar" / "wam_menu.cpp"
UPDATER = ROOT / "foobar" / "wam_updates.cpp"
PROJECT = ROOT / "foobar" / "foo_out_wam.vcxproj"
BUILD = ROOT / ".github" / "workflows" / "build.yml"
RELEASE = ROOT / ".github" / "workflows" / "release.yml"


class FoobarUpdateCheckerTests(TestCase):
    def test_menu_dispatches_exact_update_action(self) -> None:
        menu = MENU.read_text(encoding="utf-8")
        self.assertRegex(
            menu,
            r'(?s)"Check for updates",.*?L"check-updates",\s*false,'
        )
        self.assertRegex(
            menu,
            r'(?s)std::wcscmp\(item.action, L"check-updates"\) == 0\)\s*'
            r'\{\s*wam::check_for_updates\(\);\s*return;\s*\}'
        )

    def test_rollout_publication_precedes_advertised_sha(self) -> None:
        updater = UPDATER.read_text(encoding="utf-8")
        build = BUILD.read_text(encoding="utf-8")
        release = RELEASE.read_text(encoding="utf-8")
        self.assertIn("WAMBRIDGE_BUILD_SHA", updater)
        self.assertIn('"target_commitish"', updater)
        self.assertIn("/releases/tags/latest", updater)
        self.assertIn("WAMBRIDGE_BUILD_SHA", build)
        self.assertIn("$env:GITHUB_SHA", build)
        self.assertLess(
            release.index('gh release upload "$TAG"'),
            release.index('gh release edit "$TAG"'),
            "release must upload the component before advertising its new SHA",
        )
        self.assertLess(
            release.index('gh release edit "$TAG"'),
            release.index('gh api --method PATCH "repos/'),
        )

    def test_checker_never_self_installs_and_worker_joins(self) -> None:
        updater = UPDATER.read_text(encoding="utf-8")
        self.assertIn("WINHTTP_FLAG_SECURE", updater)
        self.assertIn("winhttp.lib", PROJECT.read_text(encoding="utf-8"))
        self.assertIn("ShellExecuteW", updater)
        self.assertIn("MB_YESNO", updater)
        self.assertIn("does not modify or install files automatically", updater)
        self.assertIn("main_thread_callback_manager::get()->add_callback", updater)
        self.assertIn("void on_quit() override", updater)
        self.assertIn("if (owned.joinable()) owned.join();", updater)
        self.assertNotIn(".detach()", updater)
        self.assertNotIn("CreateFileW(", updater)
        self.assertNotIn("MoveFileExW(", updater)

    def test_only_matching_valid_sha_can_show_up_to_date(self) -> None:
        updater = UPDATER.read_text(encoding="utf-8")
        branch = re.search(
            r'if \(valid_sha\(installed\) && installed == m_result\.sha\)\s*'
            r'\{(?P<body>.*?)\breturn;',
            updater,
            flags=re.DOTALL,
        )
        self.assertIsNotNone(branch)
        self.assertIn("WAM Bridge is up to date", branch.group("body"))
        self.assertIn('L"unknown (older or local build)"', updater)
        self.assertIn("foobar/wam_build_sha.h", (ROOT / ".gitignore").read_text())

    def test_missing_or_unuploaded_release_asset_is_rejected(self) -> None:
        updater = UPDATER.read_text(encoding="utf-8")
        self.assertIn('json_string(asset, "state")', updater)
        self.assertIn('std::optional<std::string>("uploaded")', updater)
        self.assertIn("The foobar component asset is not ready.", updater)
