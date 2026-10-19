import gzip
import json
from pathlib import Path
import tempfile
import unittest

from v3_jfr import duration_ms, events


class JfrParsingTest(unittest.TestCase):
    def test_java_duration_units(self):
        self.assertAlmostEqual(duration_ms("PT0.000044415S"), .044415)
        self.assertEqual(duration_ms("PT1M2.5S"), 62500)
        self.assertEqual(duration_ms("PT1H"), 3600000)
        self.assertEqual(duration_ms("PT0S"), 0)
        with self.assertRaises(ValueError):
            duration_ms("PT")

    def test_streams_records_across_chunks_and_preserves_unicode(self):
        expected = [
            {"type": "sample", "values": {"text": "가" * 70000}},
            {"type": "sample", "values": {"duration": "PT0S", "nested": {"value": "[,]"}}},
        ]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "events.json.gz"
            with gzip.open(path, "wt", encoding="utf-8") as output:
                json.dump({"recording": {"events": expected}}, output, ensure_ascii=False)
            self.assertEqual(list(events(path)), expected)


if __name__ == "__main__":
    unittest.main()
