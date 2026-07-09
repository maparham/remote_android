const canvas = document.getElementById('screen');
const ctx = canvas.getContext('2d');
const statusEl = document.getElementById('status');
const host = location.host;
let meta = null;

function setStatus(t) { statusEl.textContent = t; }

// ---- Video ----
// WebCodecs (VideoDecoder) needs a secure context (HTTPS or localhost). Over a plain-HTTP
// LAN IP it is undefined, so fall back to MJPEG which works in any browser.
const canUseH264 = window.isSecureContext && ('VideoDecoder' in window);

function fitCanvas(w, h) {
  if (canvas.width !== w) { canvas.width = w; canvas.height = h; }
}

if (canUseH264) {
  startH264();
} else {
  startMjpeg();
}

function startH264() {
  setStatus('H.264 mode — connecting…');
  const decoder = new VideoDecoder({
    output: (frame) => {
      fitCanvas(frame.displayWidth, frame.displayHeight);
      ctx.drawImage(frame, 0, 0);
      frame.close();
    },
    error: (e) => { console.error('decoder', e); setStatus('decoder error: ' + e.message); },
  });
  const vsock = new WebSocket(`ws://${host}/video`);
  vsock.binaryType = 'arraybuffer';
  let configured = false;
  vsock.onclose = () => setStatus('video disconnected');
  vsock.onerror = () => setStatus('video error');
  vsock.onmessage = (ev) => {
    if (typeof ev.data === 'string') { meta = JSON.parse(ev.data); return; }
    const buf = new Uint8Array(ev.data);
    const isKey = (buf[0] & 0x01) !== 0;
    const payload = buf.subarray(1);
    // Wait for the first keyframe — deltas before it are undecodable, and the keyframe
    // carries in-band SPS/PPS (encoder prepends headers to sync frames).
    if (!configured) {
      if (!isKey) return;
      // No `description` => WebCodecs expects Annex-B start codes, which MediaCodec emits.
      decoder.configure({ codec: 'avc1.42E01E', optimizeForLatency: true });
      configured = true;
      setStatus('streaming (H.264)');
    }
    try {
      decoder.decode(new EncodedVideoChunk({
        type: isKey ? 'key' : 'delta',
        timestamp: performance.now() * 1000,
        data: payload,
      }));
    } catch (e) {
      console.error('decode', e);
    }
  };
}

function startMjpeg() {
  setStatus('MJPEG mode — connecting…');
  const vsock = new WebSocket(`ws://${host}/mjpeg`);
  vsock.binaryType = 'arraybuffer';
  vsock.onclose = () => setStatus('video disconnected');
  vsock.onerror = () => setStatus('video error');
  let rendering = false;
  vsock.onmessage = async (ev) => {
    if (typeof ev.data === 'string') { meta = JSON.parse(ev.data); return; }
    if (rendering) return; // drop frames while a decode is in flight to stay live
    rendering = true;
    try {
      const bitmap = await createImageBitmap(new Blob([ev.data], { type: 'image/jpeg' }));
      fitCanvas(bitmap.width, bitmap.height);
      ctx.drawImage(bitmap, 0, 0);
      bitmap.close();
      setStatus('streaming (MJPEG)');
    } catch (e) {
      console.error('mjpeg', e);
    } finally {
      rendering = false;
    }
  };
}

// ---- Control ----
const csock = new WebSocket(`ws://${host}/control`);
function send(obj) { if (csock.readyState === 1) csock.send(JSON.stringify(obj)); }

function norm(ev) {
  const r = canvas.getBoundingClientRect();
  return {
    x: Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width)),
    y: Math.min(1, Math.max(0, (ev.clientY - r.top) / r.height)),
  };
}

let down = null, downTime = 0;
canvas.addEventListener('pointerdown', (e) => {
  down = norm(e);
  downTime = performance.now();
  canvas.setPointerCapture(e.pointerId);
});
canvas.addEventListener('pointerup', (e) => {
  if (!down) return;
  const up = norm(e);
  const dt = performance.now() - downTime;
  const moved = Math.hypot(up.x - down.x, up.y - down.y) > 0.02;
  if (moved) {
    send({ type: 'swipe', x1: down.x, y1: down.y, x2: up.x, y2: up.y, durationMs: Math.max(50, Math.round(dt)) });
  } else if (dt > 500) {
    send({ type: 'longpress', x: down.x, y: down.y, durationMs: Math.round(dt) });
  } else {
    send({ type: 'tap', x: down.x, y: down.y });
  }
  down = null;
});

// ---- Wheel / trackpad scrolling ----
// Translate wheel deltas into swipe gestures. Coalesce over a short window so we emit at
// most one swipe per ~100ms — overlapping dispatchGesture calls get dropped by Android.
let wheelDX = 0, wheelDY = 0, wheelPos = null, wheelTimer = null;
canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  wheelDX += e.deltaX;
  wheelDY += e.deltaY;
  wheelPos = norm(e);
  if (!wheelTimer) wheelTimer = setTimeout(flushWheel, 100);
}, { passive: false });

function flushWheel() {
  wheelTimer = null;
  const dx = wheelDX, dy = wheelDY;
  wheelDX = 0; wheelDY = 0;
  if (!wheelPos || (Math.abs(dx) < 1 && Math.abs(dy) < 1)) return;
  // Wheel down (dy>0) scrolls content down → finger swipes up (y decreases).
  const ax = Math.min(0.6, Math.abs(dx) * 0.0015);
  const ay = Math.min(0.6, Math.abs(dy) * 0.0015);
  const x2 = Math.min(1, Math.max(0, wheelPos.x + (dx > 0 ? -ax : ax)));
  const y2 = Math.min(1, Math.max(0, wheelPos.y + (dy > 0 ? -ay : ay)));
  const dist = Math.hypot(x2 - wheelPos.x, y2 - wheelPos.y);
  const dur = Math.max(50, Math.round(dist * 220));
  send({ type: 'swipe', x1: wheelPos.x, y1: wheelPos.y, x2, y2, durationMs: dur });
}

document.querySelectorAll('button[data-key]').forEach((btn) =>
  btn.addEventListener('click', () => send({ type: 'key', action: btn.dataset.key })));

// ---- Audio (Opus over WebSocket → WASM decode → Web Audio) ----
// Gated behind a button: browsers block autoplay until a user gesture, and connecting
// starts capture on the phone. Works over plain HTTP (no WebCodecs / secure context).
const audioBtn = document.getElementById('audioBtn');
let audioCtx = null, asock = null, opusDec = null, nextTime = 0;

audioBtn.addEventListener('click', async () => {
  if (asock) { stopAudio(); return; }
  audioBtn.textContent = 'Audio…';
  try {
    audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 });
    await audioCtx.resume();
    const { OpusDecoder } = window['opus-decoder'];
    opusDec = new OpusDecoder({ channels: 2, sampleRate: 48000 });
    await opusDec.ready;
    nextTime = 0;
    asock = new WebSocket(`ws://${host}/audio`);
    asock.binaryType = 'arraybuffer';
    asock.onmessage = onAudioMessage;
    asock.onclose = () => { audioBtn.textContent = 'Enable audio'; asock = null; };
    asock.onerror = () => setStatus('audio error');
    audioBtn.textContent = 'Disable audio';
  } catch (e) {
    console.error('audio', e);
    setStatus('audio failed: ' + e.message);
    stopAudio();
  }
});

function stopAudio() {
  if (asock) { try { asock.close(); } catch (e) {} asock = null; }
  if (audioCtx) { try { audioCtx.close(); } catch (e) {} audioCtx = null; }
  opusDec = null;
  audioBtn.textContent = 'Enable audio';
}

function onAudioMessage(ev) {
  if (typeof ev.data === 'string') {
    const msg = JSON.parse(ev.data);
    if (msg.error) { setStatus('audio: ' + msg.error + ' (grant mic permission / some apps block capture)'); stopAudio(); }
    return;
  }
  if (!audioCtx || !opusDec) return;
  const { channelData, samplesDecoded } = opusDec.decodeFrame(new Uint8Array(ev.data));
  if (!samplesDecoded) return;
  const buf = audioCtx.createBuffer(channelData.length, samplesDecoded, 48000);
  for (let c = 0; c < channelData.length; c++) buf.copyToChannel(channelData[c], c);
  const src = audioCtx.createBufferSource();
  src.buffer = buf;
  src.connect(audioCtx.destination);
  const now = audioCtx.currentTime;
  // Prime / re-prime a ~120 ms jitter buffer on start or underrun.
  if (nextTime < now + 0.02) nextTime = now + 0.12;
  src.start(nextTime);
  nextTime += buf.duration;
}

window.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'BUTTON') return;
  if (e.key.length === 1) {
    send({ type: 'text', value: e.key });
    e.preventDefault();
  } else if (e.key === 'Backspace') {
    send({ type: 'key', action: 'back' });
    e.preventDefault();
  } else if (e.key === 'Enter') {
    send({ type: 'text', value: '\n' });
    e.preventDefault();
  }
});
