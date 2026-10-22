"""Test the delivered PDF files against pre-authored expected data, without changing either PDFs or expectations."""
import argparse
import hashlib
import html
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import unicodedata

spec = importlib.util.spec_from_file_location("cv_layout_runner", Path(__file__).with_name("run.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def normalized(value):
    return unicodedata.normalize("NFC", value).strip().casefold()


def evaluate(result, expected):
    fields = {}
    for field, expected_key in [("fullName", "fullName"), ("email", "email"), ("phone", "phone")]:
        actual = result.get("suggestions", {}).get(field, [])
        values = [item["normalizedValue"] if field != "fullName" else item["value"] for item in actual]
        preferred = [item["normalizedValue"] if field != "fullName" else item["value"] for item in actual if item["preferred"]]
        target = expected[expected_key]
        candidate_found = any(normalized(value) == normalized(target) for value in values)
        if field == "email" and expected.get("emailPreferred") is False:
            correct = (not preferred and set(map(normalized, values)) == set(map(normalized, expected["emailCandidates"]))
                       and expected["warning"] in result.get("warnings", []))
        else:
            correct = len(preferred) == 1 and normalized(preferred[0]) == normalized(target)
        fields[field] = {"expected": target, "candidateFound": candidate_found, "correct": correct,
                         "preferred": preferred, "candidates": actual}
    expected_outcome = "PARTIAL" if expected.get("emailPreferred") is False else "COMPLETE"
    return fields, expected_outcome


def verify(directory, report_directory):
    manifest = json.loads((directory / "expected.json").read_text(encoding="utf-8"))
    image_id = subprocess.check_output(["docker", "image", "inspect", runner.IMAGE, "--format", "{{.Id}}"], text=True).strip()
    report_directory.mkdir(parents=True, exist_ok=True)
    cases = []
    for entry in manifest["samples"]:
        path = directory / entry["file"]
        before = hashlib.sha256(path.read_bytes()).hexdigest()
        if before != entry["sha256"]:
            raise ValueError("PDF differs from pre-authored manifest: " + entry["file"])
        started = time.monotonic()
        result = runner.extract(path, image=image_id)
        seconds = round(time.monotonic() - started, 3)
        fields, expected_outcome = evaluate(result, entry["expected"])
        assert hashlib.sha256(path.read_bytes()).hexdigest() == before
        passed = result.get("outcome") == expected_outcome and all(field["correct"] for field in fields.values())
        case = {"file": entry["file"], "description": entry["description"], "kind": entry["kind"],
                "sha256": before, "passed": passed, "seconds": seconds, "fields": fields,
                "expectedOutcome": expected_outcome, "result": result}
        cases.append(case)
        (report_directory / (path.stem + ".json")).write_text(json.dumps(case, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"file": entry["file"], "passed": passed, "outcome": result.get("outcome"),
                          "fields": {key: value["correct"] for key, value in fields.items()}}, ensure_ascii=True), flush=True)
    summary = {"imageId": image_id, "synthetic": True, "total": len(cases),
               "passed": sum(case["passed"] for case in cases), "failed": sum(not case["passed"] for case in cases),
               "fieldResults": {field: {"correct": sum(case["fields"][field]["correct"] for case in cases),
                                         "candidateFound": sum(case["fields"][field]["candidateFound"] for case in cases),
                                         "total": len(cases)} for field in ["fullName", "email", "phone"]}, "cases": cases}
    (report_directory / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    rows = []
    for case in cases:
        field_cells = []
        for field in ["fullName", "email", "phone"]:
            data = case["fields"][field]
            actual = ", ".join(data["preferred"]) or "Chưa có lựa chọn ưu tiên"
            field_cells.append("<td class='" + ("pass" if data["correct"] else "fail") + "'>"
                               + html.escape(actual) + "<small>Kỳ vọng: " + html.escape(data["expected"]) + "</small></td>")
        rows.append("<tr><td><a href='../" + html.escape(case["file"]) + "'>" + html.escape(case["file"]) + "</a><small>"
                    + html.escape(case["description"]) + "</small></td>" + "".join(field_cells)
                    + "<td>" + ("ĐẠT" if case["passed"] else "CHƯA ĐẠT") + "<small>" + str(case["seconds"]) + " giây</small></td></tr>")
    page = """<!doctype html><html lang='vi'><meta charset='utf-8'><title>Kiểm thử CV nhiều bố cục</title>
<style>body{font:15px Arial,sans-serif;background:#f3f6fa;color:#17324d;padding:30px}main{max-width:1400px;margin:auto}
table{border-collapse:collapse;width:100%;background:white}th,td{text-align:left;vertical-align:top;border:1px solid #d8e2ec;padding:14px}
th{background:#17324d;color:white}small{display:block;color:#526171;font-size:12px;margin-top:8px}.pass{background:#edf8f1}.fail{background:#fff0ed}
a{color:#125f8a}h1{font-size:28px}code{word-break:break-all}</style><main><h1>Kiểm thử CV nhiều bố cục</h1>"""
    page += f"<p><b>{summary['passed']}/{summary['total']} mẫu đạt toàn bộ tiêu chí.</b> Dữ liệu giả lập; kỳ vọng được lưu trước khi chạy bộ trích.</p>"
    page += "<p>File được kiểm tra SHA-256 trước và sau khi chạy. Xem JSON từng ca để đọc đầy đủ lựa chọn/cảnh báo. Hai email ngang nhau phải giữ trạng thái cần xác nhận.</p>"
    page += "<table><thead><tr><th>PDF</th><th>Họ tên</th><th>Email</th><th>Điện thoại</th><th>Kết quả</th></tr></thead><tbody>" + "".join(rows) + "</tbody></table>"
    page += "<p>Image được kiểm thử: <code>" + html.escape(image_id) + "</code></p></main></html>"
    (report_directory / "report.html").write_text(page, encoding="utf-8")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--report-directory", type=Path, required=True)
    args = parser.parse_args()
    summary = verify(args.directory, args.report_directory)
    print(json.dumps({key: value for key, value in summary.items() if key != "cases"}, ensure_ascii=True))
