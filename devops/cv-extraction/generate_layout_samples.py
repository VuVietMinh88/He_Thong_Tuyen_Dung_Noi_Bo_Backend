"""Create reproducible, synthetic CVs with independent expected data and visual previews.

Install reportlab, pymupdf, pypdf into the operator's tooling environment, not application dependencies.
The output manifest is authored before extraction; the generator never calls the extraction engine.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import pymupdf as fitz
from PIL import Image, ImageDraw
from pypdf import PdfReader
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

W, H = A4
VI = {"name": "Nguyễn Minh Anh", "email": "minh.anh@example.test", "phone": "0912 345 678",
      "normalizedPhone": "+84912345678", "role": "CHUYÊN VIÊN PHÁT TRIỂN PHẦN MỀM"}
EN = {"name": "Emily Carter", "email": "emily.carter@example.test", "phone": "+84 987 654 321",
      "normalizedPhone": "+84987654321", "role": "SOFTWARE ENGINEER"}
NAVY = colors.HexColor("#17324d")
TEAL = colors.HexColor("#137c85")
GRAY = colors.HexColor("#526171")
LIGHT = colors.HexColor("#edf3f6")


def register_fonts(font_directory):
    for name, file in [("CV", "arial.ttf"), ("CV-Bold", "arialbd.ttf"), ("CV-Italic", "ariali.ttf")]:
        pdfmetrics.registerFont(TTFont(name, str(font_directory / file)))


def text(c, x, y, value, size=10, bold=False, color=NAVY):
    c.setFillColor(color)
    c.setFont("CV-Bold" if bold else "CV", size)
    c.drawString(x, H - y, value)


def paragraph(c, x, y, value, width, size=10, color=GRAY, leading=16):
    words, line = value.split(), ""
    for word in words:
        candidate = (line + " " + word).strip()
        if pdfmetrics.stringWidth(candidate, "CV", size) > width and line:
            text(c, x, y, line, size, color=color)
            y += leading
            line = word
        else:
            line = candidate
    if line:
        text(c, x, y, line, size, color=color)
    return y + leading


def heading(c, x, y, value, width=490, color=TEAL):
    text(c, x, y, value, 12, True, color)
    c.setStrokeColor(colors.HexColor("#d3e0e7"))
    c.line(x, H - y - 8, x + width, H - y - 8)
    return y + 31


def footer(c, number=1, note="CV giả lập dùng để kiểm thử - không phải hồ sơ người thật"):
    c.setStrokeColor(colors.HexColor("#d8e2e8"))
    c.line(42, 43, W - 42, 43)
    text(c, 42, H - 27, note, 8, color=GRAY)
    text(c, W - 57, H - 27, str(number), 8, color=GRAY)


def contact(c, person, x, y, labelled_name=True, width=490, color=NAVY):
    prefix = "Họ và tên: " if person == VI else "Full name: "
    text(c, x, y, prefix + person["name"] if labelled_name else person["name"], 15, True, color)
    text(c, x, y + 29, "Email: " + person["email"], 10, color=color)
    prefix = "Điện thoại: " if person == VI else "Phone: "
    text(c, x, y + 50, prefix + person["phone"], 10, color=color)


def body(c, x, y, width=490, english=False):
    title = "PROFILE" if english else "GIỚI THIỆU"
    y = heading(c, x, y, title, width)
    value = ("Software engineer with four years of experience building reliable recruitment and internal business systems. "
             "Enjoys clear requirements, automated tests and collaborative code reviews." if english else
             "Có bốn năm kinh nghiệm xây dựng hệ thống nghiệp vụ nội bộ. Quan tâm đến chất lượng phần mềm, "
             "kiểm thử tự động và khả năng bảo trì. Mong muốn phát triển sản phẩm phục vụ người dùng thực tế.")
    y = paragraph(c, x, y, value, width) + 20
    y = heading(c, x, y, "WORK EXPERIENCE" if english else "KINH NGHIỆM", width)
    text(c, x, y, "2023 - 2026 | Demo Technology", 10, True)
    y += 23
    for line in (["Built Java APIs and PostgreSQL data models for internal products.",
                  "Added automated tests, monitoring and transactional safeguards.",
                  "Worked with product owners and reviewed pull requests."] if english else
                 ["Xây dựng API Java và mô hình dữ liệu PostgreSQL cho sản phẩm nội bộ.",
                  "Viết kiểm thử tự động, tối ưu truy vấn và xử lý giao dịch.",
                  "Phối hợp phân tích nghiệp vụ và review thay đổi trong nhóm."]):
        y = paragraph(c, x, y, "- " + line, width) + 5
    y += 19
    y = heading(c, x, y, "EDUCATION" if english else "HỌC VẤN", width)
    y = paragraph(c, x, y, "2018 - 2022 | Bachelor of Software Engineering" if english else
                  "2018 - 2022 | Cử nhân Công nghệ thông tin", width) + 22
    y = heading(c, x, y, "SKILLS" if english else "KỸ NĂNG", width)
    paragraph(c, x, y, "Java, Spring Boot, PostgreSQL, Docker, Git, REST API, JUnit", width)


def make_pdf(path, draw):
    c = canvas.Canvas(str(path), pagesize=A4, pageCompression=1, invariant=1)
    c.setTitle(path.stem)
    c.setAuthor("Synthetic CV test fixtures")
    draw(c)
    c.save()


def classic(c):
    text(c, 45, 51, "HỒ SƠ ỨNG TUYỂN", 11, True, TEAL)
    contact(c, VI, 45, 91)
    text(c, 45, 172, VI["role"], 10, color=GRAY)
    body(c, 45, 217)
    footer(c)


def sidebar(c):
    c.setFillColor(NAVY)
    c.rect(0, 0, 192, H, fill=1, stroke=0)
    c.setFillColor(TEAL)
    c.circle(96, H - 80, 35, fill=1, stroke=0)
    text(c, 72, 88, "NMA", 21, True, colors.white)
    text(c, 24, 162, "LIÊN HỆ", 11, True, colors.white)
    text(c, 24, 193, "Email:", 9, True, colors.white)
    text(c, 24, 213, VI["email"], 8.5, color=colors.white)
    text(c, 24, 250, "Điện thoại:", 9, True, colors.white)
    text(c, 24, 271, VI["phone"], 10, color=colors.white)
    text(c, 24, 326, "KỸ NĂNG", 11, True, colors.white)
    for index, value in enumerate(["Java / Spring Boot", "PostgreSQL", "Docker / Git", "Kiểm thử tự động"]):
        text(c, 24, 356 + index * 26, value, 10, color=colors.white)
    text(c, 218, 69, VI["name"], 25, True)
    paragraph(c, 218, 100, VI["role"], 330, 10, TEAL)
    body(c, 218, 161, 330)
    text(c, 218, H - 28, "Dữ liệu giả lập - trang 1", 8, color=GRAY)


def columns(c):
    text(c, 43, 61, EN["name"], 28, True)
    text(c, 43, 93, EN["role"], 10, color=TEAL)
    text(c, 43, 124, "Email: " + EN["email"], 10)
    text(c, 319, 124, "Phone: " + EN["phone"], 10)
    body(c, 43, 182, 230, english=True)
    y = heading(c, 319, 182, "PROJECTS", 230)
    for title in ["Recruitment portal", "Internal reporting", "Document processing"]:
        text(c, 319, y, title, 11, True)
        y = paragraph(c, 319, y + 24, "Designed APIs, added tests and documented operational behavior for the delivery team.", 230) + 32
    footer(c, note="Synthetic candidate - layout test only")


def table(c):
    text(c, 45, 58, "THÔNG TIN ỨNG VIÊN", 24, True)
    rows = [("Họ và tên:", VI["name"]), ("Email:", VI["email"]), ("Điện thoại:", VI["phone"]),
            ("Vị trí ứng tuyển:", "Chuyên viên phần mềm")]
    for i, (label, value) in enumerate(rows):
        top = 90 + i * 44
        c.setFillColor(LIGHT if i % 2 == 0 else colors.white)
        c.rect(45, H - top - 44, 505, 44, fill=1, stroke=0)
        text(c, 59, top + 27, label, 10, True)
        text(c, 217, top + 27, value, 10)
    body(c, 45, 313)
    footer(c)


def dark(c):
    c.setFillColor(NAVY)
    c.rect(0, H - 201, W, 201, fill=1, stroke=0)
    text(c, 45, 53, "APPLICATION PROFILE", 10, True, colors.HexColor("#83d6d4"))
    contact(c, EN, 45, 98, labelled_name=False, color=colors.white)
    body(c, 45, 244, english=True)
    footer(c, note="Synthetic candidate - dark header test")


def creative(c):
    c.setFillColor(colors.HexColor("#f5efe5"))
    c.rect(0, 0, W, H, fill=1, stroke=0)
    c.setFillColor(colors.HexColor("#bf633e"))
    c.rect(43, H - 177, 7, 129, fill=1, stroke=0)
    text(c, 70, 76, VI["name"], 30, True)
    text(c, 70, 106, "KỸ SƯ PHẦN MỀM", 11, True, colors.HexColor("#9b4b2c"))
    text(c, 70, 139, "Email: " + VI["email"], 10)
    text(c, 70, 162, "Điện thoại: " + VI["phone"], 10)
    body(c, 45, 221)
    footer(c)


def multipage(c):
    text(c, 45, 60, VI["name"], 26, True)
    text(c, 45, 94, "HỒ SƠ NĂNG LỰC", 12, True, TEAL)
    body(c, 45, 151)
    footer(c, 1)
    c.showPage()
    text(c, 45, 63, "THÔNG TIN BỔ SUNG", 20, True)
    contact(c, VI, 45, 119)
    y = heading(c, 45, 236, "DỰ ÁN TIÊU BIỂU")
    paragraph(c, 45, y, "Cổng tuyển dụng nội bộ: thiết kế API, kiểm thử giao dịch và hướng dẫn vận hành. "
              "Hệ thống báo cáo: tổng hợp dữ liệu và xuất báo cáo theo phòng ban.", 490)
    footer(c, 2)


def references(c):
    contact(c, EN, 45, 63)
    body(c, 45, 162, english=True)
    y = heading(c, 45, 662, "REFERENCES")
    text(c, 45, y, "Full name: Michael Brown", 10)
    text(c, 45, y + 22, "Email: michael.brown@example.test", 10)
    text(c, 45, y + 44, "Phone: 0901 222 333", 10)
    footer(c, note="Synthetic applicant and referee - no real personal data")


def ambiguous(c):
    contact(c, VI, 45, 66)
    text(c, 45, 140, "Email: minh.anh.backup@example.test", 10)
    text(c, 45, 180, "Ứng viên cung cấp hai địa chỉ liên hệ có cùng mức ưu tiên.", 10, color=GRAY)
    body(c, 45, 229)
    footer(c)


def rasterize(source, target, dpi, grayscale=False):
    document = fitz.open(source)
    c = canvas.Canvas(str(target), pagesize=A4, pageCompression=1, invariant=1)
    c.setTitle(target.stem)
    c.setAuthor("Synthetic scan - no hidden text layer")
    for page in document:
        pixmap = page.get_pixmap(dpi=dpi, colorspace=fitz.csGRAY if grayscale else fitz.csRGB)
        image = Image.open(io.BytesIO(pixmap.tobytes("png")))
        c.drawImage(ImageReader(image), 0, 0, W, H)
        c.showPage()
    c.save()
    document.close()


def mixed(c, preview_directory):
    buffer = io.BytesIO()
    card = canvas.Canvas(buffer, pagesize=A4, pageCompression=1, invariant=1)
    contact(card, VI, 45, 65)
    card.save()
    with fitz.open(stream=buffer.getvalue(), filetype="pdf") as document:
        pixmap = document[0].get_pixmap(dpi=220, clip=fitz.Rect(30, 30, 565, 140))
    image = Image.open(io.BytesIO(pixmap.tobytes("png")))
    c.drawImage(ImageReader(image), 30, H - 140, width=535, height=110)
    body(c, 45, 205)
    footer(c)


def main(output, font_directory):
    output.mkdir(parents=True, exist_ok=True)
    previews = output / "previews"
    previews.mkdir(exist_ok=True)
    register_fonts(font_directory)
    samples = [
        ("01-classic-vietnamese", "Một cột, tiếng Việt, các trường có nhãn", classic, VI),
        ("02-sidebar-vietnamese", "Sidebar tối, tên không có nhãn", sidebar, VI),
        ("03-two-columns-english", "Hai cột, email và điện thoại cùng hàng", columns, EN),
        ("04-table-vietnamese", "Thông tin dạng bảng với nhãn và giá trị ở hai ô", table, VI),
        ("05-dark-header-english", "Header nền tối, chữ trắng, tên không nhãn", dark, EN),
        ("06-creative-vietnamese", "Bố cục sáng tạo, nền kem, tên lớn", creative, VI),
        ("07-two-pages-contact-page-2", "Hai trang, email/điện thoại ở trang 2", multipage, VI),
        ("08-applicant-and-reference", "Có người tham chiếu với đủ thông tin riêng", references, EN),
        ("09-two-equal-emails", "Hai email cùng mức ưu tiên, cần xác nhận", ambiguous, VI),
    ]
    manifest = []
    for stem, description, draw, person in samples:
        path = output / (stem + ".pdf")
        make_pdf(path, draw)
        manifest.append({"file": path.name, "description": description, "kind": "text", "expected": {
            "fullName": person["name"], "email": person["email"], "phone": person["normalizedPhone"]}})
    manifest[-1]["expected"]["emailCandidates"] = [VI["email"], "minh.anh.backup@example.test"]
    manifest[-1]["expected"]["emailPreferred"] = False
    manifest[-1]["expected"]["warning"] = "EMAIL_AMBIGUOUS"
    for name, source, dpi, gray in [
            ("10-scan-classic-220dpi", "01-classic-vietnamese", 220, False),
            ("11-scan-sidebar-150dpi", "02-sidebar-vietnamese", 150, True)]:
        rasterize(output / (source + ".pdf"), output / (name + ".pdf"), dpi, gray)
        manifest.append({"file": name + ".pdf", "description": f"Bản scan {dpi}DPI, không có lớp chữ",
                         "kind": "scan", "expected": {"fullName": VI["name"], "email": VI["email"], "phone": VI["normalizedPhone"]}})
    make_pdf(output / "12-image-contact-text-body.pdf", lambda c: mixed(c, previews))
    manifest.append({"file": "12-image-contact-text-body.pdf", "description": "Liên hệ là ảnh, body là chữ thật",
                     "kind": "mixed", "expected": {"fullName": VI["name"], "email": VI["email"], "phone": VI["normalizedPhone"]}})
    contact_sheet = Image.new("RGB", (1200, 4 * 465), "#e6edf2")
    draw_sheet = ImageDraw.Draw(contact_sheet)
    for index, entry in enumerate(manifest):
        path = output / entry["file"]
        entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        reader = PdfReader(path)
        entry["pages"] = len(reader.pages)
        if entry["kind"] == "scan":
            assert all(not page.extract_text().strip() for page in reader.pages), "Scan unexpectedly contains text"
        document = fitz.open(path)
        for number, page in enumerate(document, 1):
            image_path = previews / f"{path.stem}-page-{number}.png"
            page.get_pixmap(dpi=110).save(image_path)
        thumbnail = Image.open(previews / f"{path.stem}-page-1.png").convert("RGB")
        thumbnail.thumbnail((300, 410))
        left, top = (index % 3) * 400 + 45, (index // 3) * 465 + 12
        contact_sheet.paste(thumbnail, (left, top))
        draw_sheet.text((left, top + 422), path.stem[:34], fill="#17324d")
        document.close()
    contact_sheet.save(previews / "all-layouts.png")
    (output / "expected.json").write_text(json.dumps({"synthetic": True, "samples": manifest}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"pdfCount": len(manifest), "output": str(output.resolve()), "scanTextLayerChecked": True}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--font-directory", type=Path, default=Path("C:/Windows/Fonts"))
    args = parser.parse_args()
    main(args.output, args.font_directory)
