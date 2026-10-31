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

  createLinearGradient() { return { addColorStop() {} }; }

  createPattern(image, repetition) {
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

  putImageData(image, x, y) {
    this.lastImageData = image;
    for (let row = 0; row < image.height; row++) {
      for (let column = 0; column < image.width; column++) {
        const at = ((y + row) * this.surface.width + (x + column)) * 4;
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
  defineAsset, imageOf, paint, strideAt, triangleField, setFrame(value) { frame = value; },
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

function quadFrame({ asset = 2, uv = [0, 0, 8, 4], nominal = [8, 4], colours = [-1, -1, -1, -1] } = {}) {
  return new Int32Array([
    1,
    bits(10), bits(20), bits(30), bits(40),
    colours[0], colours[1], colours[2], colours[3],
    asset,
    bits(uv[0]), bits(uv[1]), bits(uv[2]), bits(uv[3]),
    bits(nominal[0]), bits(nominal[1]),
  ]);
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
