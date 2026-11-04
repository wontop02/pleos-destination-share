import { AutoPlayQueue, SSEParser, ServerClock, parseInvitation, naverNavigationUrl } from './helpers.js';
import { MicRecorder, VoicePlayer, microphoneError } from './audio.js';

const $ = (id) => document.getElementById(id);
const storageKey = 'together-room-v1';
let session = null;
let entryBusy = false;
let entryController = null;
let incomingInvite = null;
const current = (context) => session === context && !context.controller.signal.aborted;
const message = (text, error = false) => { $('message').textContent = text; $('message').dataset.error = String(error); };
const time = (at) => new Date(at).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
function savedSession(value) { try { value ? sessionStorage.setItem(storageKey, JSON.stringify(value)) : sessionStorage.removeItem(storageKey); } catch {} }
function linkedSignals(signals) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  for (const signal of signals.filter(Boolean)) { if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true }); }
  return { signal: controller.signal, cleanup: () => { for (const signal of signals.filter(Boolean)) signal.removeEventListener('abort', abort); } };
}
async function api(path, { method = 'GET', json, body, token = session?.token, signal = session?.controller.signal, headers = {}, binary = false } = {}) {
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), 15_000);
  const linked = linkedSignals([signal, timeout.signal]);
  const started = performance.now();
  try {
    const response = await fetch(path, { method, credentials: 'omit', cache: 'no-store', signal: linked.signal,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(json === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
      body: json === undefined ? body : JSON.stringify(json) });
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      const error = new Error(data.error || `요청에 실패했어요. (${response.status})`);
      error.status = response.status; error.code = data.code; throw error;
    }
    if (binary) return await response.arrayBuffer();
    const data = await response.json();
    data.roundTripMs = performance.now() - started;
    return data;
  } catch (error) {
    if (timeout.signal.aborted && !signal?.aborted) throw new Error('응답을 기다리다 중단했어요. 연결을 확인한 뒤 직접 다시 시도해 주세요.');
    throw error;
  } finally { clearTimeout(timer); linked.cleanup(); }
}
function setEntryBusy(busy) {
  entryBusy = busy;
  $('create-submit').disabled = busy;
  $('join-submit').disabled = busy;
}
function setConnection(context, text, state = '') {
  if (!current(context)) return;
  $('connection').textContent = text; $('connection').dataset.state = state;
}
function updateControls(context) {
  if (!current(context)) return;
  const recording = Boolean(context.recorder);
  $('record-start').hidden = recording;
  $('record-start').disabled = Boolean(context.uploading || context.pendingClip);
  $('record-stop').hidden = !recording;
  $('record-stop').disabled = !context.recorder?.startedAt || context.finishing;
  $('record-cancel').hidden = !recording;
  $('record-cancel').disabled = context.finishing;
  $('retry-area').hidden = !context.pendingClip || context.uploading;
  $('retry-send').disabled = context.uploading;
  $('retry-discard').disabled = context.uploading;
  $('autoplay-toggle').disabled = recording || context.uploading || context.unlocking;
}
function showAuto(context, text) {
  if (!current(context)) return;
  const enabled = context.queue.enabled;
  $('autoplay-toggle').setAttribute('aria-pressed', String(enabled));
  $('autoplay-toggle').textContent = enabled ? '자동 재생 끄고 중지' : '자동 재생 켜기';
  $('autoplay-status').textContent = text || (enabled ? '켜짐 · 열린 화면에서 새 원음을 기다리고 있어요.' : '꺼짐 · 목록에서 직접 재생할 수 있어요.');
}
function disableAuto(context, text) {
  context.queue.setEnabled(false);
  context.playbackGeneration += 1;
  context.player.stop();
  if (current(context)) { $('playback-stop').disabled = true; showAuto(context, text); }
}
function clearClip(context) {
  clearTimeout(context.clipTimer);
  context.pendingClip = null;
  updateControls(context);
}
function closeLocal() {
  const previous = session;
  session = null;
  if (previous) {
    previous.controller.abort();
    previous.queue.setEnabled(false);
    previous.playbackGeneration += 1;
    clearInterval(previous.recordTimer);
    clearTimeout(previous.clipTimer);
    previous.pendingClip = null;
    previous.recorder?.cancel();
    previous.player.close();
  }
  savedSession(null);
  $('room').hidden = true; $('start').hidden = false;
  $('invite-fallback').hidden = true; $('invite-output').value = '';
  $('record-timer').textContent = '0.0 / 15초';
  $('voices').replaceChildren(); $('members').replaceChildren();
  $('playback-status').textContent = '재생 중인 원음이 없어요.';
  return previous;
}
function renderDestination(context, update) {
  if (!current(context) || update.revision < context.revision) return;
  context.revision = update.revision;
  context.destination = update.destination;
  const destination = update.destination;
  $('destination-empty').hidden = Boolean(destination);
  $('destination-info').hidden = !destination;
  if (!destination) return;
  $('destination-title').textContent = destination.name;
  $('destination-meta').textContent = `목적지 수신 · ${time(destination.changedAt)}`;
  $('destination-coordinates').textContent = `위도 ${destination.latitude} · 경도 ${destination.longitude}`;
  const navigationUrl = naverNavigationUrl(destination, location.origin);
  $('navigation').hidden = !navigationUrl;
  $('navigation-unavailable').hidden = Boolean(navigationUrl);
  if (navigationUrl) $('naver').href = navigationUrl; else $('naver').removeAttribute('href');
}
function renderMembers(context, list) {
  if (!current(context)) return;
  $('member-count').textContent = `참여자 ${list.length}명 보기`;
  $('members').replaceChildren(...list.map((member) => {
    const item = document.createElement('li');
    item.textContent = `${member.name}${member.id === context.memberId ? ' (나)' : ''} · ${member.role === 'host' ? '대표 차량' : '참여자'}`;
    return item;
  }));
}
function refreshFreshness(context) {
  if (!current(context)) return;
  for (const row of $('voices').children) {
    const item = context.messages.get(row.dataset.voiceId);
    row.querySelector('.freshness').textContent = context.clock.now() < item.autoPlayUntil ? '새 원음 · 수동 재생 가능' : '보관된 원음 · 수동 재생 가능';
  }
}
function renderVoices(context) {
  if (!current(context)) return;
  const list = [...context.messages.values()].sort((a, b) => a.sequence - b.sequence);
  $('voices-empty').hidden = list.length > 0;
  $('voices').replaceChildren(...list.map((item) => {
    const row = document.createElement('li'); row.dataset.voiceId = item.id;
    const detail = document.createElement('div');
    const sender = document.createElement('strong'); sender.textContent = `${item.sender.name}${item.sender.id === context.memberId ? ' (나)' : ''}`;
    const meta = document.createElement('p'); meta.className = 'small muted'; meta.textContent = `${time(item.acceptedAt)} 수신 · ${(item.durationMs / 1000).toFixed(1)}초`;
    const freshness = document.createElement('span'); freshness.className = 'freshness';
    detail.append(sender, meta, freshness);
    const button = document.createElement('button'); button.type = 'button'; button.className = 'secondary compact'; button.textContent = '재생'; button.setAttribute('aria-label', `${item.sender.name} ${time(item.acceptedAt)} 원음 재생`);
    button.addEventListener('click', () => playManually(context, item));
    row.append(detail, button); return row;
  }));
  refreshFreshness(context);
}
function receiveVoices(context, list) {
  for (const item of list) context.messages.set(item.id, item);
  context.queue.offer(list);
  renderVoices(context);
  drainAuto(context);
}
function applyState(context, state, roundTripMs = 0) {
  if (!current(context)) return;
  context.clock.update(state.serverTime, roundTripMs);
  context.selfName = state.self.name;
  $('self-name').textContent = `${state.self.name} · ${context.role === 'host' ? '대표 차량' : '참여자'}`;
  $('room-heading').textContent = '함께 연결된 공유방';
  $('room-expiry').textContent = `${time(state.room.expiresAt)} 만료 예정`;
  renderDestination(context, state);
  renderMembers(context, state.members);
  receiveVoices(context, state.voiceMessages);
}
function enter(credentials, state, roundTripMs = 0) {
  closeLocal();
  const context = { ...credentials, controller: new AbortController(), revision: -1, messages: new Map(),
    player: new VoicePlayer(), clock: new ServerClock(), playbackGeneration: 0, draining: false, recorder: null,
    finishing: false, uploading: false, pendingClip: null, unlocking: false };
  context.queue = new AutoPlayQueue({ selfId: context.memberId, now: () => context.clock.now() });
  session = context;
  context.player.onInterrupted = () => {
    if (current(context)) { disableAuto(context, '꺼짐 · 브라우저가 오디오를 일시정지했어요. 다시 눌러 주세요.'); $('playback-status').textContent = '오디오가 일시정지되어 재생을 중지했어요.'; }
  };
  savedSession(credentials);
  $('start').hidden = true; $('room').hidden = false;
  const host = context.role === 'host';
  $('host-form-card').hidden = !host; $('invite-card').hidden = !host || !context.inviteSecret;
  $('end').hidden = !host; $('leave').hidden = host;
  $('destination-info').hidden = true; $('destination-empty').hidden = false;
  $('record-status').textContent = '마이크 버튼을 누르면 녹음이 시작돼요.';
  $('voices-empty').hidden = false;
  showAuto(context); updateControls(context); setConnection(context, '연결 중');
  if (state) applyState(context, state, roundTripMs);
  watchEvents(context);
  return context;
}
function wait(ms, signal) {
  return new Promise((fulfill) => {
    const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); fulfill(); };
    const timer = setTimeout(done, ms);
    if (signal.aborted) done(); else signal.addEventListener('abort', done, { once: true });
  });
}
function handleEvent(context, event) {
  if (!current(context)) return;
  const data = JSON.parse(event.data);
  context.clock.update(data.serverTime);
  if (event.event === 'snapshot') {
    applyState(context, data); setConnection(context, '연결됨');
    if (context.reconnecting) { context.reconnecting = false; message('다시 연결됐어요. 최신 목적지와 원음 목록을 받았어요.'); }
  }
  if (event.event === 'destination') { renderDestination(context, data); message('새 목적지를 받았어요.'); }
  if (event.event === 'members') renderMembers(context, data.members);
  if (event.event === 'voice') { receiveVoices(context, [data.message]); if (data.message.sender.id !== context.memberId) message(`${data.message.sender.name}의 원음을 받았어요.`); }
  if (event.event === 'closed') {
    closeLocal();
    message(data.reason === 'expired' ? '공유방의 6시간이 지나 종료됐어요. 보관한 원음도 삭제됐어요.' : '공유방 연결이 종료됐어요.');
  }
}
async function watchEvents(context) {
  let delay = 1000;
  while (current(context)) {
    let reader;
    try {
      const response = await fetch(`/api/rooms/${context.roomId}/events`, { headers: { Authorization: `Bearer ${context.token}` }, credentials: 'omit', cache: 'no-store', signal: context.controller.signal });
      if (!response.ok) {
        const body = await response.json(); const error = new Error(body.error); error.status = response.status; throw error;
      }
      if (!response.body?.getReader) { const error = new Error('이 브라우저는 실시간 수신을 지원하지 않아요. 최신 브라우저에서 열어 주세요.'); error.unsupported = true; throw error; }
      reader = response.body.getReader();
      const decoder = new TextDecoder();
      const parser = new SSEParser((event) => handleEvent(context, event));
      delay = 1000;
      while (current(context)) {
        const result = await reader.read();
        if (result.done) break;
        parser.feed(decoder.decode(result.value, { stream: true }));
      }
      if (current(context)) throw new Error('실시간 연결이 끊겼어요.');
    } catch (error) {
      if (!current(context)) return;
      if (error.unsupported) { setConnection(context, '수신 미지원'); message(error.message, true); return; }
      if ([401, 403, 404].includes(error.status)) { closeLocal(); message(error.message, true); return; }
      setConnection(context, '재연결 중', 'reconnecting');
      context.reconnecting = true;
      message('연결이 끊겨 다시 연결하고 있어요. 마지막 받은 목적지는 유지해요.', true);
    } finally { await reader?.cancel().catch(() => {}); }
    if (current(context)) { await wait(delay, context.controller.signal); delay = Math.min(delay * 2, 10_000); }
  }
}
async function playVoice(context, item, automatic) {
  return context.player.play({
    download: (signal) => api(`/api/rooms/${context.roomId}/voice/${item.id}/audio`, {
      token: context.token, signal: linkedPlaybackSignal(context, signal), binary: true }),
    canStart: () => current(context) && (!automatic || (context.queue.enabled && context.queue.isFresh(item))),
    onStart: () => {
      if (!current(context)) return;
      $('playback-stop').disabled = false;
      $('playback-status').textContent = `${item.sender.name}의 원음을 재생하고 있어요. (${(item.durationMs / 1000).toFixed(1)}초)`;
    },
  });
}
function linkedPlaybackSignal(context, signal) {
  // Player stop is called on leave before its controller is discarded; no additional long-lived listener.
  if (!current(context)) { const controller = new AbortController(); controller.abort(); return controller.signal; }
  return signal;
}
async function drainAuto(context) {
  if (!current(context) || context.draining || !context.queue.enabled || document.hidden) return;
  context.draining = true;
  const generation = context.playbackGeneration;
  try {
    let item;
    while (current(context) && context.queue.enabled && generation === context.playbackGeneration && (item = context.queue.take())) {
      try {
        const finished = await playVoice(context, item, true);
        if (finished && current(context)) $('playback-status').textContent = `${item.sender.name}의 원음 재생을 마쳤어요.`;
      } catch (error) {
        if (!current(context) || generation !== context.playbackGeneration || error.name === 'AbortError') break;
        if (error.name === 'StaleAudioError') continue;
        disableAuto(context, `자동 재생 중단 · ${error.message}`);
        $('playback-status').textContent = '재생하지 못했어요. 목록에서 직접 다시 눌러 주세요.';
        break;
      }
    }
  } finally {
    context.draining = false;
    if (current(context) && generation === context.playbackGeneration) $('playback-stop').disabled = true;
    if (current(context) && context.queue.enabled && context.queue.pending.length) drainAuto(context);
  }
}
async function playManually(context, item) {
  if (!current(context)) return;
  if (context.recorder) { message('녹음을 마치거나 취소한 뒤 재생해 주세요.', true); return; }
  disableAuto(context, '꺼짐 · 수동 재생을 선택했어요.');
  const generation = ++context.playbackGeneration;
  try {
    const unlocked = context.player.unlock();
    $('playback-status').textContent = '원음을 불러오고 있어요.';
    await unlocked;
    if (!current(context) || generation !== context.playbackGeneration) return;
    const finished = await playVoice(context, item, false);
    if (finished && current(context) && generation === context.playbackGeneration) $('playback-status').textContent = `${item.sender.name}의 원음 재생을 마쳤어요.`;
  } catch (error) {
    if (current(context) && generation === context.playbackGeneration && error.name !== 'AbortError') $('playback-status').textContent = `재생 실패 · ${error.message}`;
  } finally { if (current(context) && generation === context.playbackGeneration) $('playback-stop').disabled = true; }
}
async function finishRecording(context) {
  if (!current(context) || !context.recorder || context.finishing) return;
  if (document.hidden) return cancelRecording(context, '화면이 숨겨져 녹음을 취소했어요.');
  context.finishing = true; updateControls(context); clearInterval(context.recordTimer);
  const recorder = context.recorder;
  $('record-status').textContent = '녹음을 마무리하고 있어요.';
  try {
    const clip = await recorder.stop();
    if (!current(context) || context.recorder !== recorder) return;
    context.recorder = null;
    context.pendingClip = { ...clip, clientMessageId: crypto.randomUUID() };
    context.clipTimer = setTimeout(() => {
      if (current(context) && context.pendingClip && !context.uploading) {
        clearClip(context); $('record-status').textContent = '녹음 후 30초가 지나 전송하지 못한 원음을 지웠어요.';
      }
    }, 30_000);
    $('record-timer').textContent = `${(clip.durationMs / 1000).toFixed(1)} / 15초`;
    await sendClip(context);
  } catch (error) {
    if (current(context) && context.recorder === recorder) {
      context.recorder = null;
      $('record-status').textContent = microphoneError(error);
    }
  } finally { context.finishing = false; updateControls(context); }
}
async function cancelRecording(context, text = '녹음을 취소했어요. 원음을 보내지 않았어요.') {
  const recorder = context.recorder;
  context.recorder = null;
  context.finishing = false;
  clearInterval(context.recordTimer);
  await recorder?.cancel();
  if (current(context)) { $('record-status').textContent = text; $('record-timer').textContent = '0.0 / 15초'; updateControls(context); }
}
async function sendClip(context) {
  const clip = context.pendingClip;
  if (!current(context) || !clip || context.uploading) return;
  if (document.hidden || Date.now() - clip.capturedAt >= 30_000) {
    clearClip(context); $('record-status').textContent = '오래됐거나 화면이 숨겨진 녹음은 보내지 않았어요. 새로 녹음해 주세요.'; return;
  }
  context.uploading = true; updateControls(context);
  $('record-status').textContent = '원음을 전송하고 있어요.';
  try {
    const result = await api(`/api/rooms/${context.roomId}/voice`, { method: 'POST', body: clip.bytes,
      headers: { 'Content-Type': 'audio/wav', 'X-Client-Message-Id': clip.clientMessageId } });
    if (!current(context)) return;
    context.clock.update(result.serverTime, result.roundTripMs);
    receiveVoices(context, [result.message]);
    clearClip(context);
    $('record-status').textContent = result.duplicate ? '이미 접수된 원음이에요. 중복으로 전달하지 않았어요.' : '서버가 원음을 접수했어요. 상대의 재생 여부는 상대 화면에서 확인해 주세요.';
  } catch (error) {
    if (!current(context)) return;
    if (Date.now() - clip.capturedAt >= 30_000 || [400, 403, 404, 409, 413, 415, 422, 507].includes(error.status)) clearClip(context);
    $('record-status').textContent = `전송 실패 · ${error.message || '연결을 확인해 주세요.'}`;
  } finally { context.uploading = false; updateControls(context); }
}

$('create-form').addEventListener('submit', async (event) => {
  event.preventDefault(); if (entryBusy) return;
  setEntryBusy(true); entryController = new AbortController(); message('공유방을 만들고 있어요.');
  try {
    const result = await api('/api/rooms', { method: 'POST', json: { name: $('create-name').value }, token: null, signal: entryController.signal });
    enter({ roomId: result.roomId, memberId: result.memberId, role: result.role, token: result.token, inviteSecret: result.inviteSecret }, result.state, result.roundTripMs);
    message('공유방을 만들었어요. 초대 링크를 복사해 함께 갈 사람에게 보내 주세요.');
  } catch (error) { if (error.name !== 'AbortError') message(error.message, true); }
  finally { setEntryBusy(false); }
});
$('join-form').addEventListener('submit', async (event) => {
  event.preventDefault(); if (entryBusy) return;
  const invite = incomingInvite ?? parseInvitation($('join-link').value.trim(), location.origin);
  if (!invite) { message('이 로컬 주소에서 만든 초대 링크를 확인해 주세요.', true); return; }
  setEntryBusy(true); entryController = new AbortController(); message('공유방에 참여하고 있어요.');
  try {
    const result = await api(`/api/rooms/${invite.roomId}/members`, { method: 'POST', json: { name: $('join-name').value }, token: invite.inviteSecret, signal: entryController.signal });
    incomingInvite = null; $('join-link').value = ''; $('join-link-field').hidden = false;
    $('invite-ready').textContent = '대표 차량에게 받은 초대 링크를 붙여 넣어 주세요.';
    enter({ roomId: result.roomId, memberId: result.memberId, role: result.role, token: result.token }, result.state, result.roundTripMs);
    message('공유방에 참여했어요. 받은 목적지와 원음을 여기서 확인해 주세요.');
  } catch (error) { if (error.name !== 'AbortError') message(error.message, true); }
  finally { setEntryBusy(false); }
});
$('destination-form').addEventListener('submit', async (event) => {
  event.preventDefault(); const context = session; if (!context || context.role !== 'host') return;
  $('destination-send').disabled = true;
  try {
    const result = await api(`/api/rooms/${context.roomId}/destination`, { method: 'POST', json: { name: $('destination-name').value, latitude: Number($('latitude').value), longitude: Number($('longitude').value) } });
    if (current(context)) { context.clock.update(result.serverTime, result.roundTripMs); renderDestination(context, result); message('새 목적지를 전달했어요.'); }
  } catch (error) { if (current(context)) message(error.message, true); }
  finally { $('destination-send').disabled = false; }
});
$('copy-invite').addEventListener('click', async () => {
  const context = session; if (!context?.inviteSecret) return;
  const link = `${location.origin}/#join=${context.roomId}.${context.inviteSecret}`;
  try { await navigator.clipboard.writeText(link); if (current(context)) message('초대 링크를 복사했어요.'); }
  catch { if (current(context)) { $('invite-fallback').hidden = false; $('invite-output').value = link; $('invite-output').focus(); $('invite-output').select(); message('선택된 초대 링크를 직접 복사해 주세요.'); } }
});
$('record-start').addEventListener('click', async () => {
  const context = session; if (!context || context.recorder || context.uploading || context.pendingClip) return;
  if (document.hidden) return;
  disableAuto(context, '꺼짐 · 녹음 중에는 자동 재생하지 않아요.');
  $('playback-status').textContent = '재생을 중지했어요.';
  const recorder = new MicRecorder({ onLimit: () => finishRecording(context) });
  context.recorder = recorder; updateControls(context);
  $('record-status').textContent = '마이크 권한을 확인하고 있어요. 취소할 수 있어요.';
  $('record-timer').textContent = '0.0 / 15초';
  try {
    await recorder.start();
    if (!current(context) || context.recorder !== recorder) { await recorder.cancel(); return; }
    $('record-status').textContent = '녹음 중 · 중지하고 보내거나 취소해 주세요.';
    context.recordTimer = setInterval(() => { if (current(context)) $('record-timer').textContent = `${Math.min(15, (performance.now() - recorder.startedAt) / 1000).toFixed(1)} / 15초`; }, 200);
    updateControls(context);
  } catch (error) { if (current(context) && context.recorder === recorder) { context.recorder = null; $('record-status').textContent = microphoneError(error); updateControls(context); } }
});
$('record-stop').addEventListener('click', () => { if (session) finishRecording(session); });
$('record-cancel').addEventListener('click', () => { if (session) cancelRecording(session); });
$('retry-send').addEventListener('click', () => { if (session) sendClip(session); });
$('retry-discard').addEventListener('click', () => { if (session) { clearClip(session); $('record-status').textContent = '전송하지 못한 원음을 지웠어요.'; } });
$('autoplay-toggle').addEventListener('click', async () => {
  const context = session; if (!context || context.recorder || context.uploading || context.unlocking) return;
  if (context.queue.enabled) { disableAuto(context, '꺼짐 · 현재 재생과 대기 중인 원음을 중지했어요.'); $('playback-status').textContent = '재생을 중지했어요.'; return; }
  context.unlocking = true; updateControls(context);
  try {
    await context.player.unlock();
    if (!current(context) || document.hidden) return;
    context.queue.setEnabled(true); showAuto(context);
  } catch (error) { if (current(context)) disableAuto(context, `자동 재생 허용 실패 · ${error.message}`); }
  finally { context.unlocking = false; updateControls(context); }
});
$('playback-stop').addEventListener('click', () => { if (session) { disableAuto(session, '꺼짐 · 현재 재생과 대기 중인 원음을 중지했어요.'); $('playback-status').textContent = '재생을 중지했어요.'; } });
$('naver').addEventListener('click', () => message('네이버 지도에 경로를 요청했어요. 앱에서 실제 길 안내 시작을 확인해 주세요.'));
$('end').addEventListener('click', async () => {
  const context = session; if (!context) return; $('end').disabled = true;
  try { await api(`/api/rooms/${context.roomId}`, { method: 'DELETE' }); if (current(context)) { closeLocal(); message('공유방을 종료하고 보관한 원음을 삭제했어요.'); } }
  catch (error) { if (current(context)) message(error.message, true); }
  finally { $('end').disabled = false; }
});
$('leave').addEventListener('click', async () => {
  const context = session; if (!context) return; $('leave').disabled = true;
  let failed = false;
  try { await api(`/api/rooms/${context.roomId}/members/me`, { method: 'DELETE' }); }
  catch (error) { failed = error.name !== 'AbortError'; }
  finally { if (current(context)) { closeLocal(); message(failed ? '서버 연결을 확인하지 못했어요. 이 화면에서는 나갔어요.' : '공유방에서 나갔어요.', failed); } $('leave').disabled = false; }
});
document.addEventListener('visibilitychange', () => {
  const context = session; if (!context) return;
  if (document.hidden) {
    disableAuto(context, '꺼짐 · 화면이 숨겨져 재생을 중지했어요. 돌아오면 직접 켜 주세요.');
    $('playback-status').textContent = '화면이 숨겨져 재생을 중지했어요.';
    if (context.recorder) cancelRecording(context, '화면이 숨겨져 녹음을 취소했어요. 원음을 보내지 않았어요.');
    if (context.pendingClip && !context.uploading) { clearClip(context); $('record-status').textContent = '화면이 숨겨져 전송하지 못한 원음을 지웠어요.'; }
  } else {
    api(`/api/rooms/${context.roomId}`).then((state) => applyState(context, state, state.roundTripMs)).catch((error) => {
      if (!current(context)) return;
      if ([401, 403, 404].includes(error.status)) closeLocal();
      message(error.message, true);
    });
  }
});
window.addEventListener('pagehide', () => { const context = session; if (context) { context.controller.abort(); context.recorder?.cancel(); context.player.close(); context.queue.setEnabled(false); } });
window.addEventListener('pageshow', (event) => { if (event.persisted) restore(); });
setInterval(() => { if (session) refreshFreshness(session); }, 1000);

function receiveInvitation() {
  if (!location.hash) return false;
  incomingInvite = parseInvitation(location.href, location.origin);
  history.replaceState(null, '', `${location.pathname}${location.search}`);
  entryController?.abort();
  if (session) closeLocal();
  savedSession(null);
  $('join-link-field').hidden = Boolean(incomingInvite);
  $('invite-ready').textContent = incomingInvite ? '초대 링크를 확인했어요. 이름을 입력하면 참여할 수 있어요.' : '대표 차량에게 받은 초대 링크를 붙여 넣어 주세요.';
  message(incomingInvite ? '초대받은 공유방에 참여할 이름을 입력해 주세요.' : '초대 링크 형식을 확인해 주세요.', !incomingInvite);
  return true;
}
window.addEventListener('hashchange', receiveInvitation);
async function restore() {
  if (receiveInvitation()) return;
  let value;
  try { value = JSON.parse(sessionStorage.getItem(storageKey)); } catch { return; }
  if (!value || !/^[a-f0-9]{24}$/.test(value.roomId) || !/^[a-f0-9]{24}$/.test(value.memberId) ||
      !/^[A-Za-z0-9_-]{43}$/.test(value.token) || !['host', 'member'].includes(value.role)) { savedSession(null); return; }
  const context = enter(value);
  try { const state = await api(`/api/rooms/${context.roomId}`); applyState(context, state, state.roundTripMs); }
  catch (error) { if (current(context)) { if ([401, 403, 404].includes(error.status)) closeLocal(); message(error.message, true); } }
}
restore();
