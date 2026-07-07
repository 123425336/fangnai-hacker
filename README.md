# 芳乃 Hacker

芳乃 Hacker 是一个基于 Forge 1.20.1 的客户端模组，主要提供游戏内 AI 助手、可确认的本地操作、动态工具生成、实体清理、战斗辅助与 HUD 渲染等功能。

项目目标不是让 AI 直接绕过权限或自动执行危险操作，而是让 AI 先提出操作方案，再由玩家在客户端确认执行。

## 主要功能

### 1. 游戏内 AI 助手

通过 `/fangnai hacker <自然语言>` 或 `/fangnai hacker ask <自然语言>` 调用 AI。

AI 可以：

- 直接回答问题；
- 提出 Minecraft 原版命令；
- 提出实体清理操作；
- 分析 mods 目录下的 jar；
- 生成并编译 Java 工具类；
- 保存已生成工具，后续可继续复用；
- 在编译失败或工具调用失败时自动把诊断发回 AI，让 AI 尝试修复。

AI 产生的高风险动作不会直接执行，而是进入待确认队列，需要玩家使用确认按钮或命令确认。

常用命令：

```text
/fangnai hacker <自然语言>
/fangnai hacker ask <自然语言>
/fangnai hacker status
/fangnai hacker reload
/fangnai hacker cancel
/fangnai hacker confirm <id>
/fangnai hacker confirmall
/fangnai hacker deny <id>
/fangnai hacker denyall
/fangnai hacker memory status
/fangnai hacker memory clear
/fangnai hacker tools list
/fangnai hacker tools info <toolId>
```

### 2. AI 上下文记忆

`AiConversationManager` 会保留最近若干条 user/assistant 上下文，让 AI 能理解连续对话。

相关配置位于 `config/hacker-client.json`：

```json
{
  "ai": {
    "contextMemoryEnabled": true,
    "contextMemoryMaxEntries": 20,
    "contextMemoryEntryMaxChars": 4000
  }
}
```

注意：上下文记忆只用于理解对话，不代表新的玩家授权。只有玩家确认过的操作才视为已执行。

### 3. Prompt Caching

Anthropic Messages API 模式下支持 prompt caching。

当 `promptCachingEnabled` 为 `true` 时，系统提示词会以带 `cache_control` 的格式发送：

```json
"system": [
  {
    "type": "text",
    "text": "...",
    "cache_control": { "type": "ephemeral" }
  }
]
```

这可以缓存较长的 system prompt，减少后续请求的输入 token 成本和延迟。

如果使用本地兼容接口或不支持 Anthropic 缓存格式的服务，可以关闭：

```json
{
  "ai": {
    "promptCachingEnabled": false
  }
}
```

### 4. 可确认操作队列

AI 生成的命令、实体清理、class define、保存工具调用等操作会先进入 `PendingActionRegistry`。

玩家可以逐个确认，也可以使用 `confirmall` 一次确认多个操作。

这样可以避免 AI 在没有明确授权的情况下直接执行命令或修改运行时。

### 5. 实体清理

内置实体清理功能由 `FangnaiRemove` 和 `EntityClearProposal` 提供。

支持示例：

```text
/fangnai hacker 清除羊
/fangnai hacker 全部清除
/fangnai hacker 循环清除羊
/fangnai hacker 停止循环清除
```

默认跳过玩家，且永远不会清除本地玩家。多人服中服务器可能会重新同步实体，因此部分清理只影响本地客户端视图。

### 6. Jar 分析与自动工作流

AI 可以通过 `propose_jar_analysis` 请求只读分析 mods 目录下的 jar。

支持的分析模式包括：

- `list_jars`
- `list_entries`
- `manifest`
- `find_class`
- `javap_class`

确认一次 jar 分析后，系统可以进入有界自动工作流：AI 每轮只允许提出一个下一步，要么继续分析，要么生成 Java 源码，要么停止。

### 7. 动态 Java 工具生成

AI 可以提出 `propose_compile_define_java_class`，由本地先保存源码、执行 javac 预编译，再编译并 define/redefine 到当前 JVM。

生成类必须位于：

```java
package com.fangnai.hacker.generated;
```

推荐工具方法格式：

```java
public static String run()
public static void run()
public static boolean run()
public static int run()
public static long run()
```

这些 `public static` 无参方法会被工具索引识别，之后可通过 `execute_saved_tool` 复用。

重要限制：

- 生成类不应使用 `V2`、`Fixed`、`New` 等后缀绕开旧类；
- 修复同一个工具时应复用相同 `className`；
- 生成代码应直接调用 Minecraft/Forge API，不要用反射调用 `net.minecraft` 或 `net.minecraftforge` 方法；
- Forge 生产环境存在 SRG 混淆，反射查找开发期方法名容易出现 `NoSuchMethodException`，例如 `Minecraft.getInstance()`。

正确示例：

```java
Minecraft minecraft = Minecraft.getInstance();
```

错误示例：

```java
Minecraft.class.getMethod("getInstance").invoke(null);
```

### 8. 保存工具系统

`ToolRegistry` 会把 AI 生成的工具保存到游戏配置目录下的工具目录，默认是：

```text
run/tool/
```

常见内容：

```text
tool/registry.json
tool/recipes/
tool/classes/
tool/classes/source/
tool/classes/compiled/
tool/docs/
```

工具索引会记录：

- 工具 ID；
- class 名；
- class/source sha256；
- 可调用方法；
- 创建与更新时间；
- 运行时更新状态；
- 原因与预期效果。

AI 后续可以通过 `inspect_saved_tool` 只读查看已保存工具，再提出复用或修复。

### 9. 战斗辅助 KillArea

`KillAreaModule` 提供一个客户端战斗辅助模块。

功能包括：

- 攻击范围；
- CPS；
- FOV；
- 目标类型过滤；
- 目标优先级；
- 攻击冷却；
- 视线检测；
- 持物暂停；
- 暴击；
- 免击退。

设置界面由 `HackerSettingsScreen` 提供，可通过快捷键打开。

### 10. HUD 与目标渲染

客户端渲染模块提供：

- 准星目标拾取；
- 实体外框渲染；
- 玻璃风格 HUD；
- KillArea 状态 HUD；
- 局部模糊背景。

相关类主要位于：

```text
client/render/
client/target/
client/screen/
```

## 重要模块说明

### `HackerMod`

客户端初始化入口。

负责：

- 加载 `HackerClientConfig`；
- 加载 `ToolRegistry`。

### `ClientEvents`

客户端 tick 与渲染事件入口。

负责：

- 打开设置界面；
- 切换 KillArea；
- 驱动 AI tick；
- 驱动实体循环清理；
- 驱动 KillArea；
- 渲染实体框和 HUD。

### `FangnaiHackerCommands`

游戏内命令注册与分发。

负责：

- `/fangnai hacker` 命令；
- AI 请求；
- status/reload/cancel；
- confirm/deny；
- memory；
- tools list/info。

### `AiConversationManager`

AI 会话核心。

负责：

- 拼装 system prompt 和上下文记忆；
- 发送请求；
- 处理 AI 回复；
- 处理工具调用；
- 处理自动编译修复；
- 处理 jar 分析工作流；
- 维护当前请求状态。

### `PromptBuilder`

系统提示词构造器。

这里约束 AI 的行为，例如：

- 不能声称执行了未确认操作；
- 生成工具时必须等待确认；
- jar 分析只能分析 mods 目录；
- 生成类必须位于 `com.fangnai.hacker.generated`；
- 禁止反射调用 Minecraft/Forge API；
- 实体清理优先用内置 proposal。

### `AiClientFactory` 与 API 客户端

负责根据配置创建不同接口模式的客户端。

支持模式：

```text
OPENAI_CHAT_COMPLETIONS
OPENAI_COMPLETIONS
MESSAGES
```

相关实现：

- `OpenAiChatCompletionsClient`
- `OpenAiCompletionsClient`
- `MessagesClient`
- `AbstractJsonAiClient`

### `ToolSchemaJson`

定义 OpenAI Chat Tools / Anthropic Tool Use 可用的工具 schema。

AI 能提出的工具调用由这里描述。

### `PendingActionRegistry`

待确认操作队列。

负责：

- 保存 AI 提出的操作；
- 过期清理；
- 单个确认；
- 批量确认；
- 拒绝操作。

### `ClassDefineService`

动态 Java class 编译、校验、define/redefine 的核心。

负责：

- 保存 AI 生成的 Java 源码；
- 调用本地 `javac` 编译；
- 校验 classfile；
- define 新类；
- 尝试 live redefine 已加载类；
- instrumentation 失败时回退到隔离 ClassLoader；
- 保存 class 和文档。

### `IsolatedGeneratedClassLoader`

用于加载同名 generated class 的新版本。

当 instrumentation 不能 redefine 类结构变化时，会创建新的隔离 ClassLoader 加载新版本。

### `ToolRegistry`

保存工具注册表。

负责：

- 保存工具 recipe；
- 保存 generated class 元数据；
- 识别源码中的 public static 无参方法；
- 生成工具文档；
- 提供 prompt 中的工具目录摘要；
- 支持 inspect 和 execute saved tool。

### `JarAnalysisService`

只读 jar 分析服务。

负责对 mods 目录下 jar 执行受限分析，给 AI 提供类名、manifest、javap 等信息。

### `InstrumentationAccess`

负责加载 Java agent 并提供 `Instrumentation`。

它会从模组资源中取出内置的 `hacker-agent.jar`，尝试 attach 到当前 Minecraft JVM。

### `HackerAgent` 与 `InstrumentationBridge`

Java agent 部分。

`HackerAgent.agentmain` 接收 JVM 提供的 `Instrumentation`，并写入 `InstrumentationBridge`，供主模组使用。

### Mixins

主要 mixin：

- `MixinClientPacketListener`：注册客户端命令、拦截本地命令分发、实现免击退；
- `MixinMinecraft` / `MixinScreen`：用于客户端输入、界面或命令相关扩展。

## Agent 加载与否的影响

这里的 agent 指项目内置的 Java Instrumentation Agent，不是 AI agent。

构建时，`agentJar` 任务会生成：

```text
hacker-agent.jar
```

并把它打包进主模组资源：

```text
assets/hacker/agent/hacker-agent.jar
```

运行时，`InstrumentationAccess` 会尝试把这个 agent attach 到当前 Minecraft 进程。

### Agent 加载成功时

`/fangnai hacker status` 会显示类似：

```text
agent available, canRetransform=true, canRedefine=true
```

这时可用能力更完整：

1. **支持 live redefine**

   AI 生成的新 Java 工具如果复用同一个 className，且只是修改方法体，系统可以尝试用 JVM instrumentation 直接替换当前已加载类。

2. **更适合热修复工具**

   如果 AI 第一次生成的工具有 bug，后续修复同名 class 时，不一定需要重启游戏。

3. **已有 Class 引用更容易更新**

   instrumentation redefine 成功时，当前 JVM 中同名类的字节码会被更新。

4. **retransform 类能力可用**

   与 class retransformation 相关的功能有机会正常工作。

### Agent 加载失败或不可用时

`/fangnai hacker status` 会显示类似：

```text
agent unavailable
```

或者带有 `lastAttachError`。

这时基础功能仍然可用：

- AI 聊天；
- 命令 proposal；
- 实体清理；
- jar 分析；
- 新 generated class define；
- 保存工具；
- 工具 inspect；
- 部分 execute saved tool。

但会有这些限制：

1. **不能稳定 live redefine 已加载类**

   如果同名 generated class 已经加载过，再生成同名新版时，无法通过 instrumentation 直接替换旧字节码。

2. **会回退到隔离 ClassLoader**

   `ClassDefineService` 会尝试用 `IsolatedGeneratedClassLoader` 加载同名类的新版本。新工具调用通常可以使用新版本，但持有旧 `Class` 引用的代码仍可能继续指向旧版本。

3. **某些情况需要重启游戏**

   如果旧类引用无法释放，或者隔离加载也无法覆盖当前调用路径，系统会保存源码和 class，但当前 JVM 可能仍使用旧版，需要重启后生效。

4. **retransform class 相关能力不可用或受限**

   依赖 JVM instrumentation 的类重转换功能无法正常工作。

### 简单判断

- 只是聊天、提命令、清实体、分析 jar：agent 加不加载影响不大。
- 需要 AI 反复生成/修复 Java 工具：agent 成功加载体验更好。
- 需要热替换已经加载过的同名工具类：agent 很重要。
- agent 不可用时仍能保存工具，但遇到旧类不更新时可能需要重启游戏。

## 构建

Windows PowerShell：

```powershell
.\gradlew build
```

Git Bash / Linux shell：

```bash
./gradlew build
```

构建产物位于：

```text
build/libs/
```

## 配置文件

运行后会生成客户端配置：

```text
config/hacker-client.json
```

常见 AI 配置项：

```json
{
  "ai": {
    "enabled": true,
    "baseUrl": "http://localhost:11434/v1",
    "apiKey": "",
    "apiKeyEnv": "",
    "model": "",
    "endpointMode": "OPENAI_CHAT_COMPLETIONS",
    "timeoutSeconds": 180,
    "maxTokens": 8192,
    "temperature": 0.2,
    "stream": true,
    "promptCachingEnabled": true
  }
}
```

如果本地兼容服务不需要 key，可以填任意占位值。

## 安全与确认模型

本项目的 AI 操作设计遵循“提出 proposal，玩家确认后执行”的模式。

AI 不应该：

- 声称已经执行未确认的操作；
- 绕过服务器权限；
- 直接执行任意 shell；
- 在未经确认时 define/redefine class；
- 在未经确认时执行保存工具；
- 用反射调用 Minecraft/Forge 混淆环境下的方法。

玩家始终应该检查 AI 提出的操作摘要，再决定 confirm 或 deny。
