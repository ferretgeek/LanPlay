from __future__ import annotations

import argparse
import fnmatch
import hashlib
import ipaddress
import json
import logging
import math
import os
import random
import re
import shutil
import socket
import subprocess
import sys
import time
import tomllib
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from io import BytesIO
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import quote, urljoin, urlsplit, urlunsplit

import httpx
from bs4 import BeautifulSoup
from PIL import Image, ImageOps

SCRIPT_DIR = Path(__file__).resolve().parent

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

VIDEO_EXTENSIONS = {
    ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".ts", ".m2ts", ".webm", ".flv"
}
CODE_PATTERNS = (
    re.compile(r"(?i)(?:FC2[-_\s]?(?:PPV[-_\s]?)?)(\d{5,8})"),
    re.compile(r"(?i)(?:^|[^A-Z0-9])([A-Z]{2,10})[-_\s]?(\d{2,6})(?:[^A-Z0-9]|$)"),
    re.compile(r"(?i)(\d{6})[-_\s]?(\d{2,4})"),  # FC2 等纯数字前缀的兜底
)
MAX_HTML_BYTES = 4 * 1024 * 1024
MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_IMAGE_BYTES = 24 * 1024 * 1024
MAX_IMAGE_PIXELS = 40_000_000
MAX_REDIRECTS = 5

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136 Safari/537.36"
)


@dataclass
class Actor:
    name: str
    name_zh: str | None = None
    avatar_url: str | None = None
    avatar: str | None = None
    is_main: bool = False


@dataclass
class Movie:
    code: str
    title: str | None = None
    title_zh: str | None = None
    release_date: str | None = None
    runtime_min: int | None = None
    studio: str | None = None
    label: str | None = None
    series: str | None = None
    genres: list[str] = field(default_factory=list)
    actors: list[Actor] = field(default_factory=list)
    poster_url: str | None = None
    cover_url: str | None = None
    poster_urls: list[str] = field(default_factory=list, repr=False)
    cover_urls: list[str] = field(default_factory=list, repr=False)
    source: str | None = None


def recognize_code(filename: str) -> str | None:
    stem = Path(filename).stem.upper()
    # 下载站水印经常形如 source.example@，必须先清掉，
    # 否则可能把域名中的字母和数字错当成影片编号。
    stem = re.sub(r"(?i)[A-Z0-9-]+\.(?:COM|NET|ORG|CC|TV|ME)@?", " ", stem)
    stem = re.sub(r"(?i)(?:4KS?|FHD|UHD|1080P?|2160P?|CH|UNCENSORED)", " ", stem)
    for pattern in CODE_PATTERNS:
        match = pattern.search(stem)
        if not match:
            continue
        if len(match.groups()) == 1:
            return f"FC2-PPV-{match.group(1)}"
        prefix, number = match.group(1), match.group(2)
        if prefix.isdigit():
            return f"{prefix}-{number}"
        return f"{prefix.upper()}-{number}"
    return None


def safe_error(exc: BaseException) -> str:
    """日志只保留可排错信息，不回显查询编号、URL 或本机路径。"""
    if isinstance(exc, httpx.HTTPStatusError):
        return f"HTTP {exc.response.status_code}"
    value = re.sub(r"https?://\S+", "<远端地址>", str(exc))
    value = re.sub(r"(?i)\b[A-Z]{2,12}(?:-\w+)?-\d{2,8}\b", "<编号>", value)
    value = re.sub(r"(?:[A-Za-z]:\\|/)[^\r\n\t]+", "<本机路径>", value)
    return (value.strip() or exc.__class__.__name__)[:180]


def clean_local_title(filename: str) -> str:
    value = Path(filename).stem
    if "@" in value:
        value = value.rsplit("@", 1)[-1]
    value = re.sub(r"[_]+", " ", value)
    value = re.sub(r"\s+", " ", value).strip(" .-_")
    return value or "本地视频"


def path_is_excluded(path: Path, root: Path, excluded_names: set[str]) -> bool:
    try:
        relative = path.relative_to(root)
    except ValueError:
        return True
    return any(part.casefold() in excluded_names for part in relative.parts[:-1])


def path_is_in_scope(
    path: Path,
    root: Path,
    include_top_level_patterns: list[str],
    excluded_names: set[str],
) -> bool:
    try:
        relative = path.relative_to(root)
    except ValueError:
        return False
    if path_is_excluded(path, root, excluded_names):
        return False
    if not include_top_level_patterns:
        return True
    if len(relative.parts) < 2:
        return False
    top_level = relative.parts[0].casefold()
    return any(
        fnmatch.fnmatchcase(top_level, pattern.casefold())
        for pattern in include_top_level_patterns
    )


def text(node: Any) -> str | None:
    value = node.get_text(" ", strip=True) if node else ""
    return re.sub(r"\s+", " ", value).strip() or None


def clean_title(value: str | None, code: str) -> str | None:
    if not value:
        return None
    value = re.sub(re.escape(code), "", value, flags=re.I)
    return re.sub(r"\s+", " ", value).strip(" -|") or None


@contextmanager
def output_lock(path: Path):
    """阻止两个刮削进程同时改写同一输出目录。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    stream = path.open("a+b")
    try:
        stream.seek(0)
        if stream.read(1) == b"":
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            try:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as exc:
                raise RuntimeError("另一个刮削任务正在使用这个输出目录") from exc
        else:
            import fcntl
            try:
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                raise RuntimeError("另一个刮削任务正在使用这个输出目录") from exc
        yield
    finally:
        try:
            stream.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
        finally:
            stream.close()


class Scraper:
    def __init__(self, config: dict[str, Any], force: bool = False) -> None:
        self.cfg = config
        paths = config["paths"]
        self.video_dir = Path(paths["video_dir"]).expanduser().resolve()
        self.output_dir = Path(paths["output_dir"]).expanduser().resolve()
        self.force = force
        net = config.get("network", {})
        proxy = str(net.get("proxy", "")).strip() or None
        self.timeout = float(net.get("timeout", 20))
        self.delay_min = float(net.get("delay_min", 1.0))
        self.delay_max = float(net.get("delay_max", 3.0))
        self.retry = max(1, int(net.get("retry", 3)))
        self.client = httpx.Client(
            headers={"User-Agent": USER_AGENT, "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.6"},
            timeout=self.timeout,
            proxy=proxy,
            trust_env=False,
            follow_redirects=False,
            http2=True,
        )
        self.manual = self._read_toml(Path(__file__).with_name("manual.toml")).get("movies", {})
        self.index: dict[str, Any] = {}
        self.gfriends: dict[str, str] | None = None
        performance = config.get("performance", {})
        self.workers = max(1, min(8, int(performance.get("workers", 3))))
        self.checkpoint_every = max(1, int(performance.get("checkpoint_every", 5)))
        self.cache_days = max(1, int(performance.get("cache_days", 7)))
        self.cache_dir = self.output_dir / ".cache"
        self.movie_cache_dir = self.cache_dir / "movies"
        excluded = config.get("filters", {}).get(
            "exclude_directory_names",
            ["电影", "movie", "movies"],
        )
        self.include_top_level_patterns = [
            str(value).strip()
            for value in config.get("filters", {}).get(
                "include_top_level_patterns",
                [],
            )
            if str(value).strip()
        ]
        self.excluded_directory_names = {
            str(value).strip().casefold()
            for value in excluded
            if str(value).strip()
        }

    @staticmethod
    def _read_toml(path: Path) -> dict[str, Any]:
        if not path.exists():
            return {}
        with path.open("rb") as fh:
            return tomllib.load(fh)

    @staticmethod
    def _pin_public_url(url: str) -> tuple[str, str, str]:
        parsed = urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise RuntimeError("只允许访问公开的 http/https 地址")
        if parsed.username or parsed.password:
            raise RuntimeError("URL 不允许包含用户名或密码")
        try:
            addresses = {
                item[4][0].split("%", 1)[0]
                for item in socket.getaddrinfo(parsed.hostname, parsed.port or 443, type=socket.SOCK_STREAM)
            }
        except OSError as exc:
            raise RuntimeError(f"无法解析远端主机：{parsed.hostname}") from exc
        if not addresses:
            raise RuntimeError("远端主机没有可用地址")
        public_addresses: list[ipaddress.IPv4Address | ipaddress.IPv6Address] = []
        for address in addresses:
            ip = ipaddress.ip_address(address)
            if not ip.is_global:
                raise RuntimeError("已拒绝访问本机、局域网、链路本地或保留地址")
            public_addresses.append(ip)
        # 实际连接改用已经验证过的 IP，避免验证后由 httpx 再次 DNS 解析造成 rebinding。
        selected = sorted(public_addresses, key=lambda item: (item.version, int(item)))[0]
        pinned_host = f"[{selected}]" if selected.version == 6 else str(selected)
        port = f":{parsed.port}" if parsed.port is not None else ""
        pinned = urlunsplit((parsed.scheme, f"{pinned_host}{port}", parsed.path, parsed.query, ""))
        return pinned, parsed.netloc, parsed.hostname

    def request(
        self,
        url: str,
        max_bytes: int = MAX_HTML_BYTES,
        *,
        allow_not_found: bool = False,
        polite: bool = True,
    ) -> httpx.Response:
        last: Exception | None = None
        for attempt in range(self.retry):
            # 查询页面遵守随机间隔；静态图片首请求直连，失败重试仍指数退避。
            if attempt or (polite and self.delay_max > 0):
                time.sleep(random.uniform(self.delay_min, self.delay_max) * (2 ** attempt))
            try:
                current = url
                for _ in range(MAX_REDIRECTS + 1):
                    pinned, original_host, sni_hostname = self._pin_public_url(current)
                    parsed = urlsplit(current)
                    headers = {
                        "Host": original_host,
                        "Referer": f"{parsed.scheme}://{parsed.netloc}/",
                    }
                    with self.client.stream(
                        "GET",
                        pinned,
                        headers=headers,
                        extensions={"sni_hostname": sni_hostname},
                    ) as streamed:
                        if streamed.status_code in {301, 302, 303, 307, 308}:
                            location = streamed.headers.get("location")
                            if not location:
                                raise RuntimeError("重定向缺少 Location")
                            current = urljoin(current, location)
                            continue
                        if streamed.status_code in (429, 503):
                            raise httpx.HTTPStatusError(
                                f"服务器暂时限流：{streamed.status_code}",
                                request=streamed.request,
                                response=streamed,
                            )
                        if not (allow_not_found and streamed.status_code == 404):
                            streamed.raise_for_status()
                        length = streamed.headers.get("content-length")
                        if length and int(length) > max_bytes:
                            raise RuntimeError(f"远端响应超过 {max_bytes // 1024 // 1024} MB 上限")
                        payload = bytearray()
                        for chunk in streamed.iter_bytes(64 * 1024):
                            payload.extend(chunk)
                            if len(payload) > max_bytes:
                                raise RuntimeError(
                                    f"远端响应超过 {max_bytes // 1024 // 1024} MB 上限"
                                )
                        response_headers = streamed.headers.copy()
                        # iter_bytes 已完成解压；构造内存 Response 时移除编码头，避免二次解压。
                        response_headers.pop("content-encoding", None)
                        response_headers.pop("content-length", None)
                        return httpx.Response(
                            streamed.status_code,
                            headers=response_headers,
                            content=bytes(payload),
                            request=httpx.Request("GET", current),
                        )
                raise RuntimeError("重定向次数过多")
            except (httpx.HTTPError, OSError, RuntimeError, ValueError) as exc:
                last = exc
                logging.warning(
                    "远端请求失败（%s/%s）：%s",
                    attempt + 1,
                    self.retry,
                    safe_error(exc),
                )
        raise RuntimeError(str(last) if last else "请求失败")

    def scrape_javdb(self, code: str) -> Movie | None:
        search = self.request(f"https://javdb.com/search?q={quote(code)}&f=all")
        soup = BeautifulSoup(search.text, "lxml")
        target = None
        for card in soup.select("a.box"):
            if code.replace("-", "").lower() in text(card).replace("-", "").lower():
                target = urljoin(str(search.url), card.get("href", ""))
                break
        if not target:
            return None
        response = self.request(target)
        soup = BeautifulSoup(response.text, "lxml")
        movie = Movie(code=code, source="javdb")
        movie.title = clean_title(text(soup.select_one("h2.title, .video-detail h2")), code)
        cover = soup.select_one(".video-cover img, img.video-cover, .column-video-cover img")
        movie.cover_url = urljoin(str(response.url), cover.get("src", "")) if cover else None
        for row in soup.select(".movie-panel-info .panel-block, .video-meta-panel .panel-block"):
            label = text(row.select_one("strong")) or ""
            values = [text(x) for x in row.select("a") if text(x)]
            raw = text(row) or ""
            if "日期" in label:
                found = re.search(r"\d{4}-\d{2}-\d{2}", raw)
                movie.release_date = found.group(0) if found else None
            elif "時長" in label or "时长" in label:
                found = re.search(r"(\d+)", raw)
                movie.runtime_min = int(found.group(1)) if found else None
            elif "片商" in label:
                movie.studio = values[0] if values else None
            elif "系列" in label:
                movie.series = values[0] if values else None
            elif "類別" in label or "类别" in label:
                movie.genres = values
            elif "演員" in label or "演员" in label:
                movie.actors = [Actor(name=v, is_main=i == 0) for i, v in enumerate(values)]
        return movie if movie.title or movie.actors or movie.cover_url else None

    def scrape_javbus(self, code: str) -> Movie | None:
        response = self.request(
            f"https://www.javbus.com/{quote(code)}",
            allow_not_found=True,
        )
        if response.status_code == 404:
            return None
        soup = BeautifulSoup(response.text, "lxml")
        title = text(soup.select_one("h3"))
        if not title or code.replace("-", "").lower() not in title.replace("-", "").lower():
            return None
        movie = Movie(code=code, title=clean_title(title, code), source="javbus")
        cover = soup.select_one("a.bigImage, .bigImage")
        if cover:
            movie.cover_url = urljoin(str(response.url), cover.get("href", ""))
        for paragraph in soup.select(".info p"):
            raw = text(paragraph) or ""
            values = [text(x) for x in paragraph.select("a") if text(x)]
            if "發行日期" in raw or "发行日期" in raw:
                found = re.search(r"\d{4}-\d{2}-\d{2}", raw)
                movie.release_date = found.group(0) if found else None
            elif "長度" in raw or "长度" in raw:
                found = re.search(r"(\d+)", raw)
                movie.runtime_min = int(found.group(1)) if found else None
            elif "製作商" in raw or "制作商" in raw:
                movie.studio = values[0] if values else None
            elif "系列" in raw:
                movie.series = values[0] if values else None
        movie.genres = [x for x in (text(a) for a in soup.select(".genre a")) if x]
        actors = []
        for index, node in enumerate(soup.select(".star-name a, .star-box a")):
            name = text(node)
            if name and name not in {a.name for a in actors}:
                image = node.find("img")
                actors.append(Actor(
                    name=name,
                    avatar_url=urljoin(str(response.url), image.get("src", "")) if image else None,
                    is_main=index == 0,
                ))
        movie.actors = actors
        return movie

    @staticmethod
    def _object_name(value: Any) -> str | None:
        if isinstance(value, dict):
            value = value.get("name")
        if value is None:
            return None
        cleaned = re.sub(r"\s+", " ", str(value)).strip()
        return cleaned or None

    def scrape_r18(self, code: str) -> Movie | None:
        """R18.dev 提供无需账号的 DMM 结构化资料接口。"""
        response = self.request(
            "https://r18.dev/videos/vod/movies/detail/-/"
            f"dvd_id={quote(code, safe='')}/json",
            MAX_JSON_BYTES,
            allow_not_found=True,
        )
        if response.status_code == 404:
            return None
        try:
            payload = response.json()
        except ValueError:
            return None
        if not isinstance(payload, dict) or not payload.get("content_id"):
            return None
        movie = Movie(code=code, source="r18")
        movie.title = clean_title(self._object_name(payload.get("title")), code)
        movie.release_date = self._object_name(payload.get("release_date"))
        runtime = payload.get("runtime_minutes")
        if isinstance(runtime, int) and not isinstance(runtime, bool):
            movie.runtime_min = runtime
        movie.studio = self._object_name(payload.get("maker"))
        movie.label = self._object_name(payload.get("label"))
        movie.series = self._object_name(payload.get("series"))
        movie.genres = [
            name
            for name in (self._object_name(item) for item in payload.get("categories", []))
            if name
        ]
        movie.actors = [
            Actor(name=name, is_main=index == 0)
            for index, name in enumerate(
                name
                for name in (
                    self._object_name(item) for item in payload.get("actresses", [])
                )
                if name
            )
        ]
        images = payload.get("images", {})
        jacket = images.get("jacket_image", {}) if isinstance(images, dict) else {}
        if isinstance(jacket, dict):
            for key in ("large2", "large", "medium"):
                value = self._object_name(jacket.get(key))
                if value:
                    movie.cover_urls.append(urljoin(str(response.url), value))
        if movie.cover_urls:
            movie.cover_url = movie.cover_urls[0]
        return movie if movie.title or movie.actors or movie.cover_url else None

    @staticmethod
    def merge_movies(movies: Iterable[Movie]) -> Movie | None:
        values = list(movies)
        if not values:
            return None
        merged = Movie(code=values[0].code)
        scalar_fields = (
            "title", "title_zh", "release_date", "runtime_min", "studio",
            "label", "series", "poster_url", "cover_url",
        )
        sources: list[str] = []
        actor_map: dict[str, Actor] = {}
        for movie in values:
            if movie.source and movie.source not in sources:
                sources.append(movie.source)
            for field_name in scalar_fields:
                if getattr(merged, field_name) in (None, ""):
                    setattr(merged, field_name, getattr(movie, field_name))
            for field_name in ("genres", "cover_urls", "poster_urls"):
                target = getattr(merged, field_name)
                incoming = getattr(movie, field_name)
                for item in incoming:
                    if item and item.casefold() not in {x.casefold() for x in target}:
                        target.append(item)
            if movie.cover_url and movie.cover_url not in merged.cover_urls:
                merged.cover_urls.append(movie.cover_url)
            if movie.poster_url and movie.poster_url not in merged.poster_urls:
                merged.poster_urls.append(movie.poster_url)
            for actor in movie.actors:
                key = actor.name.casefold()
                current = actor_map.get(key)
                if current is None:
                    current = Actor(
                        name=actor.name,
                        name_zh=actor.name_zh,
                        avatar_url=actor.avatar_url,
                        is_main=actor.is_main,
                    )
                    actor_map[key] = current
                    merged.actors.append(current)
                else:
                    current.name_zh = current.name_zh or actor.name_zh
                    current.avatar_url = current.avatar_url or actor.avatar_url
                    current.is_main = current.is_main or actor.is_main
        merged.source = "+".join(sources)
        return merged

    @staticmethod
    def _movie_to_dict(movie: Movie) -> dict[str, Any]:
        return {
            "code": movie.code,
            "title": movie.title,
            "title_zh": movie.title_zh,
            "release_date": movie.release_date,
            "runtime_min": movie.runtime_min,
            "studio": movie.studio,
            "label": movie.label,
            "series": movie.series,
            "genres": movie.genres,
            "actors": [
                {
                    "name": actor.name,
                    "name_zh": actor.name_zh,
                    "avatar_url": actor.avatar_url,
                    "is_main": actor.is_main,
                }
                for actor in movie.actors
            ],
            "poster_url": movie.poster_url,
            "cover_url": movie.cover_url,
            "poster_urls": movie.poster_urls,
            "cover_urls": movie.cover_urls,
            "source": movie.source,
        }

    @staticmethod
    def _movie_from_dict(value: dict[str, Any]) -> Movie:
        movie = Movie(
            code=str(value["code"]),
            title=value.get("title"),
            title_zh=value.get("title_zh"),
            release_date=value.get("release_date"),
            runtime_min=value.get("runtime_min"),
            studio=value.get("studio"),
            label=value.get("label"),
            series=value.get("series"),
            genres=list(value.get("genres", [])),
            poster_url=value.get("poster_url"),
            cover_url=value.get("cover_url"),
            poster_urls=list(value.get("poster_urls", [])),
            cover_urls=list(value.get("cover_urls", [])),
            source=value.get("source"),
        )
        movie.actors = [
            Actor(
                name=str(actor["name"]),
                name_zh=actor.get("name_zh"),
                avatar_url=actor.get("avatar_url"),
                is_main=bool(actor.get("is_main", False)),
            )
            for actor in value.get("actors", [])
            if isinstance(actor, dict) and actor.get("name")
        ]
        return movie

    def _movie_cache_path(self, code: str) -> Path:
        digest = hashlib.sha256(code.casefold().encode("utf-8")).hexdigest()
        return self.movie_cache_dir / f"{digest}.json"

    def load_movie_cache(self, code: str) -> Movie | None:
        path = self._movie_cache_path(code)
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            cached_at = datetime.fromisoformat(str(payload["cachedAt"]))
            if datetime.now().astimezone() - cached_at > timedelta(days=self.cache_days):
                return None
            movie = self._movie_from_dict(payload["movie"])
            return movie if movie.code.casefold() == code.casefold() else None
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
            return None

    def save_movie_cache(self, movie: Movie) -> None:
        self.movie_cache_dir.mkdir(parents=True, exist_ok=True)
        self.atomic_json(
            self._movie_cache_path(movie.code),
            {
                "cachedAt": datetime.now().astimezone().isoformat(),
                "movie": self._movie_to_dict(movie),
            },
        )

    @staticmethod
    def metadata_complete(movie: Movie) -> bool:
        return all(
            (
                movie.title,
                movie.cover_url,
                movie.release_date,
                movie.runtime_min,
                movie.studio,
                movie.actors,
            )
        )

    def manual_override(
        self,
        relative_path: str | None,
        code: str | None,
    ) -> dict[str, Any]:
        """相对路径优先；番号 key 只作为旧版 manual.toml 的兼容后备。"""
        for key in (relative_path, code):
            if key:
                value = self.manual.get(key)
                if isinstance(value, dict):
                    return value
        return {}

    def scrape(self, code: str, relative_path: str | None = None) -> Movie | None:
        override = self.manual_override(relative_path, code)
        effective_code = str(override.get("code", code)).strip().upper()
        if not effective_code:
            return None
        cached = None if self.force else self.load_movie_cache(effective_code)
        if cached:
            return self.apply_manual(cached, override)
        sources = self.cfg.get("sources", {})
        attempts = (
            ("R18", bool(sources.get("r18", True)), self.scrape_r18),
            ("JavDB", bool(sources.get("javdb", True)), self.scrape_javdb),
            ("JavBus", bool(sources.get("javbus", True)), self.scrape_javbus),
        )
        found: list[Movie] = []
        for name, enabled, handler in attempts:
            if not enabled:
                continue
            try:
                movie = handler(effective_code)
                if movie:
                    found.append(movie)
                    merged = self.merge_movies(found)
                    if merged and self.metadata_complete(merged):
                        break
            except Exception as exc:
                logging.warning("%s 查询失败：%s", name, safe_error(exc))
        merged = self.merge_movies(found)
        if merged:
            try:
                self.save_movie_cache(merged)
            except OSError as exc:
                logging.warning("资料缓存写入失败：%s", safe_error(exc))
            return self.apply_manual(merged, override)
        if override:
            return self.apply_manual(Movie(code=effective_code, source="manual"), override)
        return None

    def apply_manual(
        self,
        movie: Movie,
        override: dict[str, Any] | None = None,
    ) -> Movie:
        override = override if override is not None else self.manual_override(None, movie.code)
        if "code" in override:
            movie.code = str(override["code"]).strip().upper()
        field_map = {
            "title": "title", "title_zh": "title_zh", "release_date": "release_date",
            "runtime_min": "runtime_min", "studio": "studio", "label": "label",
            "series": "series", "poster_url": "poster_url", "cover_url": "cover_url",
        }
        for source, target in field_map.items():
            if source in override:
                setattr(movie, target, override[source])
        if "actors" in override:
            values = override["actors"]
            if isinstance(values, str):
                values = [x.strip() for x in values.split(",")]
            movie.actors = [Actor(name=x, is_main=i == 0) for i, x in enumerate(values) if x]
        return movie

    def gfriends_avatar(self, actor_name: str) -> str | None:
        if not self.cfg.get("sources", {}).get("gfriends", True):
            return None
        if self.gfriends is None:
            self.gfriends = {}
            cache_path = self.cache_dir / "gfriends-map.json"
            try:
                cached = json.loads(cache_path.read_text(encoding="utf-8"))
                cached_at = datetime.fromisoformat(str(cached["cachedAt"]))
                if datetime.now().astimezone() - cached_at <= timedelta(days=self.cache_days):
                    values = cached.get("actors", {})
                    if isinstance(values, dict):
                        self.gfriends = {
                            str(key): str(value)
                            for key, value in values.items()
                            if key and value
                        }
            except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
                pass
            try:
                if self.gfriends:
                    return self.gfriends.get(actor_name.casefold())
                gf_cfg = self.cfg.get("gfriends", {})
                index_url = gf_cfg.get(
                    "index_url",
                    "https://raw.githubusercontent.com/gfriends/gfriends/master/Filetree.json",
                )
                base_url = str(
                    gf_cfg.get(
                        "base_url",
                        "https://cdn.jsdelivr.net/gh/gfriends/gfriends@master/",
                    )
                ).rstrip("/") + "/"
                tree = self.request(index_url, MAX_JSON_BYTES).json().get("Content", {})
                # 官方说明文件树按头像质量升序排列，同名后出现的覆盖前面的低质量图。
                for company, entries in tree.items():
                    if not isinstance(entries, dict):
                        continue
                    for alias, stored in entries.items():
                        key = Path(alias.split("?", 1)[0]).stem.casefold()
                        company_path = quote(str(company), safe="")
                        stored_path = quote(str(stored), safe="/?=&")
                        self.gfriends[key] = (
                            f"{base_url}Content/{company_path}/{stored_path}"
                        )
                self.cache_dir.mkdir(parents=True, exist_ok=True)
                self.atomic_json(
                    cache_path,
                    {
                        "cachedAt": datetime.now().astimezone().isoformat(),
                        "actors": self.gfriends,
                    },
                )
            except Exception as exc:
                logging.warning(
                    "Gfriends 文件树不可用，将回退站点头像：%s",
                    safe_error(exc),
                )
        return self.gfriends.get(actor_name.casefold())

    @staticmethod
    def valid_image(path: Path) -> bool:
        if not path.is_file() or path.stat().st_size <= 0:
            return False
        try:
            with Image.open(path) as image:
                image.verify()
            return True
        except (OSError, ValueError):
            return False

    def item_assets_complete(self, item: dict[str, Any]) -> bool:
        scrape_cfg = self.cfg.get("scrape", {})
        for enabled_key, item_key in (
            ("download_cover", "cover"),
            ("download_poster", "poster"),
        ):
            if not scrape_cfg.get(enabled_key, True):
                continue
            relative = item.get(item_key)
            if not isinstance(relative, str) or not relative:
                return False
            try:
                target = (self.output_dir / relative).resolve()
                target.relative_to(self.output_dir)
            except (OSError, ValueError):
                return False
            if not self.valid_image(target):
                return False
        return True

    def save_image_payload(self, payload: bytes, path: Path, kind: str) -> bool:
        temp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
        try:
            with Image.open(BytesIO(payload)) as source:
                if source.width * source.height > MAX_IMAGE_PIXELS:
                    raise RuntimeError("图片像素数超过安全上限")
                image = ImageOps.exif_transpose(source).convert("RGB")
                out_cfg = self.cfg.get("output", {})
                if kind == "poster":
                    ratio = 2 / 3
                    w, h = image.size
                    target_w = min(w, int(h * ratio))
                    left = max(0, (w - target_w) // 2)
                    image = image.crop((left, 0, left + target_w, h))
                    max_size = int(out_cfg.get("poster_max_size", 900))
                elif kind == "avatar":
                    side = min(image.size)
                    left = (image.width - side) // 2
                    top = (image.height - side) // 2
                    image = image.crop((left, top, left + side, top + side))
                    max_size = int(out_cfg.get("avatar_size", 400))
                else:
                    max_size = int(out_cfg.get("cover_max_size", 1600))
                image.thumbnail((max_size, max_size), Image.Resampling.LANCZOS)
                path.parent.mkdir(parents=True, exist_ok=True)
                with temp.open("wb") as stream:
                    image.save(
                        stream,
                        "JPEG",
                        quality=int(out_cfg.get("jpeg_quality", 88)),
                        optimize=True,
                    )
                    stream.flush()
                    os.fsync(stream.fileno())
            if not self.valid_image(temp):
                raise RuntimeError("下载后的图片校验失败")
            os.replace(temp, path)
            return True
        except Exception as exc:
            logging.warning("%s 图片保存失败：%s", kind, safe_error(exc))
            return False
        finally:
            temp.unlink(missing_ok=True)

    def download_image(self, url: str, path: Path, kind: str) -> bool:
        try:
            payload = self.request(url, MAX_IMAGE_BYTES, polite=False).content
        except Exception as exc:
            logging.warning("%s 图片下载失败：%s", kind, safe_error(exc))
            return False
        return self.save_image_payload(payload, path, kind)

    def download_first_image(
        self,
        urls: Iterable[str],
        path: Path,
        kind: str,
    ) -> bool:
        seen: set[str] = set()
        for url in urls:
            if not url or url in seen:
                continue
            seen.add(url)
            if self.download_image(url, path, kind):
                return True
        return False

    def extract_video_frame(self, video_path: Path) -> bytes | None:
        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            return None
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        for seek_seconds in ("10", "1"):
            temp = self.cache_dir / f".frame-{uuid.uuid4().hex}.jpg"
            try:
                completed = subprocess.run(
                    [
                        ffmpeg,
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-ss",
                        seek_seconds,
                        "-i",
                        str(video_path),
                        "-map",
                        "0:v:0",
                        "-frames:v",
                        "1",
                        "-q:v",
                        "2",
                        "-y",
                        str(temp),
                    ],
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=60,
                    check=False,
                    creationflags=creation_flags,
                )
                if completed.returncode == 0 and temp.is_file() and temp.stat().st_size > 0:
                    return temp.read_bytes()
            except (OSError, subprocess.SubprocessError):
                pass
            finally:
                temp.unlink(missing_ok=True)
        return None

    @staticmethod
    def safe_name(value: str) -> str:
        cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value).strip(" .")
        return cleaned[:100] or hashlib.sha1(value.encode("utf-8")).hexdigest()[:12]

    @classmethod
    def safe_actor_name(cls, value: str) -> str:
        """姓名清洗会碰撞；保留可读前缀并用原值哈希稳定消歧。"""
        digest = hashlib.sha1(value.encode("utf-8")).hexdigest()[:10]
        prefix = cls.safe_name(value)[:89].rstrip(" ._-") or "actor"
        return f"{prefix}-{digest}"

    def movie_json(self, movie: Movie, video_path: Path | None = None) -> dict[str, Any]:
        scrape_cfg = self.cfg.get("scrape", {})
        poster_rel = None
        cover_rel = None
        cover_path = self.output_dir / "covers" / f"{self.safe_name(movie.code)}.jpg"
        poster_path = self.output_dir / "posters" / f"{self.safe_name(movie.code)}.jpg"
        want_cover = bool(scrape_cfg.get("download_cover", True))
        want_poster = bool(scrape_cfg.get("download_poster", True))
        cover_ready = want_cover and not self.force and self.valid_image(cover_path)
        poster_ready = want_poster and not self.force and self.valid_image(poster_path)
        cover_urls = list(movie.cover_urls)
        if movie.cover_url:
            cover_urls.insert(0, movie.cover_url)
        poster_urls = list(movie.poster_urls)
        if movie.poster_url:
            poster_urls.insert(0, movie.poster_url)

        # 大多数来源只有一张横版原图：只下载一次，同时生成横版封面和竖版海报。
        if (want_cover and not cover_ready) or (want_poster and not poster_ready):
            shared_payload = None
            if cover_urls and (not poster_urls or poster_urls[0] == cover_urls[0]):
                seen: set[str] = set()
                for url in cover_urls:
                    if not url or url in seen:
                        continue
                    seen.add(url)
                    try:
                        shared_payload = self.request(
                            url,
                            MAX_IMAGE_BYTES,
                            polite=False,
                        ).content
                        with Image.open(BytesIO(shared_payload)) as probe:
                            probe.verify()
                        break
                    except Exception as exc:
                        logging.warning("候选封面不可用：%s", safe_error(exc))
                        shared_payload = None
            elif movie.source == "local" and video_path is not None:
                shared_payload = self.extract_video_frame(video_path)
            if shared_payload:
                if want_cover and not cover_ready:
                    cover_ready = self.save_image_payload(shared_payload, cover_path, "cover")
                if want_poster and not poster_ready and not poster_urls:
                    poster_ready = self.save_image_payload(shared_payload, poster_path, "poster")

        if want_cover and not cover_ready and cover_urls:
            cover_ready = self.download_first_image(cover_urls, cover_path, "cover")
        effective_poster_urls = poster_urls or cover_urls
        if want_poster and not poster_ready and effective_poster_urls:
            poster_ready = self.download_first_image(
                effective_poster_urls,
                poster_path,
                "poster",
            )
        # 公开图片全部不可用时，用本地视频画面兜底，保证 APP 不出现空白卡片。
        if (
            video_path is not None
            and scrape_cfg.get("local_fallback", True)
            and ((want_cover and not cover_ready) or (want_poster and not poster_ready))
        ):
            fallback_payload = self.extract_video_frame(video_path)
            if fallback_payload:
                if want_cover and not cover_ready:
                    cover_ready = self.save_image_payload(
                        fallback_payload,
                        cover_path,
                        "cover",
                    )
                if want_poster and not poster_ready:
                    poster_ready = self.save_image_payload(
                        fallback_payload,
                        poster_path,
                        "poster",
                    )
        if cover_ready:
            cover_rel = cover_path.relative_to(self.output_dir).as_posix()
        if poster_ready:
            poster_rel = poster_path.relative_to(self.output_dir).as_posix()
        actors_json = []
        for actor in movie.actors:
            avatar_rel = None
            avatar_url = self.gfriends_avatar(actor.name) or actor.avatar_url
            if scrape_cfg.get("download_actor_avatar", True) and avatar_url:
                avatar_path = self.output_dir / "actors" / f"{self.safe_actor_name(actor.name)}.jpg"
                if self.valid_image(avatar_path) or self.download_image(avatar_url, avatar_path, "avatar"):
                    avatar_rel = avatar_path.relative_to(self.output_dir).as_posix()
            actors_json.append({
                "name": actor.name,
                "nameZh": actor.name_zh,
                "avatar": avatar_rel,
                "isMain": actor.is_main,
            })
        return {
            "code": movie.code, "title": movie.title, "titleZh": movie.title_zh,
            "releaseDate": movie.release_date, "runtimeMin": movie.runtime_min,
            "studio": movie.studio, "label": movie.label, "series": movie.series,
            "genres": movie.genres, "actors": actors_json, "poster": poster_rel,
            "cover": cover_rel, "source": movie.source, "scrapedAt": datetime.now().astimezone().isoformat(),
        }

    def load_existing(self) -> dict[str, Any]:
        path = self.output_dir / "index.json"
        if not path.exists():
            return {}
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            logging.warning("旧 index.json 无法读取，将重新生成")
            return {}

    def run(self) -> int:
        if not self.video_dir.is_dir():
            raise RuntimeError("配置的视频目录不存在或当前不可访问")
        self.output_dir.mkdir(parents=True, exist_ok=True)
        with output_lock(self.output_dir / ".scraper.lock"):
            return self._run_locked()

    def _run_locked(self) -> int:
        videos = sorted(
            (
                p for p in self.video_dir.rglob("*")
                if p.is_file() and p.suffix.lower() in VIDEO_EXTENSIONS
                and path_is_in_scope(
                    p,
                    self.video_dir,
                    self.include_top_level_patterns,
                    self.excluded_directory_names,
                )
            ),
            key=lambda p: p.relative_to(self.video_dir).as_posix().casefold(),
        )
        current_keys = {p.relative_to(self.video_dir).as_posix() for p in videos}
        old = self.load_existing()
        # 当前文件集合是唯一事实来源；删除/改名的旧影片不能继续污染演员索引。
        items = {
            key: value
            for key, value in dict(old.get("items", {})).items()
            if key in current_keys
        }
        failed: list[dict[str, str]] = []
        local_fallback = bool(
            self.cfg.get("scrape", {}).get("local_fallback", True)
        )
        pending_network: list[tuple[Path, str, str]] = []
        pending_local: list[tuple[Path, str, dict[str, Any]]] = []
        completed = 0
        since_checkpoint = 0

        def progress(message: str) -> None:
            print(f"[{completed}/{len(videos)}] {message}", flush=True)

        def checkpoint() -> None:
            nonlocal since_checkpoint
            if since_checkpoint >= self.checkpoint_every:
                self.write_outputs(videos, items, failed, len(items), complete=False)
                since_checkpoint = 0

        for path in videos:
            relative = path.relative_to(self.video_dir).as_posix()
            recognized_code = recognize_code(path.name)
            override = self.manual_override(relative, recognized_code)
            code = str(override.get("code", recognized_code or "")).strip().upper() or None
            local_code = "LOCAL-" + hashlib.sha1(relative.encode("utf-8")).hexdigest()[:12].upper()
            expected_code = code or (local_code if local_fallback else None)
            if not code:
                if (
                    expected_code
                    and not self.force
                    and self.cfg.get("scrape", {}).get("incremental", True)
                    and relative in items
                    and items[relative].get("code") == expected_code
                    and self.item_assets_complete(items[relative])
                ):
                    completed += 1
                    progress("✓ 已有本地资料，跳过")
                elif local_fallback:
                    pending_local.append((path, relative, override))
                else:
                    failed.append({"file": relative, "reason": "番号未识别"})
                    completed += 1
                    since_checkpoint += 1
                    progress("✗ 无法识别编号")
                    checkpoint()
                continue
            if (
                not self.force
                and self.cfg.get("scrape", {}).get("incremental", True)
                and relative in items
                and items[relative].get("code") == code
                and self.item_assets_complete(items[relative])
            ):
                completed += 1
                progress("✓ 已有公开资料，跳过")
                continue
            pending_network.append((path, relative, code))

        with ThreadPoolExecutor(
            max_workers=self.workers,
            thread_name_prefix="lanplay-metadata",
        ) as executor:
            futures = {
                executor.submit(self.scrape, code, relative): (path, relative)
                for path, relative, code in pending_network
            }

            # 没有公开编号的媒体在本机生成标题与预览图；不会读取外部字幕。
            for path, relative, override in pending_local:
                local_code = (
                    "LOCAL-"
                    + hashlib.sha1(relative.encode("utf-8")).hexdigest()[:12].upper()
                )
                movie = Movie(
                    code=local_code,
                    title=clean_local_title(path.name),
                    source="local",
                )
                movie = self.apply_manual(movie, override)
                items[relative] = self.movie_json(movie, path)
                completed += 1
                since_checkpoint += 1
                progress("✓ 已生成本地资料")
                checkpoint()

            for future in as_completed(futures):
                path, relative = futures[future]
                try:
                    movie = future.result()
                except Exception as exc:
                    logging.warning("公开资料查询任务失败：%s", safe_error(exc))
                    movie = None
                if movie:
                    items[relative] = self.movie_json(movie, path)
                    message = "✓ 公开资料已完成"
                elif relative in items:
                    message = "△ 更新失败，已保留旧资料"
                else:
                    failed.append({"file": relative, "reason": "所有数据源均未查到"})
                    message = "✗ 公开资料未查到"
                completed += 1
                since_checkpoint += 1
                progress(message)
                checkpoint()

        self.write_outputs(videos, items, failed, len(items), complete=True)
        print(f"\n共 {len(videos)} 个视频：完成 {len(items)}，失败 {len(failed)}。")
        return 0

    def write_outputs(
        self,
        videos: list[Path],
        items: dict[str, Any],
        failed: list[dict[str, str]],
        matched: int,
        complete: bool = True,
    ) -> None:
        now = datetime.now().astimezone().isoformat()
        try:
            relative_root = self.video_dir.relative_to(self.output_dir.parent)
            scan_root = "" if relative_root == Path(".") else relative_root.as_posix()
        except ValueError:
            # 兼容旧配置：资料目录不在视频目录的共享根路径下时，沿用原有约定。
            scan_root = self.video_dir.name
        payload = {
            "version": 1,
            "generatedAt": now,
            "scanRoot": scan_root,
            # 检查点可供 APP 提前导入已完成项目，但只有最终索引才有资格触发
            # 陈旧元数据删除。默认值只用于测试和外部兼容调用，正式检查点显式传 false。
            "complete": complete,
            "stats": {"total": len(videos), "matched": matched, "failed": len(failed)},
            "items": items,
            "failed": failed,
        }
        self.atomic_json(self.output_dir / "index.json", payload)
        actors: dict[str, dict[str, Any]] = {}
        for filename, movie in items.items():
            for actor in movie.get("actors", []):
                name = actor.get("name")
                if not name:
                    continue
                entry = actors.setdefault(name, {
                    "name": name, "nameZh": actor.get("nameZh"),
                    "avatar": actor.get("avatar"), "movies": [],
                })
                if not entry.get("nameZh") and actor.get("nameZh"):
                    entry["nameZh"] = actor["nameZh"]
                if not entry.get("avatar") and actor.get("avatar"):
                    entry["avatar"] = actor["avatar"]
                if filename not in entry["movies"]:
                    entry["movies"].append(filename)
        self.atomic_json(self.output_dir / "actors.json", {
            "version": 1, "generatedAt": now, "actors": actors,
        })
        self.atomic_text(
            self.output_dir / "failed.txt",
            "\n".join(f"{x['file']}\t{x['reason']}" for x in failed),
        )
        referenced_assets = {
            value
            for movie in items.values()
            for value in (movie.get("poster"), movie.get("cover"))
            if isinstance(value, str) and value
        }
        referenced_assets.update(
            actor["avatar"]
            for actor in actors.values()
            if isinstance(actor.get("avatar"), str) and actor["avatar"]
        )
        removed = 0
        for folder in ("posters", "covers", "actors"):
            asset_dir = self.output_dir / folder
            if not asset_dir.is_dir():
                continue
            for asset in asset_dir.glob("*.jpg"):
                relative = asset.relative_to(self.output_dir).as_posix()
                if relative not in referenced_assets:
                    asset.unlink(missing_ok=True)
                    removed += 1
        if removed:
            logging.info("已清理 %s 个不再被索引引用的旧图片", removed)

    @staticmethod
    def atomic_text(path: Path, value: str) -> None:
        temp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
        try:
            with temp.open("w", encoding="utf-8", newline="\n") as stream:
                stream.write(value)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp, path)
        finally:
            temp.unlink(missing_ok=True)

    @staticmethod
    def atomic_json(path: Path, payload: dict[str, Any]) -> None:
        Scraper.atomic_text(path, json.dumps(payload, ensure_ascii=False, indent=2))


def validate_config(config: dict[str, Any]) -> None:
    allowed = {
        "paths": {"video_dir", "output_dir"},
        "network": {"proxy", "timeout", "delay_min", "delay_max", "retry"},
        "sources": {"r18", "javdb", "javbus", "gfriends"},
        "scrape": {
            "incremental", "local_fallback", "download_poster", "download_cover",
            "download_actor_avatar",
        },
        "output": {"poster_max_size", "cover_max_size", "avatar_size", "jpeg_quality"},
        "performance": {"workers", "checkpoint_every", "cache_days"},
        "filters": {"include_top_level_patterns", "exclude_directory_names"},
        "gfriends": {"index_url", "base_url"},
    }
    unknown_sections = set(config) - set(allowed)
    if unknown_sections:
        raise RuntimeError(f"配置包含未知分组：{', '.join(sorted(unknown_sections))}")
    for section, keys in allowed.items():
        values = config.get(section, {})
        if not isinstance(values, dict):
            raise RuntimeError(f"配置分组 [{section}] 必须是键值表")
        unknown = set(values) - keys
        if unknown:
            raise RuntimeError(f"[{section}] 包含未实现选项：{', '.join(sorted(unknown))}")
    paths = config.get("paths", {})
    if not paths.get("video_dir") or not paths.get("output_dir"):
        raise RuntimeError("[paths] 必须配置 video_dir 和 output_dir")
    excluded = config.get("filters", {}).get(
        "exclude_directory_names",
        ["电影", "movie", "movies"],
    )
    if (
        not isinstance(excluded, list)
        or any(not isinstance(value, str) or not value.strip() for value in excluded)
    ):
        raise RuntimeError("[filters].exclude_directory_names 必须是非空文字组成的数组")
    included = config.get("filters", {}).get("include_top_level_patterns", [])
    if (
        not isinstance(included, list)
        or any(not isinstance(value, str) or not value.strip() for value in included)
    ):
        raise RuntimeError("[filters].include_top_level_patterns 必须是文字数组")

    def number(
        section: str,
        key: str,
        default: int | float,
        minimum: int | float,
        maximum: int | float,
        *,
        integer: bool = False,
    ) -> int | float:
        value = config.get(section, {}).get(key, default)
        valid_type = isinstance(value, int if integer else (int, float)) and not isinstance(value, bool)
        if not valid_type or not math.isfinite(float(value)) or not minimum <= value <= maximum:
            kind = "整数" if integer else "数字"
            raise RuntimeError(
                f"[{section}].{key} 必须是 {minimum}～{maximum} 的{kind}"
            )
        return value

    timeout = number("network", "timeout", 20, 0.1, 300)
    delay_min = number("network", "delay_min", 1.0, 0, 300)
    delay_max = number("network", "delay_max", 3.0, 0, 300)
    number("network", "retry", 3, 1, 20, integer=True)
    if delay_min > delay_max:
        raise RuntimeError("[network].delay_min 不能大于 delay_max")
    # 保留局部变量，让静态类型检查可确认这些默认值也走过有限值校验。
    _ = timeout
    number("output", "poster_max_size", 900, 1, 10_000, integer=True)
    number("output", "cover_max_size", 1_600, 1, 10_000, integer=True)
    number("output", "avatar_size", 400, 1, 10_000, integer=True)
    number("output", "jpeg_quality", 88, 1, 100, integer=True)
    number("performance", "workers", 3, 1, 8, integer=True)
    number("performance", "checkpoint_every", 5, 1, 100, integer=True)
    number("performance", "cache_days", 7, 1, 365, integer=True)


def load_config(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise RuntimeError(f"配置文件不存在：{path}")
    with path.open("rb") as fh:
        config = tomllib.load(fh)
    validate_config(config)
    return config


def configure_logging(output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    log_path = output_dir / "scraper.log"
    if log_path.exists():
        try:
            previous = log_path.read_text(encoding="utf-8", errors="replace")
            sanitized = re.sub(r"https?://\S+", "<远端地址>", previous)
            sanitized = re.sub(
                r"(?i)\b[A-Z]{2,12}(?:-\w+)?-\d{2,8}\b",
                "<编号>",
                sanitized,
            )
            sanitized = re.sub(
                r"(?:[A-Za-z]:\\|/)[^\r\n\t]+",
                "<本机路径>",
                sanitized,
            )
            if sanitized != previous:
                Scraper.atomic_text(log_path, sanitized)
        except OSError:
            pass
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=[
            logging.FileHandler(log_path, encoding="utf-8"),
            logging.StreamHandler(sys.stdout),
        ],
    )
    # httpx 的 INFO 记录包含完整查询 URL；只保留真正的网络告警。
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)


def recognize_only(
    video_dir: Path,
    include_top_level_patterns: list[str],
    excluded_names: set[str],
) -> int:
    videos = [
        p
        for p in video_dir.rglob("*")
        if p.is_file()
        and p.suffix.lower() in VIDEO_EXTENSIONS
        and path_is_in_scope(
            p,
            video_dir,
            include_top_level_patterns,
            excluded_names,
        )
    ]
    matched = 0
    for path in videos:
        matched += int(recognize_code(path.name) is not None)
    ratio = matched / len(videos) if videos else 0
    print(f"共 {len(videos)} 个视频，识别标准编号 {matched} 个（{ratio:.0%}）。")
    print(f"其余 {len(videos) - matched} 个可由本地标题与视频取帧兜底。")
    return 0 if ratio >= 0.9 else 2


def main() -> int:
    parser = argparse.ArgumentParser(description="LanPlay PC 端影片资料刮削工具")
    parser.add_argument(
        "--config",
        default=str(SCRIPT_DIR / "config.toml"),
        help="配置文件路径（默认使用脚本同目录的 config.toml）",
    )
    parser.add_argument("--force", action="store_true", help="重新处理已有条目")
    parser.add_argument("--recognize-only", action="store_true", help="只检查番号识别，不联网")
    args = parser.parse_args()
    config_path = Path(args.config).resolve()
    config = load_config(config_path)
    video_dir = Path(config["paths"]["video_dir"]).expanduser().resolve()
    if args.recognize_only:
        excluded = {
            str(value).strip().casefold()
            for value in config.get("filters", {}).get(
                "exclude_directory_names",
                ["电影", "movie", "movies"],
            )
            if str(value).strip()
        }
        included = [
            str(value).strip()
            for value in config.get("filters", {}).get(
                "include_top_level_patterns",
                [],
            )
            if str(value).strip()
        ]
        return recognize_only(video_dir, included, excluded)
    output_dir = Path(config["paths"]["output_dir"]).expanduser().resolve()
    configure_logging(output_dir)
    with Scraper(config, args.force) as scraper:
        return scraper.run()


def _scraper_close(self: Scraper) -> None:
    self.client.close()


Scraper.__enter__ = lambda self: self
Scraper.__exit__ = lambda self, *_: _scraper_close(self)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\n已取消。")
        raise SystemExit(130)
    except Exception as exc:
        logging.exception("任务失败：%s", exc)
        print(f"\n[失败] {exc}")
        raise SystemExit(1)
