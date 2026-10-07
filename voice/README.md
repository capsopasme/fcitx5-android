# 内置离线语音输入（sherpa-onnx + 高通 QNN）

工具栏新增麦克风按钮，点开后进入语音面板，边说边识别，停顿后整句上屏。全程离线。

## 模型

| 模型 | 运行位置 | 说明 |
|---|---|---|
| SenseVoice-Small · QNN | 骁龙 8 Gen 3（SM8650）NPU / HTP v75 | 默认。预编译 context binary（QNN 2.40，20 秒输入窗口），中英混说、自动标点 |
| SenseVoice-Small · CPU | 任意 arm64 | 兜底，不依赖 NPU |
| Qwen3-ASR 0.6B · CPU | 任意 arm64 | 精度最高，模型 ~880 MB，每句延迟 1–2 秒，不做实时预览 |

模型在「设置 → 语音输入 → 模型」里下载（可填 GitHub 加速前缀），或从文件导入
sherpa-onnx 官方的 `.tar.bz2` 包。模型存放在应用私有目录 `files/voice-models/`。

## 架构

- `voice/VoiceRecognitionService`：运行在独立进程 `:voice`，负责 Silero VAD 断句 + sherpa-onnx 离线识别。
  QNN / onnxruntime 初始化失败时会直接 `exit()`，独立进程可以保证键盘本身不受影响。
- `voice/VoiceClient` + `voice/AudioCapture`：在输入法进程里录音（16 kHz），通过 Messenger 把音频流发给识别进程。
- `input/voice/VoiceInputWindow`：语音面板 UI。VAD 判定一句话结束后立即 `commitText`；说话过程中每 ~350 ms
  重新识别当前句用于实时预览（模拟流式）。
- `voice/archive/*`：内置的 bzip2 + tar 解压实现（Android 没有自带 bzip2）。

### 省电与容错

- 聆听中一段时间（默认 20 秒，设置里可调 5–120 秒）没有识别出任何句子就自动停止并释放麦克风。
  按音频时长计算，持续背景噪声被 VAD 当成语音、但识别不出文字时同样会停止。
- 麦克风光圈只在音量跨档时重绘一帧，安静时不刷新界面。
- QNN 的致命错误是直接 `exit()`：加载 NPU 模型前写一个标记文件，加载并完成一次预热推理后删除。
  标记还在说明上次加载把进程带崩了，之后不再尝试 NPU：已下载「SenseVoice · CPU」就自动用它，
  否则提示用户。设置里对 NPU 模型点「自检」会强制重试；应用更新后也会重新尝试。
- 识别进程崩溃后客户端主动解绑，不让系统立刻拉起一个空进程。

## 构建

原生库不在仓库里，需要先运行：

```sh
export ANDROID_NDK=/path/to/ndk
./voice/prepare-native.sh          # 编译 sherpa-onnx v1.13.8 (QNN=ON)，下载 QNN 2.40 运行库
./gradlew :app:assembleRelease -PbuildABI=arm64-v8a
```

`QNN_HTP_ARCHS="73 75 79 81"` 可以把 8 Gen 2 / 8 Elite 等的 Skel 库也打进去（但模型本身目前只配了 SM8650）。

`com/k2fsa/sherpa/onnx/*.kt` 是从 sherpa-onnx v1.13.8 原样拷贝的 Kotlin API（删掉了示例函数），
JNI 按字段名访问这些类，升级 sherpa-onnx 时两边要一起换。
