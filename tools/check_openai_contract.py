#!/usr/bin/env python3
"""Validate generated wire fixtures with the official OpenAI SDK (openai==3.41.0)."""
import argparse
import json
from pathlib import Path

import openai
from openai.types.chat import ChatCompletion, ChatCompletionChunk
from openai.types.responses import Response
from openai.types.responses.response_stream_event import ResponseStreamEvent
from pydantic import BaseModel, TypeAdapter

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", nargs="?", type=Path,
                    default=Path(__file__).resolve().parents[1] / "Android/src/app/build/contract-fixtures/testDebugUnitTest")
args = parser.parse_args()

def read(name):
    return json.loads((args.directory / name).read_text(encoding="utf-8"))

def standard_only(value):
    """SDK models allow unknown fields by default; forbid them for our public wire contract."""
    if isinstance(value, BaseModel):
        extras = value.model_extra or {}
        if extras:
            raise AssertionError(f"Nonstandard fields in {type(value).__name__}: {list(extras)}")
        for name in type(value).model_fields:
            standard_only(getattr(value, name, None))
    elif isinstance(value, list):
        for item in value:
            standard_only(item)
    elif isinstance(value, dict):
        for item in value.values():
            standard_only(item)

standard_only(ChatCompletion.model_validate(read("chat.json")))
chat_chunks = read("chat-events.json")
for chunk in chat_chunks:
    standard_only(ChatCompletionChunk.model_validate(chunk))
for name in ("response.json", "response-tools.json"):
    standard_only(Response.model_validate(read(name)))
adapter = TypeAdapter(ResponseStreamEvent)
response_events = read("response-events.json") + read("response-tool-events.json") + read("response-error-events.json")
for event in response_events:
    standard_only(adapter.validate_python(event))
print(f"Official OpenAI SDK {openai.__version__}: 3 responses, "
      f"{len(chat_chunks)} chat chunks and {len(response_events)} Responses events validated; no extra fields.")
