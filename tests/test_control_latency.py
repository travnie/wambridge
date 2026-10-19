"""Control latency is diagnostics, not a playback success oracle."""

from __future__ import annotations

import unittest

from wambridge.control_latency import comparison_csv, read_samples, stats


class ControlLatencyTests(unittest.TestCase):
    def test_parse_logged_callback_durations(self) -> None:
        parsed = read_samples(
            "INFO: WAMBRIDGE CONTROL_TIMING command=pause dispatch_ms=124.250\n"
            "INFO: WAMBRIDGE CONTROL_TIMING command=volume dispatch_ms=0.500\n"
            "WARNING: unrelated error\n"
            "INFO: WAMBRIDGE CONTROL_TIMING command=pause dispatch_ms=125.750\n"
        )
        self.assertEqual(parsed[("pause", "dispatch_ms")], [124.25, 125.75])
        self.assertEqual(parsed[("volume", "dispatch_ms")], [0.5])

    def test_parse_manual_audible_measurements(self) -> None:
        parsed = read_samples(
            "command,metric,ms\npause,audible_ms,5000\npause,audible_ms,5200\n"
        )
        self.assertEqual(parsed, {("pause", "audible_ms"): [5000.0, 5200.0]})

    def test_reject_invalid_and_missing_measurements(self) -> None:
        for data in (
            "",
            "nothing relevant",
            "command,metric,ms\npause,audible_ms,-1\n",
            "command,metric,ms\npause,audible_ms,NaN\n",
            "command,metric,ms\npause,unknown,200\n",
            "command,metric,ms\npause,audible_ms,infinity\n",
        ):
            with self.subTest(data=data), self.assertRaises(ValueError):
                read_samples(data)

    def test_nearest_rank_p95_never_extrapolates(self) -> None:
        self.assertEqual(stats([2.0]), (1, 2.0, 2.0))
        self.assertEqual(stats([1.0, 2.0, 3.0, 4.0]), (4, 2.5, 4.0))

    def test_comparison_preserves_distinct_scopes_and_missing_baseline(self) -> None:
        before = read_samples("command,metric,ms\npause,audible_ms,5000\n")
        after = read_samples(
            "command,metric,ms\npause,audible_ms,1000\n"
            "pause,dispatch_ms,200\n"
        )
        report = comparison_csv(before, after)
        self.assertIn("pause,audible_ms,1,5000.000,5000.000,1,1000.000,1000.000,-4000.000", report)
        self.assertIn("pause,dispatch_ms,0,,,1,200.000,200.000,", report)


if __name__ == "__main__":
    unittest.main()
