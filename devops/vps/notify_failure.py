"""Send only a run link/status to the explicitly configured team recipients."""
from email.message import EmailMessage
import os
import smtplib
import ssl
import sys


def main():
    required = ["SMTP_HOST", "SMTP_USERNAME", "SMTP_PASSWORD", "ALERT_TO", "RUN_URL"]
    missing = [key for key in required if not os.environ.get(key)]
    if missing:
        raise SystemExit("Missing notification settings: " + ", ".join(missing))
    message = EmailMessage()
    message["Subject"] = "[TTCS] FAILED: build/test or VPS deployment"
    message["From"] = os.environ["SMTP_USERNAME"]
    message["To"] = os.environ["ALERT_TO"]
    message.set_content(
        "TTCS backend pipeline FAILED.\n"
        f"Build/test: {os.environ.get('BUILD_RESULT', 'unknown')}\n"
        f"Deploy: {os.environ.get('DEPLOY_RESULT', 'unknown')}\n"
        "Check the run for DEPLOY_OK / ROLLBACK_OK / ROLLBACK_FAILED.\n"
        "A build/test failure does not change the VPS. A deploy failure may need recovery.\n"
        "If the deploy log shows FAILED without a BACKUP: line, the running site was not touched.\n"
        f"Run: {os.environ['RUN_URL']}\n"
    )
    context = ssl.create_default_context()
    port = int(os.environ.get("SMTP_PORT", "587"))
    try:
        if port == 465:
            connection = smtplib.SMTP_SSL(os.environ["SMTP_HOST"], port, timeout=30, context=context)
        else:
            connection = smtplib.SMTP(os.environ["SMTP_HOST"], port, timeout=30)
        with connection as smtp:
            if port != 465:
                smtp.starttls(context=context)
            smtp.login(os.environ["SMTP_USERNAME"], os.environ["SMTP_PASSWORD"])
            smtp.send_message(message)
    except Exception as error:
        print(f"Notification failed ({type(error).__name__}); see GitHub Actions status.", file=sys.stderr)
        return 1
    print("Failure email sent to the configured team recipients.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
