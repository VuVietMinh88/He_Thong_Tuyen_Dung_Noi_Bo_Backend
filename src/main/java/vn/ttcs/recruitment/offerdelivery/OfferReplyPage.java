package vn.ttcs.recruitment.offerdelivery;

/** The capability stays in memory: remove its URL fragment before any request; never use browser storage. */
public final class OfferReplyPage {
    private OfferReplyPage() {
    }

    public static String html() {
        return """
            <!doctype html>
            <html lang="vi"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="referrer" content="no-referrer"><meta name="robots" content="noindex,nofollow">
            <title>Phản hồi thư mời nhận việc</title>
            <style>
            body{font-family:Arial,sans-serif;line-height:1.6;background:#f3f4f6;color:#172033;margin:0}
            main{max-width:640px;margin:24px auto;background:white;padding:24px;border-radius:8px}
            h1{font-size:1.6rem}dl{display:grid;grid-template-columns:1fr 2fr;gap:8px}dt{font-weight:bold}dd{margin:0;overflow-wrap:anywhere}
            textarea{box-sizing:border-box;width:100%;font:inherit;padding:8px}button{font:inherit;padding:10px 18px;margin:12px 8px 0 0;cursor:pointer}
            button:disabled{opacity:.6;cursor:not-allowed}button:focus-visible,textarea:focus-visible{outline:3px solid #2563eb}
            [hidden]{display:none!important}.error{color:#b91c1c}
            </style><script src="/api/v1/public/offers/reply.js" defer></script></head>
            <body><main><h1>Phản hồi thư mời nhận việc</h1>
            <p id="status" role="status" aria-live="polite">Đang tải thông tin…</p>
            <noscript>Vui lòng bật JavaScript để xem và phản hồi thư mời.</noscript>
            <button id="retry" type="button" hidden>Thử lại</button>
            <section id="details" hidden><h2>Điều kiện tuyển dụng</h2><dl id="facts"></dl></section>
            <section id="reply" hidden><h2>Phản hồi của bạn</h2>
            <label for="note">Ý kiến (không bắt buộc, tối đa 2000 ký tự)</label>
            <textarea id="note" rows="4" maxlength="2000"></textarea>
            <p>Bạn chỉ có thể phản hồi một lần. Vui lòng kiểm tra các điều kiện trước khi chọn.</p>
            <button id="accept" type="button">Chấp nhận</button><button id="decline" type="button">Từ chối</button>
            <p id="error" class="error" role="alert"></p></section></main></body></html>
            """;
    }

    public static String script() {
        return """
            (function () {
              'use strict';
              var token = null;
              try {
                var fragment = window.location.hash;
                window.history.replaceState(null, '', window.location.pathname);
                var match = /^#token=([A-Za-z0-9_-]{43})$/.exec(fragment);
                token = match ? match[1] : null;
              } catch (ignored) { token = null; }
              var status = document.getElementById('status');
              var facts = document.getElementById('facts');
              var details = document.getElementById('details');
              var reply = document.getElementById('reply');
              var note = document.getElementById('note');
              var accept = document.getElementById('accept');
              var decline = document.getElementById('decline');
              var retry = document.getElementById('retry');
              var error = document.getElementById('error');
              var busy = false, locked = true;
              function controls() { accept.disabled = decline.disabled = note.disabled = busy || locked; }
              function fact(label, value) {
                var dt = document.createElement('dt'), dd = document.createElement('dd');
                dt.textContent = label; dd.textContent = value == null ? '' : String(value);
                facts.appendChild(dt); facts.appendChild(dd);
              }
              function money(value) { return new Intl.NumberFormat('vi-VN').format(value) + ' VND'; }
              function date(value) {
                var parsed = new Date(typeof value === 'number' ? value * 1000 : value);
                return isNaN(parsed.getTime()) ? '' : new Intl.DateTimeFormat('vi-VN', {
                  timeZone:'Asia/Ho_Chi_Minh', dateStyle:'long', timeStyle:'short'
                }).format(parsed) + ' (giờ Việt Nam)';
              }
              function state(data) {
                locked = data.status !== 'SENT';
                status.textContent = data.status === 'ACCEPTED' ? 'Bạn đã chấp nhận thư mời nhận việc.'
                  : data.status === 'DECLINED' ? 'Bạn đã từ chối thư mời nhận việc.'
                  : data.status === 'SENT' ? 'Vui lòng đọc các điều kiện và chọn phản hồi bên dưới.'
                  : 'Thư mời hiện không thể phản hồi.';
                controls();
              }
              function message(code) {
                return code === 400 || code === 404 || code === 410
                  ? 'Liên kết không hợp lệ hoặc không còn hiệu lực. Vui lòng liên hệ bộ phận tuyển dụng.'
                  : code === 409 ? 'Bạn đã phản hồi thư mời này. Vui lòng tải lại thông tin.'
                  : code === 429 ? 'Bạn đã thử quá nhiều lần. Vui lòng đợi ít phút.'
                  : 'Không thể kết nối hoặc hệ thống tạm gián đoạn. Vui lòng thử lại.';
              }
              async function post(path, body) {
                try {
                  var response = await fetch('/api/v1/public/offers/' + path, {
                    method:'POST', headers:{'Content-Type':'application/json','Accept':'application/json'},
                    body:JSON.stringify(body), credentials:'omit', cache:'no-store', redirect:'error', referrerPolicy:'no-referrer'
                  });
                  var data = await response.json().catch(function () { return null; });
                  return {ok:response.ok && data !== null, status:response.status, data:data};
                } catch (ignored) { return {ok:false,status:0,data:null}; }
              }
              async function load() {
                retry.hidden = true; busy = true; controls();
                var result = await post('lookup', {token:token}); busy = false;
                if (!result.ok) {
                  status.textContent = message(result.status);
                  retry.hidden = !(result.status === 0 || result.status === 429 || result.status >= 500);
                  locked = true; controls(); return;
                }
                facts.replaceChildren();
                fact('Vị trí', result.data.positionTitle); fact('Lương', money(result.data.salary));
                fact('Phụ cấp', money(result.data.allowance)); fact('Ngày dự kiến nhận việc', result.data.startDate);
                fact('Hạn phản hồi', date(result.data.responseDeadline));
                details.hidden = reply.hidden = false; state(result.data);
              }
              async function respond(action) {
                if (busy || locked || !token) return;
                error.textContent = ''; busy = true; controls();
                var result = await post('respond', {token:token,action:action,note:note.value.trim() || null});
                busy = false;
                if (result.ok) { state(result.data); return; }
                if (result.status === 400 && result.data && result.data.code === 'OFFER_RESPONSE_VALIDATION') {
                  error.textContent = 'Ý kiến tối đa 2000 ký tự và không chứa ký tự điều khiển.';
                  controls(); return;
                }
                error.textContent = message(result.status);
                if (result.status === 409) { await load(); return; }
                locked = !(result.status === 0 || result.status === 429 || result.status >= 500); controls();
              }
              accept.addEventListener('click', function () { respond('ACCEPT'); });
              decline.addEventListener('click', function () { respond('DECLINE'); });
              retry.addEventListener('click', load); controls();
              if (token) load(); else status.textContent = 'Vui lòng mở lại liên kết phản hồi trong email offer.';
            })();
            """;
    }
}
