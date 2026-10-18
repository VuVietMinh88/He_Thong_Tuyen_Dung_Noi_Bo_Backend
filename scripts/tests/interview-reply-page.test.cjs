'use strict';

// Run: node --test scripts/tests/interview-reply-page.test.cjs
// Uses the actual Java text blocks through javac/java, Node's VM and a small observable DOM; no npm packages.
const { test, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');

const root = path.resolve(__dirname, '../..');
const scratchParent = fs.realpathSync(os.tmpdir());
const scratch = fs.mkdtempSync(path.join(scratchParent, 'ttcs-interview-page-'));
function cleanup() {
  const resolved = path.resolve(scratch);
  // Only remove this test's freshly created temporary directory, never a computed project/system parent.
  assert.equal(path.dirname(resolved), scratchParent);
  assert.ok(path.basename(resolved).startsWith('ttcs-interview-page-'));
  fs.rmSync(resolved, { recursive: true, force: true });
}
function javaTool(name) {
  return process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : ''))
    : name;
}
let script, html;
try {
  const exporter = path.join(scratch, 'ExportInterviewReplyPage.java');
  fs.writeFileSync(exporter, `
import java.nio.charset.StandardCharsets;
import vn.ttcs.recruitment.invitation.InterviewReplyPage;
public class ExportInterviewReplyPage {
  public static void main(String[] args) throws Exception {
    String value = args[0].equals("html") ? InterviewReplyPage.html() : InterviewReplyPage.script();
    System.out.write(value.getBytes(StandardCharsets.UTF_8));
  }
}
`, 'utf8');
  execFileSync(javaTool('javac'), ['--release', '21', '-encoding', 'UTF-8', '-d', scratch,
    path.join(root, 'src/main/java/vn/ttcs/recruitment/invitation/InterviewReplyPage.java'), exporter],
  { encoding: 'utf8', timeout: 30000 });
  const exportValue = kind => execFileSync(javaTool('java'), ['-cp', scratch, 'ExportInterviewReplyPage', kind],
    { encoding: 'utf8', timeout: 10000 });
  script = exportValue('script'); html = exportValue('html');
} catch (error) {
  cleanup();
  throw error;
}
after(cleanup);

const TOKEN = 'A'.repeat(43);
const BASE = '/api/v1/public/interview-invitations/';
const details = (patch = {}) => ({
  positionTitle: 'Java developer', roundName: 'Vòng chuyên môn', startsAt: '2026-11-01T02:00:00Z',
  endsAt: '2026-11-01T03:00:00Z', mode: 'ONSITE', room: 'Phòng 1', meetingUrl: null,
  preparationInstructions: 'Mang theo CV.', status: 'PENDING', respondedAt: null,
  expiresAt: '2026-11-01T02:00:00Z', ...patch
});
const settle = () => new Promise(resolve => setImmediate(resolve));

class Element {
  constructor(tag, id = '') {
    this.tagName = tag.toUpperCase(); this.id = id; this.children = []; this._text = '';
    this.hidden = false; this.disabled = false; this.value = ''; this.listeners = new Map(); this.attributes = new Map();
  }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this._text = String(value); this.children = []; }
  set innerHTML(value) { assert.fail('Untrusted HTML insertion is not permitted in the invitation page'); }
  get firstChild() { return this.children[0] || null; }
  appendChild(child) { this.children.push(child); return child; }
  removeChild(child) { this.children.splice(this.children.indexOf(child), 1); return child; }
  addEventListener(type, handler) { this.listeners.set(type, handler); }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  removeAttribute(name) { this.attributes.delete(name); }
  focus() { this.focused = true; }
  // Deliver queued events even when controls have just become disabled, exercising the actual busy/locked guard.
  click() { this.listeners.get('click')?.(); }
}

function browser({ hash = '#token=' + TOKEN, historyFails = false } = {}) {
  const nodes = new Map(), created = [], requests = [], events = [];
  for (const match of html.matchAll(/<([a-z][\w-]*)\b[^>]*\bid="([^"]+)"[^>]*>/gi)) {
    const node = new Element(match[1], match[2]); node.hidden = /\bhidden\b/.test(match[0]); nodes.set(node.id, node);
  }
  const location = { hash, pathname: BASE + 'reply', search: '' };
  const forbiddenStorage = new Proxy({}, { get() { assert.fail('The invitation token must not use browser storage'); } });
  const window = {
    location, localStorage: forbiddenStorage, sessionStorage: forbiddenStorage,
    history: { replaceState(state, title, url) {
      if (historyFails) throw new Error('History unavailable');
      events.push({ type: 'history', url }); location.hash = '';
    } }
  };
  const document = {
    getElementById(id) { assert.ok(nodes.has(id), `Missing actual HTML element ${id}`); return nodes.get(id); },
    createElement(tag) { const node = new Element(tag); created.push(node); return node; },
    set cookie(value) { assert.fail('The invitation token must not use cookies'); },
    get cookie() { assert.fail('The invitation page must not read cookies'); }
  };
  const fetch = (url, options) => {
    events.push({ type: 'fetch', url, hashAtFetch: location.hash });
    return new Promise((resolve, reject) => requests.push({ url, options, resolve, reject }));
  };
  vm.runInNewContext(script, { window, document, fetch, URL, Intl, Date }, { timeout: 1000, filename: 'reply.js' });
  return {
    nodes, created, requests, events, location,
    async reply(index, status, data) {
      assert.ok(requests[index], `Missing request ${index}`);
      requests[index].resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(data) });
      await settle();
    },
    async fail(index) { requests[index].reject(new Error('Network unavailable')); await settle(); },
    click(id) { nodes.get(id).click(); },
    body(index) { return JSON.parse(requests[index].options.body); }
  };
}

test('opening a valid link clears the fragment before lookup and never submits an RSVP automatically', async () => {
  const page = browser();
  assert.deepEqual(page.events.map(event => event.type), ['history', 'fetch']);
  assert.equal(page.events[1].hashAtFetch, '');
  assert.equal(page.requests.length, 1);
  assert.equal(page.requests[0].url, BASE + 'lookup');
  assert.deepEqual(page.body(0), { token: TOKEN });
  const options = page.requests[0].options;
  assert.equal(options.method, 'POST'); assert.equal(options.credentials, 'omit');
  assert.equal(options.cache, 'no-store'); assert.equal(options.referrerPolicy, 'no-referrer');
  assert.equal(options.redirect, 'error');
  await page.reply(0, 200, details());
  assert.equal(page.requests.length, 1);
  assert.equal(page.nodes.get('confirm').disabled, false);
  assert.ok(![...page.nodes.values()].some(node => node.textContent.includes(TOKEN)));
  assert.ok(!page.events.some(event => event.url.includes(TOKEN)));
});

test('missing or malformed fragments and an uncleared history fail closed without network requests', () => {
  for (const config of [{ hash: '' }, { hash: '#token=short' }, { hash: '#token=' + TOKEN + '&extra=x' }, { historyFails: true }]) {
    const page = browser(config);
    assert.equal(page.requests.length, 0);
    assert.equal(page.nodes.get('confirm').disabled, true);
    assert.equal(page.nodes.get('reschedule').disabled, true);
    assert.ok(page.nodes.get('status').textContent.length > 0);
  }
});

test('server-controlled labels remain text and only credential-free HTTPS meeting links become anchors', async () => {
  const malicious = '<img src=x onerror="window.compromised=true">';
  for (const meetingUrl of ['javascript:alert(1)', 'http://example.test/meeting', 'https://user:secret@example.test/meeting']) {
    const page = browser();
    await page.reply(0, 200, details({ roundName: malicious, preparationInstructions: malicious, meetingUrl }));
    assert.ok(page.nodes.get('facts').textContent.includes(malicious));
    assert.equal(page.created.filter(node => node.tagName === 'IMG' || node.tagName === 'SCRIPT' || node.tagName === 'A').length, 0);
  }
  const safe = browser();
  await safe.reply(0, 200, details({ meetingUrl: 'https://meet.example.test/interview' }));
  const anchors = safe.created.filter(node => node.tagName === 'A');
  assert.equal(anchors.length, 1); assert.equal(anchors[0].href, 'https://meet.example.test/interview');
  assert.equal(anchors[0].target, '_blank'); assert.equal(anchors[0].rel, 'noopener noreferrer');
});

test('explicit confirmation sends its optional trimmed note once even with queued double clicks', async () => {
  const page = browser(); await page.reply(0, 200, details());
  page.nodes.get('note').value = '  Tôi sẽ đến sớm.  ';
  page.click('confirm'); page.click('confirm'); page.click('reschedule');
  assert.equal(page.requests.length, 2); assert.equal(page.requests[1].url, BASE + 'respond');
  assert.deepEqual(page.body(1), { token: TOKEN, action: 'CONFIRM', note: 'Tôi sẽ đến sớm.' });
  assert.equal(page.nodes.get('confirm').disabled, true);
  await page.reply(1, 200, { status: 'CONFIRMED', respondedAt: '2026-10-18T13:00:00Z' });
  assert.equal(page.nodes.get('confirm').disabled, true); assert.equal(page.nodes.get('reschedule').disabled, true);
  page.click('confirm'); assert.equal(page.requests.length, 2);
  assert.match(page.nodes.get('status').textContent, /xác nhận/);
});

test('requesting a new time validates a visible bounded note before posting and then locks the reply', async () => {
  const page = browser(); await page.reply(0, 200, details());
  for (const note of ['', '   ', '\u00a0', 'x'.repeat(2001)]) {
    page.nodes.get('note').value = note; page.click('reschedule');
    assert.equal(page.requests.length, 1);
    assert.equal(page.nodes.get('note').attributes.get('aria-invalid'), 'true');
    assert.ok(page.nodes.get('reply-error').textContent.length > 0);
  }
  page.nodes.get('note').value = '  Tôi có thể đến chiều mai.  '; page.click('reschedule');
  assert.deepEqual(page.body(1), { token: TOKEN, action: 'REQUEST_RESCHEDULE', note: 'Tôi có thể đến chiều mai.' });
  await page.reply(1, 200, { status: 'RESCHEDULE_REQUESTED', respondedAt: '2026-10-18T13:00:00Z' });
  assert.equal(page.nodes.get('reschedule').disabled, true); assert.match(page.nodes.get('status').textContent, /đề nghị đổi lịch/);
});

test('a lookup network error offers a retry that reloads details without submitting a response', async () => {
  const page = browser(); await page.fail(0);
  assert.equal(page.nodes.get('retry').hidden, false); assert.equal(page.nodes.get('confirm').disabled, true);
  page.click('retry'); assert.equal(page.requests[1].url, BASE + 'lookup');
  await page.reply(1, 200, details());
  assert.equal(page.nodes.get('retry').hidden, true); assert.equal(page.nodes.get('confirm').disabled, false);
  assert.equal(page.requests.length, 2);
});

test('a response network failure preserves the note and permits the same explicit response to be retried', async () => {
  const page = browser(); await page.reply(0, 200, details());
  page.nodes.get('note').value = 'Tôi sẽ tham dự.'; page.click('confirm'); await page.fail(1);
  assert.equal(page.nodes.get('confirm').disabled, false); assert.equal(page.nodes.get('note').value, 'Tôi sẽ tham dự.');
  page.click('confirm'); assert.deepEqual(page.body(2), page.body(1));
  await page.reply(2, 200, { status: 'CONFIRMED', respondedAt: '2026-10-18T13:00:00Z' });
  assert.equal(page.nodes.get('confirm').disabled, true);
});

test('quota and server failures remain retryable for lookup and explicit replies', async () => {
  for (const status of [429, 503]) {
    const page = browser(); await page.reply(0, status, null);
    assert.equal(page.nodes.get('retry').hidden, false); page.click('retry');
    await page.reply(1, 200, details()); page.click('confirm'); await page.reply(2, status, null);
    assert.equal(page.nodes.get('confirm').disabled, false); assert.ok(page.nodes.get('reply-error').textContent.length > 0);
  }
});

test('a known server note-validation error keeps the form editable without reflecting its message', async () => {
  const page = browser(); await page.reply(0, 200, details());
  page.nodes.get('note').value = '\u200b'; page.click('reschedule');
  const untrusted = '<img src=x onerror="window.compromised=true">';
  await page.reply(1, 400, { code: 'INTERVIEW_INVITATION_VALIDATION', message: untrusted });
  assert.equal(page.nodes.get('reschedule').disabled, false); assert.equal(page.nodes.get('note').disabled, false);
  assert.equal(page.nodes.get('note').attributes.get('aria-invalid'), 'true'); assert.equal(page.nodes.get('note').focused, true);
  assert.ok(page.nodes.get('reply-error').textContent.length > 0);
  assert.ok(!page.nodes.get('reply-error').textContent.includes(untrusted));
  page.nodes.get('note').value = 'Tôi có thể đến lúc 14 giờ.'; page.click('reschedule');
  assert.equal(page.body(2).note, 'Tôi có thể đến lúc 14 giờ.');
  await page.reply(2, 200, { status: 'RESCHEDULE_REQUESTED', respondedAt: '2026-10-18T13:00:00Z' });
  assert.equal(page.nodes.get('reschedule').disabled, true);
});

test('a conflicting response locks controls and reloads the response that actually won', async () => {
  const page = browser(); await page.reply(0, 200, details());
  page.click('confirm'); await page.reply(1, 409, null);
  assert.equal(page.nodes.get('confirm').disabled, true); assert.equal(page.requests.length, 3);
  assert.equal(page.requests[2].url, BASE + 'lookup');
  await page.reply(2, 200, details({ status: 'RESCHEDULE_REQUESTED', respondedAt: '2026-10-18T13:00:00Z' }));
  assert.match(page.nodes.get('status').textContent, /đề nghị đổi lịch/);
  page.click('confirm'); page.click('reschedule'); assert.equal(page.requests.length, 3);
});

test('invalid or expired links never expose response controls or offer retries', async () => {
  const page = browser(); await page.reply(0, 400, null);
  assert.equal(page.nodes.get('details').hidden, true); assert.equal(page.nodes.get('retry').hidden, true);
  page.click('confirm'); assert.equal(page.requests.length, 1);
  const stale = browser(); await stale.reply(0, 200, details()); stale.click('confirm'); await stale.reply(1, 400, null);
  assert.equal(stale.nodes.get('confirm').disabled, true); assert.equal(stale.nodes.get('reschedule').disabled, true);
  stale.click('confirm'); assert.equal(stale.requests.length, 2);
});

test('an already answered invitation renders its final status without posting another response', async () => {
  const page = browser(); await page.reply(0, 200, details({ status: 'CONFIRMED', respondedAt: '2026-10-18T13:00:00Z' }));
  assert.equal(page.nodes.get('confirm').disabled, true); assert.equal(page.nodes.get('note').disabled, true);
  page.click('reschedule'); assert.equal(page.requests.length, 1);
  assert.match(page.nodes.get('status').textContent, /xác nhận/);
});
