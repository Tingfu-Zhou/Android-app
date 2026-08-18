# Xcup 延迟优化同步单（Android → iOS 移植对照）

> 第一层（纯逻辑，不动模型）修改已在 Android 端完成。本单按移植顺序列出每处改动的规则、
> 位置与 iOS 落地要点，末尾附明确「保持不变」的部分与验证清单。

| 项目 | 值 |
|---|---|
| 仓库 | `Tingfu-Zhou/xcup-android` |
| 分支 | `claude/xcup-video-latency-accuracy-iqu52w` |
| 提交 | `82d0e6b`（第一层改动）→ `a64ba2a`（启动仲裁修正） |
| 日期 | 2026-08-18 |
| 改动文件 | `VideoProcessActivity.java`、`OnlineAnalysisActivity.java`、`AudioInferenceHelper.java` |
| 适用范围 | **离线模式**（VideoProcessActivity，含网页视频模式）＋ **在线模式**（OnlineAnalysisActivity） |

---

## 背景（供不了解本项目的人阅读）

Xcup 是智能飞机杯的配套 app。它一边分析视频画面（MobileNetV3Small + Temporal Average
Pooling，三分类 `[normal_plot, oral, sex]`，输入 12 帧 × 160×160），一边分析音频
（YAMNet + 微调分类器，三分类 `[sex, oral, noise]`，输入 2 秒 16 kHz），把两者融合成
「转 / 不转」，再经蓝牙下发给设备；音频响度另外映射成 0–8 档强度。

三条流水线（本地视频 / 在线屏幕捕获 / 网页视频）分析逻辑一致，代码分布在
`VideoProcessActivity`（离线，兼网页模式）与 `OnlineAnalysisActivity`（在线）两个类里，
**两处几乎是复制关系，改动必须成对进行**。

本轮要解决的问题是**动作识别延迟明显、转场处误判多**。诊断结论是延迟由六层串联叠加
（模型窗口惯性、推理节拍、融合节拍、平滑窗口投票、蓝牙状态机、启动死区），其中
「10 秒平滑窗口 + 蓝牙状态机」属于重复的防抖保护；误判的首要原因是阈值加在 oral/sex
合并*之前*。第一层只改逻辑与参数，不重训模型。

---

## 参数速查

除音频死区外，所有改动在两个 Activity 中完全一致；iOS 端对应的两条流水线同样都要改。

| 常量 / 参数 | 旧值 | 新值 | 说明 |
|---|---|---|---|
| `MAIN_FUSION_INTERVAL` | 1000 ms | **500 ms** | 融合循环节拍 |
| `VIDEO_INFERENCE_INTERVAL` | 1000 ms | **500 ms** | 视频推理步长（抽帧仍 250 ms） |
| `SMOOTH_WINDOW_SIZE` | 10 | **6** | 6 条 × 500 ms ≈ 3 秒 |
| `SMOOTH_MIN_COUNT` | 字面量 3 | **常量 3** | 投票最少出现次数（新增常量，值不变） |
| `VIDEO_THRESHOLD_NORMAL / _ACTION` | 0.5 / 0.75 | **删除** | 被分组阈值取代 |
| `VIDEO_DO_PROB_THRESHOLD` | — | **0.65** | P(oral)+P(sex) 判「转」 |
| `VIDEO_PLOT_PROB_THRESHOLD` | — | **0.60** | P(plot) 判「不转」 |
| `AUDIO_THRESHOLD_ACTION / _NOISE` | 0.5 / 0.6 | **删除** | 被分组阈值取代 |
| `AUDIO_DO_PROB_THRESHOLD` | — | **0.55** | P(sex)+P(oral) 判「转」 |
| `AUDIO_NOISE_PROB_THRESHOLD` | — | **0.60** | P(noise) 判「不转」 |
| `FUSION_FASTPATH_VIDEO_CONF / _AUDIO_CONF` | — | **0.70 / 0.70** | 一致性快通道门槛 |
| `VIDEO_PLOT_CONFLICT_CONF` | — | **0.80** | 构成「强冲突」所需的视频剧情置信度 |
| `FUSION_CONFLICT_MARGIN` | — | **0.15** | 视频需领先音频的置信度差 |
| `CONFLICT_MAX_HOLD_TICKS` | — | **4** | 冲突延迟上限，超过后强制放行 |
| `AUDIO_STRONG_START_CONF` | — | **0.75** | 强音频直通启动门槛 |
| `AUDIO_ONLY_START_TICKS` | — | **2** | 弱证据启动所需连续 tick 数 |
| `audioOnlyDoStreak` | — | **字段，初值 0** | 弱证据 do 连续计数（仅融合线程访问） |
| `conflictHoldTicks` | — | **字段，初值 0** | 强冲突连续计数（仅融合线程访问） |
| 音频死区（离线/网页） | 4000 ms | **2000 ms** | 硬编码在音频循环内，在线模式无此项 |

---

## 逐项改动

按此顺序移植；01–02 是其余改动的前提。

### 01 · 分组概率决策（误判修复，最重要）

**位置**：`applyVideoResult()` 与音频循环的结果映射 —— 两个 Activity 各两处，共 4 处

**旧**：先 argmax 取单类，再用该单类概率过阈值，然后才把 oral/sex 归并为 `"do"`。
转场期 P(oral)=0.4、P(sex)=0.4 时「该转」总证据 0.8，却因单类 0.4 < 0.75 被判成 unclear。

**新**：在归并后的分组概率上判定，判定顺序固定为「先 do、后 Noise、否则 unclear」，
置信度取分组后的概率：

```
视频（类目顺序 [plot, oral, sex]）：
  pDo = probs[1] + probs[2];  pPlot = probs[0]
  pDo ≥ 0.65        → "do"    (confidence = pDo)
  否则 pPlot ≥ 0.60 → "Noise" (confidence = pPlot)
  否则              → ""      (confidence = 0, unclear)

音频（类目顺序 [sex, oral, noise]）：
  pDo = probs[0] + probs[1];  pNoise = probs[2]
  pDo ≥ 0.55         → "do"
  否则 pNoise ≥ 0.60 → "Noise"
  否则               → ""
```

> **iOS 要点**：注意视频与音频的类目索引顺序不同（见上）。无效结果（index < 0 或概率数组
> 长度 < 3）仍判 unclear。由于分析线程输出只剩 `"do" / "Noise" / ""`，融合循环里原来的
> `oral → do` 归一化成了死代码，Android 端已删除，iOS 如有同样代码可一并删。

### 02 · 音频推理结果暴露完整概率

**位置**：`AudioInferenceHelper.AudioInferenceResult`

结果结构新增 `float[] probs` 字段（顺序 `[sex, oral, noise]`），`predict()` 返回分类器输出的
拷贝；无效结果时为空数组。视频侧的 `Result.probs` 原本就有，无需改。

> **iOS 要点**：给音频推理结果结构体加 `probs: [Float]`，改动 01 依赖它。

### 03 · 节拍加快：融合与视频推理 1000 → 500 ms

**位置**：`MAIN_FUSION_INTERVAL`、`VIDEO_INFERENCE_INTERVAL`

融合循环本身耗时约 40–60 ms，视频推理约 154 ms（含抽帧），2 Hz 完全可承受。
**抽帧节拍（250 ms）、模型输入帧数（12 帧 / 3 秒窗口）、音频推理节拍（1000 ms）、
新鲜度过滤 `MAX_AGE`（2000 ms）都不变** —— 变的只是推理触发频率，模型每次看到的
内容不变，但相邻两次推理的窗口重叠从 8/12 帧升到 10/12 帧。

蓝牙不会因此过载：实际发送仍被状态机的 1600 ms 发送间隔与最短持续时间节流。

> **iOS 要点**：只改两个定时器的间隔常量；循环内「耗时补偿」逻辑
> （`nextDelay = max(0, interval − elapsed)`）保持原样。

### 04 · 平滑窗口收缩 10 → 6

**位置**：`SMOOTH_WINDOW_SIZE`；投票处字面量 3 改用 `SMOOTH_MIN_COUNT`

融合 tick 变为 500 ms 后，6 条记录约覆盖 3 秒（原来 10 条 × 1000 ms = 10 秒）。防抖职责
统一收归蓝牙状态机，平滑窗口只压制单次抖动。加权评分公式、时间递增权重、模态权重
（视频 0.8 / 在线 0.7，音频 1.2）、历史不足 3 条时的 `selectBestAction` 兜底逻辑全部不变。

> **iOS 要点**：纯常量修改；如果 iOS 端窗口投票里也是硬编码的 3，顺手常量化。

### 05 · 一致性快通道

**位置**：`smoothedFusion()` 内部 —— 在「记录入窗 + 裁剪窗口」之后、投票之前

```
if videoAction 非空
   且 videoAction == audioAction
   且 videoConf ≥ 0.70 且 audioConf ≥ 0.70:
    return videoAction   // 绕过窗口投票
```

两个模态同 tick 高置信度一致时没有抖动嫌疑，不必攒票。对 `"do"` 与 `"Noise"` 都生效；
停转方向的安全性仍由蓝牙状态机兜底（do→Noise 稳定 1000 ms + 最短持续 2000 ms）。

> **iOS 要点**：本 tick 的记录必须*先*加入历史窗口再走快通道判断，否则窗口会漏账、
> 影响后续投票与启动仲裁的证据检索。

### 06 · 启动仲裁：`applyDoStartGate`

**位置**：融合循环中，`smoothedFusion()` 之后、`updateBluetoothState()` 之前调用：
`finalAction = applyDoStartGate(finalAction, videoAction, videoConf, audioAction, audioConf)`。
新增方法 + 字段 `audioOnlyDoStreak`、`conflictHoldTicks`

> ⚠️ **设计前提（重要）：实测音频模型准确度高于视频模型。**
> 因此视频**不**对启动拥有否决权，只能在与音频强烈冲突时把启动短暂推迟，且推迟有硬上限。
> 这与融合权重（音频 1.2 / 视频 0.8）的取向一致。
>
> 本项最初的版本让「视频高置信度剧情」直接否决启动，方向反了 —— 较弱模态可以永久压制
> 较强模态。下方是修正后的最终规则。

作用范围仅限「从非 do 状态**启动** do」；已在 do 状态时（维持阶段）音频可单独维持，
不受任何限制。

```
applyDoStartGate(finalAction, videoAction, videoConf, audioAction, audioConf):
  if finalAction != "do":            streak = 0; hold = 0; return finalAction
  if 当前蓝牙状态已是 do (维持阶段):   streak = 0; hold = 0; return finalAction

  // —— 以下为启动阶段 ——
  audioDoConf = (audioAction == "do") ? audioConf : 0

  // 规则1 强冲突延迟（有上限，绝不永久阻塞）
  strongConflict = videoAction == "Noise"
                   且 videoConf ≥ 0.80
                   且 (videoConf − audioDoConf) ≥ 0.15
  if strongConflict:
      hold += 1
      if hold ≤ 4: return ""          // 本 tick 不启动
      // 超过上限 → 视频可能在系统性误判，按"音频更可信"继续往下走
  else:
      hold = 0

  // 规则2 强音频直通
  if audioDoConf ≥ 0.75:  streak = 0; return finalAction

  // 规则3 视频证据放行
  if 平滑窗口内存在任一条 videoAction == "do":
      streak = 0; return finalAction

  // 规则4 弱证据兜底
  streak += 1
  if streak ≥ 2: return finalAction
  return ""
```

- 返回 `""` 表示本 tick 不驱动蓝牙状态机（融合循环对空结果直接跳过发送），维持现状，
  *不*主动发 Noise。
- **最坏启动延迟有界**：4（冲突上限）+ 2（弱证据）= 6 tick ≈ 3 秒。任何情况下视频都无法
  永久阻塞音频。
- 两个计数器只在融合线程（主线程）读写，无需加锁；窗口证据检索需持有 `historyLock`。
- **重置点共 3 处**（与清空动作历史同步）：离线 seek（`handleSeekComplete`）、
  网页模式 seek（`handleWebVideoSeekComplete`）、在线模式静音重置。**两个计数器都要归零。**

> **iOS 要点**：这是唯一新增的方法级逻辑，两条流水线各复制一份（或抽公共函数）。
> 「当前蓝牙状态已是 do」读的是状态机的 `currentBluetoothState`（已确认执行中的动作），
> 不是融合输出。注意规则 1 命中上限后是*继续往下执行*规则 2–4，而不是直接放行。

> **调参提示**：若实测仍嫌启动慢，优先把 `AUDIO_STRONG_START_CONF`（0.75）调低 ——
> 它是最常命中的放行路径；若观察不到音频假启动，可把整个 `applyDoStartGate` 删掉，
> 交由平滑窗口 + 蓝牙状态机兜底。

### 07 · 音频死区 4 s → 2 s（仅离线 / 网页模式）

**位置**：离线音频循环 `(currentMs − audioStartTime) ≥ 4000` → `≥ 2000`

模型窗口只需要 [T−2s, T] 共 2 秒数据；解码器本身保持约 3 秒提前量；且
`readWindowRelaxed` 在有效样本不足 90% 时返回 null 自我保护，等满 2 秒即可开始尝试。
起播与每次 seek 后的音频空窗因此缩短 2 秒。

> ⚠️ 在线模式**没有**这处改动：它用 `getLatestData()`，数据不足直接返回 null，自带门控。
> iOS 端只改离线 / 网页模式的等待常量。

---

## 明确保持不变

移植时不要顺手改这些 —— 防抖职责已统一收归蓝牙状态机，它的参数是刻意保留的。

- **蓝牙状态机全部参数**：最短持续 `2000 ms`、发送间隔 `1600 ms`、do→Noise 需稳定
  `1000 ms`、do/oral 确认 0 ms。
- **档位 / 节律链路**：`computeFinalFreq`、方向性置信度门控（0.10 × 3）、档位迟滞（±1）、
  `LEVEL_STABLE_MS = 0`、0–8 钳制。
- **模态权重**：视频 0.8（在线模式 0.7）、音频 1.2；时间递增权重公式。
- **新鲜度过滤** `MAX_AGE = 2000 ms`；**抽帧节拍 250 ms**；**12 帧滑窗**；
  **音频推理节拍 1000 ms**。
- `selectBestAction`（历史不足时的兜底）与 seek 防抖、暂停 / 恢复、静音检测逻辑。

---

## 预期效果与验证清单

「剧情 → 动作」的启动延迟预计从约 5–8 秒降到约 2.5–4 秒；音视频一致高置信度时走快通道，
延迟趋近「模型窗口惯性 + 0.5 s tick + 蓝牙门控」。停转方向仍偏保守，这是刻意的。
四个分组阈值（0.65 / 0.60 / 0.55 / 0.60）与启动仲裁的五个参数都是初值，等第 0 层评测基准
（逐秒标注的完整视频回放）建立后再校准。

- [ ] 转场片段：从剧情切入动作后设备起转时间明显缩短，转场期 unclear 输出显著减少。
- [ ] 音频先于画面判定动作（音频更准的典型场景）：起转不被视频拖住，日志应看到
      `[启动仲裁] 音频 do 置信度 … 达直通门槛`。
- [ ] 暗光 / 特写等视频易误判场景：不应出现「音频一直判 do 但设备始终不转」；
      最长延迟约 3 秒后必然放行。
- [ ] 剧情段带配乐 / 配音喘息：若确实出现假启动，检查是否为弱证据路径放行，
      据此调 `AUDIO_STRONG_START_CONF`。
- [ ] seek（离线与网页模式）：清空后恢复分析正常，音频空窗约 2 秒。
- [ ] 在线模式：静音暂停 → 恢复后两个计数器均从零开始，无残留状态。
- [ ] 蓝牙日志：发送频率没有因 500 ms 节拍上升（仍受 1600 ms 间隔约束）。

---

## 后续计划（不在本次改动范围内）

- **第 0 层**：建评测基准 —— 5~10 部完整视频逐秒标注真值，离线回放整条流水线，输出
  「逐秒判定准确率」和「切换延迟」两个指标。后面所有调参的地基。
- **第 2 层**：本地模式前瞻分析 —— 抽帧链路与解码器本就独立于播放位置，可分析
  `currentMs + 1500~2000ms`，让决策在画面播到那一刻刚好生效，把模型窗口惯性整体抵消。
- **第 3 层**：难例挖掘重训（优先补 noise 类音频，当前仅 3564 条）、训练集加入转场窗口
  样本、缩短模型输入窗口（视频 12→8 帧、音频 2s→1s）、换流式视频模型（MoViNet-A0 stream）、
  学习型融合替代手写权重。

---

*本文件对应 Android 提交 `82d0e6b` + `a64ba2a`，分支 `claude/xcup-video-latency-accuracy-iqu52w`。*
