# 旗帜纹理提取工具

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
