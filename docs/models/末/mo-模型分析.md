# `mo` 模型详细分析（YSM 移植模型）

> 分析对象：`docs/models/末/`（`mo.geo.json` 388 KB、`mo.animation.json` 9.1 MB、`mo.png` 45 KB）
> 分析方式：**只读**结构化解析 + 逐条对照 **GeckoLib 4.9.2 源码**（`开源模组参考文件\Geckolib`，git `0d9d3ea3`）
> 结论依据的是**加载期/运行期的实际代码路径**，不是经验推测

---

## 一、结论速览

**这个模型是给 Yes Steve Model（YSM）做的玩家模型，不是原生 GeckoLib 模型。
两者的骨骼/Molang 约定部分重叠，但 YSM 独有的那部分在 GeckoLib 里会以三种不同方式失败。
最麻烦的是：其中两种失败完全静默——不崩、不报错、不打日志。**

| # | 问题 | GeckoLib 的行为 | 症状 | 级别 |
|---|---|---|---|---|
| **1** | Molang 表达式含**单引号 + 中文** | **整条动画被丢弃** | 日志里一条 ERROR + 堆栈；`fly`/`swim`/`swim_stand` 不存在 | **致命** |
| **2** | 引用 `ysm.*` 变量 | 未注册变量**恒为 0** | 完全静默；`walk`/`run`/`riptide` 的头部跟随失效 | 中 |
| **3** | **155 个骨骼**被动画引用但 geo 里没有 | 静默 `continue` 跳过 | 完全静默；14 个动画只动一部分骨骼 | **高** |
| **4** | 4 个动画**没有 `animation_length`** | 0 骨骼的退化成 **`Double.MAX_VALUE`** | `hover`/`武器拆分` 长度无穷大；`jump` = 100 秒 | 中 |
| **5** | `ysmGlow*` 骨骼带立方体 | 当**普通几何体**渲染 | 眼睛等部位**不发光**（YSM 有额外的自发光渲染通道） | 中 |
| **6** | 动画名混用中文 | 字符串键，能用 | 可维护性差；且**只有中文名的那批骨骼零缺失** | 低 |

### 一句话回答"能不能直接用"

**不能。** 它缺的不是"某个参数"，而是**运行时**：YSM 提供 `ysm.second_order()` 这类 Molang 函数和自发光通道，
GeckoLib 都不提供。而且它的动画集（`ride` / `ride_pig` / `boat` / `sleep` / `riptide` / `climb` / `swim` / `fly`）
是**玩家状态动画**，不是生物 AI 动画——它的目标宿主是"替换玩家模型"，不是"当一只怪"。

---

## 二、资产实测

### 2.1 geo（`mo.geo.json`）

| 项 | 实测值 | 说明 |
|---|---|---|
| `format_version` | `1.12.0` | ✅ GeckoLib `FormatVersion` 支持（`:9-11` 只认 1.12.0 / 1.14.0 / 1.21.0） |
| `identifier` | `geometry.unknown` | 无影响：GeckoLib 走模型类里的**显式资源路径**，不读这个字段 |
| `texture_width/height` | `256 × 256` | ✅ **与 PNG 实测一致**（不同于地黄龙那种 512 贴图配 256 声明的坑） |
| `visible_bounds_*` | `16 / 6 / [0,1,0]` | GeckoLib 4.x 里是**死数据**（只解析不消费），但可作剔除盒修复的现成数值 |
| 根骨骼 | **1 个**（`Root`） | ✅ 单根，比地黄龙（`Magic`/`Dragon` 双根）更省心 |
| 骨骼总数 | **211** | 地黄龙 145 |
| 有 cubes 的骨骼 | **174** | |
| 立方体总数 | **752** | 地黄龙 340 — 约 2.2 倍 |
| 重名骨骼 / 悬空 parent | **无 / 无** | ✅ geo 自身结构干净 |

### 2.2 特殊骨骼（YSM 约定）

| 类别 | geo 中数量 | 其中有 cubes | 含义 |
|---|---|---|---|
| `ysmGlow*` | **8** | **7** | YSM 的自发光几何体，YSM 用额外通道渲染 |
| `*_Molang` | **6** | **0** | 纯变换节点，YSM 把 Molang 求值结果写进来 |
| `*Locator` | **2**（`LeftHandLocator` / `RightHandLocator`） | **0** | 手持物挂点（YSM 用） |

**YSM 的驱动结构是一条交替链**（实测父子关系）：

```
LeftEyeball                    cubes=4    ← 真正的眼球几何体
└─ LeftEye_pupil_Molang        cubes=0    ← 空节点，Molang 结果写进它的 transform
   └─ ysmGlow_LeftEye_pupil    cubes=7    ← 实际可见的瞳孔，YSM 走自发光通道
      └─ LeftEye_Iris_Molang   cubes=0
         └─ ysmGlow_LeftEye_Iris   cubes=6
            └─ LeftEye_light_Molang cubes=0
               └─ ysmGlow_LeftEye_light cubes=1
```

右眼完全对称。⇒ 在 GeckoLib 里这些节点**都能正常加载、也能被动画驱动**，
只是 `ysmGlow*` 会当普通不透明几何体画（**不发光**），`_Molang` 只是空节点。

### 2.3 animation（`mo.animation.json`）

- `format_version` = **`1.8.0`** —— ✅ **GeckoLib 完全不读这个字段**（`BakedAnimationsAdapter` 里没有它的身影），无影响
- **29 个动画**，9.1 MB（地黄龙 96 个动画才 477 KB ⇒ 平均每个动画大 60 倍）

| 动画名 | 骨骼数 | 时长 | 缺失骨骼 | `animation_length` |
|---|---|---|---|---|
| `待机动画` | 117 | 4 | 0 | 有 |
| `头发选择备份` | 85 | 0.1042 | 0 | 有 |
| `躯体选择备份` | 17 | 0.1667 | 0 | 有 |
| `pose` | 73 | — | 0 | **缺** |
| `翅膀默认（展开）` | 24 | — | 0 | **缺** |
| `翅膀默认（收起）` | 2 | 1 | 0 | 有 |
| `以巴` / `以巴2` | 11 / 11 | 4 / 4 | 0 / 0 | 有 |
| `武器拆分` | **0** | — | 0 | **缺** |
| `hover` | **0** | — | 0 | **缺** |
| `jump` | **0** | **100** | 0 | 有（100 秒！） |
| `walk` | 193 | 1 | **74** | 有 |
| `run` | 194 | 0.6667 | **75** | 有 |
| `swim` | 195 | 5 | **75** | 有 |
| `swim_stand` | 194 | 2 | **75** | 有 |
| `fly` | 194 | 1.3333 | **75** | 有 |
| `jump_up` | 193 | 0.5667 | **73** | 有 |
| `sneak` / `sneaking` | 195 / 192 | 1.3333 / 3 | 73 / 73 | 有 |
| `climb` / `climbing` | 196 / 195 | 1.3333 / 3 | 73 / 73 | 有 |
| `sit` / `sleep` / `boat` | **261** | 3 / 4 / 3 | **85 / 84 / 84** | 有 |
| `ride` / `ride_pig` | 39 / 40 | 4 / 4 | 23 / 24 | 有 |
| `attacked` | 75 | 0.5833 | 54 | 有 |
| `death` | 70 | 1 | 54 | 有 |
| `riptide` | 23 | 0.25 | 6 | 有 |

> **注意这个分布**：**中文名动画（`待机动画` / `头发选择备份` / `躯体选择备份` / `以巴` / `翅膀默认*` / `pose`）缺失骨骼全是 0**，
> 而**英文名玩家状态动画缺失 54~85 个**。⇒ 这个文件是**两批来源的合并**：
> 中文那批是**为本模型手工做的**；英文那批（`walk`/`run`/`sit`/`sleep`/`boat`/…）是从**别的模型**（YSM 玩家模板）**导入的**。

---

## 三、六类问题逐条

### 问题 1【致命，会丢动画】表达式含 `'` 与中文 ⇒ 整条动画被丢弃

**GeckoLib 源码**（`loading/math/MathParser.java`）：

```java
// :46
private static final Pattern EXPRESSION_FORMAT = Pattern.compile("^[\\w\\s_+-/*%^&|<>=!?:;.,(){}]+$");
// :187-188
if (!EXPRESSION_FORMAT.matcher(expression).matches())
    throw new CompoundException("Invalid characters found in expression: '" + expression + "'");
```

白名单 = `\w`（`[A-Za-z0-9_]`）+ 空白 + 一批 ASCII 标点。
**既没有单引号 `'`，也没有任何 CJK 字符。**

而模型里有 10 条这样的表达式：

```
math.clamp(ysm.second_order('飞行身体前倾',ysm.input_vertical*ysm.ground_speed2*5.5,1.5,1,1), -20, 60)
ysm.second_order('游泳待机身体侧倾', -ysm.input_horizontal*20,2,1,1)
ysm.second_order('游泳身体前倾',-ysm.head_pitch,2,1,1)
... （共 10 条，全部含中文参数名 + 单引号）
```

⇒ `EXPRESSION_FORMAT` 不匹配 ⇒ **抛 `CompoundException`**。

**然后**（`loading/json/typeadapter/BakedAnimationsAdapter.java:39-53`）：

```java
for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
    try {
        animations.put(entry.getKey(), bakeAnimation(...));
    }
    catch (Exception ex) {
        GeckoLibConstants.LOGGER.error("Unable to parse animation: " + entry.getKey());
        ex.printStackTrace();
    }
}
```

⇒ **捕获、打一条 ERROR + 堆栈、然后跳过这一整条动画。模型照常加载。**

**实测受影响的动画（3 个）：`fly`、`swim`、`swim_stand`**

**症状分两层**：
1. 加载期日志出现 `Unable to parse animation: fly` + 堆栈（唯一线索）；
2. **代码里写 `RawAnimation` 请求这三个名字时完全静默**——
   `GeoModel.getAnimation()` 找不到就返回 `null`（`:143-167`），
   `AnimationProcessor:50-61` 是 `if (animation != null) animations.add(...)` ⇒ **无错误、无日志、动画就是不播**。

> 这是本次分析里**最容易误诊**的一条：代码看着完全正确，游戏里那三个动画就是不动，且控制台干净。

### 问题 2【静默失效】`ysm.*` 变量未注册 ⇒ 恒为 0

**GeckoLib 源码**（`loading/math/MolangQueries.java:149-150`）：

```java
static Variable getVariableFor(String name) {
    return VARIABLES.computeIfAbsent(applyPrefixAliases(name.toLowerCase(Locale.ROOT), "query.", "q."),
                                     key -> new Variable(key, 0));
}
```

⇒ **任何未被注册的标识符都会自动创建一个值为 `0` 的变量**。不抛异常、不打日志。

模型里这类表达式（通过了白名单，所以**不会**触发问题 1）：

| 动画 | 表达式 |
|---|---|
| `walk` | `ysm.head_yaw/8` |
| `run` | `ysm.head_yaw/3` |
| `riptide` | `ysm.head_pitch` / `-ysm.head_yaw` / `math.clamp(ysm.head_yaw,-60,60)` |

⇒ 全部求值为 **0**（`math.clamp` 是注册过的，`math.clamp(0,-60,60)=0`）。

**症状**：这三个动画**能播**，但"头随视角转动"之类的效果**静默失效**——骨骼被钉在 0 度。
在 YSM 里 `ysm.head_yaw` / `ysm.head_pitch` 是运行时注入的玩家视角变量，GeckoLib 没有。

> 顺带一个**对我们自己代码的直接提醒**：地黄龙的 `query.head_yaw` 是靠
> `DihuangLoongModel#applyMolangQueries` 里 `MathParser.setVariable(...)` **手动注册**的。
> 这正是因为 GeckoLib 不会替我们注册——而一旦漏注册，是**静默 0**，不是报错。

### 问题 3【高，静默半残】155 个被引用的骨骼在 geo 里不存在

**GeckoLib 源码**（`animation/AnimationController.java:521-534`）：

```java
for (BoneAnimation boneAnimation : this.currentAnimation.animation().boneAnimations()) {
    ...
    if (boneSnapshot == null) continue;          // ← 缺失骨骼在这里就已被跳过
    if (bone == null) {
        if (crashWhenCantFindBone)
            throw new RuntimeException("Could not find bone: " + boneAnimation.boneName());
        continue;                                 // ← 默认：静默跳过
    }
```

`crashWhenCantFindBone` 来自 `GeoModel#crashIfBoneMissing()`，**默认 `false`**（`GeoModel.java:91-93`）。

⇒ **缺失骨骼被静默跳过：不崩、不打日志、那些骨骼就是不动。**

**实测**：全模型共 **155 个**被引用但不存在的骨骼，按构成分类：

| 类别 | 数量 | 例子 |
|---|---|---|
| `ysmGlow*` | **42** | `ysmGlowLeft_finger_1`、`ysmGlowWeapen_Gem_1` |
| 翅膀 / 披风 / 头发 | **70** | `Left_Wing_1`、`LeftCloak_1_1`、`LongHair`、`FLongHair` |
| `*_Molang` | **3** | `Root_Molang`、`AllBody_Molang`、`Head_Molang` |
| 其余 | **40** | `punch_left`、`s_back`/`s_front`/`s_left`/`s_right`、`face`、`ElytraLocator`、`MeiMaoL`、`YanBaiL` |

**症状**：受影响的 14 个动画（`walk`/`run`/`swim`/`swim_stand`/`fly`/`jump_up`/`sneak`/`sneaking`/`climb`/`climbing`/`sit`/`sleep`/`boat`/`ride`/`ride_pig`/`attacked`/`death`/`riptide`）
**只驱动存在的那部分骨骼** ⇒ 身体大体在动，**翅膀/披风/头发/发光件/手部细节完全静止**。

> ⚠️ **绝对不要为了"尽早发现问题"把 `crashIfBoneMissing()` 覆写为 `true`**——
> 那会让这个模型在**运行期第一次播这些动画时直接抛 `RuntimeException`**。
> 顺带：我们 `DihuangLoongModel.java:27` 的注释把方法名写成了 `shouldCrashOnMissingBone()`，
> **4.9.2 里的真名是 `crashIfBoneMissing()`**（`GeoModel.java:91-93`）——名字写错，
> 将来真要去覆写时会踩空（编译器会报，但如果只是照注释搜文档就会找不到）。

### 问题 4【中】`animation_length` 缺失 ⇒ 0 骨骼动画的长度变成 `Double.MAX_VALUE`

**GeckoLib 源码**（`BakedAnimationsAdapter.java:59,64-65`）：

```java
double length = animationObj.has("animation_length") ? GsonHelper.getAsDouble(animationObj, "animation_length") * 20d : -1;
...
if (length == -1)
    length = calculateAnimationLength(boneAnimations);
```

```java
// :260-269
private static double calculateAnimationLength(BoneAnimation[] boneAnimations) {
    double length = 0;
    for (BoneAnimation animation : boneAnimations) {
        length = Math.max(length, animation.rotationKeyFrames().getLastKeyframeTime());
        ...
    }
    return length == 0 ? Double.MAX_VALUE : length;   // ← 兜底是 MAX_VALUE，不是 0
}
```

⇒ 实测：

| 动画 | 骨骼数 | `animation_length` | 结果长度 |
|---|---|---|---|
| `hover` | **0** | 缺 | **`Double.MAX_VALUE`** |
| `武器拆分` | **0** | 缺 | **`Double.MAX_VALUE`** |
| `pose` | 73 | 缺 | 由关键帧推算（正常路径） |
| `翅膀默认（展开）` | 24 | 缺 | 由关键帧推算（正常路径） |
| `jump` | 0 | **100** | `100 × 20 = 2000` tick = **100 秒** |

**为什么 `Double.MAX_VALUE` 要紧**：长度参与循环/推进判断，一个"无穷长"的动画在 `thenPlay` 下永不结束；
在 `thenLoop` 下等价于只播第一帧。另外 `jump = 100 秒` 显然是占位值/手滑。

### 问题 5【中】`ysmGlow*` 会被当普通几何体渲染 ⇒ 不发光

YSM 对 `ysmGlow` 前缀的骨骼走**额外的自发光渲染通道**（这是 GAL/角色模型的"眼睛发亮"效果）。
GeckoLib **没有这个概念**——`AnimationController`/`GeoRenderer` 里不存在任何按骨骼名切换渲染通道的逻辑，
所有带 cubes 的骨骼都按同一个 `RenderType` 画。

实测 geo 里 8 个 `ysmGlow*` 骨骼**有 7 个带 cubes**（眼睛瞳孔/虹膜/高光、`ysmGlowRightEyeball`），
且它们是**该部位唯一的几何体**（见 §2.2 的交替链）。

⇒ **症状是"眼睛不亮"**，而不是"多出一块几何体"（这点要说准：它们不是普通几何体的复制品）。

### 问题 6【低】中文动画名 + 两批来源混在一个文件

- 8 个动画名是中文：`待机动画`、`头发选择备份`、`躯体选择备份`、`以巴`、`以巴2`、`武器拆分`、`翅膀默认（展开）`、`翅膀默认（收起）`
- 作为字符串键**技术上可用**（GeckoLib 只做 `Map` 查表），但：
  - 与 `RawAnimation.thenLoop("...")` 的调用点容易因编码问题不一致；
  - `以巴` 很可能是 `尾巴` 的错别字；`*备份` 是"backup"——说明文件经过人工反复改动；
- 更实质的问题：**同一个文件里混了两批来源**（§2.3），英文那批是导入的、与本 geo 不匹配（问题 3）。

---

## 四、不是问题的部分（避免误判）

分析中确认**以下几条都合法**，不要在这上面浪费时间：

| 项 | 结论 | 证据 |
|---|---|---|
| `lerp_mode: "catmullrom"` | ✅ 支持 | `EasingType.java:61` `CATMULLROM = register("catmullrom", ...)`；`BakedAnimationsAdapter.java:162-172` 会把它转成 `"easing"`，`:242-247` 有专门的 spline 参数处理 |
| geo `format_version: 1.12.0` | ✅ 支持 | `FormatVersion.java:9-11` 只认 `1.12.0 / 1.14.0 / 1.21.0`，1.12.0 在列 |
| animation `format_version: 1.8.0` | ✅ 无影响 | `BakedAnimationsAdapter` 全程不读该字段 |
| 贴图 256 与声明 256 | ✅ 一致 | PNG IHDR 实测 256×256；geo 声明 256×256（**与地黄龙那个 512/256 的坑不同**） |
| `identifier: geometry.unknown` | ✅ 无影响 | GeckoLib 走模型类的显式资源路径 |
| 缺失骨骼不崩 | ✅ 前提是别覆写 `crashIfBoneMissing()` | `GeoModel.java:91-93` 默认 `false`；`AnimationController.java:529-534` |
| 单个动画解析失败不影响其他动画 | ✅ | `BakedAnimationsAdapter.java:39-53` 逐条 try/catch |

**另外两条几何/约定层面已核对一致**：

- `BakedAnimationsAdapter.java:205-207` 对旋转做 Bedrock→GeckoLib 转换：**X、Y 取负，Z 不取负**。
  本模型是 Blockbench/Bedrock 系导出的，与地黄龙同源，**预期一致**（未实机验证，见 §七）。
- geo 只有 1 个根骨骼（`Root`），不存在多根骨骼的遍历歧义。

---

## 五、症状对照表（在游戏里怎么认出来）

| 你看到的现象 | 最可能的问题 | 怎么确认 |
|---|---|---|
| 加载日志有 `Unable to parse animation: X` + 堆栈 | **问题 1** | 直接看日志；`fly`/`swim`/`swim_stand` |
| 代码请求某动画，什么都不发生，控制台干净 | **问题 1 的下游** | 该动画名是否在日志的 ERROR 里出现过 |
| 身体在动，但翅膀/披风/头发/手部静止 | **问题 3** | 该动画的缺失骨骼数（§2.3 表） |
| 眼睛不亮 | **问题 5** | `ysmGlow*` 骨骼 |
| 动画"卡在第一帧不动" | **问题 4** | 该动画是否 0 骨骼 + 无 `animation_length` |
| 头不随视角转 | **问题 2** | 是否用了 `ysm.head_yaw` / `ysm.head_pitch` |
| 整个模型不动 | 不是本模型的问题 | 检查 `AnimationController` 谓词、资源路径 |

---

## 六、要让它可用，有哪几条路（**本次不实施，只列方向**）

### 路线 A：承认它是 YSM 模型，不移植（成本最低）

它本来就是给 YSM 的玩家模型。如果目标只是"整合包里有个角色外观"，**继续用 YSM**，
本模组不碰它。这条路的唯一成本是承认"不能当实体模型用"。

### 路线 B：改成真正的 GeckoLib 实体模型（成本最高，但可控）

按问题清单逐项处理：

1. **问题 1**：把 10 条 `ysm.second_order('中文', ...)` 表达式**改成硬编码关键帧**或
   **在 `applyMolangQueries` 里注册同名的 GeckoLib 变量**（去掉单引号与中文参数）；
2. **问题 2**：在模型类里 `MathParser.setVariable("ysm.head_yaw", ...)` 注册真实值
   （地黄龙已有同款先例，可直接照搬）；
3. **问题 3**：**这是最大的工作量**——决定 155 个缺失骨骼是"从别的模型补回来"还是
   "把动画里对它们的引用删掉"；英文那批动画（walk/run/sit/sleep/…）目前对本模型**基本无效**；
4. **问题 4**：给 4 个动画补 `animation_length`，修 `jump` 的 100 秒；
5. **问题 5**：接受"不发光"，或在渲染器里对 `ysmGlow*` 用自发光 `RenderType` 单独画一遍；
6. **问题 6**：动画改名（中文 → ASCII）、拆文件。

### 路线 C：只借几何体，不借动画（折中）

geo 本身**结构干净**（211 骨骼、单根、无重名、无悬空 parent、贴图一致、格式合法），
**可以直接当 GeckoLib 模型的几何体用**。真正不可用的是那 29 个动画。

⇒ 可行做法：**留下 geo，为本模组的用途重做少量动画**（待机/行走/攻击几个），
把 9.1 MB 的 animation 换成自己写的。这条路避开了问题 1/2/3/6。

### 路线 D：先只做最小验证

把 geo + 贴图放进资源目录，写一个最小 `GeoModel` + `GeoEntityRenderer`，
写一个只有 `待机动画` 的控制器（它 117 骨骼、0 缺失、有 `animation_length`，
是**这个文件里最干净的动画**），先确认"几何体+贴图在 GeckoLib 下渲染正常"。
确认后再决定 A/B/C。

---

## 七、待确认项（需要实机或额外信息）

1. **本模型的原宿主**：它是否来自某个具体 YSM 角色包？如果原作者还在维护，
   向作者要一版"为 GeckoLib 导出"的资产比我们自己改便宜得多。
2. **YSM 的旋转符号约定**是否与 GeckoLib 的 `X、Y 取负、Z 不取负` 完全一致
   （本机没有 YSM 源码，无法核实）。若不一致，移植后模型会**镜像**。
3. **`ysmGlow*` 在 YSM 里的确切渲染方式**（额外通道？alpha 混合？全亮度？）——
   决定问题 5 的修法成本。
4. **`以巴` 是否为 `尾巴`（tail）错别字**；`*备份` 系列的动画是否还有用。
5. **英文那批动画的来源**：能否找到匹配的原始 geo（若找到，155 个缺失骨骼可一次性补齐）。
6. **实机渲染效果**：本报告全部结论都是静态解析 + 源码对照得出的，
   **未在游戏里加载过这个模型**。几何体是否真的正常渲染（752 立方体、二乘方贴图映射、
   是否有面片反转）必须实机确认。

---

## 八、给本分支的可复用结论

无论最终走哪条路线，本次分析确认了三条对**我们自己的 NPC 模型**同样适用的规则：

1. **GeckoLib 的失败默认是静默的**。三种失败（动画名找不到、骨骼找不到、变量未注册）
   全部不报错。⇒ **不能靠"没报错"判断模型正常**，必须实机看。
2. **`animation_length` 缺失的兜底是 `Double.MAX_VALUE`，不是 0**。
   我们自己的动画导出时**务必显式写 `animation_length`**。
3. **注释里的方法名也要核实**：`DihuangLoongModel.java:27` 写的 `shouldCrashOnMissingBone()`
   在 4.9.2 里并不存在（真名 `crashIfBoneMissing()`）——
   与本项目"注释要解释为什么并附原版行号"的约定相符，**注释里的 API 名也是需要维护的事实**。
