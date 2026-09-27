# `mo` 模型详细分析（YSM 移植模型）

> 分析对象：`docs/models/末/`（`mo.geo.json` 388 KB、`mo.animation.json` 9.1 MB、`mo.png` 45 KB）
> 分析方式：**只读**结构化解析 + 逐条对照 **GeckoLib 4.9.2 源码**（`开源模组参考文件\Geckolib`，git `0d9d3ea3`）
> 结论依据的是**加载期/运行期的实际代码路径**，不是经验推测
>
> **2026-09-27 追加**：已用 **YSM 2.6.5 的反编译产物**（`闭源模组解压文件\YSM`）
> 核实了前文标注为"未核实"的条目，见 **§九**。该模组是**混淆过的**，
> 因此本报告以**常量池字符串**（可靠）为主、反编译逻辑（仅供参考）为辅，
> 凡引用反编译逻辑处均注明不确定性。

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
| **7** | `ysm.first_order` / `ysm.second_order` | 未注册函数 ⇒ 同问题 1；即使注册也**无法模拟** | 平滑跟随类效果无法移植（**有状态**，见 §9.3） | **高（移植时）** |

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

## 三、七类问题逐条

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

> **§九 + §十 已核实**：`ysmGlow*` 确为 YSM 的**引擎级**约定 ——
> `GeoBone.java` 里有 `private static final String GLOWING_PREFIX = "ysmGlow";`，
> `YSMClientMapper.java` 在烘焙时 `if (rb.name.startsWith("ysmGlow")) bb.glow = true;`
> （证据来自 OpenYSM 参考源，见 §十）。GeckoLib **没有**这个概念，
> `AnimationController`/`GeoRenderer` 里不存在任何按骨骼名切换渲染通道的逻辑，
> 所有带 cubes 的骨骼都按同一个 `RenderType` 画 ⇒ 哪 7 根带几何体的 `ysmGlow*`
> 会当普通不透明几何体渲染（**眼睛不亮**，而不是"多出一块几何体"）。

YSM 对 `ysmGlow` 前缀的骨骼走**额外的自发光渲染通道**（这是角色模型的"眼睛发亮"效果）。
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

### 问题 7【移植时高】`ysm.first_order` / `ysm.second_order` 是**有状态**的，无法模拟

`fly` / `swim` / `swim_stand` 里的 10 条表达式全都包在 `ysm.second_order(...)` 里。
它不是一个纯计算函数——**详细核实见 §9.3**。这里只给结论：

- 它是**一阶/二阶平滑滤波器**，状态**按通道 ID 存在实体模型对象上、跨帧保持**；
- 通道 ID 来自那个字符串参数（`'飞行身体前倾'` → int）；
- ⇒ 即使在模型类里注册一个同名 `MathParser` 函数，**也做不到**——
  GeckoLib 的 `MathValue` 求值是无状态的，没有地方挂接跨帧状态。

**修法方向**：在模型类里用 Java 侧字段维护滤波器状态，每帧算好后 `setVariable`
（与地黄龙 `query.head_yaw` 的做法同型，只是多了一个积分环节）。

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
2. **YSM 的旋转符号约定**是否与 GeckoLib 的 `X、Y 取负、Z 不取负` 完全一致。
   > §九 追加：YSM 源码现已可得（混淆，需反编译），但**本次未查这一条**。
   > 3.5 MB 级的混淆代码里定位"应用动画"的那一处成本较高，且**只在真要移植时才需要**。
   > 预期结论是"一致"（两者都直接消费 Blockbench 导出的 Bedrock 标准 JSON），
   > 但**在移植前必须实机确认，不能靠预期**。
3. ~~**`ysmGlow*` 在 YSM 里的确切判定与渲染方式**~~
   > **§十 已结案**：判定就是 `bone.name.startsWith("ysmGlow")` → 打 `glow` 标记 → 单独渲染通道
   > （OpenYSM `GeoBone.GLOWING_PREFIX` + `YSMClientMapper`，见 §10.3）。
   > 修法成本由此确定：GeckoLib 侧要对这 7 根带几何体的骨骼单独用一次自发光 `RenderType`
   > 再画一遍（或接受"眼睛不亮"）。
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
4. **"有状态"的 Molang 函数无法用纯表达式移植**（§九 核实）：YSM 的
   `first_order` / `second_order` 不是纯函数，而是**按通道 ID 存于实体模型上的跨帧平滑滤波器**。
   ⇒ 若将来要给我们的模型引入"平滑跟随"效果，正确位置是 **Java 侧维护状态**
   （在 `applyMolangQueries` 里累加后 `setVariable`），而不是写进动画 JSON 的表达式里。

---

## 九、从 YSM 反编译源码核实的事实（2026-09-27 追加）

**来源**：`D:\Minecraft\闭源模组解压文件\YSM`（`ysm-2.6.5-neoforge+mc1.21.1-release.jar`，已解压为 933 个 `.class`）。
**反编译**：Vineflower 1.10.1（取自 Gradle 缓存）。

### 9.1 方法与可靠性声明

该模组**经过混淆**（类名如 `Oo000O00O0OOoO00OOOO000O`），因此：

- 本节的**主要依据是常量池字符串**（`javap -v`）——混淆不会改动字符串，**可靠**；
- **反编译出的控制流**只作辅助，凡引用处均标注。样例：Vineflower 在
  `Oo000O00O0OOoO00OOOO000O` 上报了 `Unable to simplify switch on enum` 错误，
  说明该类的反编译**不完整**；
- 结论中凡无法确证的一律标"未定位"，不用推断填空。

### 9.2 决定性证据：`ysm.*` 是 YSM 的私有命名空间

YSM 的 Molang 注册类（`Oo000O00O0OOoO00OOOO000O`，常量池含 **605** 个 `Utf8`）里，
注册名是**裸名**（反编译可见）：

```java
this.oOo0OO0O0o000OO0O000oo0o("head_yaw",        v -> v.<...>().OO000o0ooOooooOOOOO0Ooo0);   // 变量
this.oOo0OO0O0o000OO0O000oo0o("head_pitch",      v -> v.<...>().Oo0O0OoOo0O0oOoo0000O0oO);
this.oOoo00O0o0oO0o0oO00OO0O0("ground_speed2",   Oo000O00O0OOoO00OOOO000O::Oo0O0OoOo0O0oOoo0000O0oO);
this.oOoo00O0o0oO0o0oO00OO0O0("input_vertical",  OO00O0O0OOOo0oo0o0o00oo0::oOo0OO0O0o000OO0O000oo0o);
this.oOoo00O0o0oO0o0oO00OO0O0("input_horizontal",OO00O0O0OOOo0oo0o0o00oo0::oOoo00O0o0oO0o0oO00OO0O0);
...
this.oOo0OO0O0o000OO0O000oo0o("first_order",  new oooo00oo0000oOOOO0o0o000());   // 函数
this.oOo0OO0O0o000OO0O000oo0o("second_order", new oo0OoO0O00000o0ooo0O0OoO());
this.oOo0OO0O0o000OO0O000oo0o("defer",        new OO0O0O0o0Oo00Ooo0O00o0OO());
```

模型里写的是 **`ysm.head_yaw` / `ysm.second_order(...)`**，注册的是**裸名** ⇒
**`ysm.` 前缀由 YSM 的 Molang 引擎处理**（前缀剥离或命名空间绑定，
**本次未定位到那一处**——但无论哪种实现，GeckoLib 都没有）。

**这套约定不是 `mo` 的作者自创**，YSM 自己的内置模型里到处都在用（实测使用次数）：

| 名称 | 在 YSM 内置模型中的出现次数 |
|---|---|
| `ysm.head_yaw` | **6834** |
| `ysm.head_pitch` | **5894** |
| `ysm.second_order` | **715** |
| `ysm.bone_rot` | 274 |
| `ysm.input_vertical` | 264 |
| `ysm.ground_speed2` | 28 |
| `ysm.input_horizontal` | 3 |
| `ysm.first_order` / `ysm.defer` / `ysm.perlin_noise` | 0（已注册，但内置模型未用） |
| `ysmGlow*` 骨骼 | 出现在 **55** 个模型/动画 JSON 中 |

### 9.3 `first_order` / `second_order` 的真实语义：**有状态的跨帧平滑滤波器**

这是本次核实**最有价值的一条**。反编译 `oo0OoO0O00000o0ooo0O0OoO`（`second_order`）：

```java
int   id     = args.getInt(0);                    // ← 参数 0：通道 ID
float target = args.getFloat(1);                  // ← 参数 1：目标值
int   argc   = args.count();
float f = 1.0F, d = 1.0F, e = 1.0F;
if (argc >= 3) f = args.getFloat(2);              // ← 参数 2/3/4：滤波参数
if (argc >= 4) d = args.getFloat(3);
if (argc >= 5) e = args.getFloat(4);

StateMap map   = ((ModelHolder) args.owner()).state().O0O0Oo0Oooo0OOoOOO0ooo0O();
State    state = map.get(id);
if (state == null) { map.put(id, new State(target, f, d, e)); return target; }
state.update(target, f, d, e);
return state.value();
```

`first_order`（`oooo00oo0000oOOOO0o0o000`）是它的简化版（只有 `id, value, speed`）。

⇒ 三个结论：

1. **它们不是纯函数**：状态存在**实体模型对象上**（`args.owner()` 即 animatable），**跨帧保持**
   ⇒ **无法用纯 Molang 表达式复现**。想在 GeckoLib 里做同样效果，状态必须放在 Java 侧。
2. **参数 0 是"通道 ID"，来自那个中文/英文字符串**（`'飞行身体前倾'` 被转成 int）。
   ⇒ 字符串在 YSM 的 Molang 方言里是**合法且必需**的字面量语法 —— 这正是问题 1 的另一面。
3. 签名要求 `argc >= 2`，与模型里的 5 参调用 `('名字', 值, 1.5, 1, 1)` 一致。

### 9.4 YSM 支持单引号字面量 ⇒ 从反面确认问题 1

YSM 的 Molang 包装类（`O0O0oOOOOo00o0o00o0o00oO`）里有一个**预处理器**，
逐字符扫描并**维护 `'...'` 字面量状态**（在该状态下 `//` 与 `/* */` 不被当注释）：

```java
} else if (var6 == '\'') {           // 进入/离开单引号字面量
    var4 = true;
    var1.append('\'');
}
```

⇒ **单引号字符串是 YSM Molang 方言的正式语法**。

而 GeckoLib 的 `EXPRESSION_FORMAT`（`MathParser.java:46`）**不含 `'`、也不含 CJK**，
`:187-188` 直接抛异常 ⇒ 这就是问题 1 的完整闭环。

### 9.5 为什么模型作者从来没有发现这些问题

YSM 的解析失败处理**比 GeckoLib 友好得多**（`O0O0oOOOOo00o0o00o0o00oO:20-31`）：

```java
YesSteveModel.LOGGER.error("Failed to parse molang expression: {}\n{}", ex.getMessage(), 表达式原文);
// 并且在玩家客户端弹一条可翻译消息：
Component.translatable("error.yes_steve_model.parse_molang_exp")
         .append(ex.getMessage()).append("\n---\n").append(表达式原文);
...
return Constant.ZERO;    // ← 失败时优雅降级为常量
```

对比：

| | 解析失败时 |
|---|---|
| **YSM** | 日志打**表达式原文** + 给玩家一条可翻译提示 + 优雅降级为常量 |
| **GeckoLib** | `Unable to parse animation: <动画名>` + 堆栈，**并把整条动画丢弃** |

⇒ 在 YSM 里这些问题**根本不会出现**（表达式本来就合法）；
一旦搬到 GeckoLib，失败方式从"报错降级"变成"整条动画消失"，而作者没有任何理由预期这一点。

### 9.6 YSM Molang API 面（按用途分组，摘自常量池）

**GeckoLib 完全没有这些**：

| 类别 | 名称 |
|---|---|
| **有状态滤波** | `first_order`、`second_order`、`defer` |
| **特效/声音** | `perlin_noise`、`particle`、`play_sound`、`stop_sound`、`stop_all_sounds` |
| **跨端/调试** | `sync`、`eval`、`literal`、`byId`、`byString`、`copyOnClickText`、`dump_biome`/`dump_effects`/`dump_equipped_item`/`dump_mods`/`dump_relative_block` |
| **输入/视角** | `head_yaw`、`head_pitch`、`input_vertical`、`input_horizontal`、`xxa`、`yya`、`zza`、`mouse`、`keyboard`、`fps`、`person_view`、`time_delta` |
| **骨骼读写** | `bone_rot`、`bone_pos`、`bone_scale`、`bone_pivot_abs` |
| **渲染上下文** | `texture_name`、`rendering_in_inventory`、`rendering_in_paperdoll`、`first_person_mod_hide` |
| **状态/环境** | `ground_speed2`、`delta_movement_length`、`on_ground_time`、`in_ground`、`ladder_facing`、`sky_light`、`block_light`、`weather`、`biome_category`、`dimension_name`、`elytra_rot_x/y/z`、`step_height_addition`、`relative_block_name(_any)` |
| **实体/物品** | `armor_value`、`attack_damage`、`attack_knockback`、`attack_speed`、`attack_time`、`swing_time`、`hurt_time`、`air_supply`、`arrow_count`、`food_level`、`frozen_ticks`、`entity_gravity`、`entity_reach`、`block_reach`、`eye_in_water`、`effect_level`、`has_boots`/`has_helmet`/…、`hit_target_id`/`hit_target_type`、`projectile_owner`、`shoot_item_id`、`hooked_in`、`is_fishing`/`is_biting`/`is_riptide`/`is_sleep`/`is_sneak`/`is_spectral_arrow`/… |
| **模组兼容** | `is_maid`、`touhou_little_maid`（车万女仆）、`left/right_shoulder_parrot_variant` |

### 9.7 `_Molang` 机制：**它不是引擎概念**（原判"未定位"是假阳性）

> **2026-09-27 纠正。** 原文写"`_Molang` 字符串只在 1 个 class 中出现"——
> 那是一次**假阳性**：PowerShell 的 `Select-String` **默认不区分大小写**，
> 命中的其实是 `error.yes_steve_model.parse_molang_exp` 里的 `_molang`。
> 用 `-CaseSensitive` 复查，混淆版与 OpenYSM 参考源里**都没有任何 `_Molang`**。

**真实机制**：YSM 把**动画里出现的字符串值**当 Molang 表达式求值，
**与骨骼名无关** —— OpenYSM 的 `JsonKeyFrameUtils.java:86,100,125`：

```java
if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) { ... }
...
if (primitive.isString()) {
    IValue value = parser.parseExpression(primitive.getAsString(), false);
```

⇒ `<BoneName>_Molang` **纯粹是给模型师看的命名约定**（一个空骨骼，标明"这一轨写的是
Molang 而不是数字"），引擎不需要认识这个后缀。

**这条纠正对结论的影响**：

| | 原结论 | 纠正后 |
|---|---|---|
| `_Molang` 骨骼在 GeckoLib 下 | "未定位，可能有关" | **完全没有问题** —— GeckoLib 的 `MathParser.parseJson`（`:158-175`）同样把字符串当 Molang 编译，处理方式一致 |
| 真正的差异在哪 | —— | **只在于变量与函数是否存在**：`ysm.*` 是 YSM 私有的（§9.2），GeckoLib 没有 |

⇒ 也就是说，`AllBody_Molang` / `Head_Molang` / `Root_Molang` 这三根**缺失骨骼**
（问题 3 的一部分）仍然是真的缺失（geo 里没有），但它们的**后缀本身不构成额外障碍**。

**方法论教训（值得带走）**：在二进制/混淆产物里搜字符串时，
① PowerShell `Select-String` 默认不区分大小写，必须显式 `-CaseSensitive`；
② **在混淆产物里"搜不到某个字符串"是弱证据** —— 混淆器常做字符串加密，
YSM 2.6.5 里 `second_order`/`head_yaw` 等能搜到而 `ysmGlow` 搜不到，
就不能据此断定后者不存在（实际存在，见问题 5）。

### 9.8 对前文结论的净影响

| 前文结论 | 核实后 |
|---|---|
| 它是 YSM 模型 | **加强**：`ysm.*` 命名空间 + 内置模型使用规模（`head_yaw` 6834 次） |
| 问题 1（`'` + 中文 ⇒ 丢动画） | **加强**：YSM 侧预处理器专门维护 `'...'` 状态 ⇒ 单引号是正式语法，GeckoLib 收不了 |
| 问题 2（`ysm.*` 变量恒为 0） | **加强**：确证为 YSM 私有变量，且 GeckoLib 无对应物 |
| 问题 3（155 缺失骨骼） | 不变（纯几何对比，与 YSM 无关） |
| 问题 4（`Double.MAX_VALUE`） | 不变（GeckoLib 侧行为） |
| 问题 5（`ysmGlow`） | **部分修正**：约定**确认存在**（55 个文件），但**判定位置未定位** ⇒ 前文的"机制描述"已降级为推断 —— **此结论已被 §10.3 取代：判定已定位（`startsWith("ysmGlow")`），问题 5 完全成立** |
| 新增 | **问题 7**：`first_order`/`second_order` 是**有状态滤波器** ⇒ 连"用 GeckoLib 变量模拟"都做不到，必须 Java 侧维护状态 |
| 新增 | §9.5：作者没发现的原因是 **YSM 的错误报告远比 GeckoLib 友好**，且失败语义不同（降级 vs 丢动画） |

---

## 十、OpenYSM 参考源与本次实机修复（2026-09-27 追加）

### 10.1 OpenYSM 参考源

YSM 是**闭源且混淆**的，直接读它的字节码收益有限（§9.1）。用户提供了一个第三方开源实现
作为参照，已克隆到 `D:\Minecraft\开源模组参考文件\OpenYSM`。

| 项 | 值 |
|---|---|
| 来源 | `https://github.com/OpenYSM/OpenYSM` |
| 分支 / 提交 | `1.20.1-forge`（默认分支）@ `a515d44`（2026-08-02 "goodbye"） |
| 版本 / 平台 | `mod_version 2.6.6.6`；**MC 1.20.1**、Forge 1.20.1-47.4.20 + Fabric（Architectury 多加载器） |
| 检出规模 | 1158 文件 / 4.8 MB（1133 个 `.java` + 14 个 `.molang`） |
| 结构 | `common/`（912 java）+ `forge/`（155）+ `fabric/`（66） |

> ⚠️ **局限性（用户已声明，必须记住）**：这是**非官方**实现、**MC 1.20.1**、
> 与出货的 YSM 2.6.5（1.21.1 NeoForge）**差异较大**。
> ⇒ 它只能用来**印证约定与思路**，不能当作 YSM 行为的权威依据；
> 凡用它下的结论都要标注"来自 OpenYSM 参照、非官方版本"。

**克隆方式（值得复用）**：该仓库约 **200 MB**，本机到 GitHub 的大文件传输不可用
（`git clone --depth 1` 与 8 MB 的 HTTP range 请求都长时间零进展），但
**无 blob 部分克隆 + 稀疏检出**可以绕开：

```powershell
git clone --filter=blob:none --no-checkout --depth 1 --single-branch <url> <dir>   # 4.5 秒 / 0.1 MB
git -C <dir> sparse-checkout init --no-cone
git -C <dir> sparse-checkout set '/**/*.java' '/*.gradle' '/*.properties' '**/*.molang'
git -C <dir> checkout                                                             # 57 秒 / 4.8 MB
```
（要哪些就写哪些模式；`git ls-tree -r --name-only HEAD` 只需要 tree 对象，
在检出前就能列出全部文件名与扩展名分布，用来决定稀疏模式。）

### 10.2 本次实机反馈与修复：一个值得带走的 GeckoLib 陷阱

实机报了三件事：① 模型比碰撞箱大太多；② 武器位置偏移、不在手上；③ 模型整体偏离碰撞箱。
**三者同源**，而且都不是"模型被做得太大"。

**根因：把「一整套形态姿态」当成了「叠加层」。**

`翅膀默认（展开）` 不是"翅膀姿势层"，而是**有翼形态的全身姿态**——
它给 `Root` 设了 `scale = 1.8` 与 `position = [-13, -7.98, 16.89]`、
给 `Weapen` 设了 `position` 与 `scale = 1.2`、给 `Tail` 设了 `scale = 1.1`。
而 `待机动画`**根本没有 `Root` 这一轨**，也不给 `Weapen` 设位移/缩放。

**关键机制（GeckoLib 侧，与 YSM 无关）**：
`AnimationProcessor.java:107-127` 对每根骨骼是**按通道独立**写值的 ——

```java
AnimationPoint rotXPoint = boneAnimation.rotationXQueue().poll();   // 每通道各自一条队列
...
if (rotXPoint != null && rotYPoint != null && rotZPoint != null) { bone.setRotX(...); ... }
if (posXPoint != null && ...) { bone.setPosX(...); ... }
if (scaleXPoint != null && ...) { bone.setScaleX(...); ... }
```

⇒ **某控制器"没设"的通道，它不会把骨骼复位**，于是另一个控制器设的值会**原样泄漏**到最终姿态。
后果：

| 泄漏的通道 | 症状 |
|---|---|
| `Root.scale = 1.8` | 整个模型被放大 1.8 倍（症状 ①） |
| `Root.position` | 整体平移约 0.8 / 0.5 / 1.1 格（症状 ③） |
| `Weapen.position` + `scale` | 武器脱手并放大 1.2 倍；而武器**旋转**来自待机动画 ⇒ **混合姿态**（症状 ②） |

**规则（写给下一个人）**：

1. **多控制器叠加时，"两条动画重叠的骨骼"不是唯一的冲突面，要逐通道看。**
   一条动画给某骨骼设了 `scale` 而另一条只设 `rotation`，两帧叠加后得到的是
   "A 的 rotation + B 的 scale"——**没有任何一项会被报错或警告**。
2. **根骨骼（`Root`）上的 `position`/`scale` 尤其危险**：它影响整个模型，
   症状会表现为"模型大小/位置不对"，让人误以为是自己缩放写错了。
3. **判断一个动画是"姿态"还是"层"**：看它是否动了 `Root`。
   动了 `Root` 的动画基本可以断定是**整套形态姿态**（用于状态切换），不适合当叠加层。
4. 顺序仍然重要：`后注册的控制器覆盖前者`（同一通道内）。
   本次保留了"翅膀层在前、主状态机在后"，使手臂/武器以**待机姿势**为准。

**修法**（用户裁定"保留翅膀张开"）：从资产的 `翅膀默认（展开）` 里删掉 5 个泄漏键
（`Root.position` / `Root.scale` / `Weapen.position` / `Weapen.scale` / `Tail.scale`，
188 字节，24 → 22 骨骼），另加 0.80 的渲染缩放
（模型按原版玩家骨架算是偏大的：脚 0 → 头顶 39.82 单位 = 2.49 格，
原版玩家 32 单位 = 2.00 格 ⇒ `32/39.82 ≈ 0.804`）。
注意**缩放本身与泄漏是两件独立的事**：删掉泄漏后模型仍有 1.24 倍偏高，必须另外缩放。

### 10.3 由 OpenYSM 印证/纠正的结论

| 结论 | 状态 |
|---|---|
| 问题 5 `ysmGlow*` 走独立自发光通道 | **印证**：`GeoBone.GLOWING_PREFIX = "ysmGlow"` + `YSMClientMapper` 里 `startsWith("ysmGlow") → bb.glow = true` |
| §9.7 `_Molang` 有引擎语义 | **纠正**：无引擎语义，纯命名约定（原判是大小写不敏感的假阳性），详见 §9.7 |
| §9.2 `ysm.*` 是 YSM 私有命名空间 | **印证**：`client/animation/molang/YSMBinding.java` 里注册了 `head_yaw` / `input_vertical` / `ground_speed2` / `first_order` / `second_order` |
| YSM 用 `Charset.defaultCharset()` 读 JSON | **不适用**：这是 GeckoLib 的行为（`FileLoader.java:73`），YSM 用自己的解析器（OpenYSM 用 `MolangParser` + Gson），不受该字符集问题影响 |
