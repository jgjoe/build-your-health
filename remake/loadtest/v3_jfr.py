"""Read the observed Java 17 `jfr print --json` event array without loading the recording at once."""
import gzip
import json
import re


def events(path):
    decoder = json.JSONDecoder()
    with gzip.open(path, "rt", encoding="utf-8") as source:
        buffer = source.read(65536)
        marker = '"events": ['
        buffer = buffer[buffer.index(marker) + len(marker):]
        while True:
            buffer = buffer.lstrip(" \r\n\t,")
            if buffer.startswith("]"):
                return
            try:
                event, end = decoder.raw_decode(buffer)
            except json.JSONDecodeError:
                more = source.read(65536)
                if not more:
                    raise
                buffer += more
                continue
            yield event
            buffer = buffer[end:]


def duration_ms(value):
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?", value)
    if match is None or not any(match.groups()):
        raise ValueError(f"Unexpected Java Duration: {value}")
    hours, minutes, seconds = (float(part or 0) for part in match.groups())
    return (hours * 3600 + minutes * 60 + seconds) * 1000
