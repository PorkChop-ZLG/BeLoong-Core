# 旗帜纹理提取工具

> **本目录（`tools/`）刻意不进版本管理** —— `.gitignore:49` 整目录忽略，这是**项目既定政策**
> （`memory/project-context.md:322`），2026-10-11 再次确认。因此：**不要 `git add -f`，
> 也不要为它反加白名单**（那会让本目录下的 spark 转储与 `__pycache__` 冒进 `git status`）。
> 本目录的脚本 —— 包括验收门 `validate_execute.py` / `validate_execute_line.py` —— 都是
> **本机工具**：换机器或重新克隆后需要自己带过去，而「门已全绿」这类结论只有在这些脚本
> 仍在工作区时才可复现。
>
> **注意历史的例外**：`README.md`（本文件）、`extract_banner_texture.py`、`make_execute_icons.py`、
> `make_tornado_icons.py`、`recolor_tornado_texture.py`、`validate_execute.py` 这 6 个是**早年已被跟踪**的，
> 它们的改动**照常会进提交**；政策约束的是**新脚本不要再往里加**（如 `validate_execute_line.py` 至今未跟踪）。

`extract_banner_texture.py` 用于把 Minecraft 1.21.1 的旗帜物品字符串转换为本模组板子模型可直接使用的 64x64 PNG 纹理。

## 环境要求

- Python 3.14 或其他支持当前 Pillow 版本的 Python 3
- Pillow

安装 Pillow：

```powershell
python -m pip install Pillow
```

## 在哪里填写旗帜字符串

不需要修改 Python 文件。旗帜字符串填写在命令行的第一个参数中，输出位置填写在 `-o` 后面：

```powershell
python tools\extract_banner_texture.py '这里填写旗帜字符串' -o '这里填写输出图片路径.png'
```

请在项目根目录 `D:\Minecraft\BeLoong-Core` 中打开 PowerShell 后运行命令。外层使用单引号时，旗帜字符串内部的双引号不需要转义或删除。

## 基本示例

将纹理生成到项目根目录，便于先查看结果：

```powershell
python tools\extract_banner_texture.py 'minecraft:orange_banner[banner_patterns=[{color:"red",pattern:"minecraft:gradient"},{color:"yellow",pattern:"minecraft:gradient_up"}]]' -o 'preview.png'
```

成功后会输出使用的资源 JAR、底色、图案层和最终文件路径。

## 直接生成板子纹理

下面的命令会直接生成或覆盖 `red_yellow_board` 的纹理：

```powershell
python tools\extract_banner_texture.py 'minecraft:orange_banner[banner_patterns=[{color:"red",pattern:"minecraft:gradient"},{color:"yellow",pattern:"minecraft:gradient_up"}]]' -o 'src\main\resources\assets\beloong\textures\block\red_yellow_board.png'
```

建议先输出为 `preview.png` 检查，再写入正式纹理路径。脚本会自动创建不存在的输出目录，但会直接覆盖同名文件。

## 参数说明

| 参数 | 是否必需 | 说明 |
| --- | --- | --- |
| `banner` | 是 | 完整旗帜物品字符串，必须放在第一个参数位置 |
| `-o`, `--output` | 是 | 输出 PNG 文件路径 |
| `--assets-jar` | 否 | 手动指定 Minecraft 客户端资源 JAR |

查看内置帮助：

```powershell
python tools\extract_banner_texture.py --help
```

## Minecraft 资源 JAR

默认情况下，脚本会自动查找：

```text
build/moddev/artifacts/*client-extra*.jar
```

通常不需要填写 `--assets-jar`。如果自动查找失败，可以手动指定：

```powershell
python tools\extract_banner_texture.py '<旗帜字符串>' -o 'output.png' --assets-jar 'D:\path\to\minecraft-resources.jar'
```

## 输出内容

- 输出格式固定为 PNG。
- Minecraft 1.21.1 原版旗帜纹理尺寸为 64x64。
- 脚本按照旗帜底色和 `banner_patterns` 中的顺序依次染色、叠加图案。
- 输出保留完整的原版旗帜 UV 布局，可直接配合本模组当前的板子模型使用。
- 不要手动裁剪、旋转或上下翻转生成的纹理。

## 常见问题

### 找不到 `python`

安装 Python 后重新打开 PowerShell。仍然找不到时，可以使用当前机器上的完整路径：

```powershell
C:\Users\D_Ink\AppData\Local\Programs\Python\Python314\python.exe tools\extract_banner_texture.py '<旗帜字符串>' -o 'output.png'
```

### 提示缺少 Pillow

```powershell
python -m pip install Pillow
```

### 找不到 Minecraft 资源 JAR

先运行一次 NeoForge 的 Gradle 开发任务以生成 `build/moddev/artifacts`，或者使用 `--assets-jar` 指定资源 JAR。

### 找不到图案纹理

确认 `pattern` 的资源 ID 正确，例如 `minecraft:gradient`。非原版图案只有在指定的资源 JAR 中包含对应纹理时才能生成。
