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
        const rect = corners(words, at + 1, transform);
        if (rect) {
          ctx.save();
          ctx.beginPath();
          path(ctx, rect);
          ctx.clip();
          clips++;
        }
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
  while (clips-- > 0) ctx.restore();
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
    if (!image) return;
    const u0 = floatOf(words[at + 10]), v0 = floatOf(words[at + 11]);
    const u1 = floatOf(words[at + 12]), v1 = floatOf(words[at + 13]);
    const width = floatOf(words[at + 14]), height = floatOf(words[at + 15]);
    if (!positive(width) || !positive(height)) return;
    // A quad with one colour on all four corners and UVs that are affine across it is drawn in one
    // piece. Two clipped triangles composite the shared diagonal twice and leave a seam there; a
    // single clip cannot. Four unrelated corner colours keep the framework's two-triangle model.
    if (lt === rt && rt === lb && lb === rb) {
      const map = quadMap([[u0, v0], [u1, v0], [u1, v1], [u0, v1]], points, image, width, height);
      if (map) {
        texturedShape(points, image, [lt, rt, rb, lb], map);
        return;
      }
    }
    // The record's corners are LT, RT, RB, LB; the word order is lt, rt, lb, rb. Draw the two
    // triangles the game draws, each with its own UVs and per-vertex tint.
    texturedTriangle(
      [points[0], points[1], points[3]],
      [[u0, v0], [u1, v0], [u0, v1]],
      image, width, height, [lt, rt, lb],
    );
    texturedTriangle(
      [points[1], points[2], points[3]],
      [[u1, v0], [u1, v1], [u0, v1]],
      image, width, height, [rt, rb, lb],
    );
    return;
  }
  if (lt === rt && lt === lb && lt === rb) {
    paintBox(points, css(lt));
    return;
  }
  // Axis-aligned colour ramps are what the pickers use, and a gradient is exact for those.
  if (lt === lb && rt === rb) {
    const gradient = ctx.createLinearGradient(points[0][0], points[0][1], points[1][0], points[1][1]);
    gradient.addColorStop(0, css(lt));
    gradient.addColorStop(1, css(rt));
    paintBox(points, gradient);
    return;
  }
  if (lt === rt && lb === rb) {
    const gradient = ctx.createLinearGradient(points[0][0], points[0][1], points[3][0], points[3][1]);
    gradient.addColorStop(0, css(lt));
    gradient.addColorStop(1, css(lb));
    paintBox(points, gradient);
    return;
  }
  // Otherwise the two triangles the game's backends draw, for the same reason: four unrelated
  // corners have no single affine field, and this is the model the framework measured against. Each
  // triangle is shaded per vertex, so the palette's square ramps along its diagonal instead of
  // flattening into two blocks.
  shadeTriangle([points[0], points[1], points[3]], [lt, rt, lb]);
  shadeTriangle([points[1], points[2], points[3]], [rt, rb, lb]);
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
  if (textured) {
    const image = imageOf(words[at + 3]);
    // Pending, undefined or not-yet-ready: no visible draw. A failed load is the checkerboard.
    if (!image) return;
    const width = floatOf(words[coloursAt + count]);
    const height = floatOf(words[coloursAt + count + 1]);
    if (!positive(width) || !positive(height)) return;
    for (let i = 0; i + 2 < count; i += 3) {
      const points = [];
      const uvs = [];
      for (let step = 0; step < 3; step++) {
        const base = verticesAt + (i + step) * stride;
        points.push(project(transform, floatOf(words[base]), floatOf(words[base + 1])));
        uvs.push([floatOf(words[base + 2]), floatOf(words[base + 3])]);
      }
      texturedTriangle(
        points, uvs, image, width, height,
        [words[coloursAt + i], words[coloursAt + i + 1], words[coloursAt + i + 2]],
      );
    }
    return;
  }
  for (let i = 0; i + 2 < count; i += 3) {
    const points = [0, 1, 2].map((step) => {
      const base = verticesAt + (i + step) * stride;
      return project(transform, floatOf(words[base]), floatOf(words[base + 1]));
    });
    const colours = [words[coloursAt + i], words[coloursAt + i + 1], words[coloursAt + i + 2]];
    if (colours[0] === colours[1] && colours[1] === colours[2]) fill(points, css(colours[0]));
    else shadeTriangle(points, colours);
  }
}

/** Whether [value] is a per-draw dimension: finite and positive, not merely greater than zero. */
function positive(value) {
  return Number.isFinite(value) && value > 0;
}

/** Whether a projected point is something Canvas can place. */
function finitePoint(point) {
  return Number.isFinite(point[0]) && Number.isFinite(point[1]);
}

/**
 * Draw one triangle of a texture, modulated by its per-vertex colours.
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
 * Draw one textured polygon, modulated by [colours], onto the screen.
 *
 * The modulation is done on a scratch surface and composited onto the screen exactly once with
 * `source-over`, because the blend modes it needs - `multiply` for the tint, `destination-in` for
 * the alphas - apply to the whole destination. Running them on the screen would multiply or clear
 * whatever the page had already painted underneath the polygon. The scratch claims only the
 * polygon's visible bounds, is reused by every draw, and never grows past the canvas.
 *
 * The result is the framework's model: RGB is the texture's colour times the interpolated vertex
 * colour, and alpha is the texture's alpha times the interpolated vertex alpha, each applied once.
 * `multiply` is only exact when its destination is opaque - over a translucent backdrop Canvas
 * mixes the backdrop into the result - so the tint starts from an opaque copy of the texture's
 * colour and the real alpha is put back afterwards, instead of multiplying the tint into the
 * texture's own alpha and squaring it.
 *
 * A polygon with no tint is drawn straight onto the screen with one `source-over`; a white tint is
 * composited from the texture's own alpha without the multiply. Both keep the ordinary cases free
 * of the extra passes. True projective interpolation and the shared diagonal of a quad whose four
 * corners have unrelated colours remain approximated, as the docs record.
 */
function texturedShape(points, image, colours, map) {
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;
  if (!positive(width) || !positive(height)) return;
  if (!points.every(finitePoint)) return;
  const uniform = colours.every((colour) => colour === colours[0]);
  const tint = colours[0];
  // A white tint leaves the texture's RGB alone, so the texture itself is the source and neither
  // the tint pass nor the alpha restore is needed.
  const whiteRgb = colours.every(isWhiteRgb);
  if (uniform && isWhite(tint)) {
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
      if (uniform) {
        scratch.setTransform(1, 0, 0, 1, dx, dy);
        scratch.fillStyle = css(tint | 0xff);
        scratch.fillRect(left, top, boxWidth, boxHeight);
      } else {
        const field = opaqueField(colours);
        scratch.setTransform(...shifted(unitMap(points), dx, dy));
        scratch.imageSmoothingEnabled = true;
        scratch.drawImage(field, 0, 0, FIELD_SIZE, FIELD_SIZE, 0, 0, 1, 1);
      }
      // Put the texture's alpha back, which the opaque destination above replaced.
      scratch.globalCompositeOperation = 'destination-in';
      drawTexture(scratch, image, map, points, dx, dy);
    }
    if (colours.some((colour) => (colour & 0xff) !== 0xff)) {
      // The vertex tint's alpha multiplies the texture's alpha, once.
      scratch.globalCompositeOperation = 'destination-in';
      if (uniform) {
        scratch.setTransform(1, 0, 0, 1, dx, dy);
        scratch.fillStyle = `rgba(255,255,255,${((tint & 0xff) / 255).toFixed(3)})`;
        scratch.fillRect(left, top, boxWidth, boxHeight);
      } else {
        const field = triangleField(colours);
        scratch.setTransform(...shifted(unitMap(points), dx, dy));
        scratch.imageSmoothingEnabled = true;
        scratch.drawImage(field, 0, 0, FIELD_SIZE, FIELD_SIZE, 0, 0, 1, 1);
      }
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

/** [map] moved by the scratch surface's origin. */
function shifted(map, dx, dy) {
  return [map[0], map[1], map[2], map[3], map[4] + dx, map[5] + dy];
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
  try {
    context.drawImage(image, 0, 0);
    const pixels = context.getImageData(0, 0, width, height);
    const data = pixels.data;
    for (let i = 3; i < data.length; i += 4) data[i] = 255;
    context.putImageData(pixels, 0, 0);
    opaque = surface;
  } catch (error) {
    opaque = null;
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
    let pattern = null;
    try {
      pattern = context.createPattern(image, 'repeat');
    } catch (error) {
      pattern = null;
    }
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

/** The unit square to projected-triangle matrix, used to place the cached colour field. */
function unitMap(points) {
  return [
    points[1][0] - points[0][0], points[1][1] - points[0][1],
    points[2][0] - points[0][0], points[2][1] - points[0][1],
    points[0][0], points[0][1],
  ];
}

function isWhite(packed) {
  return packed === -1 || packed === 0xffffffff;
}

/** Whether a packed colour has white RGB, whatever its alpha. */
function isWhiteRgb(packed) {
  return ((packed & 0xffffff00) >>> 0) === 0xffffff00;
}

/**
 * Fill a triangle with a colour per vertex.
 *
 * Canvas 2D has no interpolating triangle, but an affine colour field *is* a bilinear one when the
 * fourth corner is the one the affine plane implies, so: put the three colours and that fourth into a
 * two-by-two image, map the unit square onto the parallelogram the triangle spans, and clip to the
 * triangle. Bilinear filtering of the tiny image does the interpolation, and one clip plus one draw
 * beats subdividing into a few hundred of them.
 */
function shadeTriangle(points, colours) {
  const field = triangleField(colours);
  ctx.save();
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.beginPath();
  path(ctx, points);
  ctx.clip();
  // (0,0) -> first vertex, (1,0) -> second, (0,1) -> third: exactly the affine map of the field.
  ctx.setTransform(
    points[1][0] - points[0][0], points[1][1] - points[0][1],
    points[2][0] - points[0][0], points[2][1] - points[0][1],
    points[0][0], points[0][1],
  );
  ctx.imageSmoothingEnabled = true;
  // The whole field, not a corner of it: sampling a two-by-two source rectangle out of a sixteen
  // square drew the colour of one corner and made every palette flat.
  ctx.drawImage(field, 0, 0, FIELD_SIZE, FIELD_SIZE, 0, 0, 1, 1);
  ctx.restore();
}

const fields = new Map();

/** How finely the affine field is sampled. Two-by-two is exact in theory and banded in practice. */
const FIELD_SIZE = 16;

/** The colour field of a triangle as a small image, cached by the colours it is built from. */
function triangleField(colours) {
  return fieldOf(colours, false);
}

/**
 * The same field with alpha forced opaque, for use as a `multiply` source.
 *
 * The multiply has to see the tint's colour only: a translucent source would mix the destination
 * into the product, and the vertex alpha is applied by its own `destination-in` pass anyway.
 */
function opaqueField(colours) {
  return fieldOf(colours, true);
}

function fieldOf(colours, opaque) {
  const key = (opaque ? 'o:' : 'a:') + colours.join(',');
  const cached = fields.get(key);
  if (cached) return cached;

  const canvas = document.createElement('canvas');
  canvas.width = FIELD_SIZE;
  canvas.height = FIELD_SIZE;
  const context = canvas.getContext('2d');
  // The affine plane through the three colours decides the fourth corner of the parallelogram, and
  // every texel is that plane evaluated where the texel will be sampled from.
  const c00 = channels(colours[0]);
  const c10 = channels(colours[1]);
  const c01 = channels(colours[2]);
  const c11 = c10.map((value, index) => value + c01[index] - c00[index]);
  const image = context.createImageData(FIELD_SIZE, FIELD_SIZE);
  for (let y = 0; y < FIELD_SIZE; y++) {
    const v = (y + 0.5) / FIELD_SIZE;
    for (let x = 0; x < FIELD_SIZE; x++) {
      const u = (x + 0.5) / FIELD_SIZE;
      const at = (y * FIELD_SIZE + x) * 4;
      for (let channel = 0; channel < 4; channel++) {
        if (opaque && channel === 3) {
          image.data[at + channel] = 255;
          continue;
        }
        const value =
          c00[channel] * (1 - u) * (1 - v) +
          c10[channel] * u * (1 - v) +
          c01[channel] * (1 - u) * v +
          c11[channel] * u * v;
        image.data[at + channel] = clamp(value);
      }
    }
  }
  context.putImageData(image, 0, 0);
  fields.set(key, canvas);
  return canvas;
}

function channels(packed) {
  return [(packed >>> 24) & 0xff, (packed >>> 16) & 0xff, (packed >>> 8) & 0xff, packed & 0xff];
}

function pack(channels) {
  return (channels[0] << 24) | (channels[1] << 16) | (channels[2] << 8) | channels[3];
}

function clamp(value) {
  return Math.max(0, Math.min(255, Math.round(value)));
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
  asking = true;
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
