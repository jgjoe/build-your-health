import unittest
from evaluate_v3 import evaluate_run


class CriteriaTest(unittest.TestCase):
    def row(self, ok=3208):
        return {
            "run": "test", "login": {"started": 3240, "ok": ok, "rejected": 3240-ok,
                                     "unexpected_error": 0, "not_completed": 0},
            "latency": {kind: {"p95": 90} for kind in ("products", "history", "create", "login_rejected")},
            "nonlogin_errors": 0, "dropped": 0, "http_by_phase": {},
            "app_startup": {}, "app_cpu_cores_jmx": 1.8,
        }

    def test_99_percent_is_per_run_and_includes_rejections_in_denominator(self):
        self.assertTrue(evaluate_run(self.row(3208), True)["pass"])
        self.assertFalse(evaluate_run(self.row(3207), True)["pass"])

    def test_overload_does_not_require_99_percent_login_success(self):
        self.assertTrue(evaluate_run(self.row(2000), False)["pass"])

    def test_drop_and_every_unexpected_error_fail(self):
        for field in ("nonlogin_errors", "dropped"):
            row = self.row()
            row[field] = 1
            self.assertFalse(evaluate_run(row, True)["pass"])
        for field in ("unexpected_error", "not_completed"):
            row = self.row()
            row["login"][field] = 1
            row["login"]["ok"] -= 1
            self.assertFalse(evaluate_run(row, True)["pass"])

    def test_latency_bounds_are_strict(self):
        for kind in ("products", "history", "create", "login_rejected"):
            row = self.row()
            row["latency"][kind]["p95"] = 100 if kind == "login_rejected" else 500
            self.assertFalse(evaluate_run(row, True)["pass"])

    def test_no_rejections_does_not_invent_a_rejection_percentile(self):
        row = self.row(3240)
        del row["latency"]["login_rejected"]
        result = evaluate_run(row, True)
        self.assertTrue(result["pass"])
        self.assertIsNone(result["rejection_p95_ms"])


if __name__ == "__main__":
    unittest.main()
