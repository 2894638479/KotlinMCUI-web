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
// The asset table, which the server sends before a keyframe: a client that connects later has missed
// the definitions the frame was the first to use, and would otherwise draw missing textures forever.
const ASSETS = 0x41;
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

// What shows where a page has not painted.
//
// The canvas is cleared to transparent and pages do not all cover the surface, so the page's own
// background colour is part of the picture a user sees. A capture has to reproduce it: `toBlob`
// exports the canvas alone, so without this the unpainted places come out transparent and a page
// that reads perfectly well in a browser photographs as a blank one.
//
// The colour is read from the document rather than repeated here, so the page's background and the
// canvas fill cannot drift apart.
const surface = surfaceColour();

/** The page's background colour, or null when nothing behind the canvas is opaque. */
function surfaceColour() {
  for (const element of [document.body, document.documentElement]) {
    const value = element === null ? null : getComputedStyle(element).backgroundColor;
    if (value && covers(value)) return value;
  }
  return null;
}

/**
 * Whether [value] is a colour a browser would actually paint.
 *
 * A three-channel `rgb()` is opaque by definition; only `rgba(..., 0)` is not. An unparseable value
 * has to answer false rather than be handed to `fillStyle`, where an invalid assignment is silently
 * ignored and the previous fill colour survives - which on a later `fillRect` would paint with
 * whatever was last used.
 */
function covers(value) {
  const channels = /^rgba?\(([^)]+)\)$/.exec(value);
  if (!channels) return false;
  const parts = channels[1].split(',').map((part) => parseFloat(part));
  return parts.length === 3 || parts[3] !== 0;
}

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
let dpr = window.devicePixelRatio || 1;
// A capture request before the first frame would photograph an empty canvas, which is worse than no
// answer at all: the server keeps asking, so staying quiet is the honest reply until there is
// something on screen.
let painted = false;
// Frame pacing. The client asks for one frame per display refresh while anything is changing, and
// drops to a slow poll once the server says nothing changed - which is also what stops a still page
// from spinning the frame thread at hundreds of frames a second.
let asking = true;
let stillUntil = 0;

// The asset ids a frame defined, and the pixels once they arrive. `undefined` means no definition;
// `null` means the request is still in flight (draw nothing); a canvas or image is ready pixels.
// A failed load becomes the checkerboard canvas, so it is a texture, never an untextured fill, and
// the failure is not retried on every frame.
const assets = [];
const images = {};
let checkerboard = null;

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
    // The language first: the server lays a page out with it, and it is a property of the process -
    // the tree is shared - so the first client to speak decides what language the UI is in.
    send('lang ' + (navigator.language || 'en_us'));
    reportMetrics();
    send('resize ' + canvas.width + ' ' + canvas.height);
    send('keyframe');
    // Nothing behind the canvas is opaque, so there is nothing to fill a capture with. That is the
    // honest picture rather than a reason to invent a colour - but it is worth saying out loud,
    // because a capture of such a page looks blank.
    if (surface === null) send('log client: the page has no opaque background, a capture will be transparent');
    asking = true;
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
    if (message[0] === ASSETS) {
      applyAssets(message);
      return;
    }
    frame = decode(frame, message);
    awaiting = false;
    paint();
    painted = true;
    // A character the server has not been told about yet is laid out with a guess; measuring it here
    // is what turns the next frame into the right one.
    reportMissingMetrics(frame);
    status.textContent = message[0] === NO_CHANGE
      ? 'still (' + message.length + ' B)'
      : 'frame ' + frame.length + ' words (' + message.length + ' B)';
    // A frame that changed asks for the next one on the coming refresh (an animation runs at the
    // display's rate); one that did not drops to the idle poll.
    asking = message[0] !== NO_CHANGE;
    stillUntil = performance.now() + IDLE_MILLIS;
  };
  socket.onclose = () => {
    status.textContent = 'disconnected, retrying';
    setTimeout(connect, 500);
  };
  socket.onerror = () => socket.close();
}

/**
 * The frame clock: one request per animation frame, which is the display's own rate.
 *
 * This is deliberately not a `setTimeout` loop. Asking again the moment a frame arrives runs the
 * server's frame thread as fast as the socket allows - hundreds of frames a second, all of them
 * competing with the input that the user is waiting on, which is exactly how a page that reports a
 * very high frame rate still feels slow. Coupling to the refresh keeps animation smooth and gives the
 * server room to answer input.
 */
function pump() {
  requestAnimationFrame(pump);
  if (awaiting || !socket || socket.readyState !== WebSocket.OPEN) return;
  if (!asking && performance.now() < stillUntil) return;
  asking = false;
  awaiting = true;
  socket.send('tick');
}

requestAnimationFrame(pump);

function send(text) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(text);
}

// Layout is the server's job, but the font is the browser's, so the browser measures and reports.
// The server scales these to whatever size a glyph is drawn at.
const measured = new Set();

function reportMetrics() {
  const codes = [];
  for (let cp = 0x20; cp <= 0x7e; cp++) codes.push(cp);
  for (const sample of SAMPLES) codes.push(sample.codePointAt(0));
  sendMetrics(codes);
}

function sendMetrics(codes) {
  const fresh = codes.filter((cp) => !measured.has(cp));
  if (!fresh.length) return;
  for (const cp of fresh) measured.add(cp);
  ctx.font = REFERENCE_PX + 'px monospace';
  const widths = fresh.map((cp) => cp + ':' + ctx.measureText(String.fromCodePoint(cp)).width.toFixed(3));
  send('metrics ' + REFERENCE_PX + ' ' + widths.join(' '));
}

/**
 * Report every character of a frame the server has not been told about yet.
 *
 * A fixed sample cannot know which scripts a page uses: the framework's own labels are translated, and
 * a translated page is full of code points a Latin sample has never seen - which is exactly how the
 * first Chinese page came out with its glyphs on top of each other. Measuring on sight means the
 * layout corrects itself one frame after a character first appears, whatever the script is, and the
 * report stays limited to what the page actually draws.
 */
function reportMissingMetrics(frame) {
  const missing = [];
  let at = 0;
  while (at < frame.length) {
    if (frame[at] === Op.TEXT) missing.push(frame[at + 1]);
    at += strideAt(frame, at);
  }
  sendMetrics(missing);
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
    case Op.QUAD: return words[at + 9] === Op.NO_ASSET ? 10 : 16;
    case Op.TRIANGLES: {
      const vertices = words[at + 1];
      const textured = words[at + 2] !== 0;
      return 4 + vertices * (textured ? 4 : 2) + vertices + (textured ? 2 : 0);
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
  // Cleared first and filled second, rather than not clearing at all: the page's colour is allowed to
  // be translucent, and a transparent page must still be photographed as transparent.
  if (surface !== null) {
    ctx.fillStyle = surface;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
  }
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
        // Every push must have exactly one save and one later restore. CLIP_POP is unconditional,
        // so a push that skipped its save would let the pop restore the clip outside this scope, and
        // a push that skipped its clip would silently reuse the previous draw's clip. An invalid or
        // empty rectangle becomes an explicit empty path, which clips everything out; the block's
        // records still replay, they just have no coverage.
        ctx.save();
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        // Canvas paths are in device space once their segments are added and ctx.save does not keep
        // the current path, so start a new one here. That also stops a clip from inheriting any
        // path a previous record happened to leave behind.
        ctx.beginPath();
        const shape = clipCorners(words, at + 1, transform);
        if (shape) path(ctx, shape);
        ctx.clip();
        clips++;
        break;
      }
      case Op.CLIP_POP:
        if (clips > 0) { ctx.restore(); clips--; }
        break;
      case Op.TEXTURE: defineAsset(words[at + 1], assetId(words, at)); break;
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
  while (clips > 0) { ctx.restore(); clips--; }
}

/**
 * The projected corners of a clip rectangle, or null when the clip must be empty.
 *
 * A usable clip needs four finite edges with a strictly positive extent, a current transform that is
 * finite and invertible, and all four corners in front of the eye (w > 0). Anything else fails
 * closed: the caller turns null into an empty path rather than skipping the clip. The corners are
 * the exact projected quadrilateral; no bounding box is substituted, because a rotated or
 * perspective clip is not its axis-aligned bounds.
 */
function clipCorners(words, at, transform) {
  const left = floatOf(words[at]), top = floatOf(words[at + 1]);
  const right = floatOf(words[at + 2]), bottom = floatOf(words[at + 3]);
  if (![left, top, right, bottom].every(Number.isFinite)) return null;
  if (!Number.isFinite(Math.fround(right - left)) || !Number.isFinite(Math.fround(bottom - top))) return null;
  if (!(right > left) || !(bottom > top)) return null;
  if (!invertible(transform)) return null;
  const points = [
    projectPositive(transform, left, top), projectPositive(transform, right, top),
    projectPositive(transform, right, bottom), projectPositive(transform, left, bottom),
  ];
  return points.every((point) => point !== null) ? points : null;
}

/** Whether every entry is finite and the matrix has an inverse with a usable determinant. */
function invertible(m) {
  for (const value of m) if (!Number.isFinite(value)) return false;
  return invertMatrix(m) !== null;
}

/** Project one point, requiring a finite positive w; null when the eye plane is crossed. */
function projectPositive(m, x, y) {
  const w = m[6] * x + m[7] * y + m[8];
  if (!Number.isFinite(w) || !(w > 0)) return null;
  const px = (m[0] * x + m[1] * y + m[2]) / w;
  const py = (m[3] * x + m[4] * y + m[5]) / w;
  return Number.isFinite(px) && Number.isFinite(py) ? [px, py] : null;
}

function corners(words, at, transform) {
  const left = floatOf(words[at]), top = floatOf(words[at + 1]);
  const right = floatOf(words[at + 2]), bottom = floatOf(words[at + 3]);
  if (![left, top, right, bottom].every(Number.isFinite)) return null;
  const points = [
    project(transform, left, top), project(transform, right, top),
    project(transform, right, bottom), project(transform, left, bottom),
  ];
  return points.every(finitePoint) ? points : null;
}

function path(context, points) {
  context.moveTo(points[0][0], points[0][1]);
  for (let i = 1; i < points.length; i++) context.lineTo(points[i][0], points[i][1]);
  context.closePath();
}

function fill(points, colour) {
  ctx.beginPath();
  path(ctx, points);
  ctx.fillStyle = colour;
  ctx.fill();
}

function assetId(words, at) {
  const length = words[at + 2];
  let id = '';
  for (let i = 0; i < length; i++) id += String.fromCharCode(words[at + 3 + i]);
  return id;
}

/**
 * Fill a projected rectangle, snapped to the pixel grid when it is axis-aligned.
 *
 * Adjacent quads are how the framework draws a segmented gradient - the hue bar's six steps, a
 * slider track's twelve - and filling each as an antialiased path leaves a hairline of background
 * between them. Growing an axis-aligned fill by half a pixel on every side makes neighbours overlap
 * instead, and snapping to whole pixels keeps one-pixel lines crisp.
 */
function paintBox(points, style) {
  const box = axisAligned(points);
  if (!box) {
    fill(points, style);
    return;
  }
  const [x0, y0, x1, y1] = box;
  ctx.fillStyle = style;
  ctx.fillRect(x0 - 0.5, y0 - 0.5, (x1 - x0) + 1, (y1 - y0) + 1);
}

/** The pixel bounds of a projected rectangle, or null when it is rotated or skewed. */
function axisAligned(points) {
  const xs = points.map((point) => point[0]);
  const ys = points.map((point) => point[1]);
  const x0 = Math.min(...xs);
  const x1 = Math.max(...xs);
  const y0 = Math.min(...ys);
  const y1 = Math.max(...ys);
  for (const point of points) {
    const onX = Math.abs(point[0] - x0) < 0.05 || Math.abs(point[0] - x1) < 0.05;
    const onY = Math.abs(point[1] - y0) < 0.05 || Math.abs(point[1] - y1) < 0.05;
    if (!onX || !onY) return null;
  }
  return [x0, y0, x1, y1];
}

function quad(words, at, transform) {
  const points = corners(words, at + 1, transform);
  if (!points) return;
  const lt = words[at + 5], rt = words[at + 6], lb = words[at + 7], rb = words[at + 8];
  const asset = words[at + 9];
  if (asset !== Op.NO_ASSET) {
    const image = imageOf(asset);
    // No pixels yet (or no definition): this draw is invisible. A failed load is already a
    // checkerboard image here, so it goes through the same texture path rather than a fill.
    if (!drawable(image)) return;
    const u0 = floatOf(words[at + 10]), v0 = floatOf(words[at + 11]);
    const u1 = floatOf(words[at + 12]), v1 = floatOf(words[at + 13]);
    const width = floatOf(words[at + 14]), height = floatOf(words[at + 15]);
    if (!positive(width) || !positive(height)) return;
    const uniform = lt === rt && rt === lb && lb === rb;
    // A texture with one tint and an affine transform is drawn in one piece: clipping a pattern
    // cannot show a seam, and under an affine map its sampling stays affine across the fourth
    // corner. A projective map, or a tint that varies per pixel, goes through the raster field
    // below instead, because a pattern (and a CanvasGradient) cannot express either correctly.
    if (uniform && isAffine(transform)) {
      const map = quadMap([[u0, v0], [u1, v0], [u1, v1], [u0, v1]], points, image, width, height);
      if (map) {
        texturedShape(points, image, [lt, rt, rb, lb], map);
        return;
      }
    }
    const rect = [floatOf(words[at + 1]), floatOf(words[at + 2]), floatOf(words[at + 3]), floatOf(words[at + 4])];
    rasterizeQuad(
      points, transform, rect, [lt, rt, lb, rb], image, width, height, [u0, v0, u1, v1],
    );
    return;
  }
  if (lt === rt && lt === lb && lt === rb) {
    paintBox(points, css(lt));
    return;
  }
  // The pickers draw two-colour ramps on an axis-aligned rectangle. A CanvasGradient is exact only
  // there: its level lines are perpendicular to the axis in *device* space, so a shear would skew
  // the ramp. Equal alpha is what makes the straight stop colours equivalent to the premultiplied
  // field; a varying alpha is not, because the canvas spec interpolates stops unpremultiplied.
  //
  // The outline being axis-aligned is not enough on its own: `axisAligned` only inspects the four
  // projected corners, so it cannot tell a genuine affine map from a projective one whose bottom
  // row merely rescales them (a bottom-right-only homography does exactly that). Only an actually
  // affine transform proves that the field's u is linear in device space and that the gradient's
  // level lines match the quad's local field.
  if (lt === lb && rt === rb && (lt & 0xff) === (rt & 0xff) && isAffine(transform) && axisAligned(points)) {
    const gradient = ctx.createLinearGradient(points[0][0], points[0][1], points[1][0], points[1][1]);
    gradient.addColorStop(0, css(lt));
    gradient.addColorStop(1, css(rt));
    paintBox(points, gradient);
    return;
  }
  if (lt === rt && lb === rb && (lt & 0xff) === (lb & 0xff) && isAffine(transform) && axisAligned(points)) {
    const gradient = ctx.createLinearGradient(points[0][0], points[0][1], points[3][0], points[3][1]);
    gradient.addColorStop(0, css(lt));
    gradient.addColorStop(1, css(lb));
    paintBox(points, gradient);
    return;
  }
  // Everything else is ONE bilinear field in the quad's original local coordinates. Both triangles
  // a backend might submit read the same field, so there is no diagonal where two independently
  // shaded triangles could disagree or composite twice.
  const rect = [floatOf(words[at + 1]), floatOf(words[at + 2]), floatOf(words[at + 3]), floatOf(words[at + 4])];
  rasterizeQuad(points, transform, rect, [lt, rt, lb, rb], null, 0, 0, null);
}

function triangles(words, at, transform) {
  const count = words[at + 1];
  const textured = words[at + 2] !== 0;
  const stride = textured ? 4 : 2;
  const verticesAt = at + 4;
  const coloursAt = verticesAt + count * stride;
  // A malformed vertex rejects the whole record rather than half of it: a partially drawn list
  // would leave triangles the server thinks it asked for missing from the picture.
  for (let i = 0; i < count; i++) {
    const base = verticesAt + i * stride;
    for (let part = 0; part < (textured ? 4 : 2); part++) {
      if (!Number.isFinite(floatOf(words[base + part]))) return;
    }
  }
  const triCount = Math.floor(count / 3);
  // A triangle list is often a mesh whose triangles share edges. Drawing each triangle through the
  // uniform fast paths (or through a per-triangle mask) antialiases the shared edge twice, so two
  // half-covered composites leave a darker seam. The flags let the shared rasteriser give every
  // shared edge to exactly one triangle, while real overlapping areas still composite in list order.
  const shared = sharedEdgeFlags(words, verticesAt, stride, triCount);
  if (textured) {
    const image = imageOf(words[at + 3]);
    // Pending, undefined or not-yet-ready: no visible draw. A failed load is the checkerboard.
    if (!drawable(image)) return;
    const width = floatOf(words[coloursAt + count]);
    const height = floatOf(words[coloursAt + count + 1]);
    if (!positive(width) || !positive(height)) return;
    for (let t = 0; t < triCount; t++) {
      const i = t * 3;
      const points = [];
      const uvs = [];
      const local = [];
      for (let step = 0; step < 3; step++) {
        const base = verticesAt + (i + step) * stride;
        const x = floatOf(words[base]), y = floatOf(words[base + 1]);
        local.push(x, y);
        points.push(project(transform, x, y));
        uvs.push([floatOf(words[base + 2]), floatOf(words[base + 3])]);
      }
      const colours = [words[coloursAt + i], words[coloursAt + i + 1], words[coloursAt + i + 2]];
      if (colours[0] === colours[1] && colours[1] === colours[2] && isAffine(transform) &&
        !hasSharedEdge(shared, i)) {
        texturedTriangle(points, uvs, image, width, height, colours);
      } else {
        rasterizeTriangle(
          points, transform, local, colours, image, width, height,
          [uvs[0][0], uvs[0][1], uvs[1][0], uvs[1][1], uvs[2][0], uvs[2][1]], shared, i,
        );
      }
    }
    return;
  }
  for (let t = 0; t < triCount; t++) {
    const i = t * 3;
    const points = [];
    const local = [];
    for (let step = 0; step < 3; step++) {
      const base = verticesAt + (i + step) * stride;
      const x = floatOf(words[base]), y = floatOf(words[base + 1]);
      local.push(x, y);
      points.push(project(transform, x, y));
    }
    const colours = [words[coloursAt + i], words[coloursAt + i + 1], words[coloursAt + i + 2]];
    if (colours[0] === colours[1] && colours[1] === colours[2] && !hasSharedEdge(shared, i)) {
      fill(points, css(colours[0]));
    } else {
      rasterizeTriangle(points, transform, local, colours, null, 0, 0, null, shared, i);
    }
  }
}

// Edge ownership for one record. 0 means an outer edge, 1 a shared edge this triangle does not own
// in the boundary tie, and 2 a shared edge it does own. Reused across draws; only the used prefix
// is cleared, so a frame with many meshes allocates at most once per maximum size.
const triangleEdgeOwners = new Map();
let triangleSharedFlags = new Uint8Array(0);

function hasSharedEdge(flags, at) {
  return flags[at] !== 0 || flags[at + 1] !== 0 || flags[at + 2] !== 0;
}

/**
 * Mark the triangle edges that are shared with another triangle in the same record.
 *
 * An edge is a vertex pair, and its key is orientation-free, so a mesh may wind its second
 * triangle either way. The first triangle to claim an edge owns it; every later triangle that uses
 * the same edge yields the boundary samples. That is the half-open rule that makes the interior of
 * a tessellated shape covered exactly once, while triangles that merely overlap (no shared
 * vertices) keep compositing independently in list order.
 */
function sharedEdgeFlags(words, verticesAt, stride, triCount) {
  const length = triCount * 3;
  if (triangleSharedFlags.length < length) triangleSharedFlags = new Uint8Array(length);
  else triangleSharedFlags.fill(0, 0, length);
  if (triCount < 2) return triangleSharedFlags;
  triangleEdgeOwners.clear();
  for (let t = 0; t < triCount; t++) {
    for (let edge = 0; edge < 3; edge++) {
      const a = t * 3 + ((edge + 1) % 3);
      const b = t * 3 + ((edge + 2) % 3);
      const key = triangleEdgeKey(words, verticesAt, stride, a, b);
      if (key === null) continue;
      const own = t * 3 + edge;
      const first = triangleEdgeOwners.get(key);
      if (first === undefined) {
        triangleEdgeOwners.set(key, own);
      } else {
        triangleSharedFlags[own] = 1;
        if (triangleSharedFlags[first] !== 1) triangleSharedFlags[first] = 2;
      }
    }
  }
  return triangleSharedFlags;
}

/** An orientation-free key for the local edge between two vertices, or null when unusable. */
function triangleEdgeKey(words, verticesAt, stride, a, b) {
  const ax = floatOf(words[verticesAt + a * stride]);
  const ay = floatOf(words[verticesAt + a * stride + 1]);
  const bx = floatOf(words[verticesAt + b * stride]);
  const by = floatOf(words[verticesAt + b * stride + 1]);
  if (!Number.isFinite(ax) || !Number.isFinite(ay) ||
    !Number.isFinite(bx) || !Number.isFinite(by)) return null;
  return ax < bx || (ax === bx && ay <= by)
    ? ax + ',' + ay + ',' + bx + ',' + by
    : bx + ',' + by + ',' + ax + ',' + ay;
}

/** Whether [value] is a per-draw dimension: finite and positive, not merely greater than zero. */
function positive(value) {
  return Number.isFinite(value) && value > 0;
}

/**
 * Whether a cached asset value is something Canvas can actually draw.
 *
 * A pending load is null and the cache is only ever supposed to hold an Image or an offscreen
 * canvas; anything else is a bad asset entry, not a CanvasImageSource. Checking the shape here -
 * before `drawImage` / `createPattern` sees it - keeps a mistyped entry from surfacing as a
 * TypeError from inside the painter and turning one bad texture into a broken frame.
 */
function drawable(image) {
  if (image === null || typeof image !== 'object') return false;
  if (typeof image.getContext === 'function') return positive(image.width) && positive(image.height);
  return typeof image.naturalWidth === 'number' && typeof image.naturalHeight === 'number' &&
    positive(image.naturalWidth) && positive(image.naturalHeight);
}

/** Whether a projected point is something Canvas can place. */
function finitePoint(point) {
  return Number.isFinite(point[0]) && Number.isFinite(point[1]);
}

/**
 * Draw one triangle of a texture with a uniform tint.
 *
 * The UVs are in the draw's nominal image pixel space, so they are scaled by the pixels actually
 * held: a loaded image of another resolution and the missing-texture checkerboard both preserve the
 * recorded sampling rectangle. See [texturedShape] for how the tint and the two alphas are combined.
 */
function texturedTriangle(points, uvs, image, nominalWidth, nominalHeight, colours) {
  const actual = actualUvs(uvs, image, nominalWidth, nominalHeight);
  texturedShape(points, image, colours, textureMap(actual, points));
}

/**
 * Draw one textured polygon with a UNIFORM tint, composited onto the screen exactly once.
 *
 * A tint that is the same on every vertex needs no interpolated colour field, so the texture can be
 * the source and the uniform factors can be applied with whole-surface blends on a scratch surface.
 * The scratch claims only the polygon's visible bounds, is reused by every draw, and never grows
 * past the canvas. Blending on the scratch instead of the screen is what keeps `multiply` and
 * `destination-in` from changing whatever the page already painted underneath.
 *
 * The result is the contract's source: premultiplied RGB is the texture's premultiplied colour times
 * the tint's premultiplied colour, and alpha is the texture's alpha times the tint's alpha, each
 * applied once. `multiply` is only exact when its destination is opaque - over a translucent
 * backdrop Canvas mixes the backdrop into the product - so the tint starts from an opaque copy of
 * the texture's colour and the texture's alpha is put back afterwards, instead of multiplying the
 * tint into the texture's own alpha and squaring it.
 *
 * A polygon with no tint is drawn straight onto the screen with one `source-over`; a white tint is
 * composited from the texture's own alpha without the multiply. A varying tint or a projective map
 * does not come here: it cannot be expressed by a pattern plus whole-surface blends, and is
 * rasterised by [rasterizeQuad] / [rasterizeTriangle] instead.
 */
function texturedShape(points, image, colours, map) {
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;
  if (!positive(width) || !positive(height)) return;
  if (!points.every(finitePoint)) return;
  const uniform = colours.every((colour) => colour === colours[0]);
  if (!uniform) return;
  const tint = colours[0];
  // A white tint leaves the texture's RGB alone, so the texture itself is the source and neither
  // the tint pass nor the alpha restore is needed.
  const whiteRgb = isWhiteRgb(tint);
  if (isWhite(tint)) {
    ctx.save();
    try {
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.beginPath();
      path(ctx, points);
      ctx.clip();
      drawTexture(ctx, image, map, points, 0, 0);
    } finally {
      ctx.restore();
    }
    return;
  }
  const bounds = visibleBounds(points);
  if (bounds === null) return;
  const [left, top, right, bottom] = bounds;
  const boxWidth = right - left, boxHeight = bottom - top;
  const dx = -left, dy = -top;
  const scratch = scratchFor(boxWidth, boxHeight);
  scratch.save();
  try {
    scratch.setTransform(1, 0, 0, 1, dx, dy);
    scratch.clearRect(left, top, boxWidth, boxHeight);
    scratch.beginPath();
    path(scratch, points);
    scratch.clip();
    if (whiteRgb) {
      drawTexture(scratch, image, map, points, dx, dy);
    } else {
      // The multiply destination, not the tint, has to be opaque for the product to be exact.
      drawTexture(scratch, opaqueSource(image) || image, map, points, dx, dy);
      scratch.globalCompositeOperation = 'multiply';
      scratch.setTransform(1, 0, 0, 1, dx, dy);
      scratch.fillStyle = css(tint | 0xff);
      scratch.fillRect(left, top, boxWidth, boxHeight);
      // Put the texture's alpha back, which the opaque destination above replaced.
      scratch.globalCompositeOperation = 'destination-in';
      drawTexture(scratch, image, map, points, dx, dy);
    }
    if ((tint & 0xff) !== 0xff) {
      // The vertex tint's alpha multiplies the texture's alpha, once.
      scratch.globalCompositeOperation = 'destination-in';
      scratch.setTransform(1, 0, 0, 1, dx, dy);
      scratch.fillStyle = `rgba(255,255,255,${((tint & 0xff) / 255).toFixed(3)})`;
      scratch.fillRect(left, top, boxWidth, boxHeight);
    }
  } finally {
    scratch.restore();
  }
  ctx.save();
  try {
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalCompositeOperation = 'source-over';
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(scratchSurface.canvas, 0, 0, boxWidth, boxHeight, left, top, boxWidth, boxHeight);
  } finally {
    ctx.restore();
  }
}

// ---- one premultiplied field per draw --------------------------------------------------------
//
// A quad's four corner colours define ONE bilinear field in the quad's original (untransformed)
// rectangle, and a triangle's three colours define ONE barycentric field. Both are evaluated with
// the same rule: normalise the encoded sRGB components and the alpha to 0..1, premultiply each
// vertex's RGB by its alpha, then interpolate the premultiplied RGB and the alpha in floating point.
// No intermediate 8-bit quantisation, and no linear-light conversion.
//
// The field is rasterised at device-pixel resolution into a reused offscreen surface and composited
// once with `source-over`. That is what makes the browser path follow a projective transform: the
// inverse homography recovers the sample's original local coordinate, which is the domain of the
// field, no matter where the quad was projected. A CanvasGradient could not do this (its level lines
// are perpendicular to the axis in device space, and the spec interpolates stops unpremultiplied),
// and two independently shaded triangles could not either (their shared diagonal would be two
// different planes and two composites).

const gradientCorners = new Float64Array(16);

/** Premultiply four straight corner colours into [gradientCorners]. */
function loadQuadCorners(colours) {
  for (let i = 0; i < 4; i++) {
    const packed = colours[i];
    const alpha = packed & 0xff;
    const at = i * 4;
    gradientCorners[at] = ((packed >>> 24) & 0xff) * alpha / 255;
    gradientCorners[at + 1] = ((packed >>> 16) & 0xff) * alpha / 255;
    gradientCorners[at + 2] = ((packed >>> 8) & 0xff) * alpha / 255;
    gradientCorners[at + 3] = alpha;
  }
}

const triangleCorners = new Float64Array(12);

/** Premultiply three straight vertex colours into [triangleCorners]. */
function loadTriangleCorners(colours) {
  for (let i = 0; i < 3; i++) {
    const packed = colours[i];
    const alpha = packed & 0xff;
    const at = i * 4;
    triangleCorners[at] = ((packed >>> 24) & 0xff) * alpha / 255;
    triangleCorners[at + 1] = ((packed >>> 16) & 0xff) * alpha / 255;
    triangleCorners[at + 2] = ((packed >>> 8) & 0xff) * alpha / 255;
    triangleCorners[at + 3] = alpha;
  }
}

/** The inverse of a row-major 3x3, or null when it has no usable inverse. */
function invertMatrix(m) {
  const a = m[0], b = m[1], c = m[2];
  const d = m[3], e = m[4], f = m[5];
  const g = m[6], h = m[7], i = m[8];
  const A = e * i - f * h, B = c * h - b * i, C = b * f - c * e;
  const D = f * g - d * i, E = a * i - c * g, F = c * d - a * f;
  const G = d * h - e * g, H = b * g - a * h, I = a * e - b * d;
  const determinant = a * A + b * D + c * G;
  if (!Number.isFinite(determinant) || determinant === 0) return null;
  const inverse = [
    A / determinant, B / determinant, C / determinant,
    D / determinant, E / determinant, F / determinant,
    G / determinant, H / determinant, I / determinant,
  ];
  return inverse.every(Number.isFinite) ? inverse : null;
}

const localPointStore = new Float64Array(2);

/**
 * Apply [inverse] to a device point and divide by the resulting w, or null when it is unusable.
 *
 * This is the inverse homography: for an affine transform w is 1, and for a valid perspective it is
 * the positive projective denominator that recovers the point in the draw's own coordinates.
 */
function localPoint(inverse, x, y) {
  const w = inverse[6] * x + inverse[7] * y + inverse[8];
  if (!Number.isFinite(w) || w === 0) return null;
  localPointStore[0] = (inverse[0] * x + inverse[1] * y + inverse[2]) / w;
  localPointStore[1] = (inverse[3] * x + inverse[4] * y + inverse[5]) / w;
  return Number.isFinite(localPointStore[0]) && Number.isFinite(localPointStore[1])
    ? localPointStore
    : null;
}

/** The texture's pixels as straight RGBA, read once per asset and cached; null when unreadable. */
const pixelSources = new WeakMap();

function texturePixels(image) {
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;
  if (!positive(width) || !positive(height)) return null;
  const cached = pixelSources.get(image);
  if (cached && cached.width === width && cached.height === height) return cached;
  let data = null;
  try {
    if (typeof image.getContext === 'function') {
      data = image.getContext('2d').getImageData(0, 0, width, height).data;
    } else {
      const surface = document.createElement('canvas');
      surface.width = width;
      surface.height = height;
      const context = surface.getContext('2d');
      context.drawImage(image, 0, 0);
      data = context.getImageData(0, 0, width, height).data;
    }
  } catch (error) {
    // A texture the page cannot read is a security decision, not a reason to throw out of the
    // frame; the draw is skipped rather than guessed. Any other error is a real bug and escapes.
    if (!error || error.name !== 'SecurityError') throw error;
    data = null;
  }
  const entry = data === null ? null : { width, height, data };
  pixelSources.set(image, entry);
  return entry;
}

/** The decoded pixels plus the scaling and UV rectangle a textured raster draw samples. */
function textureField(image, nominalWidth, nominalHeight, uv) {
  const pixels = texturePixels(image);
  if (!pixels) return null;
  return {
    data: pixels.data,
    width: pixels.width,
    height: pixels.height,
    scaleX: pixels.width / nominalWidth,
    scaleY: pixels.height / nominalHeight,
    uv,
  };
}

/** The byte offset of the texel at nominal UV (tu,tv), repeating at the image's own period. */
function texelAt(field, tu, tv) {
  let x = Math.floor(tu * field.scaleX) % field.width;
  if (x < 0) x += field.width;
  let y = Math.floor(tv * field.scaleY) % field.height;
  if (y < 0) y += field.height;
  return (y * field.width + x) * 4;
}

/** Write one source sample, premultiplied, into an ImageData as straight RGBA. */
function writePremultiplied(data, at, red, green, blue, alpha) {
  const a = Math.max(0, Math.min(255, Math.round(alpha)));
  if (a === 0) {
    data[at] = 0;
    data[at + 1] = 0;
    data[at + 2] = 0;
    data[at + 3] = 0;
    return;
  }
  // Divide by the alpha byte that will actually be stored, so the canvas's own premultiply puts
  // the colour back where it was instead of accumulating this rounding a second time.
  const scale = 255 / a;
  data[at] = Math.max(0, Math.min(255, Math.round(red * scale)));
  data[at + 1] = Math.max(0, Math.min(255, Math.round(green * scale)));
  data[at + 2] = Math.max(0, Math.min(255, Math.round(blue * scale)));
  data[at + 3] = a;
}

// The field pixels, grown to the largest visible box seen and never past the canvas. Growing on a
// new maximum is the only allocation; every later draw reuses the same ImageData and writes the
// sub-rectangle it needs.
const fieldSurface = { image: null };

function fieldImage(width, height) {
  let image = fieldSurface.image;
  if (!image || image.width < width || image.height < height) {
    image = scratchFor(width, height).createImageData(width, height);
    fieldSurface.image = image;
  }
  return image;
}

/** Composite the finished field onto the screen once, with the screen's clip still in force. */
function compositeField(bounds) {
  const [left, top, right, bottom] = bounds;
  const width = right - left, height = bottom - top;
  ctx.save();
  try {
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalCompositeOperation = 'source-over';
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(scratchSurface.canvas, 0, 0, width, height, left, top, width, height);
  } finally {
    ctx.restore();
  }
}

/** Clear one row of the field's box so skipped samples cannot reuse a previous draw's pixels. */
function clearFieldRows(image, width, height) {
  const data = image.data;
  for (let row = 0; row < height; row++) {
    const from = row * image.width * 4;
    data.fill(0, from, from + width * 4);
  }
}

/**
 * Rasterise one quad's bilinear field, with an optional texture, and composite it once.
 *
 * The per-pixel weights are the contract's (1-u)(1-v), u(1-v), (1-u)v and uv, and every pixel is
 * covered at most once: there is no geometric split, so no diagonal can crease or double-composite.
 * The texture is sampled in nominal UV space, which is independent of the gradient's local u,v, so
 * repetition, cropping and negative UVs cannot restart or rescale the colour field.
 */
function rasterizeQuad(points, transform, rect, colours, image, nominalWidth, nominalHeight, uv) {
  const bounds = visibleBounds(points);
  if (bounds === null) return;
  const inverse = invertMatrix(transform);
  if (inverse === null) return;
  const left = rect[0], top = rect[1];
  const width = rect[2] - left, height = rect[3] - top;
  if (!(width > 0) || !(height > 0)) return;
  const texture = image === null ? null : textureField(image, nominalWidth, nominalHeight, uv);
  if (image !== null && texture === null) return;
  loadQuadCorners(colours);
  const [boxLeft, boxTop, boxRight, boxBottom] = bounds;
  const boxWidth = boxRight - boxLeft, boxHeight = boxBottom - boxTop;
  const field = fieldImage(boxWidth, boxHeight);
  const data = field.data;
  clearFieldRows(field, boxWidth, boxHeight);
  for (let y = boxTop; y < boxBottom; y++) {
    const deviceY = y + 0.5;
    for (let x = boxLeft; x < boxRight; x++) {
      const local = localPoint(inverse, x + 0.5, deviceY);
      if (local === null) continue;
      const u = (local[0] - left) / width;
      if (!(u >= 0 && u <= 1)) continue;
      const v = (local[1] - top) / height;
      if (!(v >= 0 && v <= 1)) continue;
      const w0 = (1 - u) * (1 - v), w1 = u * (1 - v);
      const w2 = (1 - u) * v, w3 = u * v;
      let red = gradientCorners[0] * w0 + gradientCorners[4] * w1 +
        gradientCorners[8] * w2 + gradientCorners[12] * w3;
      let green = gradientCorners[1] * w0 + gradientCorners[5] * w1 +
        gradientCorners[9] * w2 + gradientCorners[13] * w3;
      let blue = gradientCorners[2] * w0 + gradientCorners[6] * w1 +
        gradientCorners[10] * w2 + gradientCorners[14] * w3;
      let alpha = gradientCorners[3] * w0 + gradientCorners[7] * w1 +
        gradientCorners[11] * w2 + gradientCorners[15] * w3;
      if (texture !== null) {
        const at = texelAt(texture, texture.uv[0] + (texture.uv[2] - texture.uv[0]) * u,
          texture.uv[1] + (texture.uv[3] - texture.uv[1]) * v);
        const texelAlpha = texture.data[at + 3] / 255;
        red = red * (texture.data[at] / 255) * texelAlpha;
        green = green * (texture.data[at + 1] / 255) * texelAlpha;
        blue = blue * (texture.data[at + 2] / 255) * texelAlpha;
        alpha *= texelAlpha;
      }
      writePremultiplied(data, ((y - boxTop) * field.width + (x - boxLeft)) * 4,
        red, green, blue, alpha);
    }
  }
  scratchFor(boxWidth, boxHeight).putImageData(field, 0, 0, 0, 0, boxWidth, boxHeight);
  compositeField(bounds);
}

/**
 * Rasterise one triangle's barycentric field, with an optional texture, and composite it once.
 *
 * The weights are computed from the triangle's ORIGINAL local vertices after the device pixel has
 * been taken back through the inverse homography, so a rotation, shear or perspective cannot move
 * the colour field relative to the geometry. The texture uses the same barycentric weights on its
 * nominal UVs.
 *
 * Coverage is antialiased against the triangle's projected edges. Edges shared with a sibling
 * triangle in the same record are not feathered: the record's owner (see [sharedEdgeFlags]) covers
 * a boundary pixel in full and the other side yields it, so the tessellated interior is composited
 * exactly once instead of twice at half alpha.
 */
const trianglePlanes = new Float64Array(9);
const triangleEdgeKinds = new Uint8Array(3);
const coverageSource = new Float64Array(24);
const coverageTarget = new Float64Array(24);

function rasterizeTriangle(points, transform, local, colours, image, nominalWidth, nominalHeight,
  uv, shared, sharedAt) {
  const bounds = visibleBounds(points);
  if (bounds === null) return;
  const inverse = invertMatrix(transform);
  if (inverse === null) return;
  const x0 = local[0], y0 = local[1];
  const x1 = local[2], y1 = local[3];
  const x2 = local[4], y2 = local[5];
  const denominator = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
  if (!Number.isFinite(denominator) || Math.abs(denominator) < 1e-9) return;
  if (!loadTrianglePlanes(points, shared, sharedAt)) return;
  const texture = image === null ? null : textureField(image, nominalWidth, nominalHeight, uv);
  if (image !== null && texture === null) return;
  loadTriangleCorners(colours);
  const [boxLeft, boxTop, boxRight, boxBottom] = bounds;
  const boxWidth = boxRight - boxLeft, boxHeight = boxBottom - boxTop;
  const field = fieldImage(boxWidth, boxHeight);
  const data = field.data;
  clearFieldRows(field, boxWidth, boxHeight);
  for (let y = boxTop; y < boxBottom; y++) {
    const deviceY = y + 0.5;
    for (let x = boxLeft; x < boxRight; x++) {
      const coverage = triangleCoverage(x, y);
      if (coverage <= 0) continue;
      const point = localPoint(inverse, x + 0.5, deviceY);
      if (point === null) continue;
      const w0 = ((y1 - y2) * (point[0] - x2) + (x2 - x1) * (point[1] - y2)) / denominator;
      const w1 = ((y2 - y0) * (point[0] - x2) + (x0 - x2) * (point[1] - y2)) / denominator;
      const w2 = 1 - w0 - w1;
      let red = triangleCorners[0] * w0 + triangleCorners[4] * w1 + triangleCorners[8] * w2;
      let green = triangleCorners[1] * w0 + triangleCorners[5] * w1 + triangleCorners[9] * w2;
      let blue = triangleCorners[2] * w0 + triangleCorners[6] * w1 + triangleCorners[10] * w2;
      let alpha = triangleCorners[3] * w0 + triangleCorners[7] * w1 + triangleCorners[11] * w2;
      if (texture !== null) {
        const at = texelAt(
          texture,
          texture.uv[0] * w0 + texture.uv[2] * w1 + texture.uv[4] * w2,
          texture.uv[1] * w0 + texture.uv[3] * w1 + texture.uv[5] * w2,
        );
        const texelAlpha = texture.data[at + 3] / 255;
        red = red * (texture.data[at] / 255) * texelAlpha;
        green = green * (texture.data[at + 1] / 255) * texelAlpha;
        blue = blue * (texture.data[at + 2] / 255) * texelAlpha;
        alpha *= texelAlpha;
      }
      writePremultiplied(data, ((y - boxTop) * field.width + (x - boxLeft)) * 4,
        red * coverage, green * coverage, blue * coverage, alpha * coverage);
    }
  }
  scratchFor(boxWidth, boxHeight).putImageData(field, 0, 0, 0, 0, boxWidth, boxHeight);
  compositeField(bounds);
}

/**
 * Build the three inward-facing device-space edge planes for the triangle.
 *
 * Every plane gives the signed perpendicular distance in pixels (positive inside), which is what
 * makes the coverage test independent of the transform: a homography maps straight lines to
 * straight lines, so the projected triangle's edges are still lines in device space. Shared edges
 * remember whether this triangle owns their boundary samples.
 */
function loadTrianglePlanes(points, shared, sharedAt) {
  for (let i = 0; i < 3; i++) {
    const a = points[(i + 1) % 3];
    const b = points[(i + 2) % 3];
    const c = points[i];
    const dx = b[0] - a[0], dy = b[1] - a[1];
    const length = Math.sqrt(dx * dx + dy * dy);
    if (!(length > 1e-12)) return false;
    const cross = dx * (c[1] - a[1]) - dy * (c[0] - a[0]);
    if (!Number.isFinite(cross) || Math.abs(cross) < 1e-12) return false;
    const scale = (cross > 0 ? 1 : -1) / length;
    trianglePlanes[i * 3] = -dy * scale;
    trianglePlanes[i * 3 + 1] = dx * scale;
    trianglePlanes[i * 3 + 2] = (dy * a[0] - dx * a[1]) * scale;
    triangleEdgeKinds[i] = shared === null ? 0 : shared[sharedAt + i];
  }
  return true;
}

/**
 * The fraction of pixel (x,y) this triangle covers, 0 when it cannot contribute.
 *
 * Outer edges are clipped against the unit pixel square for antialiasing. A shared edge instead
 * acts as a whole-pixel ownership test at the centre: one side gets the whole pixel, the other
 * none, so a tessellated interior is multiplied by alpha once instead of twice.
 */
function triangleCoverage(x, y) {
  const centreX = x + 0.5, centreY = y + 0.5;
  let nearest = Infinity;
  for (let i = 0; i < 3; i++) {
    const distance = trianglePlanes[i * 3] * centreX + trianglePlanes[i * 3 + 1] * centreY +
      trianglePlanes[i * 3 + 2];
    const kind = triangleEdgeKinds[i];
    if (kind !== 0) {
      if (distance < -1e-6) return 0;
      if (distance <= 1e-6 && kind === 1) return 0;
    } else if (distance < nearest) {
      nearest = distance;
    }
  }
  if (nearest >= 1) return 1;
  if (nearest <= -1) return 0;
  return clipSquareCoverage(x, y);
}

/** The exact area of the unit pixel square that lies inside every outer edge. */
function clipSquareCoverage(x, y) {
  let source = coverageSource, target = coverageTarget;
  let count = 4;
  source[0] = x; source[1] = y;
  source[2] = x + 1; source[3] = y;
  source[4] = x + 1; source[5] = y + 1;
  source[6] = x; source[7] = y + 1;
  for (let i = 0; i < 3; i++) {
    if (triangleEdgeKinds[i] !== 0) continue;
    count = clipHalfPlane(source, count, i, target);
    if (count === 0) return 0;
    const swap = source;
    source = target;
    target = swap;
  }
  let doubled = 0;
  for (let i = 0; i < count; i++) {
    const j = i === 0 ? count - 1 : i - 1;
    doubled += source[i * 2] * source[j * 2 + 1] - source[j * 2] * source[i * 2 + 1];
  }
  const area = Math.abs(doubled) / 2;
  return area >= 1 ? 1 : area;
}

/** Clip a convex polygon (interleaved x,y) by one inward half-plane of [trianglePlanes]. */
function clipHalfPlane(source, count, plane, target) {
  const nx = trianglePlanes[plane * 3];
  const ny = trianglePlanes[plane * 3 + 1];
  const offset = trianglePlanes[plane * 3 + 2];
  let out = 0;
  for (let i = 0; i < count; i++) {
    const j = i === 0 ? count - 1 : i - 1;
    const xi = source[i * 2], yi = source[i * 2 + 1];
    const xj = source[j * 2], yj = source[j * 2 + 1];
    const di = nx * xi + ny * yi + offset;
    const dj = nx * xj + ny * yj + offset;
    if (di >= 0) {
      if (dj < 0) {
        const t = dj / (dj - di);
        target[out * 2] = xj + (xi - xj) * t;
        target[out * 2 + 1] = yj + (yi - yj) * t;
        out++;
      }
      target[out * 2] = xi;
      target[out * 2 + 1] = yi;
      out++;
    } else if (dj >= 0) {
      const t = dj / (dj - di);
      target[out * 2] = xj + (xi - xj) * t;
      target[out * 2 + 1] = yj + (yi - yj) * t;
      out++;
    }
  }
  return out;
}

/** The UVs scaled from the draw's nominal size to the pixels actually held. */
function actualUvs(uv, image, nominalWidth, nominalHeight) {
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;
  const scaleX = width / nominalWidth;
  const scaleY = height / nominalHeight;
  return uv.map(([u, v]) => [u * scaleX, v * scaleY]);
}

/**
 * The affine map that covers a whole quad, or null when its UVs are not affine across the fourth
 * corner - such a quad has to keep the framework's two-triangle sampling.
 */
function quadMap(uv, points, image, nominalWidth, nominalHeight) {
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;
  if (!positive(width) || !positive(height)) return null;
  const actual = actualUvs(uv, image, nominalWidth, nominalHeight);
  const map = textureMap([actual[0], actual[1], actual[3]], [points[0], points[1], points[3]]);
  if (!map) return null;
  const corner = mapPoint(map, actual[2][0], actual[2][1]);
  return Math.abs(corner[0] - points[2][0]) <= 0.5 && Math.abs(corner[1] - points[2][1]) <= 0.5
    ? map
    : null;
}

/** Where [map] sends one UV point. */
function mapPoint(map, u, v) {
  return [map[0] * u + map[2] * v + map[4], map[1] * u + map[3] * v + map[5]];
}

/**
 * The device-pixel rectangle a polygon can actually touch, or null when it is off the canvas.
 *
 * The temporary surface is sized from this, so a triangle projected far off-screen cannot ask for
 * an unbounded allocation.
 */
function visibleBounds(points) {
  let left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
  for (const [x, y] of points) {
    if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
    if (x < left) left = x;
    if (y < top) top = y;
    if (x > right) right = x;
    if (y > bottom) bottom = y;
  }
  left = Math.max(0, Math.floor(left));
  top = Math.max(0, Math.floor(top));
  right = Math.min(canvas.width, Math.ceil(right));
  bottom = Math.min(canvas.height, Math.ceil(bottom));
  return right > left && bottom > top ? [left, top, right, bottom] : null;
}

// One scratch surface for every textured draw, grown to the largest polygon seen and never past the
// canvas. Reusing it is what keeps a frame full of textured quads from allocating per triangle.
const scratchSurface = { canvas: null };

function scratchFor(width, height) {
  const surface = scratchSurface.canvas || (scratchSurface.canvas = document.createElement('canvas'));
  const context = surface.getContext('2d');
  if (surface.width < width) surface.width = width;
  if (surface.height < height) surface.height = height;
  return context;
}

// The opaque-RGB copy of each asset, built once. Keyed by the image object (a WeakMap, so it goes
// away with the asset), never by tint: a per-tint full-image cache would grow with every colour the
// page draws. A null entry means the browser would not let us read the pixels, and the draw falls
// back to the image itself with only the partial-alpha matte approximated.
const opaqueSources = new WeakMap();

function opaqueSource(image) {
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;
  if (!positive(width) || !positive(height)) return null;
  const cached = opaqueSources.get(image);
  if (cached && cached.width === width && cached.height === height) return cached.canvas;
  const surface = document.createElement('canvas');
  surface.width = width;
  surface.height = height;
  const context = surface.getContext('2d');
  let opaque = null;
  context.drawImage(image, 0, 0);
  try {
    const pixels = context.getImageData(0, 0, width, height);
    const data = pixels.data;
    for (let i = 3; i < data.length; i += 4) data[i] = 255;
    context.putImageData(pixels, 0, 0);
    opaque = surface;
  } catch (error) {
    // A tainted surface cannot be read; the caller then multiplies over the texture itself, which
    // keeps the alpha exact. A TypeError here would be a bug in this painter and must not hide.
    if (!error || error.name !== 'SecurityError') throw error;
  }
  opaqueSources.set(image, { canvas: opaque, width, height });
  return opaque;
}

// CanvasPatterns repeat the texture instead of leaving out-of-range UVs blank. Cached per image and
// context: two patterns exist per asset at most (screen and scratch), never one per draw.
const patterns = new WeakMap();

function patternOf(context, image) {
  let byContext = patterns.get(image);
  if (!byContext) {
    byContext = new Map();
    patterns.set(image, byContext);
  }
  if (!byContext.has(context)) {
    const pattern = context.createPattern(image, 'repeat');
    byContext.set(context, pattern || null);
  }
  return byContext.get(context);
}

/**
 * Sample [image] through [map] into the current path, repeating outside the image.
 *
 * The UVs are nominal pixels, so repetition has the image's own pixel period; a UV past the edge
 * shows the opposite edge instead of nothing, which is what the framework's samplers do. A pattern
 * costs one fill however far the UVs run, so a quad with a huge UV extent cannot ask for a tile
 * loop. When the browser cannot make a pattern (or there is no invertible map) the draw still has
 * to be textured: it falls back to one transformed `drawImage` or to stretching into the bounds,
 * never to a colour fill.
 *
 * [dx], [dy] place the draw on the scratch surface, whose origin is the polygon's visible corner.
 */
function drawTexture(context, image, map, points, dx, dy) {
  context.imageSmoothingEnabled = false;
  if (map) {
    const pattern = patternOf(context, image);
    context.setTransform(map[0], map[1], map[2], map[3], map[4] + dx, map[5] + dy);
    if (pattern) {
      context.fillStyle = pattern;
      context.fill();
      return;
    }
    context.drawImage(image, 0, 0);
    return;
  }
  // Degenerate UVs have no invertible sampling map. Sampling details are a later pixel-semantics
  // project, but the draw still has to be textured rather than a colour fill.
  const xs = points.map((point) => point[0]);
  const ys = points.map((point) => point[1]);
  const x0 = Math.min(...xs), x1 = Math.max(...xs);
  const y0 = Math.min(...ys), y1 = Math.max(...ys);
  if (!(x1 > x0) || !(y1 > y0)) return;
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;
  context.setTransform(1, 0, 0, 1, dx, dy);
  context.drawImage(image, 0, 0, width, height, x0, y0, x1 - x0, y1 - y0);
}

/** The affine map from the three actual UV points to the three projected triangle points. */
function textureMap(uv, points) {
  const [u0, v0] = uv[0];
  const u1 = uv[1][0] - u0, v1 = uv[1][1] - v0;
  const u2 = uv[2][0] - u0, v2 = uv[2][1] - v0;
  const determinant = u1 * v2 - u2 * v1;
  if (!Number.isFinite(determinant) || Math.abs(determinant) < 1e-6) return null;
  const x1 = points[1][0] - points[0][0], y1 = points[1][1] - points[0][1];
  const x2 = points[2][0] - points[0][0], y2 = points[2][1] - points[0][1];
  const a = (x1 * v2 - x2 * v1) / determinant;
  const c = (u1 * x2 - u2 * x1) / determinant;
  const b = (y1 * v2 - y2 * v1) / determinant;
  const d = (u1 * y2 - u2 * y1) / determinant;
  const e = points[0][0] - a * u0 - c * v0;
  const f = points[0][1] - b * u0 - d * v0;
  return [a, b, c, d, e, f];
}

function isWhite(packed) {
  return packed === -1 || packed === 0xffffffff;
}

/** Whether a packed colour has white RGB, whatever its alpha. */
function isWhiteRgb(packed) {
  return ((packed & 0xffffff00) >>> 0) === 0xffffff00;
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
  paintBox(points, '#3a3a44');
  ctx.strokeStyle = '#8a8a8a';
  ctx.beginPath();
  path(ctx, points);
  ctx.stroke();
}

function box(words, at, transform, colour) {
  const points = corners(words, at + 1, transform);
  if (points) paintBox(points, colour);
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

/**
 * The pointer position in output-target pixels.
 *
 * The canvas backing store is the output target the server lays out against, and it is not always
 * `dpr` times the CSS size: `resizeCanvas` floors the size and enforces a minimum, and CSS may scale
 * the element independently. So each axis is mapped by the ratio that actually exists between this
 * canvas's backing pixels and its displayed rectangle - not by the nominal device pixel ratio, and
 * not with a single ratio for both axes. Fractional results are kept: the server's positions and
 * rectangles are fractional output pixels, and rounding here would make hit testing disagree with
 * drawing.
 *
 * A zero or unusable displayed rectangle has no defined mapping. Returning null makes the caller drop
 * the event rather than send a coordinate derived from a made-up ratio (and never a NaN).
 */
function position(event) {
  if (!(canvas.width > 0) || !(canvas.height > 0)) return null;
  const bounds = canvas.getBoundingClientRect();
  if (!Number.isFinite(bounds.width) || !Number.isFinite(bounds.height)) return null;
  if (!(bounds.width > 0) || !(bounds.height > 0)) return null;
  const x = (event.clientX - bounds.left) * (canvas.width / bounds.width);
  const y = (event.clientY - bounds.top) * (canvas.height / bounds.height);
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
  return [x, y];
}

function wake() {
  asking = true;
}

// The buttons the server accepted a press for. A press whose coordinates cannot be mapped never
// reaches the server, so it must not produce a release either; a press that did reach it must still
// be released if the canvas is resized or hidden before pointerup.
const heldButtons = new Set();
let capturedPointer = null;

function endPointerCapture(pointerId) {
  if (capturedPointer === null) return;
  try {
    if (canvas.hasPointerCapture && canvas.hasPointerCapture(pointerId)) {
      canvas.releasePointerCapture(pointerId);
    }
  } catch (error) {
    // Capture may already be gone (a cancelled pointer or a detached element); clearing our own
    // record is still the right cleanup, and there is nothing left to release.
  }
  capturedPointer = null;
}

function releaseButton(button, event) {
  if (!heldButtons.delete(button)) return false;
  send('button ' + button + ' up ' + modifiers(event));
  return true;
}

/**
 * End a pointer gesture, releasing every button the server still thinks is held.
 *
 * A pointerup or pointercancel can arrive after `position()` has lost its mapping - the canvas may
 * have been resized to zero or hidden while the button was down. Dropping that event would leave the
 * server's held-button state stuck forever, so the release is always delivered. No new coordinate is
 * invented: a mapped move is sent when one exists, otherwise the server keeps the last valid
 * position. All is used for cancels and lost capture, where no particular button is current.
 */
function endPointer(event, all, sendMove) {
  const point = sendMove ? position(event) : null;
  if (point !== null) send('move ' + point[0] + ' ' + point[1]);
  let released = false;
  if (all || event.button === undefined || event.button < 0) {
    for (const button of [...heldButtons]) released = releaseButton(button, event) || released;
  } else {
    released = releaseButton(event.button, event);
  }
  if (heldButtons.size === 0) endPointerCapture(event.pointerId);
  if (point !== null || released) {
    event.preventDefault();
    wake();
  }
}

// ---- assets -----------------------------------------------------------------------------------
//
// Only the id travels in a frame, once, and the pixels come over HTTP - cacheable, parallel and out
// of the way of the frame stream. The server resolves `namespace:path` on its classpath, which is
// where the framework's own icon lives.

function defineAsset(index, id) {
  assets[index] = id;
  if (id in images) return;
  let url;
  try {
    url = '/asset/' + encodeURIComponent(id);
  } catch (error) {
    // An id with an unpaired UTF-16 surrogate cannot be percent-encoded and no server could answer
    // it. That is a confirmed miss like a 404: cache the missing texture rather than throw out of
    // the frame message and leave every later draw undefined.
    images[id] = missingTexture();
    send('log client could not encode asset id, drawing the missing texture');
    return;
  }
  images[id] = null;
  const image = new Image();
  image.onload = () => {
    images[id] = image;
    wake();
  };
  // A confirmed miss is data, not a reason to throw or to paint a colour: cache the checkerboard
  // and keep drawing this texture through the recorded geometry and UVs.
  image.onerror = () => {
    images[id] = missingTexture();
    send('log client could not load asset, drawing the missing texture: ' + id);
    wake();
  };
  image.src = url;
}

/**
 * The texture used for a confirmed missing resource.
 *
 * It is a real checkerboard canvas rather than a flat colour, so the failure is visibly a texture
 * and the tint path still modulates it. One instance is shared; UV scaling uses each draw's own
 * nominal size, so a shared image is correct even when the same id has different nominal sizes.
 */
function missingTexture() {
  if (checkerboard !== null) return checkerboard;
  const tile = 8;
  checkerboard = document.createElement('canvas');
  checkerboard.width = tile * 2;
  checkerboard.height = tile * 2;
  const context = checkerboard.getContext('2d');
  context.fillStyle = '#d000d0';
  context.fillRect(0, 0, tile * 2, tile * 2);
  context.fillStyle = '#181818';
  context.fillRect(0, 0, tile, tile);
  context.fillRect(tile, tile, tile, tile);
  return checkerboard;
}

/** The table the server sends before a keyframe: `[0x41, count]` then index, length, characters. */
function applyAssets(message) {
  const view = new DataView(message.buffer, message.byteOffset, message.byteLength);
  const count = view.getUint32(1, true);
  let at = 5;
  for (let i = 0; i < count; i++) {
    const index = view.getUint32(at, true);
    const length = view.getUint32(at + 4, true);
    at += 8;
    let id = '';
    for (let c = 0; c < length; c++) {
      id += String.fromCharCode(view.getUint32(at, true));
      at += 4;
    }
    defineAsset(index, id);
  }
}

function imageOf(index) {
  const id = assets[index];
  return id === undefined ? null : images[id] || null;
}

/** Whether the current transform is affine, which is what `drawImage` can be placed with. */
function isAffine(m) {
  return m[6] === 0 && m[7] === 0 && m[8] === 1;
}

canvas.addEventListener('pointermove', (event) => {
  const point = position(event);
  if (point === null) return;
  send('move ' + point[0] + ' ' + point[1]);
  wake();
});

canvas.addEventListener('pointerdown', (event) => {
  const point = position(event);
  if (point === null) return;
  send('move ' + point[0] + ' ' + point[1]);
  send('button ' + event.button + ' down ' + modifiers(event));
  heldButtons.add(event.button);
  capturedPointer = event.pointerId;
  try {
    canvas.setPointerCapture(event.pointerId);
  } catch (error) {
    // A capture that the browser refuses is not a reason to drop a press the server accepted; the
    // matching pointerup will still be delivered to the canvas' own listener in the normal case.
  }
  event.preventDefault();
  wake();
});

canvas.addEventListener('pointerup', (event) => endPointer(event, false, true));
canvas.addEventListener('pointercancel', (event) => endPointer(event, true, false));
canvas.addEventListener('lostpointercapture', (event) => endPointer(event, true, false));

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
