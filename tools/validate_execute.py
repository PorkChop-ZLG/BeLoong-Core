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
check(upgrade["maximum_level"] == 15, "maximum_level = 15")
growth = upgrade["growth_requirement"]
check(growth["base"] == 150 and growth["per_level_above_first"] == 10,
      "growth 150 + 10/level -> L15 = %s" % (150 + 10 * 14))
threshold = effect["threshold"]
check(threshold["base"] == 3.0 and threshold["per_level_above_first"] == 0.5,
      "threshold 3.0 + 0.5/level -> L15 = %s%%" % (3.0 + 0.5 * 14))
cooldowns = effect["cooldown"]["values"]
check(len(cooldowns) == 15, "15 cooldown values")
check(cooldowns[0] == 200 and cooldowns[-1] == 100,
      "cooldown L1 = %s ticks (10s), L15 = %s ticks (5s)" % (cooldowns[0], cooldowns[-1]))
check(all(cooldowns[i] >= cooldowns[i + 1] for i in range(14)), "cooldown decreases monotonically")
check(effect["damage"]["base"] == 999999, "damage = 999999")

# 3. thresholds <-> effect amplifier -------------------------------------------
print("[3] threshold / amplifier mapping")
ok = True
for level in range(1, 16):
    percent = threshold["base"] + threshold["per_level_above_first"] * (level - 1)
    amplifier = round(percent / 0.5) - 1
    if amplifier != level + 4:
        ok = False
        print("      L%d percent=%s amplifier=%s" % (level, percent, amplifier))
check(ok, "amplifier(level) == level + 4 for every level (L1 -> 5 -> 3.0%%, L15 -> 19 -> 10.0%%)")

# 4. icons ---------------------------------------------------------------------
print("[4] icons")
for name in ("execute_0", "execute_1"):
    path = os.path.join(ROOT, "src", "main", "resources", "assets", "beloong",
                        "textures", "gui", "sprites", "abilities", name + ".png")
    check(os.path.isfile(path), "%s.png exists" % name)
    if os.path.isfile(path):
        with open(path, "rb") as handle:
            head = handle.read(8)
        check(head == b"\x89PNG\r\n\x1a\n", "%s.png has a PNG signature" % name)
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
check(not os.path.isfile(os.path.join(tags_dir, "bypasses_invulnerability.json")),
      "no bypasses_invulnerability (would pierce creative/invulnerable entities)")

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

# Percent signs that must survive into the rendered text are emitted as "%%".
check("%%" in zh_data["dragon_ability.beloong.execute.dynamic_desc"],
      "zh dynamic_desc escapes its literal percent signs as %%")
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

# 9. ExecuteEffect#getDescription argument count ------------------------------
print("[9] java/language contract")
src = open(os.path.join(ROOT, "src", "main", "java", "com", "zonlong", "beloong",
                        "ability", "ExecuteEffect.java"), encoding="utf-8").read()
check(src.count("String.format") == 4, "getDescription formats 4 values")
# Args are substituted as components, never re-parsed, so "%%" is the correct
# String.format escape for the percent sign that ends up in the argument text.
check(src.count('"%.1f%%"') == 1, "Java formats the threshold argument with String.format(\"%.1f%%\")")

print()
if FAIL:
    print("FAILED %d check(s)" % len(FAIL))
    sys.exit(1)
print("ALL CHECKS PASSED")
