# AutoUpdateUpstream

用于自动更新 Paper/Folia 风格补丁型服务端 Fork 的上游版本。

`AutoUpdateUpstream` 会自动更新 `gradle.properties` 中记录的上游 Commit，应用上游变更，验证项目是否能够正常构建，重新生成 Patch，并可配合 GitHub Actions 自动创建或更新上游同步 Pull Request。

## 功能

- 自动获取上游 Git 仓库最新 Commit
- 自动更新 `gradle.properties` 中的上游 Commit
- 默认自动跟踪上游仓库默认分支
- 支持指定上游分支
- 自动执行 Patch 应用任务
- 应用 Patch 后自动进行项目构建验证
- 支持执行多个 Patch 重建任务
- 支持执行最终 Patch 修复任务
- 当上游已经是最新版本时自动跳过
- 适合搭配 GitHub Actions 使用
- 可通过 GitHub Actions 自动创建或更新上游同步 PR
- 支持自定义 Gradle Task
- 不仅限于 Folia，也可以用于其他类似的 Patch 型项目

## 默认更新流程

默认情况下，程序会按照以下流程执行：

```text
检查当前上游 Commit
        ↓
获取最新上游 Commit
        ↓
修改 gradle.properties
        ↓
applyAllPatches
        ↓
build -x test -x scanJarForBadCalls
        ↓
rebuildAllServerPatches
rebuildFoliaApiPatches
rebuildPaperApiPatches
        ↓
rebuildFoliaSingleFilePatches
```

如果当前配置中的上游 Commit 已经是最新版本，则不会进行任何修改。

## 环境要求

本地运行需要：

- Git
- Java
- 基于 Gradle 的目标项目
- 目标项目中存在 `gradlew` 或 `gradlew.bat`
- `gradle.properties` 中包含上游 Commit 配置项

仓库附带的 GitHub Actions 示例使用 JDK 25 构建和运行。

默认配置主要面向 Paper/Folia 衍生项目。

如果你的项目结构或 Gradle Task 不同，也可以通过命令行参数覆盖默认配置。

## 构建

克隆本项目后，通过 Gradle Wrapper 构建可执行 JAR：

```bash
./gradlew jar
```

Windows：

```bat
gradlew.bat jar
```

构建后的 JAR 位于：

```text
build/libs/
```

## 使用方法

直接对当前目录执行：

```bash
java -jar build/libs/AutoUpdateUpstream-1.0-SNAPSHOT.jar
```

也可以手动指定目标项目目录：

```bash
java -jar build/libs/AutoUpdateUpstream-1.0-SNAPSHOT.jar \
  --repo=/path/to/your/server
```

所有参数均为可选。

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `--repo` | 目标项目目录 | 当前工作目录 |
| `--key` | `gradle.properties` 中保存上游 Commit 的配置项名称 | `foliaRef` |
| `--url` | 上游 Git 仓库 | PaperMC/Folia |
| `--branch` | 需要跟踪的上游分支 | 上游默认分支 |
| `--apply` | 应用 Patch 使用的 Gradle Task | `applyAllPatches` |
| `--build` | 应用 Patch 后执行的构建命令 | `build -x test -x scanJarForBadCalls` |
| `--rebuild` | Patch 重建 Task，可重复指定多个 | 见下方 |
| `--fix` | 最后执行的 Patch 修复 Task | `rebuildFoliaSingleFilePatches` |

默认重建任务为：

```text
rebuildAllServerPatches
rebuildFoliaApiPatches
rebuildPaperApiPatches
```

参数支持两种写法：

```bash
--repo=/path/to/repo
```

或者：

```bash
--repo /path/to/repo
```

## 自定义配置

如果目标项目使用其他上游 Commit 配置项，例如：

```properties
paperRef=xxxxxxxx
```

可以这样运行：

```bash
java -jar AutoUpdateUpstream.jar \
  --repo=/path/to/repo \
  --key=paperRef
```

如果需要指定上游分支：

```bash
java -jar AutoUpdateUpstream.jar \
  --repo=/path/to/repo \
  --branch=master
```

也可以自定义整个 Gradle 执行流程：

```bash
java -jar AutoUpdateUpstream.jar \
  --apply=applyAllPatches \
  --build="build -x test" \
  --rebuild=rebuildServerPatches \
  --rebuild=rebuildApiPatches \
  --fix=rebuildSingleFilePatches
```

`--rebuild` 可以重复指定多次。

例如：

```bash
--rebuild=rebuildServerPatches \
--rebuild=rebuildApiPatches
```

部分步骤也可以通过传入空值关闭，例如：

```bash
--build=
--fix=
--rebuild=
```

## GitHub Actions

项目中提供了一个可直接修改使用的 GitHub Actions 示例：

```text
auto-update-upstream-sample.yml
```

可以将其复制到目标仓库：

```text
.github/workflows/auto-update-upstream.yml
```

示例 Workflow 默认每 8 小时检查一次上游更新，同时支持通过 `workflow_dispatch` 手动执行。

### 主要配置

Workflow 中比较重要的环境变量包括：

```yaml
env:
  TOOL: "LophineLabs/AutoUpdateUpstream"
  UPSTREAM_URL: "<你的上游 Git 仓库>"
  REF: "foliaRef"
  TITLE: "Update Upstream (Folia)"
  REF_COMMIT: "auto-update/upstream"
```

你可以根据自己的项目修改：

- 上游仓库地址
- `gradle.properties` 配置项名称
- PR 标题
- 自动更新分支名称

## GitHub Actions 执行流程

示例 Workflow 大致会执行以下操作：

1. 从 `gradle.properties` 中读取当前上游 Commit
2. 查询上游最新 Commit
3. 如果当前已经是最新版则直接结束
4. 检查已有的上游同步 PR
5. Checkout `AutoUpdateUpstream`
6. 构建 `AutoUpdateUpstream`
7. 在目标项目中运行更新工具
8. 修改上游 Commit
9. 应用 Patch
10. 构建目标项目
11. 重新生成 Patch
12. 获取上游 Commit 信息
13. 提交生成后的修改
14. 推送到自动更新分支
15. 创建或更新对应 Pull Request

示例中默认使用以下分支：

```text
auto-update/upstream
```

作为自动更新分支。

## GitHub Actions 权限

Workflow 需要具备：

- 推送分支的权限
- 创建 Pull Request 的权限
- 修改已有 Pull Request 的权限

因此在启用自动更新前，请确保仓库中的 GitHub Actions 拥有足够的写入权限。

## 构建验证

修改上游 Commit 并应用 Patch 后，程序会先执行构建验证。

默认执行：

```bash
./gradlew build -x test -x scanJarForBadCalls
```

如果项目构建失败，程序会停止后续 Patch 重建流程。

这样可以防止在上游变更已经导致项目无法正常编译的情况下，继续生成可能无效的 Patch。

## Patch 应用兼容处理

Patch 型 Fork 在更新上游时，可能因为上游代码变化导致已有 Patch 无法正常应用。

`AutoUpdateUpstream` 对 `build.gradle.kts.patch` 提供了一套兼容性回退处理。

当普通 Patch 应用失败时，程序可以尝试：

- 查找直接子目录中的 `build.gradle.kts.patch`
- 对已知兼容性问题进行处理
- 使用不同方式重新执行 `git apply`
- 尝试忽略空白差异
- 尝试三方合并应用
- 最后尝试使用 `--reject` 方式宽松应用

这些逻辑主要用于提高上游升级时的自动兼容能力。

但即使程序成功完成 Patch 应用，也仍然建议人工检查生成的改动。

## 项目结构示例

一个典型的目标项目可能类似：

```text
your-server/
├── gradle.properties
├── gradlew
├── gradlew.bat
├── build.gradle.kts
├── server/
│   └── patches/
├── api/
│   └── patches/
└── ...
```

`gradle.properties` 中需要存在对应的上游 Commit 配置：

```properties
foliaRef=0123456789abcdef0123456789abcdef01234567
```

程序运行成功后，该值会被替换为最新上游 Commit SHA。

之后程序会继续执行配置好的 Patch 重建任务，重新生成对应 `.patch` 文件。

## 适用场景

`AutoUpdateUpstream` 比较适合以下项目：

- 基于 Paper 或 Folia 开发的 Fork
- 需要长期跟踪上游代码
- 使用 Patch 形式维护自身修改
- 在 `gradle.properties` 中保存上游 Commit
- 经常需要手动执行 Apply Patch / Rebuild Patch
- 希望通过 GitHub Actions 自动同步上游

正常情况下，每次同步上游通常需要手动完成：

```text
修改上游 Commit
↓
Apply Patches
↓
处理冲突
↓
构建
↓
Rebuild Patches
↓
提交修改
↓
Push
↓
创建 PR
```

使用 `AutoUpdateUpstream` 后，可以将其中大部分流程自动化。

## 注意事项

程序会直接修改目标项目中的文件。

本地运行前，建议先确保重要改动已经 Commit 或备份。

对于正式项目，更推荐使用 GitHub Actions：

```text
上游出现新 Commit
        ↓
GitHub Actions 自动执行更新
        ↓
生成新的 Patch
        ↓
Push 到独立更新分支
        ↓
自动创建 PR
        ↓
人工检查
        ↓
Merge
```

即使自动生成的代码能够正常编译，也不代表上游行为变化一定不会影响你的 Fork。

因此不建议完全跳过人工审查直接自动合并。

## License

本项目基于 GNU General Public License v3.0 开源。

完整协议内容请查看：

```text
LICENSE
```
