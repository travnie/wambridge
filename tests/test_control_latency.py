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

    def test_csv_header_is_parsed_not_prefix_matched(self) -> None:
        for data in (
            '"command","metric","ms"\npause,audible_ms,5000\n',
            "metric,command,ms\naudible_ms,pause,5000\n",
            "\ufeffcommand,metric,ms\r\npause,audible_ms,5000\r\n",
        ):
            with self.subTest(data=data):
                self.assertEqual(read_samples(data), {("pause", "audible_ms"): [5000.0]})

    def test_audible_rows_accept_stop_and_skip_but_dispatch_rows_do_not(self) -> None:
        parsed = read_samples("command,metric,ms\nstop,audible_ms,900\nskip,audible_ms,1200\n")
        self.assertEqual(
            parsed, {("stop", "audible_ms"): [900.0], ("skip", "audible_ms"): [1200.0]}
        )
        with self.assertRaises(ValueError):
            read_samples("command,metric,ms\nstop,dispatch_ms,10\n")

    def test_reject_overflowing_log_duration(self) -> None:
        huge = "9" * 400
        with self.assertRaises(ValueError):
            read_samples(f"INFO: WAMBRIDGE CONTROL_TIMING command=pause dispatch_ms={huge}.0\n")

    def test_reject_invalid_and_missing_measurements(self) -> None:
        for data in (
            "",
            "nothing relevant",
            "command,metric,ms\npause,audible_ms,-1\n",
            "command,metric,ms\npause,audible_ms,NaN\n",
            "command,metric,ms\npause,unknown,200\n",
            "command,metric,ms\npause,audible_ms,infinity\n",
            "command,metric,ms\npause,audible_ms,500,extra\n",
            "command,metric,ms,extra\npause,audible_ms,500,data\n",
            "command,metric,ms\npause,audible_ms\n",
        ):
            with self.subTest(data=data), self.assertRaises(ValueError):
                read_samples(data)

    def test_nearest_rank_p95_never_extrapolates(self) -> None:
        self.assertEqual(stats([2.0]), (1, 2.0, 2.0))
        self.assertEqual(stats([1.0, 2.0, 3.0, 4.0]), (4, 2.5, 4.0))
        # 40 samples: nearest-rank p95 is the 38th value, below the maximum
        self.assertEqual(stats([float(n) for n in range(1, 41)]), (40, 20.5, 38.0))

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
