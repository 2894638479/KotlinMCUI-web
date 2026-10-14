// The browser half of the web backend.
//
// It does three things: keep a frame buffer up to date from the binary messages, paint that buffer
// onto a canvas, and forward platform input to the server. It deliberately does *not* lay anything
// out or measure text - the framework does that on the server, and the positions arrive per glyph -
// so the browser needs no fonts, no metrics and no knowledge of the framework's types.
//
// The decode below is the same eleven lines as the Kotlin Decoder in Encoder.kt; both exist so the
// format has a testable definition on each side.

const NOCHANGE = 0x4e;
const KEYFRAME = 0x4b;
const DELTA = 0x44;
const KEYFRAME_HEADER = 9;
const DELTA_HEADER = 17;

// Opcodes, in the order Op.kt declares them.
const Op = {
  FLUSH: 0, QUAD: 1, TRIANGLES: 2, CLIP_PUSH: 3, CLIP_POP: 4, TRANSFORM_PUSH: 5, TRANSFORM_POP: 6,
  TEXT: 7, BUTTON: 8, SLOT: 9, CONTAINER: 10, TOOLTIP: 11, DEFAULT_BACKGROUND: 12, ITEM: 13, TEXTURE: 14,
  NO_ASSET: -1,
};

// How long to wait between frames once the server says nothing changed. A still page then costs a
// couple of bytes a second instead of sixty frames, while an animation still advances at ~8 Hz. The
// real fix is for the framework to say whether anything is animating; until then this is the guess.
const IDLE_MILLIS = 120;
const NO_CHANGE = 0x4e;

// `WheelEvent.deltaMode` is a unit, not a size: one line is about this many pixels, one page about
// that many. Only Firefox and a few configurations use them; Chrome reports pixels.
const LINE_PIXELS = 40;
const PAGE_PIXELS = 400;

const canvas = document.getElementById('screen');
const status = document.getElementById('status');
const ctx = canvas.getContext('2d');

// The size the advances are measured at. Everything scales linearly, so a large reference keeps the
// rounding error small without measuring at every size the page might use.
const REFERENCE_PX = 90;

// The code points the server is told about. Latin printable covers the framework's own labels;
// the samples after it stand for scripts whose width differs a lot from Latin, so a page with a
// translation in one of them still lays out sensibly.
const SAMPLES = ['中', '文', 'ぁ', 'あ', '한', 'А', 'я', '😀', '\u00a0'];

let socket = null;
let frame = null;
let awaiting = false;
let timer = 0;
let dpr = window.devicePixelRatio || 1;
// A capture request before the first frame would photograph an empty canvas, which is worse than no
// answer at all: the server keeps asking, so staying quiet is the honest reply until there is
// something on screen.
let painted = false;

function resizeCanvas() {
  dpr = window.devicePixelRatio || 1;
  canvas.width = Math.max(320, Math.floor(window.innerWidth * dpr));
  canvas.height = Math.max(240, Math.floor(window.innerHeight * dpr));
  canvas.style.width = window.innerWidth + 'px';
  canvas.style.height = window.innerHeight + 'px';
}

function connect() {
  socket = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
  socket.binaryType = 'arraybuffer';
  socket.onopen = () => {
    status.textContent = 'connected';
    // Metrics first: the server lays text out with them, so the very first frame should already have
    // them. Then the size, then a keyframe to start from.
    reportMetrics();
    send('resize ' + canvas.width + ' ' + canvas.height);
    send('keyframe');
    tick();
  };
  socket.onmessage = (event) => {
    // Text messages are commands for the client; binary ones are frames.
    if (typeof event.data === 'string') {
      if (event.data.trim() === 'capture') {
        if (!painted) return;
        canvas.toBlob((blob) => {
          if (blob) blob.arrayBuffer().then((buffer) => socket.send(buffer));
        }, 'image/png');
      }
      return;
    }
    const message = new Uint8Array(event.data);
    frame = decode(frame, message);
    awaiting = false;
    paint();
    painted = true;
    status.textContent = message[0] === NO_CHANGE
      ? 'still (' + message.length + ' B)'
      : 'frame ' + frame.length + ' words (' + message.length + ' B)';
    schedule(message[0] === NO_CHANGE ? IDLE_MILLIS : 0);
  };
  socket.onclose = () => {
    status.textContent = 'disconnected, retrying';
    setTimeout(connect, 500);
  };
  socket.onerror = () => socket.close();
}

function schedule(delay) {
  clearTimeout(timer);
  timer = setTimeout(tick, delay);
}

function tick() {
  if (awaiting || !socket || socket.readyState !== WebSocket.OPEN) return;
  awaiting = true;
  socket.send('tick');
}

function send(text) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(text);
}

// Layout is the server's job, but the font is the browser's, so the browser measures and reports.
// The server scales these to whatever size a glyph is drawn at.
function reportMetrics() {
  const codes = [];
  for (let cp = 0x20; cp <= 0x7e; cp++) codes.push(cp);
  for (const sample of SAMPLES) codes.push(sample.codePointAt(0));
  ctx.font = REFERENCE_PX + 'px monospace';
  const measured = codes.map((cp) => cp + ':' + ctx.measureText(String.fromCodePoint(cp)).width.toFixed(3));
  send('metrics ' + REFERENCE_PX + ' ' + measured.join(' '));
}

// A script error in here would otherwise be invisible on the server side.
window.addEventListener('error', (event) => {
  send('log client error: ' + event.message + ' at ' + (event.filename || '?') + ':' + (event.lineno || 0));
});

// ---- the wire format -------------------------------------------------------------------------

function decode(base, message) {
  const view = new DataView(message.buffer, message.byteOffset, message.byteLength);
  const type = message[0];
  if (type === NOCHANGE) return base;
  const words = view.getUint32(5, true);
  if (type === KEYFRAME) {
    const out = new Int32Array(words);
    for (let i = 0; i < words; i++) out[i] = view.getInt32(KEYFRAME_HEADER + i * 4, true);
    return out;
  }
  const prefix = view.getUint32(9, true);
  const literals = view.getUint32(13, true);
  const out = new Int32Array(words);
  out.set(base.subarray(0, prefix), 0);
  for (let i = 0; i < literals; i++) out[prefix + i] = view.getInt32(DELTA_HEADER + i * 4, true);
  const tail = prefix + literals;
  const baseTail = tail + base.length - words;
  out.set(base.subarray(baseTail, baseTail + (words - tail)), tail);
  return out;
}

function strideAt(words, at) {
  const op = words[at];
  switch (op) {
    case Op.QUAD: return words[at + 9] === Op.NO_ASSET ? 10 : 14;
    case Op.TRIANGLES: {
      const vertices = words[at + 1];
      return 4 + vertices * (words[at + 2] !== 0 ? 4 : 2) + vertices;
    }
    case Op.CLIP_PUSH: return 5;
    case Op.TRANSFORM_PUSH: return 10;
    case Op.TEXT: return 9;
    case Op.BUTTON: return 7;
    case Op.SLOT: case Op.CONTAINER: case Op.TOOLTIP: case Op.DEFAULT_BACKGROUND: return 5;
    case Op.ITEM: return 9;
    case Op.TEXTURE: return 3 + words[at + 2];
    default: return 1;
  }
}

// ---- painting --------------------------------------------------------------------------------

const floatBuffer = new DataView(new ArrayBuffer(4));
function floatOf(word) {
  floatBuffer.setInt32(0, word, true);
  return floatBuffer.getFloat32(0, true);
}

function css(packed) {
  const r = (packed >>> 24) & 0xff, g = (packed >>> 16) & 0xff, b = (packed >>> 8) & 0xff, a = packed & 0xff;
  return a === 255 ? `rgb(${r},${g},${b})` : `rgba(${r},${g},${b},${(a / 255).toFixed(3)})`;
}

function matrix(words, at) {
  return [
    floatOf(words[at]), floatOf(words[at + 1]), floatOf(words[at + 2]),
    floatOf(words[at + 3]), floatOf(words[at + 4]), floatOf(words[at + 5]),
    floatOf(words[at + 6]), floatOf(words[at + 7]), floatOf(words[at + 8]),
  ];
}

function multiply(a, b) {
  const out = new Array(9);
  for (let row = 0; row < 3; row++) {
    for (let column = 0; column < 3; column++) {
      out[row * 3 + column] =
        a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column];
    }
  }
  return out;
}

// Everything is projected by hand rather than handed to ctx.setTransform, so a perspective in the
// frame comes out as the trapezoid it is and clips stay exact under any transform.
function project(m, x, y) {
  const w = m[6] * x + m[7] * y + m[8];
  const px = m[0] * x + m[1] * y + m[2];
  const py = m[3] * x + m[4] * y + m[5];
  return w === 0 ? [px, py] : [px / w, py / w];
}

function paint() {
  if (!frame) return;
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const words = frame;
  let transform = [1, 0, 0, 0, 1, 0, 0, 0, 1];
  const transforms = [];
  let clips = 0;
  let at = 0;
  while (at < words.length) {
    const op = words[at];
    switch (op) {
      case Op.QUAD: quad(words, at, transform); break;
      case Op.TRIANGLES: triangles(words, at, transform); break;
      case Op.TEXT: text(words, at, transform); break;
      case Op.BUTTON: box(words, at, transform, (words[at + 5] & 1) ? '#6a6a6a' : '#4a4a4a'); break;
      case Op.SLOT: box(words, at, transform, '#2b2b30'); break;
      case Op.CONTAINER: box(words, at, transform, '#2e2e34'); break;
      case Op.TOOLTIP: box(words, at, transform, '#1b1b28'); break;
      case Op.DEFAULT_BACKGROUND: box(words, at, transform, '#1d1a16'); break;
      case Op.ITEM: item(words, at, transform); break;
      case Op.CLIP_PUSH: {
        const rect = corners(words, at + 1, transform);
        if (rect) {
          ctx.save();
          ctx.beginPath();
          path(rect);
          ctx.clip();
          clips++;
        }
        break;
      }
      case Op.CLIP_POP:
        if (clips > 0) { ctx.restore(); clips--; }
        break;
      case Op.TRANSFORM_PUSH:
        transforms.push(transform);
        transform = multiply(transform, matrix(words, at + 1));
        break;
      case Op.TRANSFORM_POP:
        if (transforms.length) transform = transforms.pop();
        break;
      default: break;
    }
    at += strideAt(words, at);
  }
  while (clips-- > 0) ctx.restore();
}

function corners(words, at, transform) {
  const left = floatOf(words[at]), top = floatOf(words[at + 1]);
  const right = floatOf(words[at + 2]), bottom = floatOf(words[at + 3]);
  if ([left, top, right, bottom].some((v) => Number.isNaN(v))) return null;
  return [
    project(transform, left, top), project(transform, right, top),
    project(transform, right, bottom), project(transform, left, bottom),
  ];
}

function path(points) {
  ctx.moveTo(points[0][0], points[0][1]);
  for (let i = 1; i < points.length; i++) ctx.lineTo(points[i][0], points[i][1]);
  ctx.closePath();
}

function fill(points, colour) {
  ctx.beginPath();
  path(points);
  ctx.fillStyle = colour;
  ctx.fill();
}

function quad(words, at, transform) {
  const points = corners(words, at + 1, transform);
  if (!points) return;
  const lt = words[at + 5], rt = words[at + 6], lb = words[at + 7], rb = words[at + 8];
  if (words[at + 9] !== Op.NO_ASSET) {
    // No pixels on this side yet: mark where a texture goes, as the SVG snapshot does.
    fill(points, 'rgba(140,140,140,0.6)');
    return;
  }
  if (lt === rt && lt === lb && lt === rb) {
    fill(points, css(lt));
    return;
  }
  // Axis-aligned colour ramps are what the pickers use, and a gradient is exact for those.
  if (lt === lb && rt === rb) {
    const gradient = ctx.createLinearGradient(points[0][0], points[0][1], points[1][0], points[1][1]);
    gradient.addColorStop(0, css(lt));
    gradient.addColorStop(1, css(rt));
    fill(points, gradient);
    return;
  }
  if (lt === rt && lb === rb) {
    const gradient = ctx.createLinearGradient(points[0][0], points[0][1], points[3][0], points[3][1]);
    gradient.addColorStop(0, css(lt));
    gradient.addColorStop(1, css(lb));
    fill(points, gradient);
    return;
  }
  // Otherwise the two triangles the game's backends draw, for the same reason: four unrelated
  // corners have no single affine field, and this is the model the framework measured against.
  fill([points[0], points[1], points[3]], css(average([lt, rt, lb])));
  fill([points[1], points[2], points[3]], css(average([rt, rb, lb])));
}

function triangles(words, at, transform) {
  const count = words[at + 1];
  const stride = words[at + 2] !== 0 ? 4 : 2;
  const verticesAt = at + 4;
  const coloursAt = verticesAt + count * stride;
  for (let i = 0; i + 2 < count; i += 3) {
    const points = [0, 1, 2].map((step) => {
      const base = verticesAt + (i + step) * stride;
      return project(transform, floatOf(words[base]), floatOf(words[base + 1]));
    });
    fill(points, css(average([words[coloursAt + i], words[coloursAt + i + 1], words[coloursAt + i + 2]])));
  }
}

function text(words, at, transform) {
  const colour = words[at + 5];
  const style = words[at + 4];
  const size = floatOf(words[at + 8]);
  const point = project(transform, floatOf(words[at + 2]), floatOf(words[at + 3]));
  const character = (style & 16) !== 0 ? '?' : String.fromCodePoint(words[at + 1]);
  const baseline = point[1] + size * 0.8;
  ctx.fillStyle = css(colour);
  ctx.font = ((style & 2) !== 0 ? 'bold ' : '') + ((style & 1) !== 0 ? 'italic ' : '') + size + 'px monospace';
  ctx.textBaseline = 'alphabetic';
  if ((style & 32) !== 0) {
    ctx.fillStyle = '#000000';
    ctx.fillText(character, point[0] + 1, baseline + 1);
    ctx.fillStyle = css(colour);
  }
  ctx.fillText(character, point[0], baseline);
  const left = floatOf(words[at + 6]);
  const right = floatOf(words[at + 7]);
  if ((style & 4) !== 0 || (style & 8) !== 0) {
    const from = project(transform, left, 0)[0];
    const to = project(transform, right, 0)[0];
    const y = point[1] + size * ((style & 4) !== 0 ? 0.95 : 0.45);
    ctx.fillStyle = css(colour);
    ctx.fillRect(from, y, Math.max(1, to - from), Math.max(1, size / 9));
  }
}

function item(words, at, transform) {
  const points = corners(words, at + 1, transform);
  if (!points) return;
  fill(points, '#3a3a44');
  ctx.strokeStyle = '#8a8a8a';
  ctx.beginPath();
  path(points);
  ctx.stroke();
}

function box(words, at, transform, colour) {
  const points = corners(words, at + 1, transform);
  if (points) fill(points, colour);
}

function average(colours) {
  let r = 0, g = 0, b = 0, a = 0;
  for (const packed of colours) {
    r += (packed >>> 24) & 0xff;
    g += (packed >>> 16) & 0xff;
    b += (packed >>> 8) & 0xff;
    a += packed & 0xff;
  }
  const n = colours.length;
  return (((r / n) & 0xff) << 24) | (((g / n) & 0xff) << 16) | (((b / n) & 0xff) << 8) | ((a / n) & 0xff);
}

// ---- input -----------------------------------------------------------------------------------

function modifiers(event) {
  let bits = 0;
  if (event.shiftKey) bits |= 1;
  if (event.ctrlKey) bits |= 2;
  if (event.altKey) bits |= 4;
  if (event.metaKey) bits |= 8;
  if (event.getModifierState && event.getModifierState('CapsLock')) bits |= 16;
  if (event.getModifierState && event.getModifierState('NumLock')) bits |= 32;
  return bits;
}

function position(event) {
  const bounds = canvas.getBoundingClientRect();
  return [
    Math.round((event.clientX - bounds.left) * dpr),
    Math.round((event.clientY - bounds.top) * dpr),
  ];
}

function wake() {
  schedule(0);
}

canvas.addEventListener('pointermove', (event) => {
  const [x, y] = position(event);
  send('move ' + x + ' ' + y);
  wake();
});

canvas.addEventListener('pointerdown', (event) => {
  const [x, y] = position(event);
  send('move ' + x + ' ' + y);
  send('button ' + event.button + ' down ' + modifiers(event));
  canvas.setPointerCapture(event.pointerId);
  event.preventDefault();
  wake();
});

canvas.addEventListener('pointerup', (event) => {
  const [x, y] = position(event);
  send('move ' + x + ' ' + y);
  send('button ' + event.button + ' up ' + modifiers(event));
  event.preventDefault();
  wake();
});

canvas.addEventListener('wheel', (event) => {
  // The wheel's deltas come in pixels, lines or pages depending on the browser and the device, so
  // they are normalised to pixels here; the server turns pixels into notches, which is the unit the
  // framework's scroll is in.
  const unit = event.deltaMode === 1 ? LINE_PIXELS : event.deltaMode === 2 ? PAGE_PIXELS : 1;
  send('wheel ' + Math.round(event.deltaX * unit) + ' ' + Math.round(event.deltaY * unit) + ' ' + modifiers(event));
  event.preventDefault();
  wake();
}, { passive: false });

canvas.addEventListener('contextmenu', (event) => event.preventDefault());

window.addEventListener('keydown', (event) => {
  send('key ' + event.code + ' down ' + modifiers(event));
  if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
    send('char ' + event.key.codePointAt(0));
  }
  event.preventDefault();
  wake();
});

window.addEventListener('keyup', (event) => {
  send('key ' + event.code + ' up ' + modifiers(event));
  event.preventDefault();
  wake();
});

window.addEventListener('blur', () => send('blur'));

window.addEventListener('resize', () => {
  resizeCanvas();
  send('resize ' + canvas.width + ' ' + canvas.height);
  send('keyframe');
  wake();
});

resizeCanvas();
connect();
