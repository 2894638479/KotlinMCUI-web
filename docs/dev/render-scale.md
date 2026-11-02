# Web 后端的输出尺度与坐标

核心的完整契约写在核心库 `docs/dev/render-scale.md`。这一页只记录 **web 后端** 把浏览器平台事实接到
那份契约上的位置和取舍，不重复核心的定义。

## 输出目标

浏览器的输出目标是 **canvas backing store**，不是 CSS 像素，也不是显示器的物理像素。`client.js`
在 `resizeCanvas` 里按 `floor(window.innerWidth * devicePixelRatio)` 计算它，并设下限 320×240；
canvas 的 CSS 尺寸则是窗口的逻辑尺寸。两者可以不同，例如 100×100 的窗口在 dpr 2 下得到 320×240
的 backing（下限生效），而 CSS 仍是 100×100。后端把 **backing 尺寸** 作为帧尺寸发送：

```text
resize <canvas.width> <canvas.height>
```

`WebBackend.frame(width, height)` 收到的就是这两个值。任何需要“输出有多大”的地方——根矩形、根尺度、
裁剪、截图——都以它为准，而不是再乘一次 DPR。

## 根矩形和根尺度

`DslRootRenderer.guiScale` 已删除。每帧在 `root.component.render()` 之前，`WebBackend.frame` 写入：

- `root.rect = (0, 0, width, height)`，单位为输出像素；
- `root.scale = WebScale.of(width, height)`。

`root.scale` 没有隐式的 1.0：页面可以在输出就绪前创建和挂载，但第一次构建前必须由后端显式写入。
`WebScale` 是本后端自己的策略，保留 320×240 基准和 1.0 下限，并保持分数尺度。零尺寸或最小化窗口
仍得到有限且大于零的尺度；那个尺寸由空矩形表达，而不是把 scale 设成 0 来表示暂停。

## 指针坐标

`position(event)` 把 DOM 坐标换算到输出像素：

```text
x = (event.clientX - bounds.left) * canvas.width  / bounds.width
y = (event.clientY - bounds.top)  * canvas.height / bounds.height
```

X、Y 各用各自的实际比例，因此非等比缩放、分数 DPR、下限截断和取整都会自然得到正确结果。结果不
四舍五入：框架的 `Position`、`Rect` 和绘制坐标本来就是分数输出像素。显示的矩形为零、非有限或没有
可用 backing 时，移动和按下被丢弃，不会发送用编造比例算出的坐标，也不会发送 NaN。

已经到达服务端的按下仍必须收到释放：画布在按住期间 resize 到零或隐藏后，`pointerup` 的坐标可能
无法映射，此时不发送新的 move，也不编造坐标，只发送对应的 `button ... up`，服务端沿用最后一次有效
位置。指针捕获随之释放；`pointercancel` 和 `lostpointercapture` 走同一条清理路径，避免按键卡住。

输入、绘制和裁剪因此共用同一坐标空间：映射后的坐标直接进入 `WebInput.mouse`，布局写出的图元坐标
和 `withScissor` 的矩形也是同一单位。`withTransform` 继续只负责几何映射和命中测试的逆变换，后端不
再对已经由核心算出的坐标乘尺度。

## 截图

`capture` 让浏览器 `canvas.toBlob()` 导出 canvas，因此 PNG 的像素尺寸就是当前 backing 尺寸，也就是
最近一次 `resize` 告诉服务端的帧尺寸。截图不带 CSS 背景；页面自己的底色在重放帧时先铺满 canvas，
见 `docs/dev/web-backend.md` 的传输层一节。

## 验收

- JVM：`RenderScaleTest` 走真实 `WebBackend.frame` 路径，覆盖首次构建前写入、resize 后重建、零尺寸
  以及未初始化/非法/帧内写入的尺度。
- 浏览器客户端：`src/test/js/primitive-input.test.cjs` 用 VM 执行实际发布的 `client.js`，覆盖 backing
  与 CSS 的实际比例、X/Y 非等比、分数坐标、零/非法显示矩形、最小 backing 与取整、截图尺寸，
  “输入坐标 → 绘制 → 裁剪”使用同一输出像素，以及按下后画布不可映射时的 pointerup/pointercancel
  释放与捕获清理。
- 无浏览器环境跳过 headless Chrome 那一条时，测试会明确 skip；本机运行结果记录在构建输出里。
