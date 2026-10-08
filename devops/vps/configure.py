"""Run interactively ON THE VPS. Never copy the development .env here."""
import base64
import getpass
import json
import os
from pathlib import Path
import re
import secrets


def dotenv_value(value):
    # JSON quotes escape backslashes/quotes; $$ prevents Compose interpolation.
    if any(c in value for c in "\r\n\x00"):
        raise ValueError("Gia tri phai nam tren mot dong.")
    return json.dumps(value, ensure_ascii=False).replace("$", "$$")


def main():
    root = Path(__file__).resolve().parent
    os.umask(0o077)
    if (root / ".env").exists():
        raise SystemExit(".env da ton tai; khong ghi de hoac doi mat khau database.")
    email = input("Email quan tri [support@internal-hire.com]: ").strip() or "support@internal-hire.com"
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email):
        raise SystemExit("Email khong hop le.")
    mail_password = getpass.getpass("Mat khau hop thu support@internal-hire.com (khong hien ky tu): ")
    if not mail_password:
        raise SystemExit("Can mat khau SMTP de backend gui email.")
    admin_password = "Aa1!" + secrets.token_urlsafe(24)
    values = {
        "DB_PASSWORD": secrets.token_urlsafe(36),
        "AUTH_JWT_SECRET": base64.b64encode(secrets.token_bytes(48)).decode(),
        "BOOTSTRAP_ADMIN_ENABLED": "true",
        "BOOTSTRAP_ADMIN_EMAIL": email,
        "BOOTSTRAP_ADMIN_PASSWORD": admin_password,
        "MAIL_PASSWORD": mail_password,
    }
    with (root / ".env").open("x", encoding="utf-8", newline="\n") as out:
        out.write("# Generated on this VPS. Private: do not commit or share.\n")
        out.writelines(f"{key}={dotenv_value(value)}\n" for key, value in values.items())
    with (root / "LOGIN.txt").open("x", encoding="utf-8", newline="\n") as out:
        out.write(f"Website: https://internal-hire.com\nEmail: {email}\nPassword: {admin_password}\n")
    print("Da tao .env va LOGIN.txt. Doc LOGIN.txt tren VPS; khong gui noi dung vao chat.")


if __name__ == "__main__":
    main()
