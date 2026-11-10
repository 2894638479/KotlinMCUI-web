'use strict';

// Executes the shipped browser painter, not a copy: client.js is evaluated in a VM with a small
// Canvas model and Image stubs, and the assertions below read the real draw calls, the real asset
// cache and the resulting pixels. No browser, network or GUI is involved.
//
// The model is deliberately small: straight-alpha RGBA pixels, nearest-neighbour sampling, binary
// clip coverage and the three composite operations the painter uses. It is enough to check the
// compositing arithmetic, but it is not a browser - smoothing, sub-pixel coverage and the browser's
// own colour management are not modelled.

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');
const { pathToFileURL } = require('node:url');
const vm = require('node:vm');

const SOURCE = fs.readFileSync(
  path.join(__dirname, '..', '..', 'main', 'resources', 'web', 'client.js'),
  'utf8',
);

function bits(value) {
  const buffer = new ArrayBuffer(4);
  new DataView(buffer).setFloat32(0, value, true);
  return new DataView(buffer).getInt32(0, true);
}

// ---- the Canvas model -------------------------------------------------------------------------

function parseColour(value) {
  if (typeof value !== 'string') return null;
  const hex = /^#([0-9a-f]{6})$/i.exec(value);
  if (hex) {
    return [
      parseInt(hex[1].slice(0, 2), 16),
      parseInt(hex[1].slice(2, 4), 16),
      parseInt(hex[1].slice(4, 6), 16),
      255,
    ];
  }
  const channels = /^rgba?\(([^)]+)\)$/.exec(value);
  if (!channels) return null;
  const parts = channels[1].split(',').map((part) => Number(part));
  if (parts.length < 3 || parts.some((part) => !Number.isFinite(part))) return null;
  return [parts[0], parts[1], parts[2], parts.length > 3 ? Math.round(parts[3] * 255) : 255];
}

function bounds(points) {
  let left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
  for (const [x, y] of points) {
    if (x < left) left = x;
    if (y < top) top = y;
    if (x > right) right = x;
    if (y > bottom) bottom = y;
  }
  return [left, top, right, bottom];
}

/** Even-odd containment, with a polygon in device coordinates. */
function inside(polygon, x, y) {
  let result = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [xi, yi] = polygon[i];
    const [xj, yj] = polygon[j];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) result = !result;
  }
  return result;
}

function pixelAt(bitmap, x, y) {
  const at = (y * bitmap.width + x) * 4;
  if (at + 3 >= bitmap.pixels.length) return [0, 0, 0, 0];
  return [bitmap.pixels[at], bitmap.pixels[at + 1], bitmap.pixels[at + 2], bitmap.pixels[at + 3]];
}

/** Nearest-neighbour sample of a repeated image, which is what a repeat pattern shows. */
function repeatSample(image, x, y) {
  const width = image.width, height = image.height;
  if (!(width > 0) || !(height > 0)) return null;
  let column = Math.floor(x) % width;
  if (column < 0) column += width;
  let row = Math.floor(y) % height;
  if (row < 0) row += height;
  return pixelAt(image, column, row);
}

class Bitmap {
  constructor() {
    this._width = 0;
    this._height = 0;
    this.pixels = new Uint8ClampedArray(0);
  }

  get width() { return this._width; }
  set width(value) {
    this._width = Math.max(0, Math.trunc(value) || 0);
    this.resize();
  }

  get height() { return this._height; }
  set height(value) {
    this._height = Math.max(0, Math.trunc(value) || 0);
    this.resize();
  }

  resize() {
    this.pixels = new Uint8ClampedArray(this._width * this._height * 4);
  }

  upload(width, height, colour) {
    this._width = width;
    this._height = height;
    this.pixels = new Uint8ClampedArray(width * height * 4);
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) this.pixels.set(colour(x, y), (y * width + x) * 4);
    }
  }

  pixel(x, y) {
    return pixelAt(this, x, y);
  }
}

class FakeContext {
  constructor(surface, state) {
    this.canvas = surface;
    this.surface = surface;
    this.state = state;
    this.transform = [1, 0, 0, 1, 0, 0];
    this.stack = [];
    this.clips = [];
    this.path = [];
    this.globalCompositeOperation = 'source-over';
    this.imageSmoothingEnabled = true;
    this.fillStyle = '#000000';
    this.strokeStyle = '#000000';
    this.font = '10px monospace';
    this.textBaseline = 'alphabetic';
    this.lastImageData = null;
  }

  save() {
    this.stack.push({
      transform: this.transform.slice(),
      composite: this.globalCompositeOperation,
      smoothing: this.imageSmoothingEnabled,
      fillStyle: this.fillStyle,
      strokeStyle: this.strokeStyle,
      font: this.font,
      textBaseline: this.textBaseline,
      clips: this.clips.slice(),
    });
  }

  restore() {
    const saved = this.stack.pop();
    if (!saved) return;
    this.transform = saved.transform;
    this.globalCompositeOperation = saved.composite;
    this.imageSmoothingEnabled = saved.smoothing;
    this.fillStyle = saved.fillStyle;
    this.strokeStyle = saved.strokeStyle;
    this.font = saved.font;
    this.textBaseline = saved.textBaseline;
    this.clips = saved.clips;
  }

  setTransform(a, b, c, d, e, f) {
    this.transform = [a, b, c, d, e, f];
    this.state.transforms.push([a, b, c, d, e, f]);
  }

  apply(x, y) {
    const [a, b, c, d, e, f] = this.transform;
    return [a * x + c * y + e, b * x + d * y + f];
  }

  invert(x, y) {
    const [a, b, c, d, e, f] = this.transform;
    const determinant = a * d - b * c;
    if (!determinant) return null;
    const px = x - e, py = y - f;
    return [(d * px - c * py) / determinant, (-b * px + a * py) / determinant];
  }

  beginPath() { this.path = []; }
  moveTo(x, y) { this.path.push(this.apply(x, y)); }
  lineTo(x, y) { this.path.push(this.apply(x, y)); }
  closePath() {}
  clip() {
    this.clips.push(this.path.slice());
    this.state.ops.push({ op: 'clip', points: this.path.slice() });
  }

  covered(x, y, usePath) {
    if (usePath && this.path.length && !inside(this.path, x, y)) return false;
    for (const clip of this.clips) if (!inside(clip, x, y)) return false;
    return true;
  }

  each(box, usePath, action) {
    const width = this.surface.width, height = this.surface.height;
    const left = Math.max(0, Math.floor(box[0]));
    const top = Math.max(0, Math.floor(box[1]));
    const right = Math.min(width, Math.ceil(box[2]));
    const bottom = Math.min(height, Math.ceil(box[3]));
    for (let y = top; y < bottom; y++) {
      for (let x = left; x < right; x++) {
        if (!this.covered(x + 0.5, y + 0.5, usePath)) continue;
        action(x, y);
      }
    }
  }

  sampleStyle(x, y) {
    const style = this.fillStyle;
    if (style && style.pattern) {
      const point = this.invert(x, y);
      return point === null ? null : repeatSample(style.image, point[0], point[1]);
    }
    if (style && style.gradient) {
      // The painter only creates gradients with equal stop alpha on an axis-aligned quad, so a
      // straight interpolation of the stops is exactly the premultiplied field there.
      const dx = style.x1 - style.x0, dy = style.y1 - style.y0;
      const length = dx * dx + dy * dy;
      let t = length > 0 ? ((x - style.x0) * dx + (y - style.y0) * dy) / length : 0;
      t = Math.max(0, Math.min(1, t));
      const stops = style.stops;
      if (!stops.length) return null;
      if (t <= stops[0][0]) return parseColour(stops[0][1]);
      if (t >= stops[stops.length - 1][0]) return parseColour(stops[stops.length - 1][1]);
      for (let i = 1; i < stops.length; i++) {
        if (t > stops[i][0]) continue;
        const from = stops[i - 1];
        const to = stops[i];
        const a = parseColour(from[1]);
        const b = parseColour(to[1]);
        if (!a || !b) return null;
        const span = to[0] - from[0] || 1;
        const k = (t - from[0]) / span;
        return [
          a[0] + (b[0] - a[0]) * k,
          a[1] + (b[1] - a[1]) * k,
          a[2] + (b[2] - a[2]) * k,
          a[3] + (b[3] - a[3]) * k,
        ];
      }
    }
    return parseColour(style);
  }

  blend(x, y, colour) {
    const pixels = this.surface.pixels;
    const at = (y * this.surface.width + x) * 4;
    const d = [pixels[at] / 255, pixels[at + 1] / 255, pixels[at + 2] / 255, pixels[at + 3] / 255];
    const s = [colour[0] / 255, colour[1] / 255, colour[2] / 255, colour[3] / 255];
    const operation = this.globalCompositeOperation;
    let out;
    if (operation === 'destination-in') {
      out = [d[0], d[1], d[2], d[3] * s[3]];
    } else if (operation === 'multiply') {
      const alpha = s[3] + d[3] - s[3] * d[3];
      const channel = (index) =>
        (s[3] * (1 - d[3]) * s[index] + s[3] * d[3] * s[index] * d[index] + (1 - s[3]) * d[3] * d[index]) / alpha;
      out = alpha <= 0 ? [0, 0, 0, 0] : [channel(0), channel(1), channel(2), alpha];
    } else {
      const alpha = s[3] + d[3] * (1 - s[3]);
      const channel = (index) => (s[index] * s[3] + d[index] * d[3] * (1 - s[3])) / alpha;
      out = alpha <= 0 ? [0, 0, 0, 0] : [channel(0), channel(1), channel(2), alpha];
    }
    pixels[at] = Math.round(out[0] * 255);
    pixels[at + 1] = Math.round(out[1] * 255);
    pixels[at + 2] = Math.round(out[2] * 255);
    pixels[at + 3] = Math.round(out[3] * 255);
  }

  paint(box, usePath) {
    this.each(box, usePath, (x, y) => {
      const colour = this.sampleStyle(x + 0.5, y + 0.5);
      if (colour) this.blend(x, y, colour);
    });
  }

  fill() {
    this.state.ops.push({
      op: 'fill',
      style: this.fillStyle,
      transform: this.transform.slice(),
      composite: this.globalCompositeOperation,
    });
    if (!this.path.length) return;
    this.paint(bounds(this.path), true);
  }

  fillRect(x, y, w, h) {
    this.state.ops.push({
      op: 'fillRect',
      style: this.fillStyle,
      x, y, w, h,
      composite: this.globalCompositeOperation,
    });
    const corners = [
      this.apply(x, y), this.apply(x + w, y), this.apply(x + w, y + h), this.apply(x, y + h),
    ];
    this.paint(bounds(corners), false);
  }

  clearRect(x, y, w, h) {
    const corners = [
      this.apply(x, y), this.apply(x + w, y), this.apply(x + w, y + h), this.apply(x, y + h),
    ];
    this.each(bounds(corners), false, (px, py) => {
      const at = (py * this.surface.width + px) * 4;
      this.surface.pixels[at] = 0;
      this.surface.pixels[at + 1] = 0;
      this.surface.pixels[at + 2] = 0;
      this.surface.pixels[at + 3] = 0;
    });
  }

  stroke() { this.state.ops.push({ op: 'stroke' }); }

  fillText(text, x, y) {
    this.state.ops.push({
      op: 'fillText',
      text,
      x,
      y,
      style: this.fillStyle,
      transform: this.transform.slice(),
    });
    // The model has no font rasteriser, so a glyph is a deterministic filled box in the same place
    // the painter places it. That is enough to check that clip coverage reaches text (and that a
    // clipped glyph leaves nothing outside the clip); it is not a claim about glyph shape.
    const size = /([0-9.]+)px/.exec(this.font);
    const pixels = size ? Number(size[1]) : 10;
    const width = Math.max(1, text.length * pixels * 0.6);
    const height = Math.max(1, pixels * 0.8);
    this.paint([x, y - height, x + width, y], false);
  }

  drawImage(source, ...args) {
    // CanvasImageSource is a closed set; passing anything else is a TypeError in a browser, and a
    // model that silently skips it would hide the same bug in the painter.
    if (!(source instanceof Bitmap)) throw new TypeError('drawImage source is not a CanvasImageSource');
    this.state.ops.push({
      op: 'drawImage',
      image: source,
      args,
      transform: this.transform.slice(),
      composite: this.globalCompositeOperation,
    });
    let sx = 0, sy = 0;
    let sw = source.width, sh = source.height;
    let dx = 0, dy = 0, dw = sw, dh = sh;
    if (args.length === 2) {
      [dx, dy] = args;
    } else if (args.length === 4) {
      [dx, dy, dw, dh] = args;
    } else if (args.length === 8) {
      [sx, sy, sw, sh, dx, dy, dw, dh] = args;
    } else {
      return;
    }
    if (!(dw > 0) || !(dh > 0) || !(sw > 0) || !(sh > 0)) return;
    const corners = [
      this.apply(dx, dy), this.apply(dx + dw, dy),
      this.apply(dx + dw, dy + dh), this.apply(dx, dy + dh),
    ];
    this.each(bounds(corners), false, (px, py) => {
      const point = this.invert(px + 0.5, py + 0.5);
      if (point === null) return;
      const ux = (point[0] - dx) / dw;
      const uy = (point[1] - dy) / dh;
      if (ux < 0 || ux > 1 || uy < 0 || uy > 1) return;
      const su = Math.min(sw - 1, Math.max(0, Math.floor(sx + ux * sw)));
      const sv = Math.min(sh - 1, Math.max(0, Math.floor(sy + uy * sh)));
      this.blend(px, py, pixelAt(source, su, sv));
    });
  }

  createLinearGradient(x0, y0, x1, y1) {
    const gradient = { gradient: true, x0, y0, x1, y1, stops: [] };
    gradient.addColorStop = (offset, colour) => gradient.stops.push([offset, colour]);
    this.state.ops.push({ op: 'createLinearGradient', x0, y0, x1, y1 });
    return gradient;
  }

  createPattern(image, repetition) {
    if (!(image instanceof Bitmap)) throw new TypeError('createPattern source is not a CanvasImageSource');
    const pattern = { pattern: true, image, repetition };
    this.state.ops.push({ op: 'createPattern', image, repetition });
    return pattern;
  }

  createImageData(width, height) {
    return { width, height, data: new Uint8ClampedArray(width * height * 4) };
  }

  getImageData(x, y, width, height) {
    const data = new Uint8ClampedArray(width * height * 4);
    for (let row = 0; row < height; row++) {
      for (let column = 0; column < width; column++) {
        const at = ((y + row) * this.surface.width + (x + column)) * 4;
        const to = (row * width + column) * 4;
        for (let channel = 0; channel < 4; channel++) data[to + channel] = this.surface.pixels[at + channel] || 0;
      }
    }
    return { width, height, data };
  }

  putImageData(image, x, y, dirtyX = 0, dirtyY = 0, dirtyWidth = image.width, dirtyHeight = image.height) {
    this.lastImageData = image;
    // The four-argument form is the whole image; the seven-argument form writes only the dirty
    // rectangle, which is how the painter reuses one bounded ImageData for differently sized draws.
    const left = Math.max(0, dirtyX);
    const top = Math.max(0, dirtyY);
    const right = Math.min(image.width, dirtyX + dirtyWidth);
    const bottom = Math.min(image.height, dirtyY + dirtyHeight);
    for (let row = top; row < bottom; row++) {
      for (let column = left; column < right; column++) {
        const to = (y + row - dirtyY) * this.surface.width + (x + column - dirtyX);
        if (to < 0 || to >= this.surface.pixels.length / 4) continue;
        const at = to * 4;
        const from = (row * image.width + column) * 4;
        for (let channel = 0; channel < 4; channel++) this.surface.pixels[at + channel] = image.data[from + channel];
      }
    }
  }

  measureText(text) { return { width: text.length * 10 }; }
}

class FakeCanvas extends Bitmap {
  constructor(state) {
    super();
    this.state = state;
    this.style = {};
    this.context = new FakeContext(this, state);
    this.listeners = {};
    // Where CSS puts and sizes the element. Null means "derive it from the inline style", which is
    // what a real browser does when only `canvas.style.width/height` were set.
    this.bounds = null;
    this.blobs = [];
    this.pointerCaptures = new Set();
  }
  getContext() { return this.context; }
  getBoundingClientRect() {
    if (this.bounds !== null) return this.bounds;
    const width = parseFloat(this.style.width);
    const height = parseFloat(this.style.height);
    return {
      left: 0,
      top: 0,
      width: Number.isFinite(width) ? width : this.width,
      height: Number.isFinite(height) ? height : this.height,
    };
  }
  addEventListener(type, listener) {
    (this.listeners[type] = this.listeners[type] || []).push(listener);
  }
  setPointerCapture(pointerId) { this.pointerCaptures.add(pointerId); }
  releasePointerCapture(pointerId) { this.pointerCaptures.delete(pointerId); }
  hasPointerCapture(pointerId) { return this.pointerCaptures.has(pointerId); }
  /** Records the backing size at capture time; the client's `capture` path calls this. */
  toBlob() { this.blobs.push([this.width, this.height]); }
}

class FakeImage extends Bitmap {
  constructor(state) {
    super();
    this.state = state;
    this.naturalWidth = 0;
    this.naturalHeight = 0;
    this.onload = null;
    this.onerror = null;
    this._src = '';
  }

  get width() { return this._width; }
  set width(value) {
    this._width = Math.max(0, Math.trunc(value) || 0);
    this.naturalWidth = this._width;
    this.resize();
  }

  get height() { return this._height; }
  set height(value) {
    this._height = Math.max(0, Math.trunc(value) || 0);
    this.naturalHeight = this._height;
    this.resize();
  }

  set src(value) {
    this._src = value;
    this.state.images.push(this);
  }

  get src() { return this._src; }

  resolve(width, height) {
    this.width = width;
    this.height = height;
    this.onload();
  }

  upload(width, height, colour) {
    super.upload(width, height, colour);
    this.naturalWidth = width;
    this.naturalHeight = height;
    this.onload();
  }

  fail() { this.onerror(); }
}

class FakeWebSocket {
  static OPEN = 1;
  constructor() {
    this.readyState = FakeWebSocket.OPEN;
    this.sent = [];
    this.binaryType = '';
  }
  send(value) { this.sent.push(value); }
  close() { this.readyState = 3; }
}

function createClient(options = {}) {
  const state = { ops: [], transforms: [], images: [], sent: [] };
  const screen = new FakeCanvas(state);
  const status = { textContent: '' };
  const document = {
    body: {},
    documentElement: {},
    getElementById: (id) => (id === 'screen' ? screen : status),
    // Offscreen canvases (the scratch surface, the tint fields, the opaque copies, the checkerboard)
    // keep their own draw log and pixels, so the assertions see the screen separately from the work.
    createElement: () => new FakeCanvas({ ops: [], transforms: [], images: [] }),
  };
  const window = {
    devicePixelRatio: 1,
    innerWidth: 640,
    innerHeight: 480,
    listeners: {},
    addEventListener(type, listener) {
      (this.listeners[type] = this.listeners[type] || []).push(listener);
    },
  };
  const sandbox = {
    console,
    document,
    window,
    WebSocket: FakeWebSocket,
    Image: class extends FakeImage {
      constructor() { super(state); }
    },
    location: { protocol: 'http:', host: 'localhost' },
    navigator: { language: 'en_us' },
    performance: { now: () => 0 },
    requestAnimationFrame: () => 0,
    getComputedStyle: () => ({ backgroundColor: options.backdrop || 'rgba(0,0,0,0)' }),
    setTimeout: () => 0,
    encodeURIComponent,
    Uint8Array,
    Uint8ClampedArray,
    Int32Array,
    ArrayBuffer,
    DataView,
  };
  vm.createContext(sandbox);
  const api = vm.runInContext(
    `${SOURCE}
;globalThis.__primitiveTest = {
  defineAsset, imageOf, paint, strideAt, setFrame(value) { frame = value; },
  position, resizeCanvas, window, canvas, socket: () => socket,
  setPainted(value) { painted = value; },
  images, assets,
  reset(outer) {
    assets.length = 0;
    for (const key of Object.keys(images)) delete images[key];
    checkerboard = null;
    scratchSurface.canvas = null;
    frame = null;
    outer.ops.length = 0;
    outer.transforms.length = 0;
    outer.images.length = 0;
    outer.sent.length = 0;
  },
  checkerboard() { return checkerboard; },
  scratch() { return scratchSurface.canvas; },
};
globalThis.__primitiveTest;`,
    sandbox,
    { filename: 'client.js' },
  );
  return { api, state, screen };
}

function quadFrame({
  asset = 2, uv = [0, 0, 8, 4], nominal = [8, 4], colours = [-1, -1, -1, -1],
  rect = [10, 20, 30, 40],
} = {}) {
  return new Int32Array([
    1,
    bits(rect[0]), bits(rect[1]), bits(rect[2]), bits(rect[3]),
    colours[0], colours[1], colours[2], colours[3],
    asset,
    bits(uv[0]), bits(uv[1]), bits(uv[2]), bits(uv[3]),
    bits(nominal[0]), bits(nominal[1]),
  ]);
}

/** A colour-only quad record: ten words, no UVs and no nominal size. */
function colourQuadFrame({ rect = [10, 20, 30, 40], colours = [-1, -1, -1, -1] } = {}) {
  return new Int32Array([
    1,
    bits(rect[0]), bits(rect[1]), bits(rect[2]), bits(rect[3]),
    colours[0], colours[1], colours[2], colours[3],
    -1,
  ]);
}

function clipPushFrame(rect) {
  return new Int32Array([3, bits(rect[0]), bits(rect[1]), bits(rect[2]), bits(rect[3])]);
}

const CLIP_POP_FRAME = new Int32Array([4]);

function transformPushFrame(m) {
  return new Int32Array([5, ...m.map((value) => bits(value))]);
}

const TRANSFORM_POP_FRAME = new Int32Array([6]);

/** A text record: one glyph, its position, style, colour and effect box. */
function textFrame({ code = 0x2588, x = 20, y = 20, size = 20, colour = -1, style = 0 } = {}) {
  return new Int32Array([
    7, code, bits(x), bits(y), style, colour,
    bits(x), bits(x + size * 0.6), bits(size),
  ]);
}

/** A widget background record (`BUTTON`, `SLOT`, ...): five geometry words and no payload. */
function rectOnlyFrame(op, rect = [0, 0, 200, 100], flags = 0, colour = -1) {
  const words = [op, bits(rect[0]), bits(rect[1]), bits(rect[2]), bits(rect[3])];
  if (op === 8) words.push(flags, colour);
  return new Int32Array(words);
}

/** An item record: geometry, asset, count, damage and flags. */
function itemFrame({ rect = [0, 0, 200, 100], asset = 0, count = 1, damage = 0, flags = 0 } = {}) {
  return new Int32Array([
    13, bits(rect[0]), bits(rect[1]), bits(rect[2]), bits(rect[3]), asset, count, damage, flags,
  ]);
}

/** An untextured triangle record: three xy vertices, then three colours. */
function colourTriangleFrame({
  points = [[0, 0], [80, 0], [0, 40]], colours = [-1, -1, -1],
} = {}) {
  const words = [2, 3, 0, -1];
  for (const point of points) words.push(bits(point[0]), bits(point[1]));
  words.push(...colours);
  return new Int32Array(words);
}

/** A textured triangle record with explicit vertices and UVs. */
function texturedTriangleFrame({
  points = [[0, 0], [80, 0], [0, 40]], uvs = [[0, 0], [8, 0], [0, 4]],
  colours = [-1, -1, -1], nominal = [8, 4], asset = 2,
} = {}) {
  const words = [2, 3, 1, asset];
  for (let i = 0; i < 3; i++) {
    words.push(bits(points[i][0]), bits(points[i][1]), bits(uvs[i][0]), bits(uvs[i][1]));
  }
  words.push(...colours, bits(nominal[0]), bits(nominal[1]));
  return new Int32Array(words);
}

/** A triangle-list record: any number of vertices, their colours, and optional UVs. */
function triangleListFrame({
  points, colours, textured = false, uvs = null, nominal = [8, 4], asset = 2,
} = {}) {
  const words = [2, points.length, textured ? 1 : 0, textured ? asset : -1];
  for (let i = 0; i < points.length; i++) {
    words.push(bits(points[i][0]), bits(points[i][1]));
    if (textured) words.push(bits(uvs[i][0]), bits(uvs[i][1]));
  }
  words.push(...colours);
  if (textured) words.push(bits(nominal[0]), bits(nominal[1]));
  return new Int32Array(words);
}

/** Concatenate wire records into one frame. */
function frameOf(...parts) {
  const length = parts.reduce((total, part) => total + part.length, 0);
  const out = new Int32Array(length);
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

// ---- the contract's own arithmetic, computed independently of the painter ---------------------
//
// These are the formulas the contract states, evaluated here over the wire colours. They are not a
// second renderer: no canvas, no coverage, no compositing order beyond the one each assertion spells
// out, so an assertion fails when the painter disagrees with the math rather than when two copies of
// the same bug disagree.

function premultipliedColour(packed) {
  const alpha = packed & 0xff;
  return [
    ((packed >>> 24) & 0xff) * alpha / 255,
    ((packed >>> 16) & 0xff) * alpha / 255,
    ((packed >>> 8) & 0xff) * alpha / 255,
    alpha,
  ];
}

/** The quad field at local (u,v), premultiplied RGBA in 0..255. */
function quadFieldAt(colours, u, v) {
  const corners = colours.map(premultipliedColour);
  const weights = [(1 - u) * (1 - v), u * (1 - v), (1 - u) * v, u * v];
  const out = [0, 0, 0, 0];
  for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) out[j] += corners[i][j] * weights[i];
  return out;
}

/** The triangle field at barycentric weights, premultiplied RGBA in 0..255. */
function triangleFieldAt(colours, weights) {
  const corners = colours.map(premultipliedColour);
  const out = [0, 0, 0, 0];
  for (let i = 0; i < 3; i++) for (let j = 0; j < 4; j++) out[j] += corners[i][j] * weights[i];
  return out;
}

/** A pixel's stored straight RGBA expressed as premultiplied RGBA in 0..255. */
function premultipliedPixel(pixel) {
  return [pixel[0] * pixel[3] / 255, pixel[1] * pixel[3] / 255, pixel[2] * pixel[3] / 255, pixel[3]];
}

function assertPremultiplied(actual, expected, message, tolerance = 2.2) {
  const value = premultipliedPixel(actual);
  for (let i = 0; i < 4; i++) {
    assert.ok(
      Math.abs(value[i] - expected[i]) <= tolerance,
      `${message}: premult channel ${i} is ${value[i].toFixed(2)}, expected ${expected[i].toFixed(2)} (${actual})`,
    );
  }
}

/** The affine/projective inverse of a row-major 3x3, as the test's own arithmetic. */
function inverseOf(m) {
  const [a, b, c, d, e, f, g, h, i] = m;
  const A = e * i - f * h, B = c * h - b * i, C = b * f - c * e;
  const D = f * g - d * i, E = a * i - c * g, F = c * d - a * f;
  const G = d * h - e * g, H = b * g - a * h, I = a * e - b * d;
  const determinant = a * A + b * D + c * G;
  return [A / determinant, B / determinant, C / determinant,
    D / determinant, E / determinant, F / determinant,
    G / determinant, H / determinant, I / determinant];
}

/** The original local coordinate of a device pixel centre under [transform]. */
function localCoordinate(transform, x, y) {
  const m = inverseOf(transform);
  const w = m[6] * (x + 0.5) + m[7] * (y + 0.5) + m[8];
  return [
    (m[0] * (x + 0.5) + m[1] * (y + 0.5) + m[2]) / w,
    (m[3] * (x + 0.5) + m[4] * (y + 0.5) + m[5]) / w,
  ];
}

/** A local point projected to device coordinates, the way the painter's `project` does it. */
function projectPoint(transform, x, y) {
  const w = transform[6] * x + transform[7] * y + transform[8];
  return [
    (transform[0] * x + transform[1] * y + transform[2]) / w,
    (transform[3] * x + transform[4] * y + transform[5]) / w,
  ];
}

/** Row-major 3x3 composition, the same order the painter uses: `a * b`, b applies first. */
function multiplyMatrix(a, b) {
  const out = new Array(9);
  for (let row = 0; row < 3; row++) {
    for (let column = 0; column < 3; column++) {
      out[row * 3 + column] =
        a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column];
    }
  }
  return out;
}

/** Rotation around (cx,cy), the same transform the framework's rotate factory builds. */
function rotationAround(cx, cy, radians) {
  const c = Math.cos(radians), s = Math.sin(radians);
  return [c, -s, cx - c * cx + s * cy, s, c, cy - s * cx - c * cy, 0, 0, 1];
}

/** The projected quad of a local rectangle, as an independent reference polygon. */
function projectedRect(transform, rect) {
  return [
    projectPoint(transform, rect[0], rect[1]),
    projectPoint(transform, rect[2], rect[1]),
    projectPoint(transform, rect[2], rect[3]),
    projectPoint(transform, rect[0], rect[3]),
  ];
}

function distanceToSegment(x, y, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1];
  const length = dx * dx + dy * dy;
  let t = length > 0 ? ((x - a[0]) * dx + (y - a[1]) * dy) / length : 0;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(x - (a[0] + t * dx), y - (a[1] + t * dy));
}

function distanceToPolygon(polygon, x, y) {
  let distance = Infinity;
  for (let i = 0; i < polygon.length; i++) {
    distance = Math.min(distance, distanceToSegment(x, y, polygon[i], polygon[(i + 1) % polygon.length]));
  }
  return distance;
}

/** A sample comfortably inside [polygon]: inside and farther than [margin] from every edge. */
function wellInside(polygon, x, y, margin = 3) {
  return inside(polygon, x, y) && distanceToPolygon(polygon, x, y) > margin;
}

/** A sample comfortably outside [polygon]: outside and farther than [margin] from every edge. */
function wellOutside(polygon, x, y, margin = 3) {
  return !inside(polygon, x, y) && distanceToPolygon(polygon, x, y) > margin;
}

/** Barycentric weights of a device pixel in a triangle's LOCAL vertices. */
function barycentricOf(vertices, x, y) {
  const [x0, y0, x1, y1, x2, y2] = vertices;
  const denominator = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
  const w0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / denominator;
  const w1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / denominator;
  return [w0, w1, 1 - w0 - w1];
}

/** Source-over of premultiplied [top] over premultiplied [bottom]. */
function over(top, bottom) {
  const keep = 1 - top[3] / 255;
  return [
    top[0] + bottom[0] * keep,
    top[1] + bottom[1] * keep,
    top[2] + bottom[2] * keep,
    top[3] + bottom[3] * keep,
  ];
}

/** The texture texel the contract would sample at nominal UV (tu,tv). */
function texelOf(texture, tu, tv, nominal) {
  const scaleX = texture.width / nominal[0];
  const scaleY = texture.height / nominal[1];
  let x = Math.floor(tu * scaleX) % texture.width;
  if (x < 0) x += texture.width;
  let y = Math.floor(tv * scaleY) % texture.height;
  if (y < 0) y += texture.height;
  return typeof texture.pixels === 'function'
    ? texture.pixels(x, y)
    : texture.pixels[y * texture.width + x];
}

function triangleFrame({ asset = 2, nominal = [8, 4], colours = [-1, -1, -1] } = {}) {
  const vertices = [
    bits(0), bits(0), bits(0), bits(0),
    bits(80), bits(0), bits(8), bits(0),
    bits(0), bits(40), bits(0), bits(4),
  ];
  return new Int32Array([
    2, 3, 1, asset,
    ...vertices,
    ...colours,
    bits(nominal[0]), bits(nominal[1]),
  ]);
}

function drawsOf(ops) {
  return ops.filter((entry) => entry.op === 'drawImage');
}

function fillsOf(ops) {
  return ops.filter((entry) => entry.op === 'fill');
}

function assertPixel(actual, expected, message) {
  for (let i = 0; i < 4; i++) {
    assert.ok(
      Math.abs(actual[i] - expected[i]) <= 1,
      `${message}: channel ${i} is ${actual[i]}, expected about ${expected[i]} (${actual})`,
    );
  }
}

// ---- output coordinates, resize and capture ---------------------------------------------------

test('pointer positions map CSS coordinates through the actual backing-to-CSS ratio', () => {
  const { api, screen } = createClient();
  // Backing 800x600 displayed at 400x300. The nominal dpr is deliberately wrong: the mapping must
  // use what the canvas actually is, not what the device claims.
  screen.width = 800;
  screen.height = 600;
  screen.bounds = { left: 100, top: 50, width: 400, height: 300 };
  api.window.devicePixelRatio = 3;

  assert.deepEqual([...api.position({ clientX: 200, clientY: 125 })], [200, 150]);
  // Fractional CSS coordinates stay fractional; rounding here would disagree with the drawing.
  assert.deepEqual([...api.position({ clientX: 200.25, clientY: 125.5 })], [200.5, 151]);
});

test('pointer positions use a separate ratio for X and Y when the target is non-uniform', () => {
  const { api, screen } = createClient();
  screen.width = 800;
  screen.height = 600;
  screen.bounds = { left: 10, top: 20, width: 300, height: 400 };

  assert.deepEqual([...api.position({ clientX: 310, clientY: 220 })], [800, 300]);
  assert.deepEqual(
    [...api.position({ clientX: 100.5, clientY: 70.25 })],
    [90.5 * (800 / 300), 50.25 * (600 / 400)],
  );
});

test('a zero or unusable displayed rectangle drops the event instead of sending a made-up position', () => {
  const { api, screen } = createClient();
  const invalid = [
    { left: 0, top: 0, width: 0, height: 480 },
    { left: 0, top: 0, width: 640, height: 0 },
    { left: 0, top: 0, width: Number.NaN, height: 480 },
    { left: 0, top: 0, width: 640, height: Number.POSITIVE_INFINITY },
  ];
  for (const bounds of invalid) {
    screen.bounds = bounds;
    assert.equal(api.position({ clientX: 10, clientY: 10 }), null, `bounds ${JSON.stringify(bounds)}`);
  }

  screen.width = 0;
  screen.height = 480;
  screen.bounds = { left: 0, top: 0, width: 640, height: 480 };
  assert.equal(api.position({ clientX: 10, clientY: 10 }), null, 'a zero backing width has no mapping');

  screen.width = 640;
  screen.bounds = { left: 0, top: 0, width: 0, height: 480 };
  api.socket().sent.length = 0;
  screen.listeners.pointermove[0]({ clientX: 10, clientY: 10 });
  screen.listeners.pointerdown[0]({ clientX: 10, clientY: 10, button: 0, pointerId: 1 });
  assert.deepEqual(api.socket().sent, [], 'an undisplayable canvas still produced input events');
});

function pointerEvent(overrides = {}) {
  return { pointerId: 7, pointerType: 'mouse', buttons: 0, button: -1,
    clientX: 10.5, clientY: 20.25, preventDefault() {}, ...overrides };
}

test('an unmappable captured surface cancels without inventing a release position', () => {
  const { api, screen } = createClient();
  screen.width = 640; screen.height = 480;
  screen.bounds = { left: 0, top: 0, width: 320, height: 240 };
  screen.listeners.pointerdown[0](pointerEvent({ buttons: 1, button: 0 }));
  assert.deepEqual(api.socket().sent, ['pointer down 7 mouse 21 40.5 0 1 1 0.5 0']);
  assert.ok(screen.hasPointerCapture(7));
  screen.bounds.width = 0;
  api.socket().sent.length = 0;
  screen.listeners.pointerup[0](pointerEvent({ button: 0 }));
  assert.deepEqual(api.socket().sent, ['pointer cancel 7']);
  assert.equal(screen.hasPointerCapture(7), false);
});

test('pointercancel clears exactly one contact and ignores the following capture loss', () => {
  const { api, screen } = createClient();
  screen.listeners.pointerdown[0](pointerEvent({ buttons: 1, button: 0, pointerType: 'touch' }));
  screen.listeners.pointerdown[0](pointerEvent({ pointerId: 8, buttons: 1, button: 0, pointerType: 'touch' }));
  api.socket().sent.length = 0;
  screen.listeners.pointercancel[0]({ pointerId: 7 });
  screen.listeners.lostpointercapture[0]({ pointerId: 7 });
  assert.deepEqual(api.socket().sent, ['pointer cancel 7']);
  assert.equal(screen.hasPointerCapture(7), false);
  assert.equal(screen.hasPointerCapture(8), true);
});

test('the normal hover path sends fractional output pixels unchanged', () => {
  const { api, screen } = createClient();
  screen.width = 640; screen.height = 480;
  screen.bounds = { left: 0, top: 0, width: 320, height: 240 };
  screen.listeners.pointermove[0](pointerEvent());
  assert.deepEqual(api.socket().sent, ['pointer move 7 mouse 21 40.5 -1 0 1 0 0']);
});

test('DOM multi-button moves normalize into one identity with complete snapshots', () => {
  const { api, screen } = createClient();
  screen.listeners.pointerdown[0](pointerEvent({ buttons: 1, button: 0 }));
  screen.listeners.pointermove[0](pointerEvent({ buttons: 3, button: 2 }));
  screen.listeners.pointermove[0](pointerEvent({ buttons: 2, button: 0 }));
  screen.listeners.pointerup[0](pointerEvent({ buttons: 0, button: 2 }));
  assert.deepEqual(api.socket().sent.map((text) => text.split(' ').slice(0, 4).join(' ')),
    ['pointer down 7 mouse', 'pointer down 7 mouse', 'pointer up 7 mouse', 'pointer up 7 mouse']);
  assert.deepEqual(api.socket().sent.map((text) => Number(text.split(' ')[7])), [1, 3, 2, 0]);
});

test('stationary pen pressure moves and non-final side button Up retain pressure', () => {
  const { api, screen } = createClient();
  screen.listeners.pointermove[0](pointerEvent({ pointerType: 'pen' }));
  screen.listeners.pointerdown[0](pointerEvent({ pointerType: 'pen', buttons: 1, pressure: 0.1 }));
  screen.listeners.pointermove[0](pointerEvent({ pointerType: 'pen', buttons: 1, pressure: 0.9 }));
  screen.listeners.pointermove[0](pointerEvent({ pointerType: 'pen', buttons: 3, pressure: 0.8 }));
  screen.listeners.pointermove[0](pointerEvent({ pointerType: 'pen', buttons: 1, pressure: 0.7 }));
  const samples = api.socket().sent.map((text) => text.split(' '));
  assert.equal(samples[2][1], 'move');
  assert.equal(samples[2][9], '0.9');
  assert.equal(samples[4][1], 'up');
  assert.equal(samples[4][9], '0.7');
});

test('Leave retains native capture and blur cancels every identity', () => {
  const { api, screen } = createClient();
  screen.listeners.pointerdown[0](pointerEvent({ buttons: 1 }));
  screen.listeners.pointerdown[0](pointerEvent({ pointerId: 8, pointerType: 'touch', buttons: 1 }));
  api.socket().sent.length = 0;
  screen.listeners.pointerleave[0]({ pointerId: 7 });
  assert.equal(screen.hasPointerCapture(7), true);
  api.window.listeners.blur[0]();
  assert.deepEqual(api.socket().sent, ['pointer leave 7', 'pointer cancel 7', 'pointer cancel 8', 'blur']);
  assert.equal(screen.hasPointerCapture(7), false);
  assert.equal(screen.hasPointerCapture(8), false);
});

test('composition commits once and a late compositionend after blur is discarded', () => {
  const { api } = createClient();
  api.window.listeners.compositionstart[0]();
  api.window.listeners.compositionupdate[0]({ data: '你' });
  api.window.listeners.compositionend[0]({ data: '你' });
  assert.deepEqual(api.socket().sent, ['compositionstart', 'preedit %E4%BD%A0', 'compositionend %E4%BD%A0']);
  api.socket().sent.length = 0;
  api.window.listeners.compositionstart[0]();
  api.window.listeners.blur[0]();
  api.window.listeners.compositionend[0]({ data: 'old' });
  assert.deepEqual(api.socket().sent, ['compositionstart', 'blur']);
});

test('native identity reuse for another device first cancels the previous source', () => {
  const { api, screen } = createClient();
  screen.listeners.pointermove[0](pointerEvent({ pointerId: -5 }));
  screen.listeners.pointerdown[0](pointerEvent({ pointerId: -5, pointerType: 'pen', buttons: 1 }));
  assert.match(api.socket().sent[0], /^pointer move 4294967291 mouse /);
  assert.equal(api.socket().sent[1], 'pointer cancel 4294967291');
  assert.match(api.socket().sent[2], /^pointer down 4294967291 pen /);
});

test('a mapped input position addresses the same output pixel in drawing and clipping', () => {
  const { api, screen } = createClient();
  screen.width = 640;
  screen.height = 480;
  screen.bounds = { left: 0, top: 0, width: 320, height: 240 };

  // Input maps CSS (10.5, 20.25) to output (21, 40.5). That fractional output coordinate is then
  // used unchanged as the drawable's rect and its clip, so the three layers agree on one space.
  const point = [...api.position({ clientX: 10.5, clientY: 20.25 })];
  assert.deepEqual(point, [21, 40.5]);
  const rect = [point[0], point[1], point[0] + 20, point[1] + 20];
  api.setFrame(frameOf(
    clipPushFrame(rect),
    colourQuadFrame({ rect }),
    CLIP_POP_FRAME,
  ));
  api.paint();

  assertPixel(screen.pixel(22, 42), [255, 255, 255, 255], 'the draw at the mapped position missed');
  assert.deepEqual(screen.pixel(20, 39), [0, 0, 0, 0], 'the clip drew outside the mapped position');
});

test('resize floors the backing size, keeps the CSS size, and reports the backing pixels', () => {
  const { api, screen } = createClient();
  api.window.innerWidth = 100;
  api.window.innerHeight = 100;
  api.window.devicePixelRatio = 2;
  api.socket().sent.length = 0;

  api.window.listeners.resize[0]();

  // 100 CSS px at dpr 2 is 200 backing px, below both minima, so the backing store is 320x240 while
  // CSS stays 100x100. That is exactly the case a blind `clientX * dpr` mapping gets wrong.
  assert.equal(screen.width, 320);
  assert.equal(screen.height, 240);
  assert.equal(screen.style.width, '100px');
  assert.equal(screen.style.height, '100px');
  assert.ok(api.socket().sent.includes('resize 320 240'), `sent: ${api.socket().sent}`);
  assert.deepEqual([...api.position({ clientX: 50, clientY: 50 })], [160, 120]);
});

test('fractional device ratios use the floored backing size rather than a rounded one', () => {
  const { api, screen } = createClient();
  api.window.innerWidth = 641;
  api.window.innerHeight = 361;
  api.window.devicePixelRatio = 1.5;
  api.socket().sent.length = 0;

  api.window.listeners.resize[0]();

  assert.equal(screen.width, 961, '641 * 1.5 floored');
  assert.equal(screen.height, 541, '361 * 1.5 floored');
  assert.ok(api.socket().sent.includes('resize 961 541'), `sent: ${api.socket().sent}`);
  assert.deepEqual(
    [...api.position({ clientX: 100.25, clientY: 50.5 })],
    [100.25 * (961 / 641), 50.5 * (541 / 361)],
  );
});

test('a capture photographs the same backing pixels the resize frame was recorded at', () => {
  const { api, screen } = createClient();
  api.window.innerWidth = 500;
  api.window.innerHeight = 400;
  api.window.devicePixelRatio = 2;
  api.socket().sent.length = 0;
  api.window.listeners.resize[0]();

  const resize = api.socket().sent.find((message) => message.startsWith('resize '));
  assert.ok(resize, `no resize was sent: ${api.socket().sent}`);
  const [width, height] = resize.split(' ').slice(1).map(Number);
  assert.deepEqual([width, height], [1000, 800]);

  api.setPainted(true);
  api.socket().onmessage({ data: 'capture' });
  const blob = screen.blobs[screen.blobs.length - 1];
  assert.deepEqual(blob, [width, height], 'the capture and the recorded frame disagree on size');
  assert.deepEqual(blob, [screen.width, screen.height]);
});

// ---- assets and sampling ----------------------------------------------------------------------

test('a pending texture is invisible; a ready one is sampled through a repeating pattern', () => {
  const { api, state } = createClient();
  api.reset(state);
  api.defineAsset(2, 'demo:pending');
  api.setFrame(quadFrame());
  api.paint();
  assert.equal(state.ops.filter((entry) => entry.op === 'drawImage' || entry.op === 'fill').length, 0,
    'a pending texture drew pixels');

  api.reset(state);
  api.defineAsset(2, 'demo:loaded');
  const image = state.images[state.images.length - 1];
  image.upload(32, 16, () => [255, 255, 255, 255]);
  api.setFrame(quadFrame());
  api.paint();

  const fills = fillsOf(state.ops);
  assert.equal(fills.length, 1, 'the untouched texture should reach the screen in one fill');
  assert.equal(fills[0].style.image, image, 'the loaded image did not reach the canvas');
  assert.equal(fills[0].style.repetition, 'repeat', 'the texture was not sampled as a repeating pattern');
  // Nominal 8x4 against actual 32x16 means UVs scale by four; the quad (10,20)-(30,40) is mapped
  // from actual UV (0,0)-(32,0)-(0,16) by (0.625, 1.25).
  assert.deepEqual(fills[0].transform, [0.625, 0, 0, 1.25, 10, 20]);
  assert.equal(drawsOf(state.ops).length, 0, 'an untinted texture should not need a scratch composite');
});

test('a confirmed load failure is the cached checkerboard, not a flat fill', () => {
  const { api, state } = createClient();
  api.reset(state);
  api.defineAsset(2, 'bad id / ??');
  const image = state.images[state.images.length - 1];
  image.fail();

  const checkerboard = api.images['bad id / ??'];
  assert.ok(checkerboard, 'the failure was not cached');
  assert.equal(checkerboard, api.checkerboard(), 'the failure did not reuse the cached checkerboard');
  assert.equal(checkerboard.width, 16);
  assert.equal(checkerboard.height, 16);

  api.setFrame(quadFrame());
  api.paint();
  const fills = fillsOf(state.ops);
  assert.ok(fills.length > 0, 'the failed texture drew nothing');
  assert.equal(fills[0].style.image, checkerboard, 'the checkerboard did not go through texture sampling');
  assert.equal(state.ops.filter((entry) => entry.op === 'fillRect').length, 0,
    'a failed texture fell back to a colour fill');
});

test('a textured triangle reads xyuv and the trailing nominal size', () => {
  const { api, state } = createClient();
  api.reset(state);
  api.defineAsset(2, 'demo:triangle');
  api.setFrame(triangleFrame());
  api.paint();
  assert.equal(state.ops.filter((entry) => entry.op === 'drawImage' || entry.op === 'fill').length, 0,
    'a pending triangle drew pixels');

  const image = state.images[state.images.length - 1];
  image.upload(16, 8, () => [255, 255, 255, 255]);
  state.ops.length = 0;
  api.setFrame(triangleFrame());
  api.paint();

  const fills = fillsOf(state.ops);
  assert.ok(fills.length > 0, 'the textured triangle drew nothing');
  assert.equal(fills[0].style.image, image, 'the triangle did not use its image');
  // UVs (0,0)-(8,0)-(0,4) nominal 8x4, actual 16x8: scale 2, then map to (0,0)-(80,0)-(0,40).
  assert.deepEqual(fills[0].transform, [5, 0, 0, 5, 0, 0]);
  assert.equal(api.strideAt(triangleFrame(), 0), 21, 'the client reads the wrong textured triangle stride');
  assert.equal(api.strideAt(quadFrame(), 0), 16, 'the client reads the wrong textured quad stride');
});

test('UVs outside the image repeat instead of leaving a gap', () => {
  const { api, state, screen } = createClient();
  api.reset(state);
  api.defineAsset(2, 'demo:repeat');
  const image = state.images[state.images.length - 1];
  image.upload(8, 4, (x) => (x < 4 ? [255, 0, 0, 255] : [0, 255, 0, 255]));

  // u runs from -8 to 16 over the quad: one whole tile before the image and two after it.
  api.setFrame(quadFrame({ uv: [-8, 0, 16, 4], nominal: [8, 4] }));
  api.paint();

  assertPixel(screen.pixel(18, 30), [255, 0, 0, 255], 'the first tile should be sampled');
  assertPixel(screen.pixel(21, 30), [0, 255, 0, 255], 'beyond the image should wrap to the next tile');
  assertPixel(screen.pixel(12, 30), [255, 0, 0, 255], 'a negative UV should wrap backwards');
  assert.equal(state.ops.filter((entry) => entry.op === 'fillRect').length, 0,
    'repeating UVs fell back to a colour fill');
});

test('the missing texture repeats through its own pixels, not a generic colour', () => {
  const { api, state, screen } = createClient();
  api.reset(state);
  api.defineAsset(2, 'demo:missing-repeat');
  state.images[state.images.length - 1].fail();
  const checkerboard = api.checkerboard();

  api.setFrame(quadFrame({ uv: [-8, 0, 8, 4], nominal: [8, 4] }));
  api.paint();

  const fills = fillsOf(state.ops);
  assert.equal(fills[0].style.image, checkerboard, 'the missing texture was not the sampled image');
  assert.equal(fills[0].style.repetition, 'repeat');
  // The 16x16 board repeats every 8 nominal pixels: dark at the tile origin, magenta half a tile on.
  assertPixel(screen.pixel(12, 25), [24, 24, 24, 255], 'the first checkerboard square is missing');
  assertPixel(screen.pixel(17, 25), [208, 0, 208, 255], 'the repeated checkerboard square is missing');
  assert.equal(state.ops.filter((entry) => entry.op === 'fillRect').length, 0,
    'the missing texture fell back to a colour fill');
});

// ---- compositing ------------------------------------------------------------------------------

test('tint and masks run on a scratch surface and reach the screen once with source-over', () => {
  const { api, state } = createClient();
  api.reset(state);
  api.defineAsset(2, 'demo:tint');
  const image = state.images[state.images.length - 1];
  image.upload(16, 8, () => [255, 0, 0, 128]);

  const translucentRed = 0xff000080 | 0;
  api.setFrame(triangleFrame({ colours: [translucentRed, translucentRed, translucentRed] }));
  api.paint();

  // Nothing on the screen may multiply or mask what is already painted there.
  assert.equal(
    state.ops.filter((entry) => entry.composite === 'multiply' || entry.composite === 'destination-in').length,
    0,
    'the tint or mask was applied to the screen instead of the scratch surface',
  );
  const composites = drawsOf(state.ops);
  assert.equal(composites.length, 1, 'the screen should receive exactly one composite');
  assert.equal(composites[0].image, api.scratch(), 'the composite is not the scratch surface');
  assert.equal(composites[0].composite, 'source-over', 'the composite is not source-over');

  const scratch = api.scratch().state.ops;
  assert.ok(scratch.some((entry) => entry.op === 'fillRect' && entry.composite === 'multiply'),
    'the tint was not multiplied on the scratch surface');
  assert.ok(scratch.some((entry) => entry.composite === 'destination-in'),
    'the texture or vertex alpha was not restored on the scratch surface');
});

test('half-alpha texture under a half-alpha white tint composites once over an opaque backdrop', () => {
  const { api, state, screen } = createClient({ backdrop: 'rgb(0,0,255)' });
  api.reset(state);
  api.defineAsset(2, 'demo:half');
  state.images[state.images.length - 1].upload(8, 4, () => [255, 0, 0, 128]);

  const tint = 0xffffff80 | 0;
  api.setFrame(quadFrame({ colours: [tint, tint, tint, tint] }));
  api.paint();

  // texture alpha 128/255 times vertex alpha 128/255 = 0.252, over opaque blue.
  assertPixel(screen.pixel(20, 30), [64, 0, 191, 255], 'the two alphas were not multiplied exactly once');
});

test('a fully transparent texture leaves the backdrop untouched', () => {
  const { api, state, screen } = createClient({ backdrop: 'rgb(0,0,255)' });
  api.reset(state);
  api.defineAsset(2, 'demo:clear');
  state.images[state.images.length - 1].upload(8, 4, () => [255, 0, 0, 0]);

  const tint = 0xffffff80 | 0;
  api.setFrame(quadFrame({ colours: [tint, tint, tint, tint] }));
  api.paint();

  assertPixel(screen.pixel(20, 30), [0, 0, 255, 255], 'a transparent texel changed the backdrop');
});

test('a non-white tint modulates RGB without mixing the matte in', () => {
  const { api, state, screen } = createClient({ backdrop: 'rgb(0,0,255)' });
  api.reset(state);
  api.defineAsset(2, 'demo:matte');
  state.images[state.images.length - 1].upload(8, 4, () => [255, 0, 0, 128]);

  const tint = (200 << 24) | (255 << 16) | (64 << 8) | 255;
  const colour = tint | 0;
  api.setFrame(quadFrame({ colours: [colour, colour, colour, colour] }));
  api.paint();

  // texture (255,0,0) times tint (200,255,64) is (200,0,0), at alpha 128/255 over opaque blue.
  // Mixing the tint with the translucent backdrop would leave the green channel around 64.
  assertPixel(screen.pixel(20, 30), [100, 0, 127, 255], 'the tint mixed matte colour into the texture');
});

// ---- rejected draws ---------------------------------------------------------------------------

test('invalid or infinite dimensions and malformed records draw nothing', () => {
  const { api, state } = createClient();
  api.reset(state);
  api.defineAsset(2, 'demo:invalid-size');
  state.images[state.images.length - 1].upload(16, 16, () => [255, 255, 255, 255]);

  const rejected = [
    quadFrame({ nominal: [0, 4] }),
    quadFrame({ nominal: [Infinity, 4] }),
    triangleFrame({ nominal: [8, 0] }),
    triangleFrame({ nominal: [8, Number.POSITIVE_INFINITY] }),
    quadFrame({ nominal: [Number.NaN, 4] }),
  ];
  const nanCorner = quadFrame();
  nanCorner[1] = bits(Number.NaN);
  rejected.push(nanCorner);
  const infiniteCorner = quadFrame();
  infiniteCorner[3] = bits(Number.POSITIVE_INFINITY);
  rejected.push(infiniteCorner);

  for (const value of rejected) {
    api.setFrame(value);
    api.paint();
    assert.equal(state.ops.filter((entry) => entry.op === 'drawImage' || entry.op === 'fill').length, 0,
      'a rejected record produced pixels');
    state.ops.length = 0;
    if (api.scratch()) api.scratch().state.ops.length = 0;
  }
});

test('an unencodable asset id is a confirmed miss, not an exception', () => {
  const { api, state } = createClient();
  api.reset(state);
  const id = '\ud800';
  assert.doesNotThrow(() => api.defineAsset(2, id), 'a lone surrogate escaped as an exception');
  assert.equal(api.images[id], api.checkerboard(), 'the unencodable id was not cached as a miss');
  assert.equal(state.images.length, 0, 'an impossible request was still sent');

  api.setFrame(quadFrame());
  api.paint();
  const fills = fillsOf(state.ops);
  assert.equal(fills.length, 1, 'the unencodable id did not draw its missing texture');
  assert.equal(fills[0].style.image, api.checkerboard());
});

// ---- premultiplied colour fields --------------------------------------------------------------
//
// Each assertion below runs the shipped painter through `paint()` and compares the resulting pixel
// with the contract's arithmetic, evaluated over the wire colours by the helpers above. The expected
// values are premultiplied, so an alpha-0 corner with hidden RGB contributes nothing and a colour
// whose alpha is small is not divided back out of an 8-bit pixel.

const OPAQUE_RED = 0xff0000ff | 0;
const OPAQUE_BLACK = 0x000000ff | 0;
const OPAQUE_WHITE = -1;

function rgba(r, g, b, a = 255) {
  return ((r << 24) | (g << 16) | (b << 8) | a) | 0;
}

function uploadTexture(api, state, id, width, height, colour) {
  api.defineAsset(2, id);
  const image = state.images[state.images.length - 1];
  image.upload(width, height, colour);
  return image;
}

test('a four-corner quad evaluates one premultiplied bilinear field at its centre', () => {
  const { api, state, screen } = createClient();
  api.reset(state);
  const rect = [10, 20, 31, 41];
  const colours = [OPAQUE_WHITE, rgba(255, 0, 0), OPAQUE_BLACK, OPAQUE_BLACK];
  api.setFrame(colourQuadFrame({ rect, colours }));
  api.paint();

  // u=v=0.5 at pixel (20,30): white and red each weigh a quarter. Two independently shaded
  // triangles would produce (128,0,0) here; the bilinear field produces the red channel of the
  // white corner too.
  const centre = screen.pixel(20, 30);
  assert.deepEqual(centre, [128, 64, 64, 255], 'the centre is not the bilinear field');
  assertPremultiplied(centre, [127.5, 63.75, 63.75, 255], 'centre premultiplied value');

  for (const [x, y] of [[10, 20], [30, 20], [10, 40], [30, 40], [20, 22], [22, 30]]) {
    const u = (x + 0.5 - rect[0]) / (rect[2] - rect[0]);
    const v = (y + 0.5 - rect[1]) / (rect[3] - rect[1]);
    assertPremultiplied(screen.pixel(x, y), quadFieldAt(colours, u, v), `pixel (${x},${y})`);
  }
});

test('the bilinear field is symmetric under horizontal and diagonal mirroring', () => {
  const rect = [10, 20, 31, 41];
  const colours = [OPAQUE_WHITE, rgba(255, 0, 0), OPAQUE_BLACK, OPAQUE_BLACK];
  const { api, state, screen } = createClient();
  api.reset(state);
  api.setFrame(colourQuadFrame({ rect, colours }));
  api.paint();
  const sample = screen.pixel(14, 28);

  api.reset(state);
  api.setFrame(colourQuadFrame({ rect, colours: [colours[1], colours[0], colours[3], colours[2]] }));
  api.paint();
  assertPremultiplied(screen.pixel(26, 28), premultipliedPixel(sample), 'horizontal mirror is not symmetric');

  api.reset(state);
  api.setFrame(colourQuadFrame({ rect, colours: [colours[0], colours[2], colours[1], colours[3]] }));
  api.paint();
  assertPremultiplied(screen.pixel(18, 24), premultipliedPixel(sample), 'transpose is not symmetric');
});

test('opaque red fading to transparent blue stays red at half alpha', () => {
  const { api, state, screen } = createClient();
  api.reset(state);
  const rect = [10, 20, 31, 41];
  const colours = [rgba(255, 0, 0), rgba(0, 0, 255, 0), rgba(255, 0, 0), rgba(0, 0, 255, 0)];
  api.setFrame(colourQuadFrame({ rect, colours }));
  api.paint();

  const centre = screen.pixel(20, 30);
  assertPremultiplied(centre, [127.5, 0, 0, 127.5], 'half-alpha fade');
  assert.equal(centre[1], 0, 'the transparent blue corner tinted the red');
  assert.equal(centre[2], 0, 'the transparent blue corner tinted the red');
  assert.ok(Math.abs(centre[3] - 128) <= 2, `alpha is ${centre[3]}, expected about 128`);
});

test('unequal corner alphas follow the premultiplied field on transparent and opaque targets', () => {
  const rect = [10, 20, 31, 41];
  const colours = [
    rgba(255, 20, 80, 255), rgba(20, 255, 40, 128), rgba(40, 80, 255, 0), rgba(240, 160, 40, 64),
  ];
  const samples = [[12, 22], [20, 30], [28, 38], [24, 30], [16, 34]];

  const transparent = createClient();
  transparent.api.reset(transparent.state);
  transparent.api.setFrame(colourQuadFrame({ rect, colours }));
  transparent.api.paint();
  for (const [x, y] of samples) {
    const u = (x + 0.5 - rect[0]) / (rect[2] - rect[0]);
    const v = (y + 0.5 - rect[1]) / (rect[3] - rect[1]);
    assertPremultiplied(
      transparent.screen.pixel(x, y), quadFieldAt(colours, u, v), `transparent target (${x},${y})`,
    );
  }

  const opaque = createClient({ backdrop: 'rgb(0,0,255)' });
  opaque.api.reset(opaque.state);
  opaque.api.setFrame(colourQuadFrame({ rect, colours }));
  opaque.api.paint();
  for (const [x, y] of samples) {
    const u = (x + 0.5 - rect[0]) / (rect[2] - rect[0]);
    const v = (y + 0.5 - rect[1]) / (rect[3] - rect[1]);
    assertPremultiplied(
      opaque.screen.pixel(x, y),
      over(quadFieldAt(colours, u, v), [0, 0, 255, 255]),
      `opaque blue target (${x},${y})`,
    );
  }
});

test('a half-alpha texture under a half-alpha tint keeps its effective alpha on a transparent target', () => {
  const { api, state, screen } = createClient();
  api.reset(state);
  uploadTexture(api, state, 'demo:half-transparent', 8, 4, () => [255, 0, 0, 128]);
  const tint = 0xffffff80 | 0;
  api.setFrame(quadFrame({ colours: [tint, tint, tint, tint] }));
  api.paint();

  const pixel = screen.pixel(20, 30);
  assertPremultiplied(pixel, [64.25, 0, 0, 64.25], 'half alpha over a transparent target');
  assert.ok(Math.abs(pixel[3] - 64) <= 2, `effective alpha is ${pixel[3]}, expected about 64`);
});

test('overlapping gradient quads composite source-over in draw order', () => {
  const coloursA = [
    rgba(255, 20, 80, 255), rgba(20, 255, 40, 128), rgba(40, 80, 255, 0), rgba(240, 160, 40, 64),
  ];
  const coloursB = [
    rgba(10, 220, 90, 200), rgba(200, 30, 10, 90), rgba(90, 40, 220, 255), rgba(255, 255, 255, 32),
  ];
  const rectA = [10, 20, 31, 41], rectB = [16, 26, 37, 47];
  const { api, state, screen } = createClient();
  api.reset(state);
  api.setFrame(frameOf(
    colourQuadFrame({ rect: rectA, colours: coloursA }),
    colourQuadFrame({ rect: rectB, colours: coloursB }),
  ));
  api.paint();

  for (const [x, y] of [[20, 30], [24, 34], [18, 28], [30, 40]]) {
    let expected = [0, 0, 0, 0];
    for (const [rect, colours] of [[rectA, coloursA], [rectB, coloursB]]) {
      if (x + 0.5 <= rect[0] || x + 0.5 >= rect[2] || y + 0.5 <= rect[1] || y + 0.5 >= rect[3]) continue;
      const u = (x + 0.5 - rect[0]) / (rect[2] - rect[0]);
      const v = (y + 0.5 - rect[1]) / (rect[3] - rect[1]);
      expected = over(quadFieldAt(colours, u, v), expected);
    }
    assertPremultiplied(screen.pixel(x, y), expected, `overlap at (${x},${y})`);
  }
});

test('a scissor limits coverage but does not restart or rescale the gradient', () => {
  const rect = [10, 20, 31, 41];
  const colours = [
    rgba(255, 20, 80, 255), rgba(20, 255, 40, 128), rgba(40, 80, 255, 0), rgba(240, 160, 40, 64),
  ];
  const { api, state, screen } = createClient();
  api.reset(state);
  api.setFrame(frameOf(
    clipPushFrame([18, 24, 34, 40]),
    colourQuadFrame({ rect, colours }),
    CLIP_POP_FRAME,
  ));
  api.paint();

  for (const [x, y] of [[20, 30], [24, 30], [28, 36]]) {
    const u = (x + 0.5 - rect[0]) / (rect[2] - rect[0]);
    const v = (y + 0.5 - rect[1]) / (rect[3] - rect[1]);
    assertPremultiplied(screen.pixel(x, y), quadFieldAt(colours, u, v), `inside clip (${x},${y})`);
  }
  for (const [x, y] of [[12, 30], [20, 22], [36, 34]]) {
    assert.deepEqual(screen.pixel(x, y), [0, 0, 0, 0], `outside the clip (${x},${y}) was painted`);
  }
});

test('rotation, shear and positive-w perspective keep the field in the quad own coordinates', () => {
  const rect = [10, 20, 31, 41];
  const colours = [
    rgba(255, 20, 80, 255), rgba(20, 255, 40, 128), rgba(40, 80, 255, 0), rgba(240, 160, 40, 64),
  ];
  const transforms = [
    ['rotation', [0.866, -0.5, 30, 0.5, 0.866, 2, 0, 0, 1]],
    ['shear', [1, 0.25, 2, 0.15, 1, 2, 0, 0, 1]],
    ['perspective', [1, 0.1, 10, 0.05, 1, 9, 0.004, 0.002, 1]],
  ];
  for (const [name, transform] of transforms) {
    const { api, state, screen } = createClient();
    api.reset(state);
    api.setFrame(frameOf(
      transformPushFrame(transform),
      colourQuadFrame({ rect, colours }),
      TRANSFORM_POP_FRAME,
    ));
    api.paint();
    for (const [u, v] of [[0.25, 0.3], [0.5, 0.5], [0.6, 0.7]]) {
      const point = projectPoint(transform, rect[0] + u * (rect[2] - rect[0]), rect[1] + v * (rect[3] - rect[1]));
      const x = Math.floor(point[0]), y = Math.floor(point[1]);
      const local = localCoordinate(transform, x, y);
      const expected = quadFieldAt(
        colours,
        (local[0] - rect[0]) / (rect[2] - rect[0]),
        (local[1] - rect[1]) / (rect[3] - rect[1]),
      );
      assertPremultiplied(screen.pixel(x, y), expected, `${name} at (${x},${y})`);
    }
  }
});

test('a textured quad with unequal corner tints multiplies the premultiplied field by the texel once', () => {
  const colours = [
    rgba(255, 20, 80, 255), rgba(20, 255, 40, 128), rgba(40, 80, 255, 0), rgba(240, 160, 40, 64),
  ];
  const rect = [10, 20, 31, 41];
  const pixels = (x, y) => [
    (x * 37 + 13) & 0xff, (y * 53 + 29) & 0xff, ((x + y) * 71 + 41) & 0xff,
    [255, 128, 64, 200][(x * 3 + y * 5) % 4],
  ];
  const cases = [
    ['crop', [0, 0, 8, 4], [8, 4], [8, 4]],
    ['repeat', [-8, 0, 16, 4], [8, 4], [8, 4]],
    ['nominal 16', [2, 3, 12, 14], [16, 16], [8, 4]],
  ];
  for (const [name, uv, nominal, textureSize] of cases) {
    const { api, state, screen } = createClient();
    api.reset(state);
    uploadTexture(api, state, `demo:unequal-${name}`, textureSize[0], textureSize[1], pixels);
    api.setFrame(quadFrame({ colours, uv, nominal, rect }));
    api.paint();
    for (const [x, y] of [[13, 23], [20, 30], [27, 37], [24, 28]]) {
      const u = (x + 0.5 - rect[0]) / (rect[2] - rect[0]);
      const v = (y + 0.5 - rect[1]) / (rect[3] - rect[1]);
      const tu = uv[0] + (uv[2] - uv[0]) * u;
      const tv = uv[1] + (uv[3] - uv[1]) * v;
      const texel = texelOf(
        { width: textureSize[0], height: textureSize[1], pixels }, tu, tv, nominal,
      );
      const gradient = quadFieldAt(colours, u, v);
      const texelAlpha = texel[3] / 255;
      assertPremultiplied(
        screen.pixel(x, y),
        [
          gradient[0] * (texel[0] / 255) * texelAlpha,
          gradient[1] * (texel[1] / 255) * texelAlpha,
          gradient[2] * (texel[2] / 255) * texelAlpha,
          gradient[3] * texelAlpha,
        ],
        `${name} textured tint at (${x},${y})`,
      );
    }
  }
});

test('UV repetition and cropping change the texel, never the gradient', () => {
  const colours = [
    rgba(255, 20, 80, 255), rgba(20, 255, 40, 128), rgba(40, 80, 255, 0), rgba(240, 160, 40, 64),
  ];
  const rect = [10, 20, 31, 41];
  const screens = [];
  for (const [uv, nominal] of [
    [[0, 0, 8, 4], [8, 4]],
    [[-8, 0, 16, 4], [8, 4]],
    [[2, 3, 12, 14], [16, 16]],
  ]) {
    const { api, state, screen } = createClient();
    api.reset(state);
    uploadTexture(api, state, `demo:white-${screens.length}`, 8, 4, () => [255, 255, 255, 255]);
    api.setFrame(quadFrame({ colours, uv, nominal, rect }));
    api.paint();
    screens.push(screen);
  }
  for (const [x, y] of [[13, 23], [20, 30], [27, 37]]) {
    const u = (x + 0.5 - rect[0]) / (rect[2] - rect[0]);
    const v = (y + 0.5 - rect[1]) / (rect[3] - rect[1]);
    const expected = quadFieldAt(colours, u, v);
    for (let i = 0; i < screens.length; i++) {
      assertPremultiplied(screens[i].pixel(x, y), expected, `texture case ${i} changed the gradient at (${x},${y})`);
    }
  }
});

test('transparent texels leave the backdrop unchanged under a varying tint', () => {
  const { api, state, screen } = createClient({ backdrop: 'rgb(0,0,255)' });
  api.reset(state);
  uploadTexture(api, state, 'demo:transparent-texel', 8, 4,
    (x, y) => (x === 0 && y === 0 ? [255, 0, 0, 0] : [0, 255, 0, 255]));
  const colours = [
    rgba(255, 255, 255, 255), rgba(200, 40, 40, 128), rgba(20, 20, 20, 64), rgba(255, 255, 255, 32),
  ];
  api.setFrame(quadFrame({ colours, rect: [10, 20, 31, 41] }));
  api.paint();
  assert.deepEqual(screen.pixel(10, 20), [0, 0, 255, 255], 'a transparent texel changed the backdrop');
  assert.notDeepEqual(screen.pixel(13, 20), [0, 0, 255, 255], 'the next opaque texel was not drawn');
});

test('both triangle overloads interpolate premultiplied barycentric colour', () => {
  const colours = [rgba(255, 0, 0, 255), rgba(0, 0, 255, 0), rgba(0, 255, 0, 128)];
  const vertices = [0, 0, 80, 0, 0, 40];
  const { api, state, screen } = createClient();
  api.reset(state);
  api.setFrame(colourTriangleFrame({ colours }));
  api.paint();
  for (const [x, y] of [[10, 10], [20, 10], [10, 20], [30, 8]]) {
    const weights = barycentricOf(vertices, x + 0.5, y + 0.5);
    assertPremultiplied(screen.pixel(x, y), triangleFieldAt(colours, weights), `untextured (${x},${y})`);
  }

  const textured = createClient();
  textured.api.reset(textured.state);
  uploadTexture(textured.api, textured.state, 'demo:triangle-unequal', 8, 4,
    (x, y) => [(x * 51 + 17) & 0xff, (y * 33 + 7) & 0xff, ((x + 2 * y) * 29 + 11) & 0xff, 255]);
  textured.api.setFrame(triangleFrame({ colours }));
  textured.api.paint();
  const uvs = [[0, 0], [8, 0], [0, 4]];
  for (const [x, y] of [[10, 10], [20, 10], [10, 20], [30, 8]]) {
    const weights = barycentricOf(vertices, x + 0.5, y + 0.5);
    const tu = weights[0] * uvs[0][0] + weights[1] * uvs[1][0] + weights[2] * uvs[2][0];
    const tv = weights[0] * uvs[0][1] + weights[1] * uvs[1][1] + weights[2] * uvs[2][1];
    const texel = texelOf(
      {
        width: 8, height: 4,
        pixels: (tx, ty) => [
          (tx * 51 + 17) & 0xff, (ty * 33 + 7) & 0xff, ((tx + 2 * ty) * 29 + 11) & 0xff, 255,
        ],
      },
      tu, tv, [8, 4],
    );
    const gradient = triangleFieldAt(colours, weights);
    assertPremultiplied(
      textured.screen.pixel(x, y),
      [gradient[0] * texel[0] / 255, gradient[1] * texel[1] / 255, gradient[2] * texel[2] / 255, gradient[3]],
      `textured (${x},${y})`,
    );
  }
});

test('a projective triangle samples its barycentric field in original local coordinates', () => {
  const colours = [rgba(255, 0, 0, 255), rgba(0, 0, 255, 0), rgba(0, 255, 0, 128)];
  const vertices = [0, 0, 80, 0, 0, 40];
  const transform = [1, 0.1, 10, 0.05, 1, 9, 0.004, 0.002, 1];
  const { api, state, screen } = createClient();
  api.reset(state);
  api.setFrame(frameOf(
    transformPushFrame(transform),
    colourTriangleFrame({ colours }),
    TRANSFORM_POP_FRAME,
  ));
  api.paint();
  for (const [u, v] of [[0.2, 0.2], [0.4, 0.1], [0.1, 0.4]]) {
    const point = projectPoint(transform, u * 80, v * 40);
    const x = Math.floor(point[0]), y = Math.floor(point[1]);
    const local = localCoordinate(transform, x, y);
    const weights = barycentricOf(vertices, local[0], local[1]);
    assertPremultiplied(screen.pixel(x, y), triangleFieldAt(colours, weights), `projective (${x},${y})`);
  }
});

test('a rotated and scissored texture samples through the inverse map', () => {
  const transform = [0.866, -0.5, 30, 0.5, 0.866, 2, 0, 0, 1];
  const pixels = (x, y) => [(x * 61 + 3) & 0xff, (y * 47 + 19) & 0xff, ((x + y) * 23 + 5) & 0xff, 255];
  const uv = [0, 0, 8, 4];
  const rect = [0, 0, 16, 8];
  const { api, state, screen } = createClient();
  api.reset(state);
  uploadTexture(api, state, 'demo:rotated-texture', 8, 4, pixels);
  api.setFrame(frameOf(
    clipPushFrame([0, 0, 96, 96]),
    transformPushFrame(transform),
    quadFrame({ colours: [OPAQUE_WHITE, OPAQUE_WHITE, OPAQUE_WHITE, OPAQUE_WHITE], uv, nominal: [8, 4], rect }),
    TRANSFORM_POP_FRAME,
    CLIP_POP_FRAME,
  ));
  api.paint();
  for (const [u, v] of [[0.25, 0.25], [0.5, 0.5], [0.7, 0.6]]) {
    const point = projectPoint(transform, u * 16, v * 8);
    const x = Math.floor(point[0]), y = Math.floor(point[1]);
    const local = localCoordinate(transform, x, y);
    const tu = uv[0] + (uv[2] - uv[0]) * (local[0] / 16);
    const tv = uv[1] + (uv[3] - uv[1]) * (local[1] / 8);
    const texel = texelOf({ width: 8, height: 4, pixels }, tu, tv, [8, 4]);
    assertPremultiplied(screen.pixel(x, y), [texel[0], texel[1], texel[2], texel[3]], `rotated texel (${x},${y})`);
  }
});

test('an asset entry that is not a CanvasImageSource is rejected, not passed to drawImage', () => {
  const { api, state } = createClient();
  api.reset(state);
  api.defineAsset(2, 'demo:not-an-image');
  api.images['demo:not-an-image'] = { width: 8, height: 4 };
  api.setFrame(quadFrame());
  assert.doesNotThrow(() => api.paint(), 'an invalid asset entry escaped as a TypeError');
  assert.equal(
    state.ops.filter((entry) => entry.op === 'drawImage' || entry.op === 'fill').length, 0,
    'an invalid asset entry still reached the canvas',
  );
});
test('an axis-aligned two-colour ramp keeps the exact linear fast path', () => {
  const rect = [10, 20, 30, 40];
  const red = rgba(255, 0, 0);
  const blue = rgba(0, 0, 255);
  const { api, state, screen } = createClient();
  api.reset(state);
  api.setFrame(colourQuadFrame({ rect, colours: [red, blue, red, blue] }));
  api.paint();
  const u = (20.5 - rect[0]) / (rect[2] - rect[0]);
  assertPremultiplied(screen.pixel(20, 30), [255 * (1 - u), 0, 255 * u, 255], 'horizontal ramp');

  api.reset(state);
  api.setFrame(colourQuadFrame({ rect, colours: [red, red, blue, blue] }));
  api.paint();
  const v = (30.5 - rect[1]) / (rect[3] - rect[1]);
  assertPremultiplied(screen.pixel(20, 30), [255 * (1 - v), 0, 255 * v, 255], 'vertical ramp');
});

test('a rotated two-colour ramp is not a device-space gradient', () => {
  const rect = [10, 20, 31, 41];
  const red = rgba(255, 0, 0);
  const blue = rgba(0, 0, 255);
  const transform = [0.866, -0.5, 30, 0.5, 0.866, 2, 0, 0, 1];
  const { api, state, screen } = createClient();
  api.reset(state);
  api.setFrame(frameOf(
    transformPushFrame(transform),
    colourQuadFrame({ rect, colours: [red, blue, red, blue] }),
    TRANSFORM_POP_FRAME,
  ));
  api.paint();
  for (const [u, v] of [[0.25, 0.3], [0.5, 0.5], [0.7, 0.2]]) {
    const point = projectPoint(transform, rect[0] + u * (rect[2] - rect[0]), rect[1] + v * (rect[3] - rect[1]));
    const x = Math.floor(point[0]), y = Math.floor(point[1]);
    const local = localCoordinate(transform, x, y);
    const lu = (local[0] - rect[0]) / (rect[2] - rect[0]);
    assertPremultiplied(
      screen.pixel(x, y), [255 * (1 - lu), 0, 255 * lu, 255], `rotated ramp at (${x},${y})`,
    );
  }
});
test('a projective textured quad with a uniform tint samples through the inverse map', () => {
  const transform = [1, 0.1, 10, 0.05, 1, 9, 0.004, 0.002, 1];
  const pixels = (x, y) => [(x * 43 + 7) & 0xff, (y * 59 + 31) & 0xff, ((x + y) * 17 + 3) & 0xff, 255];
  const rect = [10, 20, 31, 41];
  const { api, state, screen } = createClient();
  api.reset(state);
  uploadTexture(api, state, 'demo:projective-uniform', 8, 4, pixels);
  api.setFrame(frameOf(
    transformPushFrame(transform),
    quadFrame({
      colours: [OPAQUE_WHITE, OPAQUE_WHITE, OPAQUE_WHITE, OPAQUE_WHITE],
      uv: [0, 0, 8, 4], nominal: [8, 4], rect,
    }),
    TRANSFORM_POP_FRAME,
  ));
  api.paint();
  for (const [u, v] of [[0.3, 0.3], [0.5, 0.5], [0.7, 0.6]]) {
    const point = projectPoint(transform, rect[0] + u * (rect[2] - rect[0]), rect[1] + v * (rect[3] - rect[1]));
    const x = Math.floor(point[0]), y = Math.floor(point[1]);
    const local = localCoordinate(transform, x, y);
    const tu = ((local[0] - rect[0]) / (rect[2] - rect[0])) * 8;
    const tv = ((local[1] - rect[1]) / (rect[3] - rect[1])) * 4;
    const texel = texelOf({ width: 8, height: 4, pixels }, tu, tv, [8, 4]);
    assertPremultiplied(screen.pixel(x, y), [texel[0], texel[1], texel[2], texel[3]], `projective texel (${x},${y})`);
  }
});

// ---- shared triangle edges and outer antialiasing --------------------------------------------

const MESH_CORNERS = [[4, 4], [45, 4], [4, 45], [45, 4], [45, 45], [4, 45]];
const MESH_UVS = [[0, 0], [2, 0], [0, 2], [2, 0], [2, 2], [0, 2]];
const MESH_DIAGONAL = [[10, 38], [18, 30], [26, 22], [34, 14], [38, 10]];

test('a triangle mesh covers its shared edge once instead of compositing it twice', () => {
  const white = rgba(255, 255, 255, 128);
  const { api, state, screen } = createClient({ backdrop: 'rgb(0,0,255)' });
  api.reset(state);
  api.setFrame(triangleListFrame({
    points: MESH_CORNERS, colours: [white, white, white, white, white, white],
  }));
  api.paint();

  // Every pixel centre on the diagonal belongs to exactly one triangle, so the half-alpha field is
  // applied once: 255/2 over blue, not the 112 of two overlapping quarter composites.
  for (const [x, y] of MESH_DIAGONAL) {
    assertPremultiplied(screen.pixel(x, y), [128, 128, 255, 255], `shared edge at (${x},${y})`);
  }
  assertPremultiplied(screen.pixel(20, 30), [128, 128, 255, 255], 'interior of the first triangle');
  assertPremultiplied(screen.pixel(30, 20), [128, 128, 255, 255], 'interior of the second triangle');
  assertPremultiplied(screen.pixel(2, 2), [0, 0, 255, 255], 'backdrop outside the mesh');
});

test('a textured triangle mesh also covers its shared edge once', () => {
  const white = rgba(255, 255, 255, 128);
  const { api, state, screen } = createClient({ backdrop: 'rgb(0,0,255)' });
  api.reset(state);
  uploadTexture(api, state, 'demo:mesh', 2, 2, () => [255, 255, 255, 255]);
  api.setFrame(triangleListFrame({
    points: MESH_CORNERS, colours: [white, white, white, white, white, white],
    textured: true, uvs: MESH_UVS, nominal: [2, 2],
  }));
  api.paint();

  for (const [x, y] of MESH_DIAGONAL) {
    assertPremultiplied(screen.pixel(x, y), [128, 128, 255, 255], `textured shared edge (${x},${y})`);
  }
});

test('a triangle mesh keeps every shared vertex field under reversed winding', () => {
  const colours = [rgba(255, 20, 80, 255), rgba(20, 255, 40, 128), rgba(40, 80, 255, 0),
    rgba(240, 160, 40, 64)];
  // Both triangles are wound backwards, each vertex keeping its own colour. The shared diagonal is
  // therefore still the same local segment with the same colours at both ends.
  const points = [[4, 45], [45, 4], [4, 4], [4, 45], [45, 45], [45, 4]];
  const wire = [colours[2], colours[1], colours[0], colours[2], colours[3], colours[1]];
  const { api, state, screen } = createClient();
  api.reset(state);
  api.setFrame(triangleListFrame({ points, colours: wire }));
  api.paint();
  let checked = 0;

  for (const [x, y] of MESH_DIAGONAL) {
    const local = localCoordinate([1, 0, 0, 0, 1, 0, 0, 0, 1], x, y);
    // Either triangle names the same field on the shared edge; the first triangle owns the tie.
    const weights = barycentricOf([4, 45, 45, 4, 4, 4], local[0], local[1]);
    if (Math.min(...weights) < -1e-6) continue;
    const expected = triangleFieldAt([wire[0], wire[1], wire[2]], weights);
    assertPremultiplied(screen.pixel(x, y), expected, `reversed shared edge (${x},${y})`);
    checked++;
  }
  assert.ok(checked > 0, 'no reversed shared-edge sample was actually checked');
});

test('a triangle list still composites genuinely overlapping triangles in list order', () => {
  const white = rgba(255, 255, 255, 128);
  const { api, state, screen } = createClient({ backdrop: 'rgb(0,0,255)' });
  api.reset(state);
  api.setFrame(triangleListFrame({
    points: [[10, 10], [50, 10], [10, 50], [20, 20], [60, 20], [20, 60]],
    colours: [white, white, white, white, white, white],
  }));
  api.paint();

  const field = triangleFieldAt([white, white, white], [1 / 3, 1 / 3, 1 / 3]);
  const once = over(field, [0, 0, 255, 255]);
  const twice = over(field, once);
  assertPremultiplied(screen.pixel(35, 15), once, 'covered by the first triangle only');
  assertPremultiplied(screen.pixel(25, 25), twice, 'the overlap must composite twice in order');
});

test('a triangle outer edge gets fractional antialiased coverage', () => {
  const red = rgba(255, 0, 0);
  const blue = rgba(0, 0, 255);
  const { api, state, screen } = createClient();
  api.reset(state);
  // The left edge x = 20.5 cuts column 20 in half; columns 19 and 21 are fully outside and inside.
  // The third colour only keeps the record off the uniform fast path, which has no such coverage.
  api.setFrame(triangleListFrame({
    points: [[20.5, 10], [40, 10], [20.5, 40]], colours: [red, blue, red],
  }));
  api.paint();

  assertPremultiplied(screen.pixel(20, 20), [128, 0, 0, 128], 'half-covered edge pixel');
  assert.equal(screen.pixel(21, 20)[3], 255, 'the fully covered pixel is not opaque');
  const outside = premultipliedPixel(screen.pixel(19, 20));
  for (let channel = 0; channel < 4; channel++) {
    assert.equal(outside[channel], 0, `the pixel outside the edge is not empty (channel ${channel})`);
  }
});

test('a non-affine transform with an axis-aligned outline skips the linear ramp fast path', () => {
  const rect = [10, 20, 30, 40];
  const red = rgba(255, 0, 0);
  const blue = rgba(0, 0, 255);
  // Only the bottom-right element differs from identity. The projected outline is still an
  // axis-aligned rectangle, so the outline alone cannot prove the field is linear in device space.
  const transform = [1, 0, 0, 0, 1, 0, 0, 0, 2];
  const { api, state, screen } = createClient();
  api.reset(state);

  // Both fast paths must still exist for an actually affine transform.
  const identity = [1, 0, 0, 0, 1, 0, 0, 0, 1];
  api.setFrame(frameOf(
    transformPushFrame(identity),
    colourQuadFrame({ rect, colours: [red, blue, red, blue] }),
    TRANSFORM_POP_FRAME,
  ));
  api.paint();
  assert.equal(state.ops.filter((entry) => entry.op === 'createLinearGradient').length, 1,
    'the affine horizontal ramp lost its linear fast path');
  api.reset(state);
  api.setFrame(frameOf(
    transformPushFrame(identity),
    colourQuadFrame({ rect, colours: [red, red, blue, blue] }),
    TRANSFORM_POP_FRAME,
  ));
  api.paint();
  assert.equal(state.ops.filter((entry) => entry.op === 'createLinearGradient').length, 1,
    'the affine vertical ramp lost its linear fast path');
  api.reset(state);

  api.setFrame(frameOf(
    transformPushFrame(transform),
    colourQuadFrame({ rect, colours: [red, blue, red, blue] }),
    TRANSFORM_POP_FRAME,
  ));
  api.paint();
  assert.equal(state.ops.filter((entry) => entry.op === 'createLinearGradient').length, 0,
    'a non-affine horizontal ramp used the device-space gradient');
  const horizontal = localCoordinate(transform, 10, 15);
  const u = (horizontal[0] - rect[0]) / (rect[2] - rect[0]);
  assertPremultiplied(screen.pixel(10, 15), [255 * (1 - u), 0, 255 * u, 255], 'horizontal field');

  api.reset(state);
  api.setFrame(frameOf(
    transformPushFrame(transform),
    colourQuadFrame({ rect, colours: [red, red, blue, blue] }),
    TRANSFORM_POP_FRAME,
  ));
  api.paint();
  assert.equal(state.ops.filter((entry) => entry.op === 'createLinearGradient').length, 0,
    'a non-affine vertical ramp used the device-space gradient');
  const vertical = localCoordinate(transform, 10, 15);
  const v = (vertical[1] - rect[1]) / (rect[3] - rect[1]);
  assertPremultiplied(screen.pixel(10, 15), [255 * (1 - v), 0, 255 * v, 255], 'vertical field');
});

// ---- clip scopes -----------------------------------------------------------------------------
//
// The client's clip is the native Canvas clip, so these tests check the exact projected polygon and
// the push/pop bookkeeping rather than any clip arithmetic of their own. The reference samples come
// from the test's own matrix product and point-in-polygon test.

const CLIP_RED = rgba(255, 0, 0);
const CLIP_GREEN = rgba(0, 255, 0);
const CLIP_BLUE = rgba(0, 0, 255);

test('an invalid scissor consumes its push and restores the parent clip instead of stealing it', () => {
  const { api, state, screen } = createClient();
  api.reset(state);
  api.setFrame(frameOf(
    clipPushFrame([0, 0, 40, 40]),
    clipPushFrame([Number.NaN, 0, 40, 40]),
    colourQuadFrame({ rect: [0, 0, 200, 200], colours: [CLIP_RED, CLIP_RED, CLIP_RED, CLIP_RED] }),
    CLIP_POP_FRAME,
    colourQuadFrame({ rect: [0, 0, 200, 200], colours: [CLIP_GREEN, CLIP_GREEN, CLIP_GREEN, CLIP_GREEN] }),
    CLIP_POP_FRAME,
    colourQuadFrame({ rect: [80, 80, 100, 100], colours: [CLIP_BLUE, CLIP_BLUE, CLIP_BLUE, CLIP_BLUE] }),
  ));
  api.paint();

  assertPixel(screen.pixel(20, 20), [0, 255, 0, 255],
    'the draw under an invalid clip was visible, or the parent clip did not survive the pop');
  assertPixel(screen.pixel(50, 20), [0, 0, 0, 0],
    'the invalid pop restored one clip too far: the parent scissor was lost');
  assertPixel(screen.pixel(85, 85), [0, 0, 255, 255],
    'the draw after the outer pop is not unclipped');
});

test('a rotated scissor is the projected diamond, not its bounding box', () => {
  const { api, state, screen } = createClient();
  api.reset(state);
  const transform = [
    Math.SQRT1_2, -Math.SQRT1_2, 100,
    Math.SQRT1_2, Math.SQRT1_2, 100,
    0, 0, 1,
  ];
  // A local square around (100,100) becomes a diamond with vertices at 100±42.43. The inner
  // transform is pushed and popped inside the clip scope: it must not move the device-space clip.
  api.setFrame(frameOf(
    transformPushFrame(transform),
    clipPushFrame([-30, -30, 30, 30]),
    transformPushFrame([1, 0, 200, 0, 1, 200, 0, 0, 1]),
    TRANSFORM_POP_FRAME,
    TRANSFORM_POP_FRAME,
    colourQuadFrame({ rect: [0, 0, 200, 200], colours: [CLIP_RED, CLIP_RED, CLIP_RED, CLIP_RED] }),
    CLIP_POP_FRAME,
  ));
  api.paint();

  assertPixel(screen.pixel(100, 100), [255, 0, 0, 255], 'the diamond centre');
  assertPixel(screen.pixel(100, 65), [255, 0, 0, 255], 'a point inside the diamond near its top vertex');
  assertPixel(screen.pixel(60, 60), [0, 0, 0, 0],
    'a bounding-box corner outside the diamond was painted');
  assertPixel(screen.pixel(100, 50), [0, 0, 0, 0], 'a point outside the top vertex was painted');
});

test('nested projective-affine and affine-projective clips use each entry transform', () => {
  const cases = [
    ['projective parent, affine child',
      [1, 0.08, 20, 0.04, 1, 15, 0.002, 0.001, 1],
      rotationAround(100, 100, 0.35)],
    ['affine parent, projective child',
      rotationAround(100, 100, -0.3),
      [0.98, 0.12, 12, -0.08, 0.97, 18, 0.0015, 0.0008, 1]],
  ];
  const parentRect = [40, 40, 160, 160];
  const childRect = [60, 60, 140, 140];

  for (const [name, parent, child] of cases) {
    const { api, state, screen } = createClient();
    api.reset(state);
    api.setFrame(frameOf(
      transformPushFrame(parent),
      clipPushFrame(parentRect),
      transformPushFrame(child),
      clipPushFrame(childRect),
      TRANSFORM_POP_FRAME,
      TRANSFORM_POP_FRAME,
      colourQuadFrame({ rect: [0, 0, 200, 200], colours: [CLIP_RED, CLIP_RED, CLIP_RED, CLIP_RED] }),
      CLIP_POP_FRAME,
      CLIP_POP_FRAME,
    ));
    api.paint();

    const parentPolygon = projectedRect(parent, parentRect);
    const childPolygon = projectedRect(multiplyMatrix(parent, child), childRect);
    let checkedInside = 0;
    let checkedOutside = 0;
    for (let y = 2; y < 200; y += 5) {
      for (let x = 2; x < 200; x += 5) {
        const px = x + 0.5, py = y + 0.5;
        const painted = screen.pixel(x, y)[3] > 200;
        if (wellInside(parentPolygon, px, py) && wellInside(childPolygon, px, py)) {
          assert.ok(painted, `${name}: a sample well inside both clips was not painted at (${x},${y})`);
          checkedInside++;
        } else if (wellOutside(parentPolygon, px, py) || wellOutside(childPolygon, px, py)) {
          assert.ok(!painted, `${name}: a sample well outside a clip was painted at (${x},${y})`);
          checkedOutside++;
        }
      }
    }
    assert.ok(checkedInside > 5, `${name}: no well-inside samples were checked`);
    assert.ok(checkedOutside > 5, `${name}: no well-outside samples were checked`);
  }
});

test('invertible clips survive homogeneous rescaling and tiny affine scales', () => {
  const cases = [
    [[1e-5, 0, 0, 0, 1e-5, 0, 0, 0, 1e-5], [10, 10, 60, 60], [0, 0, 90, 90]],
    [[1e20, 0, 0, 0, 1e20, 0, 0, 0, 1e20], [10, 10, 60, 60], [0, 0, 90, 90]],
    [[1e-7, 0, 0, 0, 1e-7, 0, 0, 0, 1], [1e8, 1e8, 6e8, 6e8], [0, 0, 9e8, 9e8]],
  ];
  for (const [matrix, clip, rect] of cases) {
    const { api, state, screen } = createClient();
    api.reset(state);
    api.setFrame(frameOf(transformPushFrame(matrix), clipPushFrame(clip),
      colourQuadFrame({ rect, colours: [CLIP_RED, CLIP_RED, CLIP_RED, CLIP_RED] }),
      CLIP_POP_FRAME, TRANSFORM_POP_FRAME));
    api.paint();
    assertPixel(screen.pixel(30, 30), [255, 0, 0, 255], 'a valid scale must not turn the clip empty');
    assertPixel(screen.pixel(70, 70), [0, 0, 0, 0], 'the exact clip still excludes its exterior');
  }
});

test('disjoint and invalid nested clips suppress coverage and recover after each pop', () => {
  const inners = [
    ['disjoint', [100, 100, 150, 150]],
    ['unmeasured', [Number.NaN, 100, 150, 150]],
  ];
  for (const [name, inner] of inners) {
    const { api, state, screen } = createClient();
    api.reset(state);
    api.setFrame(frameOf(
      clipPushFrame([10, 10, 50, 50]),
      clipPushFrame(inner),
      colourQuadFrame({ rect: [0, 0, 200, 200], colours: [CLIP_RED, CLIP_RED, CLIP_RED, CLIP_RED] }),
      CLIP_POP_FRAME,
      colourQuadFrame({ rect: [0, 0, 200, 200], colours: [CLIP_GREEN, CLIP_GREEN, CLIP_GREEN, CLIP_GREEN] }),
      CLIP_POP_FRAME,
      colourQuadFrame({ rect: [60, 60, 80, 80], colours: [CLIP_BLUE, CLIP_BLUE, CLIP_BLUE, CLIP_BLUE] }),
    ));
    api.paint();

    assertPixel(screen.pixel(30, 30), [0, 255, 0, 255], `${name}: the outer clip did not recover after the pop`);
    assertPixel(screen.pixel(120, 120), [0, 0, 0, 0], `${name}: the disjoint inner clip let a draw through`);
    assertPixel(screen.pixel(70, 70), [0, 0, 255, 255], `${name}: the draw after the outer pop is clipped`);
    assertPixel(screen.pixel(5, 5), [0, 0, 0, 0], `${name}: a pixel outside every clip was painted`);
  }
});

test('empty, nonfinite and degenerate clip rectangles fail closed', () => {
  const rectangles = [
    ['NaN edge', [Number.NaN, 0, 40, 40]],
    ['infinite edge', [0, 0, Number.POSITIVE_INFINITY, 40]],
    ['zero width', [10, 0, 10, 40]],
    ['negative width', [40, 0, 10, 40]],
    ['zero height', [0, 10, 40, 10]],
    ['negative height', [0, 40, 40, 10]],
    ['overflow width', [-3e38, 0, 3e38, 40]],
    ['overflow height', [0, -3e38, 40, 3e38]],
  ];
  for (const [name, rect] of rectangles) {
    const { api, state, screen } = createClient();
    api.reset(state);
    api.setFrame(frameOf(
      clipPushFrame(rect),
      colourQuadFrame({ rect: [0, 0, 200, 200], colours: [CLIP_RED, CLIP_RED, CLIP_RED, CLIP_RED] }),
      CLIP_POP_FRAME,
    ));
    api.paint();
    assertPixel(screen.pixel(20, 20), [0, 0, 0, 0], `${name}: an invalid clip painted inside`);
    assertPixel(screen.pixel(5, 5), [0, 0, 0, 0], `${name}: an invalid clip painted outside`);
  }
});

test('a singular, nonfinite or eye-plane-crossing transform makes the clip empty', () => {
  const transforms = [
    ['singular scale', [0, 0, 0, 0, 1, 0, 0, 0, 1], [-100, -100, 100, 100]],
    ['zero bottom right', [1, 0, 0, 0, 1, 0, 0, 0, 0], [-100, -100, 100, 100]],
    ['nonfinite', [Number.NaN, 0, 0, 0, 1, 0, 0, 0, 1], [-100, -100, 100, 100]],
    ['eye plane', [1, 0, 0, 0, 1, 0, 0.1, 0, 1], [-100, -100, 100, 100]],
    ['zero w corner', [1, 0, 0, 0, 1, 0, 0.1, 0, 1], [-10, -100, 100, 100]],
  ];
  for (const [name, transform, rect] of transforms) {
    const { api, state, screen } = createClient();
    api.reset(state);
    api.setFrame(frameOf(
      transformPushFrame(transform),
      clipPushFrame(rect),
      TRANSFORM_POP_FRAME,
      colourQuadFrame({ rect: [0, 0, 200, 200], colours: [CLIP_RED, CLIP_RED, CLIP_RED, CLIP_RED] }),
      CLIP_POP_FRAME,
    ));
    api.paint();
    assertPixel(screen.pixel(20, 20), [0, 0, 0, 0], `${name}: an unusable transform produced a usable clip`);
  }
});

test('a clip limits text, triangles and the widget/item stand-ins without moving the clip', () => {
  const { api, state, screen } = createClient();
  api.reset(state);
  api.setFrame(frameOf(
    clipPushFrame([0, 0, 26, 48]),
    textFrame({ code: 0x2588, x: 20, y: 20, size: 20, colour: CLIP_RED }),
    CLIP_POP_FRAME,
  ));
  api.paint();
  assertPixel(screen.pixel(22, 25), [255, 0, 0, 255], 'text inside the clip was not painted');
  assertPixel(screen.pixel(30, 25), [0, 0, 0, 0], 'text glyph coverage escaped the clip');

  api.reset(state);
  api.setFrame(frameOf(
    clipPushFrame([0, 0, 50, 50]),
    colourTriangleFrame({ points: [[0, 0], [100, 0], [0, 100]], colours: [CLIP_RED, CLIP_RED, CLIP_RED] }),
    CLIP_POP_FRAME,
  ));
  api.paint();
  assertPixel(screen.pixel(10, 10), [255, 0, 0, 255], 'triangle inside the clip was not painted');
  assertPixel(screen.pixel(70, 10), [0, 0, 0, 0], 'triangle coverage escaped the clip');

  api.reset(state);
  uploadTexture(api, state, 'demo:clip-triangle', 8, 4, () => [208, 0, 208, 255]);
  api.setFrame(frameOf(
    clipPushFrame([0, 0, 50, 50]),
    triangleFrame({
      points: [[0, 0], [200, 0], [0, 200]],
      uvs: [[0, 0], [8, 0], [0, 4]],
      colours: [OPAQUE_WHITE, OPAQUE_WHITE, OPAQUE_WHITE],
    }),
    CLIP_POP_FRAME,
  ));
  api.paint();
  assertPixel(screen.pixel(10, 10), [208, 0, 208, 255], 'the textured triangle inside the clip was not sampled');
  assertPixel(screen.pixel(70, 10), [0, 0, 0, 0], 'the textured triangle escaped the clip');

  for (const [name, part] of [
    ['button', rectOnlyFrame(8, [0, 0, 200, 100], 0, -1)],
    ['slot', rectOnlyFrame(9)],
    ['container', rectOnlyFrame(10)],
    ['tooltip', rectOnlyFrame(11)],
    ['background', rectOnlyFrame(12)],
    ['item', itemFrame()],
  ]) {
    api.reset(state);
    api.setFrame(frameOf(
      clipPushFrame([0, 0, 100, 60]),
      part,
      CLIP_POP_FRAME,
    ));
    api.paint();
    assert.notDeepEqual(screen.pixel(50, 30), [0, 0, 0, 0], `${name}: the native stand-in was not drawn inside the clip`);
    assert.deepEqual(screen.pixel(150, 30), [0, 0, 0, 0], `${name}: the native stand-in escaped the clip`);
  }
});

test('a clip changes coverage only: textures, gradients and alpha layers keep their fields', () => {
  const { api, state, screen } = createClient();
  api.reset(state);
  uploadTexture(api, state, 'demo:clip-texture', 8, 4, () => [0, 0, 255, 255]);
  api.setFrame(frameOf(
    clipPushFrame([0, 0, 20, 40]),
    quadFrame({
      rect: [0, 0, 40, 40], uv: [0, 0, 8, 4], nominal: [8, 4],
      colours: [OPAQUE_WHITE, OPAQUE_WHITE, OPAQUE_WHITE, OPAQUE_WHITE],
    }),
    CLIP_POP_FRAME,
  ));
  api.paint();
  assertPixel(screen.pixel(10, 20), [0, 0, 255, 255], 'the textured draw inside the clip was not sampled');
  assertPixel(screen.pixel(30, 20), [0, 0, 0, 0], 'the textured draw escaped the clip');

  // A textured quad with a varying tint takes the per-pixel path; clipping must not change it.
  const tints = [
    rgba(255, 255, 255), rgba(255, 40, 40), rgba(40, 255, 40), rgba(40, 40, 255),
  ];
  const textured = {
    rect: [0, 0, 40, 40], uv: [0, 0, 8, 4], nominal: [8, 4], colours: tints,
  };
  api.reset(state);
  api.setFrame(quadFrame(textured));
  api.paint();
  const texturedReference = screen.pixel(10, 20);
  api.reset(state);
  api.setFrame(frameOf(clipPushFrame([0, 0, 20, 40]), quadFrame(textured), CLIP_POP_FRAME));
  api.paint();
  assert.deepEqual(screen.pixel(10, 20), texturedReference, 'the clipped textured gradient changed its field');
  assert.deepEqual(screen.pixel(30, 20), [0, 0, 0, 0], 'the textured gradient escaped the clip');

  const rect = [0, 0, 40, 40];
  const colours = [
    rgba(255, 20, 80, 255), rgba(20, 255, 40, 128), rgba(40, 80, 255, 0), rgba(240, 160, 40, 64),
  ];
  api.reset(state);
  api.setFrame(frameOf(
    clipPushFrame([0, 0, 20, 40]),
    colourQuadFrame({ rect, colours }),
    CLIP_POP_FRAME,
  ));
  api.paint();
  const u = (10.5 - rect[0]) / (rect[2] - rect[0]);
  const v = (20.5 - rect[1]) / (rect[3] - rect[1]);
  assertPremultiplied(screen.pixel(10, 20), quadFieldAt(colours, u, v), 'the clipped gradient restarted or rescaled');
  assert.deepEqual(screen.pixel(30, 20), [0, 0, 0, 0], 'the gradient escaped the clip');

  const lower = rgba(255, 0, 0, 128);
  const upper = rgba(0, 0, 255, 128);
  api.reset(state);
  api.setFrame(frameOf(
    clipPushFrame([0, 0, 20, 40]),
    colourQuadFrame({ rect, colours: [lower, lower, lower, lower] }),
    colourQuadFrame({ rect, colours: [upper, upper, upper, upper] }),
    CLIP_POP_FRAME,
  ));
  api.paint();
  const expected = over(premultipliedColour(upper), premultipliedColour(lower));
  assertPremultiplied(screen.pixel(10, 20), expected, 'two alpha layers under a clip did not composite in order');
  assert.deepEqual(screen.pixel(30, 20), [0, 0, 0, 0], 'an alpha layer escaped the clip');
});

// ---- real browser acceptance -----------------------------------------------------------------
//
// The model above is a Canvas subset, not a browser. This case runs the same shipped client.js in an
// installed headless Chrome and reads real canvas pixels, including real antialiasing and text. The
// fixture is portable: it loads client.js relative to itself and stubs only the WebSocket, so it can
// be opened by any Chromium build. If no browser is installed the case skips rather than pretending.

function findChrome() {
  const candidates = [
    process.env.CHROME_PATH,
    process.env.CHROME_BIN,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    process.env.LOCALAPPDATA
      ? path.join(process.env.LOCALAPPDATA, 'Google/Chrome/Application/chrome.exe')
      : null,
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ];
  return candidates.find((candidate) => candidate && fs.existsSync(candidate)) || null;
}

function unescapeHtml(text) {
  return text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&');
}

test('the shipped client clips exactly in an installed headless Chrome', (t) => {
  const chrome = findChrome();
  if (!chrome) {
    t.skip('no Chrome/Chromium installation was found; the portable fixture is clip-acceptance.html');
    return;
  }

  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'kotlinmcui-clip-'));
  try {
    const fixture = path.join(__dirname, 'clip-acceptance.html');
    const run = spawnSync(chrome, [
      '--headless=new',
      '--disable-gpu',
      '--no-sandbox',
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-background-networking',
      '--disable-sync',
      '--disable-extensions',
      '--hide-scrollbars',
      '--window-size=640,480',
      '--allow-file-access-from-files',
      '--user-data-dir=' + profile,
      '--dump-dom',
      pathToFileURL(fixture).href,
    ], {
      encoding: 'utf8',
      windowsHide: true,
      timeout: 120000,
      maxBuffer: 64 * 1024 * 1024,
    });

    assert.equal(run.error, undefined, 'launching Chrome failed: ' + String(run.error));
    assert.equal(run.status, 0, 'Chrome exited ' + run.status + ': ' + (run.stderr || '').slice(0, 4000));
    const reportText = /<pre id="result">([\s\S]*?)<\/pre>/.exec(run.stdout || '');
    assert.ok(reportText, 'the fixture did not report a result; DOM was: ' + (run.stdout || '').slice(0, 4000));
    const report = JSON.parse(unescapeHtml(reportText[1]));
    const failures = report.results
      .filter((entry) => !entry.ok)
      .map((entry) => entry.name + ': ' + entry.detail)
      .join('\n');
    assert.equal(report.fail, 0, 'headless Chrome clip acceptance failed:\n' + failures);
    assert.ok(report.pass >= 6, 'only ' + report.pass + ' acceptance checks ran');
  } finally {
    fs.rmSync(profile, { recursive: true, force: true });
  }
});
