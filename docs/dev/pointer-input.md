# 浏览器指针输入

客户端只监听 Pointer Events，不同时监听兼容 mouse/click。每个 DOM pointerId 分别保存 buttons、悬停能力与表面捕获；服务端把连接 id 放入 PointerId 高 32 位，DOM id 放入低 32 位，两个连接的同号触点互不覆盖。连接编号在进程内连续分配，服务器重建不复用旧编号。按键与修饰键也按连接保存，blur 只释放自己的状态。

线协议为 `pointer <down|move|up> <id> <mouse|touch|pen> <x> <y> <DOM button> <DOM buttons mask> <canHover 0|1> <pressure> <modifiers>`；Cancel/Leave 分别为 `pointer cancel <id>`、`pointer leave <id>`。位置按 backing/CSS 的真实逐轴比值映射，保留小数。滚轮为 `wheel <x> <y> <dx> <dy> <modifiers>`。

DOM pointerdown/up 只代表第一/最后按键变化。客户端比较 buttons 位掩码，把中途的 pointermove 正规化为追加 Down/非最终 Up；压力保留真实值，非最终 Up 不强制归零，原地压力 Move 不合并。无压感回退由事件缺失压力字段时采用按住 0.5、空按钮 0。

每个接触独立调用 setPointerCapture。取消、意外 lostpointercapture、blur、连接关闭与无法映射的活动表面都使用 Cancel，不能合成会激活点击的 Up。正常最终 Up 主动释放捕获时先清捕获标志，避免随后 lostpointercapture 重复取消。Leave 只清指向，保持拖拽。

鼠标明确可悬停、触摸不可悬停。笔在接触前观察到 hover 后保持可悬停；首次只看到接触的笔在该序列使用 canHover=false，抬起后新的真实 hover 可建立空闲悬停序列。浏览器没有可靠的每支笔能力查询，本适配不从 pressure==0.5 推断压感能力。

JVM 真实 socket 测试覆盖连接身份隔离、压感和单来源取消。Node 对实际 client.js 测试多指、多键、压力、Leave、失去映射与丢捕获；其中绘制用例还运行安装的 headless Chrome。这些均不等于原生多点/笔压硬件验收。组合事件桥接已有键盘焦点，不新增系统软键盘。

组合协议为 `compositionstart`、`preedit <URI 编码文本>`、`compositionend <URI 编码提交文本>`。开始时记录文本焦点；切换焦点后迟到的结束数据丢弃，不能提交到新输入框。断开或失焦结束该连接的组合。
