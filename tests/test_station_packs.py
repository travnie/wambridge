from unittest import TestCase

from wambridge.station_packs import get_station_pack, station_pack_names
from wambridge.stations import StationError


class StationPackTests(TestCase):
    def test_top3_contains_user_stations_with_fallbacks(self) -> None:
        stations = get_station_pack("TOP3")

        self.assertEqual(
            [station.alias for station in stations],
            ["bbc1", "trojka", "czworka"],
        )
        self.assertEqual([len(station.all_urls) for station in stations], [4, 3, 3])

    def test_favorites_contains_top3_and_extended_stations(self) -> None:
        stations = get_station_pack("favorites")
        aliases = [station.alias for station in stations]

        self.assertEqual(len(stations), 18)
        self.assertEqual(aliases[:4], ["travny", "bbc1", "trojka", "czworka"])
        self.assertEqual(
            stations[0].all_urls,
            (
                "https://travny-radio.taild44c6c.ts.net/radio.mp3",
                "https://radio.bartek.lol/radio.mp3",
            ),
        )
        self.assertIn("radioparadise", aliases)
        self.assertIn("bbc6", aliases)
        self.assertIn("radiozet", aliases)
        self.assertIn("streamingsoundtracks", aliases)

    def test_verified_tunein_ids_keep_static_url_fallbacks(self) -> None:
        stations = {
            station.alias: station for station in get_station_pack("favorites")
        }

        self.assertEqual(
            {
                "radioparadise": "s13606",
                "electroswing": "s162771",
                "bbc6": "s44491",
                "minimalmix": "s151855",
                "kaszebe": "s77862",
                "cinemix": "s96408",
                "streamingsoundtracks": "s1562",
            },
            {
                alias: stations[alias].tunein_id
                for alias in (
                    "radioparadise",
                    "electroswing",
                    "bbc6",
                    "minimalmix",
                    "kaszebe",
                    "cinemix",
                    "streamingsoundtracks",
                )
            },
        )
        self.assertTrue(
            all(stations[alias].all_urls for alias in (
                "radioparadise",
                "electroswing",
                "bbc6",
                "minimalmix",
                "kaszebe",
                "cinemix",
                "streamingsoundtracks",
            ))
        )

    def test_favorite_fallbacks_preserve_order(self) -> None:
        stations = {
            station.alias: station for station in get_station_pack("favorites")
        }

        self.assertEqual(
            stations["bbc1"].all_urls,
            (
                "https://as-hls-ww-live.akamaized.net/pool_01505109/live/ww/bbc_radio_one/bbc_radio_one.isml/bbc_radio_one-audio=320000.norewind.m3u8",
                "https://a.files.bbci.co.uk/ms6/live/3441A116-B12E-4D2F-ACA8-C1984642FA4B/audio/simulcast/hls/nonuk/audio_syndication_low_sbr_v1/aks/bbc_radio_one.m3u8",
                "https://lsn.lv/bbcradio.m3u8?station=bbc_radio_one&bitrate=320000",
                "https://a.files.bbci.co.uk/ms6/live/3441A116-B12E-4D2F-ACA8-C1984642FA4B/audio/simulcast/hls/nonuk/pc_hd_abr_v2/ak/bbc_radio_one.m3u8",
            ),
        )
        self.assertEqual(
            stations["trojka"].all_urls,
            (
                "http://41.dktr.pl:8000/trojka.ogg",
                "http://41.dktr.pl:8000/trojka2.ogg",
                "https://stream13.polskieradio.pl/pr3/pr3.sdp/playlist.m3u8",
            ),
        )
        self.assertEqual(
            stations["czworka"].all_urls,
            (
                "http://stream3.polskieradio.pl:8906/;stream",
                "http://mp3.polskieradio.pl:8956/;",
                "https://stream14.polskieradio.pl/pr4/pr4.sdp/playlist.m3u8",
            ),
        )
        self.assertEqual(
            stations["electroswing"].all_urls,
            (
                "https://streamer.radio.co/s2c3cc784b/listen",
                "https://streamer.radio.co:80/s2c3cc784b/listen",
            ),
        )
        self.assertEqual(
            stations["rmfmaxxx"].all_urls,
            (
                "https://rs201-krk.rmfstream.pl/rmf_maxxx",
                "http://195.150.20.7/rmf_maxxx",
            ),
        )

    def test_lists_available_packs(self) -> None:
        self.assertEqual(station_pack_names(), ("favorites", "top3"))

    def test_rejects_unknown_pack(self) -> None:
        with self.assertRaisesRegex(
            StationError,
            "available: favorites, top3",
        ):
            get_station_pack("missing")
