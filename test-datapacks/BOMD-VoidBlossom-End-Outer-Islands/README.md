# BOMD Void Blossom → End Outer Islands（测试数据包）

用于测试 BeLoong-Core 中 `VoidBlossomArenaStructureFeatureMixin` 的高度修复。

## 作用

覆盖 BOMD 原版生物群系标签：

`data/bosses_of_mass_destruction/tags/worldgen/biome/has_structure/void_blossom.json`

将 Void Blossom 结构的生成生物群系改为末地外岛：

- `minecraft:end_midlands`
- `minecraft:end_highlands`
- `minecraft:end_barrens`
- `minecraft:small_end_islands`

## 使用方法

1. 将本文件夹（或打包后的 zip）放入存档的 `datapacks` 目录：
   ```
   .minecraft/saves/<你的存档>/datapacks/BOMD-VoidBlossom-End-Outer-Islands
   ```
2. 进入存档后执行：
   ```
   /reload
   ```
3. 如果是在创建世界时使用，直接在选择数据包界面启用即可。

## 验证方式

- 传送到末地外岛区域，例如：
  ```
  /execute in minecraft:the_end run tp @s 1000 100 1000
  ```
- 使用 `/locate structure bosses_of_mass_destruction:void_blossom` 查找结构。
- 观察洞穴是否生成在岛屿内部而非虚空。
- 若需回退原版行为，将 BeLoong-Core 配置 `fixBomdVoidBlossomStructureHeight` 设为 `false`。
