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
const path = require('node:path');
const test = require('node:test');
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
  fillText() {}

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
  }
  getContext() { return this.context; }
  getBoundingClientRect() { return { left: 0, top: 0 }; }
  addEventListener() {}
  setPointerCapture() {}
  toBlob() {}
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
    addEventListener() {},
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
