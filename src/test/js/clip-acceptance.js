// Acceptance harness for clip-acceptance.html.
//
// Runs inside the page after the shipped ../../main/resources/web/client.js has executed. Every
// frame goes through client.js's own KEYFRAME decode and paint path; the assertions read the real
// canvas's pixels. Nothing here re-implements the painter, the projection or the clip: the reference
// polygons used by the nested cases are computed independently, from the same wire matrices the
// frame carries, only to decide whether a sample is comfortably inside or outside.
(function () {
  'use strict';

  const targetCanvas = canvas;
  const targetContext = ctx;
  const results = [];

  function bits(value) {
    const buffer = new ArrayBuffer(4);
    new DataView(buffer).setFloat32(0, value, true);
    return new DataView(buffer).getInt32(0, true);
  }

  function rgba(r, g, b, a) {
    return (((r << 24) | (g << 16) | (b << 8) | (a === undefined ? 255 : a)) | 0);
  }

  const RED = rgba(255, 0, 0);
  const GREEN = rgba(0, 255, 0);
  const BLUE = rgba(0, 0, 255);
  const WHITE = -1;

  const OPS = {
    QUAD: 1, TRIANGLES: 2, CLIP_PUSH: 3, CLIP_POP: 4, TRANSFORM_PUSH: 5, TRANSFORM_POP: 6,
    TEXT: 7, BUTTON: 8, SLOT: 9, CONTAINER: 10, TOOLTIP: 11, DEFAULT_BACKGROUND: 12, ITEM: 13,
  };

  function clip(rect) {
    return [OPS.CLIP_PUSH, bits(rect[0]), bits(rect[1]), bits(rect[2]), bits(rect[3])];
  }
  const POP_CLIP = [OPS.CLIP_POP];

  function transformPush(m) {
    return [OPS.TRANSFORM_PUSH].concat(m.map(bits));
  }
  const POP_TRANSFORM = [OPS.TRANSFORM_POP];

  function colourQuad(rect, colours) {
    return [OPS.QUAD, bits(rect[0]), bits(rect[1]), bits(rect[2]), bits(rect[3])]
      .concat(colours).concat([-1]);
  }

  function texturedQuad(asset, rect, uv, nominal, colours) {
    return [OPS.QUAD, bits(rect[0]), bits(rect[1]), bits(rect[2]), bits(rect[3])]
      .concat(colours).concat([asset])
      .concat(uv.map(bits)).concat(nominal.map(bits));
  }

  function text(code, x, y, size, colour) {
    return [OPS.TEXT, code, bits(x), bits(y), 0, colour, bits(x), bits(x + size * 0.6), bits(size)];
  }

  function button(rect, colour) {
    return [OPS.BUTTON, bits(rect[0]), bits(rect[1]), bits(rect[2]), bits(rect[3]), 0, colour];
  }

  function item(rect) {
    return [OPS.ITEM, bits(rect[0]), bits(rect[1]), bits(rect[2]), bits(rect[3]), 0, 1, 0, 0];
  }

  function triangle(points, colour) {
    return [OPS.TRIANGLES, 3, 0, -1]
      .concat(points.reduce((out, point) => out.concat([bits(point[0]), bits(point[1])]), []))
      .concat([colour, colour, colour]);
  }

  function concat(parts) {
    return parts.reduce((out, part) => out.concat(part), []);
  }

  /** Send one KEYFRAME through the client's real message handler and let it paint synchronously. */
  function sendFrame(parts) {
    const words = concat(parts);
    const buffer = new ArrayBuffer(9 + words.length * 4);
    const view = new DataView(buffer);
    view.setUint8(0, 0x4b);
    view.setUint32(5, words.length, true);
    for (let i = 0; i < words.length; i++) view.setInt32(9 + i * 4, words[i], true);
    socket.onmessage({ data: buffer });
  }

  function pixel(x, y) {
    return Array.from(targetContext.getImageData(x, y, 1, 1).data);
  }

  function alphaAt(x, y) {
    return pixel(x, y)[3];
  }

  function requirePainted(x, y, what) {
    const a = alphaAt(x, y);
    if (!(a > 200)) throw new Error(`${what}: expected paint at (${x},${y}), alpha was ${a}`);
  }

  function requireEmpty(x, y, what) {
    const a = alphaAt(x, y);
    if (!(a < 10)) throw new Error(`${what}: expected no coverage at (${x},${y}), alpha was ${a}`);
  }

  function requireClose(actual, expected, tolerance, what) {
    for (let channel = 0; channel < 3; channel++) {
      if (Math.abs(actual[channel] - expected[channel]) > tolerance) {
        throw new Error(`${what}: channel ${channel} is ${actual[channel]}, expected ${expected[channel]} (${actual} vs ${expected})`);
      }
    }
    if (Math.abs(actual[3] - expected[3]) > tolerance) {
      throw new Error(`${what}: alpha ${actual[3]}, expected ${expected[3]}`);
    }
  }

  function check(name, body) {
    try {
      body();
      results.push({ name, ok: true });
    } catch (error) {
      results.push({ name, ok: false, detail: String(error && error.message ? error.message : error) });
    }
  }

  // ---- independent reference arithmetic --------------------------------------------------------

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

  function projection(m, x, y) {
    const w = m[6] * x + m[7] * y + m[8];
    return [
      (m[0] * x + m[1] * y + m[2]) / w,
      (m[3] * x + m[4] * y + m[5]) / w,
    ];
  }

  function projectedRect(m, rect) {
    return [
      projection(m, rect[0], rect[1]), projection(m, rect[2], rect[1]),
      projection(m, rect[2], rect[3]), projection(m, rect[0], rect[3]),
    ];
  }

  function rotationAround(cx, cy, radians) {
    const c = Math.cos(radians), s = Math.sin(radians);
    return [c, -s, cx - c * cx + s * cy, s, c, cy - s * cx - c * cy, 0, 0, 1];
  }

  function insidePolygon(polygon, x, y) {
    let result = false;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      const xi = polygon[i][0], yi = polygon[i][1];
      const xj = polygon[j][0], yj = polygon[j][1];
      if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) result = !result;
    }
    return result;
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

  function wellInside(polygon, x, y, margin) {
    return insidePolygon(polygon, x, y) && distanceToPolygon(polygon, x, y) > margin;
  }

  function wellOutside(polygon, x, y, margin) {
    return !insidePolygon(polygon, x, y) && distanceToPolygon(polygon, x, y) > margin;
  }

  const WIDTH = targetCanvas.width;
  const HEIGHT = targetCanvas.height;
  const CX = Math.floor(WIDTH / 2);
  const CY = Math.floor(HEIGHT / 2);

  // ---- a 45-degree diamond, not its bounding box -----------------------------------------------

  check('a 45-degree scissor is the projected diamond', function () {
    const c = Math.SQRT1_2;
    const transform = [c, -c, CX, c, c, CY, 0, 0, 1];
    sendFrame([
      transformPush(transform),
      clip([-30, -30, 30, 30]),
      transformPush([1, 0, 200, 0, 1, 200, 0, 0, 1]),
      POP_TRANSFORM,
      POP_TRANSFORM,
      colourQuad([0, 0, WIDTH, HEIGHT], [RED, RED, RED, RED]),
      POP_CLIP,
    ]);
    requirePainted(CX, CY, 'diamond centre');
    requirePainted(CX, CY - 35, 'diamond interior near its top vertex');
    requireEmpty(CX - 30, CY - 30, 'a bounding-box corner outside the diamond');
    requireEmpty(CX, CY - 50, 'a point outside the top vertex');
  });

  // ---- nested clips under projective/affine parents --------------------------------------------

  check('nested projective-affine clips use each entry transform', function () {
    testNested(
      'projective parent, affine child',
      [1, 0.08, 20, 0.04, 1, 15, 0.002, 0.001, 1],
      rotationAround(100, 100, 0.35),
    );
  });

  check('nested affine-projective clips use each entry transform', function () {
    testNested(
      'affine parent, projective child',
      rotationAround(100, 100, -0.3),
      [0.98, 0.12, 12, -0.08, 0.97, 18, 0.0015, 0.0008, 1],
    );
  });

  function testNested(name, parent, child) {
    const parentRect = [40, 40, 160, 160];
    const childRect = [60, 60, 140, 140];
    sendFrame([
      transformPush(parent),
      clip(parentRect),
      transformPush(child),
      clip(childRect),
      POP_TRANSFORM,
      POP_TRANSFORM,
      colourQuad([0, 0, 200, 200], [RED, RED, RED, RED]),
      POP_CLIP,
      POP_CLIP,
    ]);

    const parentPolygon = projectedRect(parent, parentRect);
    const childPolygon = projectedRect(multiplyMatrix(parent, child), childRect);
    let checkedInside = 0;
    let checkedOutside = 0;
    for (let y = 2; y < 200; y += 5) {
      for (let x = 2; x < 200; x += 5) {
        const px = x + 0.5, py = y + 0.5;
        const painted = alphaAt(x, y) > 200;
        if (wellInside(parentPolygon, px, py, 4) && wellInside(childPolygon, px, py, 4)) {
          if (!painted) throw new Error(`${name}: a sample well inside both clips is empty at (${x},${y})`);
          checkedInside++;
        } else if (wellOutside(parentPolygon, px, py, 4) || wellOutside(childPolygon, px, py, 4)) {
          if (painted) throw new Error(`${name}: a sample well outside a clip is painted at (${x},${y})`);
          checkedOutside++;
        }
      }
    }
    if (checkedInside < 5) throw new Error(`${name}: no well-inside samples were checked`);
    if (checkedOutside < 5) throw new Error(`${name}: no well-outside samples were checked`);
  }

  // ---- disjoint, invalid and nested-lifetime cases ---------------------------------------------

  for (const inner of [
    ['disjoint', [200, 200, 300, 300]],
    ['unmeasured', [Number.NaN, 200, 300, 300]],
  ]) {
    check('a ' + inner[0] + ' inner clip suppresses coverage and the parent recovers', function () {
      sendFrame([
        clip([40, 40, 140, 140]),
        clip(inner[1]),
        colourQuad([0, 0, WIDTH, HEIGHT], [RED, RED, RED, RED]),
        POP_CLIP,
        colourQuad([0, 0, WIDTH, HEIGHT], [GREEN, GREEN, GREEN, GREEN]),
        POP_CLIP,
        colourQuad([300, 300, 340, 340], [BLUE, BLUE, BLUE, BLUE]),
      ]);
      requirePainted(80, 80, inner[0] + ': the outer clip did not recover after the pop');
      requireEmpty(250, 250, inner[0] + ': the inner clip let a draw through');
      requirePainted(320, 320, inner[0] + ': the draw after the outer pop is clipped');
      requireEmpty(20, 20, inner[0] + ': a pixel outside every clip was painted');
    });
  }

  check('an empty parent clip stays empty through a valid child', function () {
    sendFrame([
      clip([Number.NaN, 0, 10, 10]),
      clip([40, 40, 140, 140]),
      colourQuad([0, 0, WIDTH, HEIGHT], [RED, RED, RED, RED]),
      POP_CLIP,
      POP_CLIP,
      colourQuad([300, 300, 340, 340], [BLUE, BLUE, BLUE, BLUE]),
    ]);
    requireEmpty(80, 80, 'a valid child clip reopened an empty parent');
    requirePainted(320, 320, 'the scopes did not close after an empty parent');
  });

  // ---- clip changes coverage only: texture, gradient, alpha, text and widgets -------------------

  const texture = document.createElement('canvas');
  texture.width = 8;
  texture.height = 4;
  const textureContext = texture.getContext('2d');
  textureContext.fillStyle = '#0000ff';
  textureContext.fillRect(0, 0, 8, 4);
  assets[7] = 'fixture:clip-texture';
  images['fixture:clip-texture'] = texture;

  check('a clipped texture samples the same field as an unclipped one', function () {
    const rect = [0, 0, 200, 200];
    const uv = [0, 0, 8, 4];
    const nominal = [8, 4];
    sendFrame([texturedQuad(7, rect, uv, nominal, [WHITE, WHITE, WHITE, WHITE])]);
    const reference = pixel(50, 70);
    sendFrame([
      clip([0, 0, 100, 200]),
      texturedQuad(7, rect, uv, nominal, [WHITE, WHITE, WHITE, WHITE]),
      POP_CLIP,
    ]);
    requireClose(pixel(50, 70), reference, 3, 'the clipped texture changed its field');
    requireEmpty(150, 70, 'the textured draw escaped the clip');

    const tints = [
      rgba(255, 255, 255), rgba(255, 40, 40), rgba(40, 255, 40), rgba(40, 40, 255),
    ];
    sendFrame([texturedQuad(7, rect, uv, nominal, tints)]);
    const texturedGradient = pixel(50, 70);
    sendFrame([
      clip([0, 0, 100, 200]),
      texturedQuad(7, rect, uv, nominal, tints),
      POP_CLIP,
    ]);
    requireClose(pixel(50, 70), texturedGradient, 3, 'the clipped textured gradient changed its field');
    requireEmpty(150, 70, 'the textured gradient escaped the clip');
  });

  check('a clipped gradient and alpha layers keep their fields', function () {
    const rect = [0, 0, 200, 200];
    const colours = [
      rgba(255, 20, 80, 255), rgba(20, 255, 40, 128), rgba(40, 80, 255, 0), rgba(240, 160, 40, 64),
    ];
    sendFrame([colourQuad(rect, colours)]);
    const gradient = pixel(50, 70);
    sendFrame([clip([0, 0, 100, 200]), colourQuad(rect, colours), POP_CLIP]);
    requireClose(pixel(50, 70), gradient, 3, 'the clipped gradient restarted or rescaled');
    requireEmpty(150, 70, 'the gradient escaped the clip');

    const lower = rgba(255, 0, 0, 128);
    const upper = rgba(0, 0, 255, 128);
    sendFrame([colourQuad(rect, [lower, lower, lower, lower]), colourQuad(rect, [upper, upper, upper, upper])]);
    const layered = pixel(50, 70);
    sendFrame([
      clip([0, 0, 100, 200]),
      colourQuad(rect, [lower, lower, lower, lower]),
      colourQuad(rect, [upper, upper, upper, upper]),
      POP_CLIP,
    ]);
    requireClose(pixel(50, 70), layered, 3, 'two alpha layers under a clip did not composite in order');
    requireEmpty(150, 70, 'an alpha layer escaped the clip');
  });

  check('a clip reaches text and the widget/item stand-ins', function () {
    const size = 80;
    const x = 100;
    const y = 100;
    sendFrame([text(0x2588, x, y, size, RED)]);
    let left = null;
    let right = null;
    for (let py = y; py < y + size && (!left || !right); py++) {
      for (let px = x; px < x + size * 1.5; px++) {
        if (alphaAt(px, py) <= 200) continue;
        if (px < 120 && left === null) left = [px, py];
        if (px >= 125 && right === null) right = [px, py];
      }
    }
    if (!left || !right) throw new Error('the text stand-in did not paint glyph coverage around the clip edge');
    sendFrame([clip([0, 0, 120, 200]), text(0x2588, x, y, size, RED), POP_CLIP]);
    requirePainted(left[0], left[1], 'text inside the clip');
    requireEmpty(right[0], right[1], 'text coverage escaped the clip');

    sendFrame([clip([0, 0, 120, 200]), button([0, 0, 200, 100], rgba(80, 80, 80)), POP_CLIP]);
    requirePainted(50, 50, 'button inside the clip');
    requireEmpty(150, 50, 'button escaped the clip');

    sendFrame([clip([0, 0, 120, 200]), item([0, 0, 200, 100]), POP_CLIP]);
    requirePainted(50, 50, 'item inside the clip');
    requireEmpty(150, 50, 'item escaped the clip');

    sendFrame([clip([0, 0, 100, 100]), triangle([[0, 0], [200, 0], [0, 200]], RED), POP_CLIP]);
    requirePainted(20, 20, 'triangle inside the clip');
    requireEmpty(120, 20, 'triangle escaped the clip');
  });

  // ---- report ---------------------------------------------------------------------------------

  const failures = results.filter(function (entry) { return !entry.ok; });
  document.title = failures.length === 0 ? 'CLIP-PASS' : 'CLIP-FAIL';
  document.getElementById('result').textContent = JSON.stringify({
    pass: results.length - failures.length,
    fail: failures.length,
    results: results,
  });
})();
