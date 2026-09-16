from io import BytesIO
from subprocess import CompletedProcess
from unittest import TestCase
from unittest.mock import Mock, patch

from wambridge.stream import (
    AudioStreamServer,
    StreamError,
    _read_chunk,
    continuous_source,
)


class ReadOnePipe:
    def __init__(self) -> None:
        self.requested_size: int | None = None

    def read1(self, size: int) -> bytes:
        self.requested_size = size
        return b"fLaC"

    def read(self, _size: int) -> bytes:
        raise AssertionError("read() should not be used when read1() is available")


class AudioStreamServerTests(TestCase):
    @patch("wambridge.stream.shutil.which", return_value="C:/ffmpeg/bin/ffmpeg.exe")
    @patch("wambridge.stream.subprocess.run")
    def test_prepare_rejects_invalid_audio(self, run_mock, _which_mock) -> None:
        run_mock.return_value = CompletedProcess([], 1, stderr=b"Invalid data")
        server = AudioStreamServer("broken.opus")
        try:
            with self.assertRaisesRegex(StreamError, "Invalid data"):
                server.prepare()
        finally:
            server.close()

    @patch("wambridge.stream.shutil.which", return_value="C:/ffmpeg/bin/ffmpeg.exe")
    def test_audio_stays_gated_until_released(self, _which_mock) -> None:
        server = AudioStreamServer("track.opus")
        try:
            self.assertFalse(server.audio_released.is_set())
            server.release_audio()
            self.assertTrue(server.audio_released.is_set())
        finally:
            server.close()

    @patch("wambridge.stream.shutil.which", return_value="C:/ffmpeg/bin/ffmpeg.exe")
    @patch("wambridge.stream.subprocess.Popen")
    def test_continuous_source_reports_unexpected_eof(
        self,
        popen_mock,
        _which_mock,
    ) -> None:
        process = Mock()
        # A bare "fLaC" magic is a header, not audio: the startup payload now
        # demands a real frame on this path too, so the fixture has to carry a
        # last metadata block and a frame sync.
        process.stdout = BytesIO(
            b"fLaC" + bytes([0x80, 0, 0, 34]) + bytes(34) + b"\xff\xf8\x00\x00"
        )
        process.poll.return_value = 0
        process.wait.return_value = 0
        process.returncode = 0
        popen_mock.return_value = process

        with continuous_source("https://radio.example/live"):
            server = AudioStreamServer("https://radio.example/live")
        try:
            with self.assertRaisesRegex(StreamError, "ended unexpectedly"):
                server._serve_audio(BytesIO())
        finally:
            server.close()

    @patch("wambridge.stream.shutil.which", return_value="C:/ffmpeg/bin/ffmpeg.exe")
    def test_continuous_marker_is_scoped(self, _which_mock) -> None:
        with continuous_source("https://radio.example/live"):
            live_server = AudioStreamServer("https://radio.example/live")
        regular_server = AudioStreamServer("https://radio.example/live")
        try:
            self.assertTrue(live_server.continuous)
            self.assertFalse(regular_server.continuous)
        finally:
            live_server.close()
            regular_server.close()

    def test_reads_available_pipe_data_without_filling_buffer(self) -> None:
        pipe = ReadOnePipe()

        chunk = _read_chunk(pipe, 4096)  # type: ignore[arg-type]

        self.assertEqual(chunk, b"fLaC")
        self.assertEqual(pipe.requested_size, 4096)


class SecondRequestTests(TestCase):
    """An extra GET must not end the session the first one owns.

    The handler used to send 200 before anything reserved the stream, so a
    refusal raised inside `_serve_audio` landed in the shared `except`/`finally`
    - writing `owner.error` and setting `request_finished`, which is exactly
    what the PCM main loop watches to decide the session is over. The existing
    `test_pcm_stream` cases exercise the guard function itself and never go
    through the HTTP handler, so they stayed green while this was broken.
    """

    @patch("wambridge.stream.shutil.which", return_value="C:/ffmpeg/bin/ffmpeg.exe")
    def test_second_get_is_refused_without_ending_the_first(self, _which_mock) -> None:
        import threading
        import urllib.error
        import urllib.request

        server = AudioStreamServer("track.opus")
        serving = threading.Event()
        holding = threading.Event()

        def hold_the_stream(_output) -> None:
            serving.set()
            holding.wait(timeout=10)

        server._serve_audio = hold_the_stream  # type: ignore[method-assign]
        url = f"http://127.0.0.1:{server.port}{server.path}"
        first_status: list[int] = []

        def first_request() -> None:
            with urllib.request.urlopen(url, timeout=10) as response:  # noqa: S310
                first_status.append(response.status)
                response.read()

        try:
            server.start()
            server.release_audio()
            owner = threading.Thread(target=first_request, daemon=True)
            owner.start()
            self.assertTrue(serving.wait(timeout=10), "first request never began serving")

            # Read the status either way rather than asserting on the exception
            # type: without the claim the extra GET is answered 200, and this
            # way that shows up as a plain 200 != 409 instead of a hang.
            try:
                with urllib.request.urlopen(url, timeout=10) as extra:  # noqa: S310
                    second_status = extra.status
            except urllib.error.HTTPError as refused:
                second_status = refused.code

            # Sample the owner's state while its stream is still open, but do
            # not assert yet - a failed assertion here would leave the serving
            # thread blocked and turn a clear failure into a stuck test.
            error_after_refusal = server.error
            finished_after_refusal = server.request_finished.is_set()

            holding.set()
            owner.join(timeout=10)

            self.assertEqual(second_status, 409)
            self.assertIsNone(error_after_refusal)
            self.assertFalse(finished_after_refusal)
            self.assertEqual(first_status, [200])
        finally:
            holding.set()
            server.close()
