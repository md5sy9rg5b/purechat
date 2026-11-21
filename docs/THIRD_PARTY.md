# 第三方组件与许可

本项目自身代码以 [MIT](../LICENSE) 许可发布。以下第三方组件保留其原有许可。

## 运行时依赖

### KaTeX

- 用途：数学公式排版（离线打包进 APK，含字体文件）
- 版本：0.16.22
- 许可：MIT
- 主页：https://katex.org/
- 版权：Copyright (c) 2013-2020 Khan Academy and other contributors
- 完整许可见 `web/node_modules/katex/LICENSE`

随包分发的字体文件来自 KaTeX 项目，同样遵循 MIT 许可。

### esbuild

- 用途：构建期打包工具（不进入最终产物）
- 许可：MIT
- 主页：https://esbuild.github.io/

## 平台与系统组件

### Android SDK

- 用途：编译与打包
- 许可：Android SDK 许可协议
- 说明：本项目不包含任何 Android SDK 源码，仅在构建时链接系统 API

### AndroidX / 系统 API

本项目未引入 AndroidX 或任何第三方 Android 库，仅使用 Android 平台自带 API
（`android.webkit.WebView`、`android.media.*`、`android.graphics.*` 等）。

## 字体

界面文字使用系统默认字体，未打包任何商用字体。

## 图标

界面图标为项目自绘的简单线性路径（文字描述性几何图形），未复制任何图标库资源。

## 协议兼容性说明

本项目实现了与 DeepSeek API 的 Anthropic 兼容 Messages 协议对接。该协议规范由
DeepSeek 公开发布于其 API 文档，本项目仅按其公开文档实现客户端调用，未复制其
服务端实现或任何专有代码。

"DeepSeek"、"Anthropic" 等名称可能是其各自所有者的商标。本项目与上述公司
无关联，也未获其背书；提及这些名称仅用于说明接口兼容性。

## 参考与致谢

项目在早期调研阶段参考了 DeepSeek 官方开源的对话示例项目
[deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo) 的公开文档与
交互形态，用于理解其 API 用法。

需要说明的是：

- 本项目**没有复制**该项目的源代码；
- 界面、样式、图标、Markdown 解析器、网络层、协议解析均为独立实现；
- 仅借鉴了"静态前端直连 API"这一公开的架构思路，以及通用的移动端聊天交互范式。

该参考项目同样以 MIT 许可开源，其许可条款见其仓库。
