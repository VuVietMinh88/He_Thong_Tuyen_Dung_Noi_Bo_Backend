from contextlib import redirect_stderr
import importlib.util
import io
import os
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('notify', Path(__file__).resolve().parents[1] / 'notify_failure.py')
notify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notify)


class NotificationTests(unittest.TestCase):
    def settings(self):
        return dict(SMTP_HOST='smtp.example.test', SMTP_PORT='587', SMTP_USERNAME='ci@example.test',
                    SMTP_PASSWORD='private-test-value', ALERT_TO='one@example.test, two@example.test',
                    RUN_URL='https://github.com/example/repo/actions/runs/123', BUILD_RESULT='failure', DEPLOY_RESULT='skipped')

    def test_alert_to_both_recipients_has_no_password(self):
        smtp = MagicMock()
        smtp.__enter__.return_value = smtp
        with patch.dict(os.environ, self.settings(), clear=True), patch.object(notify.ssl, 'create_default_context', return_value=object()), patch.object(notify.smtplib, 'SMTP', return_value=smtp):
            self.assertEqual(notify.main(), 0)
        smtp.starttls.assert_called_once()
        smtp.login.assert_called_once()
        message = smtp.send_message.call_args.args[0]
        self.assertIn('one@example.test', str(message['To']))
        self.assertIn('two@example.test', str(message['To']))
        self.assertNotIn('private-test-value', message.as_string())
        self.assertIn('Build/test: failure', message.get_content())

    def test_smtp_failure_is_not_reported_as_success_and_hides_details(self):
        errors = io.StringIO()
        with patch.dict(os.environ, self.settings(), clear=True), redirect_stderr(errors), patch.object(notify.ssl, 'create_default_context', return_value=object()), patch.object(notify.smtplib, 'SMTP', side_effect=RuntimeError('private-test-value')):
            self.assertEqual(notify.main(), 1)
        self.assertNotIn('private-test-value', errors.getvalue())

    def test_missing_secret_stops_before_connecting(self):
        settings = self.settings()
        del settings['SMTP_PASSWORD']
        with patch.dict(os.environ, settings, clear=True), patch.object(notify.smtplib, 'SMTP') as connect:
            with self.assertRaises(SystemExit):
                notify.main()
            connect.assert_not_called()
