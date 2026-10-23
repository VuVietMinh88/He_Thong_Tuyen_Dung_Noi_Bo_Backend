package vn.ttcs.recruitment.invitation;

/**
 * The public page where a candidate answers an interview invitation, and its script. The email links to the page with
 * the token in the URL fragment (#token=...), which the browser never sends to a server. The script takes the token,
 * removes the fragment from the address bar and history before any request, and keeps it only in a local variable:
 * no cookie, no storage, never written into the page.
 *
 * <p>Opening the page changes nothing: the script only looks the invitation up (POST lookup), and answers it (POST
 * respond) when the candidate presses one of the two buttons. Every value from the server is shown with textContent,
 * and the meeting link becomes a link only when it is an https URL. The page has no inline script and loads nothing
 * but its own script, so it works under the strict Content-Security-Policy the controller sends with it.
 */
public final class InterviewReplyPage {
    static final String SCRIPT_PATH = "/api/v1/public/interview-invitations/reply.js";

    private static final String HTML = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="referrer" content="no-referrer">
            <meta name="robots" content="noindex, nofollow">
            <title>Phản hồi lời mời phỏng vấn</title>
            <style>
            body { margin: 0; background: #f3f4f6; color: #111827; font-family: Arial, Helvetica, sans-serif; line-height: 1.5; }
            main { max-width: 640px; margin: 24px auto; padding: 24px; background: #ffffff; border: 1px solid #e5e7eb; border-radius: 8px; }
            h1 { margin-top: 0; font-size: 1.5rem; }
            h2 { font-size: 1.15rem; }
            dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 16px; }
            dt { font-weight: bold; }
            dd { margin: 0; white-space: pre-line; overflow-wrap: anywhere; }
            label { display: block; font-weight: bold; margin-bottom: 4px; }
            textarea { box-sizing: border-box; width: 100%; font: inherit; padding: 8px; }
            .actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; }
            button { font: inherit; padding: 10px 18px; border-radius: 6px; border: 1px solid #1d4ed8; background: #2563eb; color: #ffffff; cursor: pointer; }
            button.secondary { background: #ffffff; color: #1d4ed8; }
            button:disabled { opacity: 0.55; cursor: not-allowed; }
            button:focus-visible, textarea:focus-visible, a:focus-visible { outline: 3px solid #f59e0b; outline-offset: 2px; }
            .hint { color: #4b5563; font-size: 0.9rem; }
            .error { color: #b91c1c; }
            [hidden] { display: none !important; }
            </style>
            <script src="/api/v1/public/interview-invitations/reply.js" defer></script>
            </head>
            <body>
            <main>
            <h1>Phản hồi lời mời phỏng vấn</h1>
            <p id="status" role="status" aria-live="polite">Đang tải thông tin lời mời…</p>
            <noscript><p>Trang này cần bật JavaScript để hiển thị và phản hồi lời mời.</p></noscript>
            <button type="button" id="retry" class="secondary" hidden>Thử lại</button>
            <section id="details" aria-labelledby="details-title" hidden>
            <h2 id="details-title">Thông tin buổi phỏng vấn</h2>
            <dl id="facts"></dl>
            </section>
            <section id="reply" aria-labelledby="reply-title" hidden>
            <h2 id="reply-title">Phản hồi của bạn</h2>
            <label for="note">Ghi chú</label>
            <textarea id="note" rows="4" maxlength="2000" aria-describedby="note-hint"></textarea>
            <p id="note-hint" class="hint">Bắt buộc khi đề nghị đổi lịch: hãy nêu lý do hoặc khung giờ bạn có thể tham dự. Tối đa 2000 ký tự. Bạn chỉ có thể phản hồi một lần.</p>
            <div class="actions">
            <button type="button" id="confirm">Xác nhận tham dự</button>
            <button type="button" id="reschedule" class="secondary">Đề nghị đổi lịch</button>
            </div>
            <p id="reply-error" class="error" role="alert"></p>
            </section>
            </main>
            </body>
            </html>
            """;

    private static final String SCRIPT = """
            (function () {
              'use strict';

              // Taken before anything else, and removed from the address bar and history before any request.
              var token = null;
              try {
                var fragment = window.location.hash;
                if (fragment) {
                  window.history.replaceState(null, '', window.location.pathname + window.location.search);
                }
                var match = /^#token=([A-Za-z0-9_-]{43})$/.exec(fragment);
                token = match ? match[1] : null;
              } catch (ignored) {
                token = null;
              }

              var BASE = '/api/v1/public/interview-invitations/';
              var MAX_NOTE = 2000;
              var STATUS_LABELS = {
                PENDING: 'Chưa phản hồi',
                CONFIRMED: 'Đã xác nhận tham dự',
                RESCHEDULE_REQUESTED: 'Đã đề nghị đổi lịch'
              };
              var MODE_LABELS = {
                ONLINE: 'Trực tuyến',
                ONSITE: 'Trực tiếp',
                OFFLINE: 'Trực tiếp',
                IN_PERSON: 'Trực tiếp',
                PHONE: 'Qua điện thoại'
              };
              var MESSAGES = {
                missing: 'Không tìm thấy mã phản hồi. Vui lòng mở lại liên kết trong email mời phỏng vấn.',
                invalid: 'Liên kết không hợp lệ hoặc đã hết hạn. Vui lòng liên hệ bộ phận tuyển dụng nếu bạn cần hỗ trợ.',
                tooMany: 'Bạn đã thử quá nhiều lần. Vui lòng đợi vài phút rồi thử lại.',
                unavailable: 'Hệ thống tạm thời gián đoạn. Vui lòng thử lại sau ít phút.',
                network: 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra kết nối mạng và thử lại.',
                conflict: 'Lời mời này đã được phản hồi trước đó và không thể thay đổi. Nếu cần hỗ trợ, vui lòng liên hệ bộ phận tuyển dụng.',
                noteRequired: 'Vui lòng nhập lý do hoặc khung giờ bạn có thể tham dự trước khi đề nghị đổi lịch.',
                noteTooLong: 'Ghi chú tối đa 2000 ký tự.',
                noteInvalid: 'Vui lòng nhập ghi chú hợp lệ, tối đa 2000 ký tự, rồi thử lại.',
                loading: 'Đang tải thông tin lời mời…',
                sending: 'Đang gửi phản hồi…',
                pending: 'Vui lòng chọn một trong hai phản hồi bên dưới.',
                confirmed: 'Bạn đã xác nhận tham dự buổi phỏng vấn. Cảm ơn bạn!',
                rescheduleRequested: 'Bạn đã gửi đề nghị đổi lịch. Bộ phận tuyển dụng sẽ xem xét đề nghị của bạn.',
                unknown: 'Lời mời này hiện không thể phản hồi. Vui lòng liên hệ bộ phận tuyển dụng nếu bạn cần hỗ trợ.'
              };

              var statusText = document.getElementById('status');
              var retryButton = document.getElementById('retry');
              var details = document.getElementById('details');
              var facts = document.getElementById('facts');
              var reply = document.getElementById('reply');
              var note = document.getElementById('note');
              var confirmButton = document.getElementById('confirm');
              var rescheduleButton = document.getElementById('reschedule');
              var replyError = document.getElementById('reply-error');
              var responseFact = null;
              var busy = false;
              var locked = true;

              var dateFormat = new Intl.DateTimeFormat('vi-VN', {
                timeZone: 'Asia/Ho_Chi_Minh',
                weekday: 'long',
                day: '2-digit',
                month: '2-digit',
                year: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
                hour12: false
              });

              function own(map, key) {
                return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : null;
              }

              function text(value) {
                return typeof value === 'string' ? value.trim() : '';
              }

              function formatInstant(value) {
                var date = null;
                if (typeof value === 'string' && value) {
                  date = new Date(value);
                } else if (typeof value === 'number') {
                  date = new Date(value * 1000);
                }
                if (!date || isNaN(date.getTime())) {
                  return '';
                }
                return dateFormat.format(date) + ' (giờ Việt Nam)';
              }

              function httpsUrl(value) {
                if (typeof value !== 'string' || value.length > 2048) {
                  return null;
                }
                try {
                  var url = new URL(value.trim());
                  if (url.protocol === 'https:' && url.hostname && !url.username && !url.password) {
                    return url.href;
                  }
                } catch (ignored) {
                  // Not a URL: shown as text.
                }
                return null;
              }

              function showStatus(message) {
                statusText.textContent = message;
              }

              function setControls() {
                var disabled = busy || locked;
                confirmButton.disabled = disabled;
                rescheduleButton.disabled = disabled;
                note.disabled = disabled;
              }

              function addFact(label, value, href) {
                if (!value) {
                  return null;
                }
                var term = document.createElement('dt');
                term.textContent = label;
                var description = document.createElement('dd');
                if (href) {
                  var anchor = document.createElement('a');
                  anchor.href = href;
                  anchor.rel = 'noopener noreferrer';
                  anchor.target = '_blank';
                  anchor.textContent = href;
                  description.appendChild(anchor);
                } else {
                  description.textContent = value;
                }
                facts.appendChild(term);
                facts.appendChild(description);
                return description;
              }

              function renderDetails(data) {
                while (facts.firstChild) {
                  facts.removeChild(facts.firstChild);
                }
                var mode = text(data.mode);
                var meeting = text(data.meetingUrl);
                addFact('Vị trí', text(data.positionTitle));
                addFact('Vòng phỏng vấn', text(data.roundName));
                addFact('Bắt đầu', formatInstant(data.startsAt));
                addFact('Kết thúc', formatInstant(data.endsAt));
                addFact('Hình thức', own(MODE_LABELS, mode.toUpperCase()) || mode);
                addFact('Địa điểm', text(data.room));
                addFact('Liên kết phòng họp', meeting, httpsUrl(meeting));
                addFact('Hướng dẫn chuẩn bị', text(data.preparationInstructions));
                addFact('Hạn phản hồi', formatInstant(data.expiresAt));
                responseFact = addFact('Trạng thái phản hồi', ' ');
                details.hidden = false;
                reply.hidden = false;
              }

              function renderStatus(status, respondedAt) {
                var label = own(STATUS_LABELS, status) || 'Không xác định';
                var responded = formatInstant(respondedAt);
                if (responseFact) {
                  responseFact.textContent = responded ? label + ', lúc ' + responded : label;
                }
                locked = status !== 'PENDING';
                setControls();
                if (status === 'PENDING') {
                  showStatus(MESSAGES.pending);
                } else if (status === 'CONFIRMED') {
                  showStatus(MESSAGES.confirmed);
                } else if (status === 'RESCHEDULE_REQUESTED') {
                  showStatus(MESSAGES.rescheduleRequested);
                } else {
                  showStatus(MESSAGES.unknown);
                }
              }

              function failureMessage(status) {
                if (status === -1) {
                  return MESSAGES.network;
                }
                if (status === 400 || status === 404 || status === 410) {
                  return MESSAGES.invalid;
                }
                if (status === 409) {
                  return MESSAGES.conflict;
                }
                if (status === 429) {
                  return MESSAGES.tooMany;
                }
                return MESSAGES.unavailable;
              }

              function canRetry(status) {
                return status === -1 || status === 0 || status === 429 || status >= 500;
              }

              // The only request of the page: always a POST with the token in the body, never in the URL.
              function post(path, payload) {
                return fetch(BASE + path, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                  body: JSON.stringify(payload),
                  credentials: 'omit',
                  cache: 'no-store',
                  redirect: 'error',
                  referrerPolicy: 'no-referrer'
                }).then(function (response) {
                  if (!response.ok) {
                    // Only a known validation code changes form behavior. Never display raw server messages.
                    return response.json().then(function (error) {
                      var code = error && error.code === 'INTERVIEW_INVITATION_VALIDATION'
                        ? 'INTERVIEW_INVITATION_VALIDATION' : null;
                      return { status: response.status, data: null, code: code };
                    }, function () {
                      return { status: response.status, data: null, code: null };
                    });
                  }
                  return response.json().then(function (data) {
                    return data && typeof data === 'object' ? { status: response.status, data: data } : { status: 0, data: null };
                  }, function () {
                    return { status: 0, data: null };
                  });
                }, function () {
                  return { status: -1, data: null };
                });
              }

              function load() {
                retryButton.hidden = true;
                showStatus(MESSAGES.loading);
                post('lookup', { token: token }).then(function (result) {
                  if (result.data) {
                    renderDetails(result.data);
                    renderStatus(text(result.data.status), result.data.respondedAt);
                    return;
                  }
                  showStatus(failureMessage(result.status));
                  retryButton.hidden = !canRetry(result.status);
                });
              }

              function respond(action) {
                if (busy || locked || !token) {
                  return;
                }
                var value = note.value.trim();
                replyError.textContent = '';
                note.removeAttribute('aria-invalid');
                if (action === 'REQUEST_RESCHEDULE' && !value) {
                  replyError.textContent = MESSAGES.noteRequired;
                  note.setAttribute('aria-invalid', 'true');
                  note.focus();
                  return;
                }
                if (value.length > MAX_NOTE) {
                  replyError.textContent = MESSAGES.noteTooLong;
                  note.setAttribute('aria-invalid', 'true');
                  note.focus();
                  return;
                }
                busy = true;
                setControls();
                showStatus(MESSAGES.sending);
                post('respond', { token: token, action: action, note: value || null }).then(function (result) {
                  busy = false;
                  if (result.data) {
                    renderStatus(text(result.data.status), result.data.respondedAt);
                    return;
                  }
                  if (result.status === 400 && result.code === 'INTERVIEW_INVITATION_VALIDATION') {
                    locked = false;
                    setControls();
                    replyError.textContent = MESSAGES.noteInvalid;
                    note.setAttribute('aria-invalid', 'true');
                    note.focus();
                    showStatus(MESSAGES.pending);
                    return;
                  }
                  replyError.textContent = failureMessage(result.status);
                  if (result.status === 409) {
                    // Already answered the other way: show the answer that counts.
                    locked = true;
                    setControls();
                    load();
                    return;
                  }
                  locked = !canRetry(result.status);
                  setControls();
                  showStatus(locked ? '' : MESSAGES.pending);
                });
              }

              confirmButton.addEventListener('click', function () {
                respond('CONFIRM');
              });
              rescheduleButton.addEventListener('click', function () {
                respond('REQUEST_RESCHEDULE');
              });
              retryButton.addEventListener('click', load);

              setControls();
              if (token) {
                load();
              } else {
                showStatus(MESSAGES.missing);
              }
            })();
            """;

    private InterviewReplyPage() {
    }

    public static String html() {
        return HTML;
    }

    public static String script() {
        return SCRIPT;
    }
}
