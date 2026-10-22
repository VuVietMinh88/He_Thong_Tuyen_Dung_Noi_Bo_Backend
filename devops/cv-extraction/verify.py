"""Real Docker/PDFBox/Tesseract checks using generated synthetic fixtures, never the development DB."""
import importlib.util
import json
from pathlib import Path
import sys
import time

spec = importlib.util.spec_from_file_location("cv_runner", Path(__file__).with_name("run.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def verify(directory):
    results = []
    for name in ["vi-text", "en-text", "vi-scan", "en-scan", "mixed", "same-page-mixed", "two-columns",
                 "reference", "ambiguous", "blank", "six-pages", "huge-page", "encrypted", "broken"]:
        start = time.monotonic()
        result = runner.extract(directory / (name + ".pdf"))
        outcome = result["outcome"]
        suggestions = result["suggestions"]
        expected_email = "an.nguyen@example.test" if name.startswith("vi-") or name in {"mixed", "same-page-mixed"} else "alice@example.test"
        checks = []
        if name in {"vi-text", "en-text", "vi-scan", "en-scan", "mixed", "same-page-mixed", "two-columns", "reference"}:
            checks.append(outcome == "COMPLETE")
            checks.append(any(c["normalizedValue"] == expected_email and c["preferred"] for c in suggestions["email"]))
            checks.append(any(c["normalizedValue"] == "+84912345678" and c["preferred"] for c in suggestions["phone"]))
            expected_name = "Nguyễn Văn An" if expected_email.startswith("an.") else "Alice Nguyen"
            checks.append(any(c["value"] == expected_name and c["preferred"] for c in suggestions["fullName"]))
        elif name == "ambiguous":
            checks.extend([outcome == "PARTIAL", "EMAIL_AMBIGUOUS" in result["warnings"],
                           not any(c["preferred"] for c in suggestions["email"])])
        elif name in {"blank", "six-pages"}:
            checks.append(outcome == "NO_DATA")
            if name == "six-pages":
                checks.extend([result["processedPages"] == 5, "FIRST_FIVE_PAGES_ONLY" in result["warnings"]])
        else:
            checks.append(outcome == "FAILED")
        results.append({"fixture": name, "passed": all(checks), "checks": len(checks),
                        "seconds": round(time.monotonic() - start, 3), "outcome": outcome,
                        "warnings": result.get("warnings", [])})
    # Contains fixture identifiers/metrics only, not extracted personal fields.
    return {"passed": all(item["passed"] for item in results), "results": results}


if __name__ == "__main__":
    summary = verify(Path(sys.argv[1]))
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    sys.exit(0 if summary["passed"] else 1)
