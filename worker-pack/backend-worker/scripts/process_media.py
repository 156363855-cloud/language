#!/usr/bin/env python3

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from deep_translator import GoogleTranslator
from faster_whisper import WhisperModel
from openai import OpenAI
from yt_dlp import YoutubeDL


DEEPSEEK_TRANSLATE_MODEL = "deepseek-chat"
LOCAL_SEGMENT_SECONDS = 180
LOCAL_TRANSCRIBE_MODEL = "small"
TRANSLATION_BATCH_SIZE = 20


def looks_like_translation_error(text: str | None) -> bool:
    if not text:
        return True
    normalized = text.strip().lower()
    return (
        normalized.startswith("error 500")
        or "server error" in normalized
        or "please try again later" in normalized
        or normalized.startswith("<!doctype html")
        or normalized.startswith("<html")
    )


def require_binary(name: str) -> None:
    if shutil.which(name) is None:
        raise RuntimeError(f"缺少依赖工具: {name}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media-url", required=True)
    parser.add_argument("--source-language", required=True)
    parser.add_argument("--target-languages", required=True)
    parser.add_argument("--output-file", required=True)
    return parser.parse_args()


def download_audio(media_url: str, task_dir: Path) -> tuple[str, Path]:
    output_template = str(task_dir / "source.%(ext)s")
    options = {
        "format": "bestaudio/best",
        "outtmpl": output_template,
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "postprocessors": [
            {
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "192",
            }
        ],
    }

    with YoutubeDL(options) as downloader:
        info = downloader.extract_info(media_url, download=True)
        title = info.get("title") or media_url

    audio_path = task_dir / "source.mp3"
    if not audio_path.exists():
        matches = sorted(task_dir.glob("source.*"))
        if not matches:
            raise RuntimeError("下载完成但没有找到音频文件")
        audio_path = matches[0]
    return title, audio_path


def split_audio_locally(audio_path: Path, chunk_directory: Path) -> list[Path]:
    chunk_directory.mkdir(parents=True, exist_ok=True)
    output_pattern = chunk_directory / "chunk_%03d.wav"
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(audio_path),
            "-f",
            "segment",
            "-segment_time",
            str(LOCAL_SEGMENT_SECONDS),
            "-c:a",
            "pcm_s16le",
            str(output_pattern),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    chunks = sorted(chunk_directory.glob("chunk_*.wav"))
    if not chunks:
        return [audio_path]
    return chunks


def transcribe_chunk(model: WhisperModel, audio_path: Path, source_language: str | None) -> list[dict]:
    attempts = [
        {"language": source_language, "vad_filter": True},
        {"language": source_language, "vad_filter": False},
        {"language": None, "vad_filter": False},
    ]

    for attempt in attempts:
        kwargs = {
            "vad_filter": attempt["vad_filter"],
            "beam_size": 1,
            "best_of": 1,
            "condition_on_previous_text": False,
            "temperature": 0,
        }
        if attempt["language"]:
            kwargs["language"] = attempt["language"]

        segments, _ = model.transcribe(str(audio_path), **kwargs)
        result = []
        for index, segment in enumerate(segments):
            text = segment.text.strip()
            if not text:
                continue
            result.append(
                {
                    "id": f"seg-{index + 1}",
                    "startSeconds": round(float(segment.start), 2),
                    "endSeconds": round(float(segment.end), 2),
                    "originalText": text,
                }
            )
        if result:
            return result

    return []


def transcribe_audio_locally(audio_path: Path, source_language: str, task_dir: Path) -> list[dict]:
    model = WhisperModel(LOCAL_TRANSCRIBE_MODEL, device="cpu", compute_type="int8")
    chunk_directory = task_dir / "local_chunks"
    chunks = split_audio_locally(audio_path, chunk_directory)
    all_segments = []

    for chunk_index, chunk_path in enumerate(chunks):
        chunk_segments = transcribe_chunk(model, chunk_path, source_language)
        offset = chunk_index * LOCAL_SEGMENT_SECONDS

        for segment in chunk_segments:
            all_segments.append(
                {
                    "id": f"seg-{len(all_segments) + 1}",
                    "startSeconds": round(segment["startSeconds"] + offset, 2),
                    "endSeconds": round(segment["endSeconds"] + offset, 2),
                    "originalText": segment["originalText"],
                }
            )

    if all_segments:
        return all_segments

    raise RuntimeError("本地转写完成，但没有得到可用字幕片段")


def extract_json_object(content: str) -> dict:
    if not content:
        raise RuntimeError("翻译接口返回空内容")
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        start = content.find("{")
        end = content.rfind("}")
        if start != -1 and end != -1 and end > start:
            return json.loads(content[start:end + 1])
        raise


def translate_batch_with_deepseek(client: OpenAI, segments: list[dict], target_languages: list[str]) -> list[dict]:
    indexed_segments = [{"id": segment["id"], "text": segment["originalText"]} for segment in segments]
    response = client.chat.completions.create(
        model=DEEPSEEK_TRANSLATE_MODEL,
        response_format={"type": "json_object"},
        messages=[
            {
                "role": "system",
                "content": (
                    "You translate subtitle segments. Return strict JSON with a top-level key named "
                    "'translations'. Each item must contain 'id' and a 'translations' object. Keep the meaning "
                    "natural, concise, and suitable for subtitles."
                ),
            },
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "targetLanguages": target_languages,
                        "segments": indexed_segments,
                    },
                    ensure_ascii=False,
                ),
            },
        ],
    )
    content = response.choices[0].message.content
    payload = extract_json_object(content)
    translation_map = {
        item["id"]: item.get("translations", {})
        for item in payload.get("translations", [])
    }

    for segment in segments:
        segment["translations"] = translation_map.get(segment["id"], {})
    return segments


def translate_segments_with_deepseek(api_key: str, segments: list[dict], target_languages: list[str]) -> list[dict]:
    client = OpenAI(api_key=api_key, base_url="https://api.deepseek.com")
    translated_segments = []

    for index in range(0, len(segments), TRANSLATION_BATCH_SIZE):
        batch = segments[index:index + TRANSLATION_BATCH_SIZE]
        try:
            translated_segments.extend(translate_batch_with_deepseek(client, batch, target_languages))
        except Exception:
            translated_segments.extend(translate_segments_locally(batch, target_languages))

    return translated_segments


def translate_segments_locally(segments: list[dict], target_languages: list[str]) -> list[dict]:
    language_map = {
        "zh": "zh-CN",
        "ja": "ja",
        "en": "en",
    }
    translators = {
        language: GoogleTranslator(source="auto", target=language_map.get(language, language))
        for language in target_languages
    }
    for segment in segments:
        translations = {}
        for language, translator in translators.items():
            if language == "en":
                translations[language] = segment["originalText"]
            else:
                try:
                    translated_text = translator.translate(segment["originalText"])
                except Exception:
                    translated_text = ""

                translations[language] = (
                    segment["originalText"] if looks_like_translation_error(translated_text) else translated_text
                )
        segment["translations"] = translations
    return segments


def normalize_audio(audio_path: Path, normalized_path: Path) -> Path:
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(audio_path),
            "-ac",
            "1",
            "-ar",
            "16000",
            str(normalized_path),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return normalized_path


def main() -> int:
    args = parse_args()
    deepseek_api_key = os.getenv("DEEPSEEK_API_KEY")

    require_binary("ffmpeg")
    require_binary("yt-dlp")

    task_dir = Path(args.output_file).resolve().parent
    task_dir.mkdir(parents=True, exist_ok=True)

    title, audio_path = download_audio(args.media_url, task_dir)
    normalized_path = normalize_audio(audio_path, task_dir / "normalized.wav")
    target_languages = [item.strip() for item in args.target_languages.split(",") if item.strip()]
    segments = transcribe_audio_locally(normalized_path, args.source_language, task_dir)
    if deepseek_api_key:
        segments = translate_segments_with_deepseek(deepseek_api_key, segments, target_languages)
    else:
        segments = translate_segments_locally(segments, target_languages)

    payload = {
        "mediaTitle": title,
        "segments": segments,
    }
    Path(args.output_file).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
