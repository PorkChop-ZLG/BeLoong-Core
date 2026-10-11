"""Static acceptance checks for the Execute passive (read-only)."""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
FAIL = []


def check(ok, message):
    print(("  ok   " if ok else "  FAIL ") + message)
    if not ok:
        FAIL.append(message)


def load_json(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def icon_saturation(path):
    """Worst-case saturation of an icon, or None when Pillow is not importable.

    Used by section [4] to prove the level-0 icon is really grayscale (the icon tool derives it
    with Image.convert("L"), so any colour left in it means the wrong file was shipped).
    """
    try:
        from PIL import Image
    except ImportError:
        return None
    with Image.open(path) as image:
        pixels = list(image.convert("RGB").getdata())
    worst = 0.0
    for red, green, blue in pixels:
        high, low = max(red, green, blue), min(red, green, blue)
        if high:
            worst = max(worst, (high - low) / high)
    return worst


# 1. every JSON under src/main/resources parses ---------------------------------
print("[1] JSON syntax")
count = 0
for base, _dirs, files in os.walk(os.path.join(ROOT, "src", "main", "resources")):
    for name in files:
        if not name.endswith(".json"):
            continue
        path = os.path.join(base, name)
        count += 1
        try:
            load_json(path)
        except Exception as exc:  # noqa: BLE001
            check(False, "%s -> %s" % (os.path.relpath(path, ROOT), exc))
check(True, "%d JSON files parsed" % count)

# 2. ability definition --------------------------------------------------------
print("[2] ability execute.json")
ability = load_json(os.path.join(
    ROOT, "src", "main", "resources", "data", "beloong", "dragonsurvival", "dragon_ability", "execute.json"))
effect = ability["actions"][0]["target_selection"]["applied_effects"]["entity_effect"][0]
check(effect["effect_type"] == "beloong:execute", "effect_type = beloong:execute")
check(ability["activation"]["activation_type"] == "dragonsurvival:passive", "activation is passive")
check("cooldown" not in ability["activation"],
      "activation has no cooldown (DS auto-release would fire on every hit)")
upgrade = ability["upgrade"]
check(upgrade["upgrade_type"] == "dragonsurvival:dragon_growth", "upgrade = dragon_growth")
check(upgrade["maximum_level"] == 16,
      "maximum_level = 16 (growth 150..300, one level per 10)")
growth = upgrade["growth_requirement"]
check(growth["base"] == 150 and growth["per_level_above_first"] == 10,
      "growth 150 + 10/level -> L1 = 150, L16 = %s" % (150 + 10 * 15))
threshold = effect["threshold"]
check(threshold["base"] == 5.0 and threshold["per_level_above_first"] == 1.0,
      "threshold 5.0 + 1.0/level -> L1 = 5.0%%, L16 = %s%%" % (5.0 + 1.0 * 15))
# The cooldown used to be a 15-entry "lookup" whose fallback carried the awkward step
# -7.142857. Once maximum_level became 16, level 16 silently fell through to that
# fallback (-7.142857 * 15 -> 92 ticks), dropping under the intended 5s floor. An
# integer step keeps every level exact, so a plain "linear" is both simpler and safe.
cooldown = effect["cooldown"]
# I-3b: every LevelBasedValue must declare its dispatch type. Without this, a typo in
# "minecraft:linear" (or switching a field to "lookup") passes BOTH gates and only blows up when
# the datapack actually loads in game.
for field, value in (("growth_requirement", growth), ("threshold", threshold),
                     ("cooldown", cooldown), ("damage", effect["damage"]),
                     ("mark_duration", effect["mark_duration"])):
    check(value.get("type") == "minecraft:linear", "%s is a plain linear" % field)
check(effect["damage"]["per_level_above_first"] == 0, "damage does not scale with level")
check(cooldown["type"] == "minecraft:linear", "cooldown is a plain linear (no lookup fallback)")
check(cooldown["base"] == 200 and cooldown["per_level_above_first"] == -8,
      "cooldown 200 - 8/level -> L1 = 200 ticks (10s), L16 = %s ticks (%ss)"
      % (200 - 8 * 15, (200 - 8 * 15) / 20))
cd_values = [cooldown["base"] + cooldown["per_level_above_first"] * (level - 1)
             for level in range(1, 17)]
check(all(float(value).is_integer() for value in cd_values),
      "every cooldown is a whole number of ticks (no (int)-truncation drift vs the tooltip)")
check(all(cd_values[i] > cd_values[i + 1] for i in range(15)),
      "cooldown decreases at every level")
check(cd_values[-1] < 100,
      "L16 cooldown %s ticks is below the 5s (100 tick) floor" % cd_values[-1])
check(effect["damage"]["base"] == 999999, "damage = 999999")

# 3. thresholds <-> effect amplifier -------------------------------------------
print("[3] threshold / amplifier mapping")
# I-3a: the grid step lives in JAVA (ExecuteThresholdEffect: THRESHOLD_PER_LEVEL /
# THRESHOLD_STEP_PERCENT) -- READ IT from there instead of hard-coding 0.5 here. Otherwise
# changing the Java step would leave this gate green while the in-game line silently shifts.
effect_path = os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                           "registry", "ExecuteThresholdEffect.java")
effect_src = open(effect_path, encoding="utf-8").read()
per_level_match = re.search(r"THRESHOLD_PER_LEVEL\s*=\s*([0-9.]+)F", effect_src)
step_match = re.search(r"THRESHOLD_STEP_PERCENT\s*=\s*([0-9.]+)F", effect_src)
check(per_level_match is not None, "ExecuteThresholdEffect declares THRESHOLD_PER_LEVEL")
check(step_match is not None, "ExecuteThresholdEffect declares THRESHOLD_STEP_PERCENT")
per_level = float(per_level_match.group(1)) if per_level_match else 0.005
step = float(step_match.group(1)) if step_match else 0.5
check(abs(per_level - step / 100.0) < 1e-9,
      "the two Java constants agree: fraction %s == percent %s / 100" % (per_level, step))
# The threshold is quantised onto that grid by ExecuteThresholdEffect#amplifierForThreshold, and the
# in-game tooltip prints the RAW linear value. A 1.0%/level curve from 5.0% lands on the grid
# exactly at every level, so tooltip == applied value everywhere; the mapping is amplifier == 2*level + 7.
ok = True
for level in range(1, 17):
    percent = threshold["base"] + threshold["per_level_above_first"] * (level - 1)
    amplifier = round(percent / step) - 1
    applied = (amplifier + 1) * step
    if amplifier != 2 * level + 7 or abs(applied - percent) > 1e-9:
        ok = False
        print("      L%d percent=%s amplifier=%s applied=%s" % (level, percent, amplifier, applied))
check(ok, "every level lands exactly on the Java grid (%s%%): amplifier == 2*level + 7 "
          "(L1 -> 9 -> 5.0%%, L16 -> 39 -> 20.0%%), so the tooltip matches the applied value" % step)
max_amplifier = max(round((threshold["base"] + threshold["per_level_above_first"] * (level - 1)) / step) - 1
                    for level in range(1, 17))
check(max_amplifier <= 255,
      "max amplifier %d stays within MobEffectInstance's 0..255 clamp" % max_amplifier)

# 4. icons ---------------------------------------------------------------------
print("[4] icons")
for name in ("execute_0", "execute_1"):
    path = os.path.join(ROOT, "src", "main", "resources", "assets", "beloong",
                        "textures", "gui", "sprites", "abilities", name + ".png")
    check(os.path.isfile(path), "%s.png exists" % name)
    if os.path.isfile(path):
        with open(path, "rb") as handle:
            head = handle.read(26)
        check(head[:8] == b"\x89PNG\r\n\x1a\n", "%s.png has a PNG signature" % name)
        # I-3e: this gate used to stop at the signature, so a wrong size -- or a colour level-0
        # icon -- could not fail it. 64x64 is the AUTHORISED exception for this one ability
        # (32x32 is the project norm, see 总设计 §7.3); the level-0 icon must be grayscale.
        width = int.from_bytes(head[16:20], "big")
        height = int.from_bytes(head[20:24], "big")
        check((width, height) == (64, 64),
              "%s.png is 64x64 (authorised exception), got %dx%d" % (name, width, height))
        if name == "execute_0":
            saturation = icon_saturation(path)
            if saturation is None:
                # Fall back to the PNG colour type (0 = grayscale, 4 = grayscale+alpha).
                check(head[25] in (0, 4),
                      "execute_0.png is grayscale (PNG colour type %d, Pillow unavailable)" % head[25])
            else:
                check(saturation <= 0.001,
                      "execute_0.png is grayscale (max saturation %.4f)" % saturation)
for entry in ability["icon"]["texture_entries"]:
    resource = entry["texture_resource"]
    path = os.path.join(ROOT, "src", "main", "resources", "assets", "beloong",
                        "textures", "gui", "sprites", resource.split(":")[1] + ".png")
    check(os.path.isfile(path), "icon %s -> %s" % (entry["from_level"], resource))

# 5. damage type + tags --------------------------------------------------------
print("[5] damage type and tags")
damage_type = load_json(os.path.join(ROOT, "src", "main", "resources", "data", "beloong", "damage_type", "execute.json"))
check(damage_type["message_id"] == "beloong.execute", "message_id = beloong.execute")
tags_dir = os.path.join(ROOT, "src", "main", "resources", "data", "minecraft", "tags", "damage_type")
for tag in ("bypasses_armor", "bypasses_effects", "bypasses_resistance", "bypasses_enchantments",
            "bypasses_shield", "bypasses_wolf_armor", "bypasses_cooldown", "no_knockback"):
    path = os.path.join(tags_dir, tag + ".json")
    values = load_json(path)["values"]
    check("beloong:execute" in values, "minecraft:%s contains beloong:execute" % tag)
# bypasses_invulnerability 的往返记录（同一天内两次反转，都保留在案）：
#   初版 —— 断言**不存在**这个标签文件（理由：它会一并穿透创造模式与 setInvulnerable(true) 的实体）。
#   2026-10-11(三) —— 用户接受全部代价后允许加入，好让靠伤害钳制 / 无敌帧过日子的 Boss 也能被斩杀。
#   2026-10-11(五) —— **用户实测后撤回**：该标签的效果接近 /kill，会让**部分 Boss 的动画失效**
#     （`bypasses_invulnerability` 是被 `WitherBoss` 的出生无敌、灾变各 Boss 的转阶段无敌、
#      `ArmorStand`/`ItemFrame`/`Ghast`/`Warden` 的无敌、以及 `checkTotemDeathProtection` 共同读取的
#     同一个标签 —— 一旦列入就等于对所有读取方同时宣布「这是 kill 级伤害」）。
#     用户选择保住 Boss 动画，接受「部分 Boss 挡得住斩杀」。
#   结论 —— 不变量回到初版：**不得存在**这个文件。缺了它，Boss 只是「挡下斩杀」而不会崩行为；
#     而 (a) 已保证挡下时仍然消耗标记 + 进冷却 + 给出「斩杀被挡下」提示，所以不会退化成每 tick 重试。
check(not os.path.isfile(os.path.join(tags_dir, "bypasses_invulnerability.json")),
      "no bypasses_invulnerability (kill-like: it also disables boss animations; reverted 2026-10-11)")

# 6. effect_type registered in Java --------------------------------------------
print("[6] java registration")
registry = open(os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                             "ability", "AbilityEffectRegistry.java"), encoding="utf-8").read()
check('"execute"' in registry and "ExecuteEffect.CODEC" in registry,
      "AbilityEffectRegistry registers beloong:execute -> ExecuteEffect.CODEC")
mob_effects = open(os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                                "registry", "ModMobEffects.java"), encoding="utf-8").read()
check('"execute_threshold"' in mob_effects, "ModMobEffects registers beloong:execute_threshold")
core = open(os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                         "BeLoongCore.java"), encoding="utf-8").read()
check("ModAttachments.register" in core, "BeLoongCore registers ModAttachments")

# Regression guard for the 2026-10-09 PvP fix: DS's TargetingMode.NON_ALLIES treats
# teammates as allies and rejects them outright (even when friendly fire is on), so
# player victims must be routed through vanilla's canHarmPlayer instead.
mark_handler = open(os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                                 "registry", "ExecuteMarkHandler.java"), encoding="utf-8").read()
check("canHarmPlayer" in mark_handler and "victim instanceof Player" in mark_handler,
      "ExecuteMarkHandler routes player victims through Player#canHarmPlayer")

# 7. lang parity ---------------------------------------------------------------
print("[7] lang parity")
zh_data = load_json(os.path.join(ROOT, "src", "main", "resources", "assets", "beloong", "lang", "zh_cn.json"))
en_data = load_json(os.path.join(ROOT, "src", "main", "resources", "assets", "beloong", "lang", "en_us.json"))
zh, en = set(zh_data), set(en_data)
check(zh == en, "zh_cn / en_us key sets identical (zh=%d en=%d, only-zh=%s, only-en=%s)"
      % (len(zh), len(en), sorted(zh - en), sorted(en - zh)))

required_keys = [
    "death.attack.beloong.execute",
    "death.attack.beloong.execute.player",
    "death.attack.beloong.execute.unknown_source",
    "death.attack.beloong.execute.stage.newborn",
    "death.attack.beloong.execute.stage.young",
    "death.attack.beloong.execute.stage.adult",
    "death.attack.beloong.execute.stage.ancient",
    "dragon_ability.beloong.execute",
    "dragon_ability.beloong.execute.desc",
    "dragon_ability.beloong.execute.dynamic_desc",
    "effect.beloong.execute_threshold",
    "effect.beloong.execute_threshold.description",
    "message.beloong.execute.triggered",
]
for key in required_keys:
    check(key in zh, "lang key present: " + key)

# 8. lang values must survive vanilla's translation format parser ---------------
# Faithful port of TranslatableContents#decomposeTemplate (1.21.1, lines 124-170):
#   * FORMAT_PATTERN only matches "%s", "%<n>$s" and "%%";
#   * any other "%X"  -> TranslatableFormatException("Unsupported format")
#   * any bare "%" left in a literal chunk -> IllegalArgumentException
#     -> TranslatableFormatException
# In other words: **a literal percent sign must be written as "%%"**.
# A wrong value here throws at tooltip-render time, so it is checked for EVERY key.
print("[8] lang format placeholders (vanilla decomposeTemplate rules)")
FORMAT_PATTERN = re.compile(r"%(?:(\d+)\$)?([A-Za-z%]|$)")


def translation_errors(value):
    """Return the reasons `value` would throw TranslatableFormatException."""
    errors = []
    cursor = 0

    for match in FORMAT_PATTERN.finditer(value):
        start, end = match.start(), match.end()
        literal = value[cursor:start]
        if "%" in literal:
            errors.append("bare %% in %r" % literal)
        conversion = match.group(2)
        if not (conversion == "%" and value[start:end] == "%%") and conversion != "s":
            errors.append("unsupported format %r" % value[start:end])
        cursor = end

    tail = value[cursor:]
    if "%" in tail:
        errors.append("bare %% in %r" % tail)

    return errors


for name, data in (("zh_cn.json", zh_data), ("en_us.json", en_data)):
    problems = []
    for key, value in data.items():
        if not isinstance(value, str):
            continue
        problems.extend((key, reason) for reason in translation_errors(value))
    check(not problems,
          "%s: every lang value parses as a translation (%d bad: %s)" % (name, len(problems), problems[:5]))

check(zh_data["dragon_ability.beloong.execute.dynamic_desc"].count("%s") == 4,
      "dynamic_desc takes 4 arguments (matches ExecuteEffect#getDescription)")
check(zh_data["death.attack.beloong.execute"].count("%s") == 1, "base death message takes 1 argument")
check(zh_data["death.attack.beloong.execute.player"].count("%s") == 3,
      "player death message takes 3 arguments (victim, killer, stage)")
check(zh_data["death.attack.beloong.execute.unknown_source"].count("%s") == 2,
      "unknown-source death message takes 2 arguments")
for stage_path in ("newborn", "young", "adult", "ancient"):
    stage_key = "death.attack.beloong.execute.stage." + stage_path
    check(zh_data[stage_key].count("%s") == 0, "stage word %s has no placeholder" % stage_path)
check(en_data["dragon_ability.beloong.execute.dynamic_desc"].count("%s") == 4,
      "en dynamic_desc takes 4 arguments")

# I-3c: the two actionbar messages and the effect description were previously checked for
# EXISTENCE only. A stray extra %s in either message throws MissingFormatArgumentException at
# render time -- in game, never at build time.
for name, data in (("zh_cn", zh_data), ("en_us", en_data)):
    check(data["message.beloong.execute.triggered"].count("%s") == 1,
          "%s 'triggered' message takes exactly 1 argument" % name)
    check(data["message.beloong.execute.refused"].count("%s") == 1,
          "%s 'refused' message takes exactly 1 argument" % name)
    check(data["effect.beloong.execute_threshold.description"].count("%s") == 0,
          "%s effect description takes no argument" % name)

# I-3d: every subtitle declared in sounds.json must exist in BOTH languages, otherwise the
# subtitle line renders a raw key.
sounds = load_json(os.path.join(ROOT, "src", "main", "resources", "assets", "beloong", "sounds.json"))
subtitles = sorted({entry["subtitle"] for entry in sounds.values()
                    if isinstance(entry, dict) and "subtitle" in entry})
check(bool(subtitles), "sounds.json declares at least one subtitle")
for key in subtitles:
    check(key in zh_data and key in en_data, "subtitle key present in both langs: " + key)

# Percent signs that must survive into the rendered text are emitted as "%%".
# NOTE (2026-10-10): the execute dynamic_desc used to end with the per-level formula
# ("每级斩杀线 = 效果等级 × 0.5% + 0.5%"), which is why this assertion originally
# targeted it. That line was dropped from both lang files, so the key now carries no
# literal percent sign at all -- there is nothing left to escape there. The effect
# descriptions are the keys that still print a literal "%". (Whether a value contains
# a bare "%" anywhere is already covered for EVERY key by section [8] above.)
check("%%" in zh_data["effect.beloong.execute_threshold.description"],
      "zh effect description escapes its literal percent signs as %%")
check("%%" in en_data["effect.beloong.execute_threshold.description"],
      "en effect description escapes its literal percent signs as %%")

# 8b. ExecuteDamageSource keys must all exist in both lang files ----------------
# The death message is rendered by an overridden DamageSource, so the keys are
# hard-coded on the Java side; drift there is invisible until someone dies.
print("[8b] ExecuteDamageSource <-> lang contract")
death_src = open(os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                              "registry", "ExecuteDamageSource.java"), encoding="utf-8").read()
message_keys = [
    "death.attack.beloong.execute",
    "death.attack.beloong.execute.player",
    "death.attack.beloong.execute.unknown_source",
]
declared = set(re.findall(r'"(death\.attack\.beloong\.execute[A-Za-z_.]*)"', death_src))
expected = set(message_keys) | {"death.attack.beloong.execute.stage."}
check(declared == expected,
      "ExecuteDamageSource declares exactly the expected lang keys (extra=%s missing=%s)"
      % (sorted(declared - expected), sorted(expected - declared)))
for key in message_keys:
    check(key in zh_data and key in en_data, "lang has both translations for " + key)

# Stage words are ours (never DS's stage name + a "dragon" suffix, which doubles up
# on any resource pack that already translates the stage as "...龙").
stage_keys = ["death.attack.beloong.execute.stage." + p
              for p in ("newborn", "young", "adult", "ancient")]
for key in stage_keys:
    check(key in zh_data and key in en_data, "lang has both translations for " + key)
check(all(('"%s"' % p) in death_src for p in ("newborn", "young", "adult", "ancient")),
      "ExecuteDamageSource maps all four built-in stage paths to its own keys")
check("stage_suffix" not in death_src and "stage_suffix" not in zh_data,
      "the removed 'stage_suffix' scheme is gone from Java and lang")
check("dragon_stage." in death_src,
      "datapack-defined stages still fall back to DS's own dragon_stage.* key")

# Regression guard for the 2026-10-09 attribution fix: LivingEntity#die reads
# damageSource.getEntity() for kill stats / ATTACKING_ENTITY / PLAYER_KILLED_ENTITY,
# so the execute damage source MUST carry the killer entity.
check("super(type, killer)" in death_src,
      "ExecuteDamageSource passes the killer entity to DamageSource (kill attribution)")
check("new ExecuteDamageSource(damageType, player)" in open(
    os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                 "registry", "ExecuteThresholdEffect.java"), encoding="utf-8").read(),
      "ExecuteThresholdEffect builds the damage source with the killer")

# 9. ExecuteEffect#getDescription argument count ------------------------------
print("[9] java/language contract")
src = open(os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                        "ability", "ExecuteEffect.java"), encoding="utf-8").read()
check(src.count("String.format") == 4, "getDescription formats 4 values")
# Args are substituted as components, never re-parsed, so "%%" is the correct
# String.format escape for the percent sign that ends up in the argument text.
check(src.count('"%.1f%%"') == 1, "Java formats the threshold argument with String.format(\"%.1f%%\")")

# 10. the execute must not retry every tick when a target refuses the damage ---------------
# Some mobs refuse our damage with an unconditional `return false` that ignores every damage-type
# tag (Ferrous Wroughtnaut: EntityWroughtnaut.hurt:194-207, its BYPASSES_INVULNERABILITY check
# sits in the "no source entity" else-branch; Cloud Golem: Cloud_GolemEntity.hurt:1181/1184,
# vertical-4-block anti-cheese plus a 10-tick post-hit window). No generic lever can force damage
# through those, so we accept that they block the execute -- but the mark MUST still be consumed
# and the cooldown MUST still start, otherwise the effect retries on every single tick (the
# Wroughtnaut's own "undamaged" clang then plays 20x/second). All four invariants are pinned.
print("[10] refused executes are consumed and cooled down, not retried every tick")
def strip_java_comments(text):
    """Remove block and line comments.

    Needed because section [10] reasons about *code* (where a `return` sits); a javadoc `@return`
    or a prose "return" must not count, and a commented-out line must not satisfy an assertion.
    """
    return re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", " ", text, flags=re.S))


threshold_src = strip_java_comments(open(
    os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                 "registry", "ExecuteThresholdEffect.java"), encoding="utf-8").read())
HURT_CALL = "victim.hurt(new ExecuteDamageSource(damageType, player), damage)"
REMOVE_MARK = "victim.removeEffect(ModMobEffects.EXECUTE_THRESHOLD);"
check(HURT_CALL in threshold_src, "(a) calls hurt() on the victim")
check("if (!victim.hurt(" not in threshold_src,
      "(a) the hurt() result is NOT used as an early-return guard any more")
check(HURT_CALL in threshold_src and REMOVE_MARK in threshold_src,
      "(a) both the hurt call and the mark removal exist")
if HURT_CALL in threshold_src and REMOVE_MARK in threshold_src:
    hurt_at = threshold_src.index(HURT_CALL)
    remove_at = threshold_src.index(REMOVE_MARK)
    check(hurt_at < remove_at, "(a) the mark is removed after the hurt call")
    # THE anti-retry invariant (review C-2): nothing between the hurt call and the UNCONDITIONAL
    # mark removal may return early. Inserting `if (!damaged) { return; }` right after the hurt
    # call -- i.e. reintroducing the every-tick retry -- must turn this red. The previous version
    # of this section only compared index ORDER and stayed green on exactly that mutation.
    check("return" not in threshold_src[hurt_at:remove_at],
          "(a) no early return between hurt() and the mark removal (else: every-tick retry)")
    stray = [i for i in range(hurt_at, len(threshold_src)) if threshold_src.startswith("return", i)]
    check(all(i > remove_at for i in stray),
          "(a) every return after the hurt call only happens once the mark is consumed")
check("boolean killed = victim.isDeadOrDying();" in threshold_src,
      "(I-1) success is decided by isDeadOrDying(), not by hurt()'s boolean (capped damage lies)")
check("if (killed && !damaged)" in threshold_src,
      "the already-dying case is handled separately (mark consumed, NO cooldown)")
check("if (!damaged || !killed)" in threshold_src,
      "(d) the refused case is decided by the RESULT, not by the boolean alone")
check('MESSAGE_REFUSED = "message.beloong.execute.refused"' in threshold_src,
      "(d) ExecuteThresholdEffect declares MESSAGE_REFUSED")

mark_src = strip_java_comments(open(
    os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                 "registry", "ExecuteMarkHandler.java"), encoding="utf-8").read())
check("ExecuteCooldown.isReady(player)" in mark_src,
      "(b) the mark is not applied while the execute is on cooldown")
# C-1: addEffect() returning false ALSO means "a stronger mark already exists"
# (MobEffectInstance.update:144-160), and the forceAddEffect fallback overwrites unconditionally
# -- so it must be gated on "no mark exists", or a weaker dragon can downgrade a stronger line.
check("victim.getEffect(ModMobEffects.EXECUTE_THRESHOLD) == null" in mark_src,
      "(C-1) the forceAddEffect fallback only fires when no mark exists (never downgrades)")
check("isOurExecuteDamage(event.getSource())" in mark_src,
      "(c) our own execute damage does not re-mark the target")
check("ExecuteThresholdEffect.DAMAGE_TYPE" in mark_src,
      "(c) the self-damage check goes by damage type, not by class identity")

for name in ("zh_cn", "en_us"):
    lang = load_json(os.path.join(ROOT, "src", "main", "resources", "assets", "beloong",
                                  "lang", name + ".json"))
    check("message.beloong.execute.refused" in lang,
          "(d) %s carries message.beloong.execute.refused" % name)

print()
if FAIL:
    print("FAILED %d check(s)" % len(FAIL))
    sys.exit(1)
print("ALL CHECKS PASSED")
