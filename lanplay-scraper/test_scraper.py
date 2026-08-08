import json
import tempfile
import unittest
from io import BytesIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image

import scraper


def make_config(videos: Path, output: Path) -> dict:
    return {
        "paths": {"video_dir": str(videos), "output_dir": str(output)},
        "network": {
            "timeout": 20,
            "delay_min": 0,
            "delay_max": 0,
            "retry": 1,
        },
        "sources": {
            "r18": False,
            "javdb": False,
            "javbus": False,
            "gfriends": False,
        },
        "scrape": {"incremental": True, "local_fallback": True},
        "output": {
            "poster_max_size": 900,
            "cover_max_size": 1600,
            "avatar_size": 400,
            "jpeg_quality": 88,
        },
        "gfriends": {},
    }


class ScraperSecurityTest(unittest.TestCase):
    def test_rejects_credentialed_private_urls(self):
        with patch(
            "scraper.socket.getaddrinfo",
            return_value=[(None, None, None, None, ("127.0.0.1", 80))],
        ):
            with self.assertRaises(RuntimeError):
                scraper.Scraper._pin_public_url("http://example.test/private")
        credential_url = (
            "https://" + "<USER>" + ":" + "<PASSWORD>" + "@example.invalid/"
        )
        with self.assertRaises(RuntimeError):
            scraper.Scraper._pin_public_url(credential_url)
        with self.assertRaises(RuntimeError):
            scraper.Scraper._pin_public_url("file:///etc/passwd")

    def test_accepts_only_globally_routable_resolution(self):
        with patch(
            "scraper.socket.getaddrinfo",
            return_value=[(None, None, None, None, ("93.184.216.34", 443))],
        ):
            pinned, host, sni = scraper.Scraper._pin_public_url(
                "https://example.com:8443/path?q=1"
            )
            self.assertEqual("https://93.184.216.34:8443/path?q=1", pinned)
            self.assertEqual("example.com:8443", host)
            self.assertEqual("example.com", sni)


class ScraperImageTest(unittest.TestCase):
    def test_download_image_fsyncs_writable_stream_and_replaces_atomically(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            videos.mkdir()
            output.mkdir()
            encoded = BytesIO()
            Image.new("RGB", (64, 96), "navy").save(encoded, "JPEG")
            config = make_config(videos, output)
            config["output"]["jpeg_quality"] = 80
            target = output / "posters" / "DEMO-001.jpg"
            with scraper.Scraper(config) as instance, patch.object(
                instance,
                "request",
                return_value=SimpleNamespace(content=encoded.getvalue()),
            ):
                self.assertTrue(instance.download_image("https://example.test/a.jpg", target, "poster"))
            self.assertTrue(instance.valid_image(target))
            self.assertEqual([], list(target.parent.glob("*.tmp")))

    def test_one_cover_download_generates_cover_and_poster(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            videos.mkdir()
            output.mkdir()
            encoded = BytesIO()
            Image.new("RGB", (640, 360), "teal").save(encoded, "JPEG")
            movie = scraper.Movie(
                code="DEMO-001",
                title="Demo",
                cover_url="https://example.test/cover.jpg",
                source="r18",
            )
            with scraper.Scraper(make_config(videos, output)) as instance, patch.object(
                instance,
                "request",
                return_value=SimpleNamespace(content=encoded.getvalue()),
            ) as request:
                value = instance.movie_json(movie)
            self.assertEqual(1, request.call_count)
            self.assertTrue((output / value["cover"]).is_file())
            self.assertTrue((output / value["poster"]).is_file())


class ScraperMetadataTest(unittest.TestCase):
    def test_r18_structured_metadata_and_high_quality_image(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            videos.mkdir()
            output.mkdir()
            response = SimpleNamespace(
                status_code=200,
                url="https://r18.dev/detail/json",
                json=lambda: {
                    "content_id": "demo00001",
                    "title": "DEMO-001 Sample title",
                    "release_date": "2026-01-02",
                    "runtime_minutes": 123,
                    "maker": {"name": "Maker"},
                    "label": {"name": "Label"},
                    "series": {"name": "Series"},
                    "categories": [{"name": "Genre"}],
                    "actresses": [{"name": "Actor"}],
                    "images": {
                        "jacket_image": {
                            "large2": "https://pics.example/original.jpg",
                            "large": "https://pics.example/large.jpg",
                        }
                    },
                },
            )
            with scraper.Scraper(make_config(videos, output)) as instance, patch.object(
                instance,
                "request",
                return_value=response,
            ):
                movie = instance.scrape_r18("DEMO-001")
            self.assertEqual("Sample title", movie.title)
            self.assertEqual(123, movie.runtime_min)
            self.assertEqual(["Actor"], [actor.name for actor in movie.actors])
            self.assertEqual("https://pics.example/original.jpg", movie.cover_url)

    def test_merge_supplements_fields_and_deduplicates_people(self):
        primary = scraper.Movie(
            code="DEMO-001",
            title="Primary",
            actors=[scraper.Actor("Actor")],
            cover_url="https://example/one.jpg",
            source="r18",
        )
        fallback = scraper.Movie(
            code="DEMO-001",
            release_date="2026-01-02",
            studio="Studio",
            actors=[scraper.Actor("actor", avatar_url="https://example/a.jpg")],
            cover_url="https://example/two.jpg",
            source="javdb",
        )
        movie = scraper.Scraper.merge_movies([primary, fallback])
        self.assertEqual("Primary", movie.title)
        self.assertEqual("2026-01-02", movie.release_date)
        self.assertEqual(1, len(movie.actors))
        self.assertEqual("https://example/a.jpg", movie.actors[0].avatar_url)
        self.assertEqual(["https://example/one.jpg", "https://example/two.jpg"], movie.cover_urls)
        self.assertEqual("r18+javdb", movie.source)

    def test_recognizes_fc2_without_confusing_ppv_as_studio(self):
        self.assertEqual(
            "FC2-PPV-1234567",
            scraper.recognize_code("site.example@FC2-PPV-1234567.mp4"),
        )


class ScraperIndexTest(unittest.TestCase):
    def test_scan_root_is_relative_to_metadata_share_root(self):
        with tempfile.TemporaryDirectory() as root:
            share = Path(root) / "share"
            nested_videos = share / "媒体" / "影片"
            output = share / ".lanplay_meta"
            nested_videos.mkdir(parents=True)
            output.mkdir()

            with scraper.Scraper(make_config(nested_videos, output)) as instance:
                instance.write_outputs([], {}, [], 0)
            nested_index = json.loads(
                (output / "index.json").read_text(encoding="utf-8")
            )
            self.assertEqual("媒体/影片", nested_index["scanRoot"])
            self.assertTrue(nested_index["complete"])

            with scraper.Scraper(make_config(share, output)) as instance:
                instance.write_outputs([], {}, [], 0)
            root_index = json.loads(
                (output / "index.json").read_text(encoding="utf-8")
            )
            self.assertEqual("", root_index["scanRoot"])
            self.assertTrue(root_index["complete"])

    def test_checkpoint_is_marked_incomplete(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            videos.mkdir()
            output.mkdir()

            with scraper.Scraper(make_config(videos, output)) as instance:
                instance.write_outputs([], {}, [], 0, complete=False)

            index = json.loads((output / "index.json").read_text(encoding="utf-8"))
            self.assertFalse(index["complete"])

    def test_recursive_index_uses_relative_paths_and_prunes_stale_items(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            nested = videos / "系列一"
            nested.mkdir(parents=True)
            output.mkdir()
            current = nested / "ABC-123.mkv"
            current.write_bytes(b"")
            (output / "index.json").write_text(
                json.dumps(
                    {
                        "items": {
                            "系列一/ABC-123.mkv": {"code": "ABC-123"},
                            "已删除.mkv": {"code": "OLD-001"},
                        }
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            config = make_config(videos, output)
            with scraper.Scraper(config) as instance:
                self.assertEqual(0, instance.run())
            index = json.loads((output / "index.json").read_text(encoding="utf-8"))
            self.assertEqual(["系列一/ABC-123.mkv"], list(index["items"]))

    def test_subtitle_files_are_never_scanned_or_indexed(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            videos.mkdir()
            output.mkdir()
            (videos / "ABC-123.mkv").write_bytes(b"")
            (videos / "ABC-123.srt").write_text("subtitle", encoding="utf-8")
            (videos / "ABC-123.ass").write_text("subtitle", encoding="utf-8")
            movie_dir = videos / "电影"
            movie_dir.mkdir()
            (movie_dir / "MOVIE-001.mkv").write_bytes(b"")
            (output / "index.json").write_text(
                json.dumps({"items": {"ABC-123.mkv": {"code": "ABC-123"}}}),
                encoding="utf-8",
            )
            with scraper.Scraper(make_config(videos, output)) as instance:
                self.assertEqual(0, instance.run())
            index = json.loads((output / "index.json").read_text(encoding="utf-8"))
            self.assertEqual(1, index["stats"]["total"])
            self.assertEqual(["ABC-123.mkv"], list(index["items"]))

    def test_configured_directory_exclusion_is_case_insensitive(self):
        with tempfile.TemporaryDirectory() as root:
            videos = Path(root) / "videos"
            videos.mkdir()
            target = videos / "Movies" / "A.mkv"
            target.parent.mkdir()
            target.write_bytes(b"")
            self.assertTrue(
                scraper.path_is_excluded(target, videos, {"movies"})
            )

    def test_top_level_include_patterns_limit_scope(self):
        with tempfile.TemporaryDirectory() as root:
            videos = Path(root) / "videos"
            included = videos / "AV-分组" / "A.mkv"
            excluded = videos / "其他" / "B.mkv"
            included.parent.mkdir(parents=True)
            excluded.parent.mkdir(parents=True)
            included.write_bytes(b"")
            excluded.write_bytes(b"")
            self.assertTrue(
                scraper.path_is_in_scope(
                    included,
                    videos,
                    ["av*", "收藏*"],
                    {"电影"},
                )
            )
            self.assertFalse(
                scraper.path_is_in_scope(
                    excluded,
                    videos,
                    ["av*", "收藏*"],
                    {"电影"},
                )
            )

    def test_relative_manual_override_precedes_legacy_code_key(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            videos.mkdir()
            output.mkdir()
            with scraper.Scraper(make_config(videos, output)) as instance:
                instance.manual = {
                    "系列/错误文件名.mkv": {
                        "code": "RIGHT-002",
                        "title_zh": "按路径覆盖",
                    },
                    "WRONG-001": {"title_zh": "旧番号覆盖"},
                }
                movie = instance.scrape("WRONG-001", "系列/错误文件名.mkv")
            self.assertIsNotNone(movie)
            self.assertEqual("RIGHT-002", movie.code)
            self.assertEqual("按路径覆盖", movie.title_zh)

    def test_unrecognized_filename_can_be_recovered_by_relative_manual_code(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            nested = videos / "系列"
            nested.mkdir(parents=True)
            output.mkdir()
            (nested / "完全无法识别.mkv").write_bytes(b"")
            with scraper.Scraper(make_config(videos, output)) as instance:
                instance.manual = {
                    "系列/完全无法识别.mkv": {
                        "code": "DEMO-009",
                        "title_zh": "手工资料",
                    }
                }
                self.assertEqual(0, instance.run())
            index = json.loads((output / "index.json").read_text(encoding="utf-8"))
            item = index["items"]["系列/完全无法识别.mkv"]
            self.assertEqual("DEMO-009", item["code"])
            self.assertEqual("手工资料", item["titleZh"])

    def test_actor_aggregate_enriches_later_values_and_deduplicates_movies(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            videos.mkdir()
            output.mkdir()
            items = {
                "A.mkv": {
                    "actors": [{"name": "Actor", "nameZh": None, "avatar": None}]
                },
                "B.mkv": {
                    "actors": [
                        {
                            "name": "Actor",
                            "nameZh": "演员",
                            "avatar": "actors/actor.jpg",
                        },
                        {
                            "name": "Actor",
                            "nameZh": "演员",
                            "avatar": "actors/actor.jpg",
                        },
                    ]
                },
            }
            with scraper.Scraper(make_config(videos, output)) as instance:
                instance.write_outputs([], items, [], 2)
            actors = json.loads((output / "actors.json").read_text(encoding="utf-8"))
            actor = actors["actors"]["Actor"]
            self.assertEqual("演员", actor["nameZh"])
            self.assertEqual("actors/actor.jpg", actor["avatar"])
            self.assertEqual(["A.mkv", "B.mkv"], actor["movies"])

    def test_actor_asset_name_disambiguates_sanitized_and_truncated_collisions(self):
        self.assertNotEqual(
            scraper.Scraper.safe_actor_name("A:B"),
            scraper.Scraper.safe_actor_name("A?B"),
        )
        common = "演员" * 80
        self.assertNotEqual(
            scraper.Scraper.safe_actor_name(common + "甲"),
            scraper.Scraper.safe_actor_name(common + "乙"),
        )
        self.assertEqual(
            scraper.Scraper.safe_actor_name("同名"),
            scraper.Scraper.safe_actor_name("同名"),
        )

    def test_asset_check_rejects_missing_and_escaped_paths(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            videos.mkdir()
            output.mkdir()
            with scraper.Scraper(make_config(videos, output)) as instance:
                self.assertFalse(
                    instance.item_assets_complete(
                        {"cover": "covers/missing.jpg", "poster": "../outside.jpg"}
                    )
                )

    def test_output_cleanup_removes_only_unreferenced_generated_jpg(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            videos.mkdir()
            (output / "covers").mkdir(parents=True)
            encoded = BytesIO()
            Image.new("RGB", (64, 64), "green").save(encoded, "JPEG")
            keep = output / "covers" / "keep.jpg"
            orphan = output / "covers" / "orphan.jpg"
            note = output / "covers" / "note.txt"
            keep.write_bytes(encoded.getvalue())
            orphan.write_bytes(encoded.getvalue())
            note.write_text("keep", encoding="utf-8")
            items = {
                "A.mkv": {
                    "cover": "covers/keep.jpg",
                    "poster": None,
                    "actors": [],
                }
            }
            with scraper.Scraper(make_config(videos, output)) as instance:
                instance.write_outputs([], items, [], 1)
            self.assertTrue(keep.exists())
            self.assertFalse(orphan.exists())
            self.assertTrue(note.exists())

    def test_failed_contract_is_text_file(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            videos = root_path / "videos"
            output = root_path / "meta"
            videos.mkdir()
            output.mkdir()
            with scraper.Scraper(make_config(videos, output)) as instance:
                instance.write_outputs(
                    [],
                    {},
                    [{"file": "无法识别.mkv", "reason": "番号未识别"}],
                    0,
                )
            self.assertEqual(
                "无法识别.mkv\t番号未识别",
                (output / "failed.txt").read_text(encoding="utf-8"),
            )
            self.assertFalse((output / "failed.json").exists())


class ScraperConfigTest(unittest.TestCase):
    def test_accepts_documented_numeric_boundaries(self):
        config = make_config(Path("videos"), Path("output"))
        scraper.validate_config(config)

    def test_rejects_invalid_numeric_values_and_reversed_delay(self):
        cases = (
            ("network", "timeout", 0),
            ("network", "retry", 1.5),
            ("output", "jpeg_quality", 101),
            ("output", "avatar_size", True),
        )
        for section, key, value in cases:
            with self.subTest(section=section, key=key, value=value):
                config = make_config(Path("videos"), Path("output"))
                config[section][key] = value
                with self.assertRaises(RuntimeError):
                    scraper.validate_config(config)
        config = make_config(Path("videos"), Path("output"))
        config["network"]["delay_min"] = 3
        config["network"]["delay_max"] = 1
        with self.assertRaises(RuntimeError):
            scraper.validate_config(config)


if __name__ == "__main__":
    unittest.main()
