"""
從 TPBL API 轉換真實數據 -> data.js
- time_on_court 單位：秒（accumulated）/ 場次 / 60 = 分鐘
- is_import：alt_name 含英文字母 = 外援
- 過濾：出場數 >= 5 場 && 場均 >= 4 分鐘
- 情境得分：fast_break / second_chance / paint / off_turnovers（真實數據）
"""
import sys, json, re, math
sys.stdout.reconfigure(encoding="utf-8")

with open("tpbl_raw.json", encoding="utf-8") as f:
    raw = json.load(f)

player_stats = raw["player_stats"]
team_stats   = raw["team_stats"]
standings    = raw["standings"]

# ── 球隊顏色 ─────────────────────────────────────────────────────
COLORS = {
    "臺北台新戰神":     "#C8102E",
    "新北中信特攻":     "#1B3A6B",
    "新北國王":         "#E31837",
    "桃園台啤永豐雲豹": "#F58220",
    "新竹御嵿攻城獅":   "#FFB81C",
    "福爾摩沙夢想家":   "#005DAA",
    "高雄全家海神":     "#00796B",
}

POS_MAP = {
    "PointGuard": "G", "ShootingGuard": "G",
    "SmallForward": "F", "PowerForward": "F",
    "Center": "C",
}

def sv(v, d=0.0):
    try: return float(v) if v is not None else d
    except: return d

def is_import(player):
    alt = player.get("meta", {}).get("alt_name", "") or ""
    return bool(re.search(r'[A-Za-z]', alt))

# ── 球員資料 ─────────────────────────────────────────────────────
players_js = []
for entry in player_stats:
    pl  = entry["player"]
    gc  = entry.get("game_count", 0) or 0
    if gc < 5:
        continue

    acc = entry.get("accumulated_stats", {})
    avg = entry.get("accumulated_stats", {})  # 用 accumulated，再除以 gc

    def a(key): return sv(acc.get(key)) / gc

    # 分鐘（accumulated time_on_court = 秒 / 60 / games）
    mins = sv(acc.get("time_on_court")) / 60 / gc
    if mins < 4 or mins > 44:
        continue  # 太少或不合理

    name      = pl.get("name", "")
    team_name = pl.get("team", {}).get("name", "")
    pos_raw   = pl.get("meta", {}).get("position", "")
    pos       = POS_MAP.get(pos_raw, "F")
    imp       = is_import(pl)

    pts  = a("score")
    reb  = a("rebounds")
    ast  = a("assists")
    stl  = a("steals")
    blk  = a("blocks")
    to_  = a("turnovers")
    pm   = a("plus_minus")

    fg_m = a("field_goals_made")
    fg_a = a("field_goals_attempted")
    t3_m = a("three_pointers_made")
    t3_a = a("three_pointers_attempted")
    ft_m = a("free_throws_made")
    ft_a = a("free_throws_attempted")
    orb  = a("offensive_rebounds")
    drb  = a("defensive_rebounds")

    fg   = fg_m / fg_a if fg_a > 0 else 0
    t3   = t3_m / t3_a if t3_a > 0 else 0
    ft   = ft_m / ft_a if ft_a > 0 else 0
    denom = 2 * (fg_a + 0.44 * ft_a)
    tsp  = pts / denom if denom > 0 else 0

    # 出手型態（真實數據）
    jump_m  = a("two_pointers_jump_shot_made") + a("three_pointers_jump_shot_made") + \
              a("two_pointers_floating_jump_shot_made") + a("three_pointers_floating_jump_shot_made") + \
              a("two_pointers_fadeaway_jump_shot_made") + a("two_pointers_step_back_jump_shot_made") + \
              a("two_pointers_pull_up_jump_shot_made") + a("two_pointers_turnaround_jump_shot_made")
    layup_m = a("two_pointers_layup_made") + a("two_pointers_driving_layup_made")
    dunk_m  = a("two_pointers_dunk_made") + a("two_pointers_alley_oop_made")
    hook_m  = a("two_pointers_hook_shot_made") + a("three_pointers_hook_shot_made")
    total_m = jump_m + layup_m + dunk_m + hook_m
    if total_m > 0:
        shot_types = {
            "jump":  round(jump_m  / total_m, 3),
            "layup": round(layup_m / total_m, 3),
            "dunk":  round(dunk_m  / total_m, 3),
            "hook":  round(hook_m  / total_m, 3),
        }
    else:
        shot_types = {"jump": 0.4, "layup": 0.35, "dunk": 0.15, "hook": 0.1}

    # 情境得分
    fb_pts  = a("fast_break_points")
    sc_pts  = a("second_chance_points")
    pip_pts = a("points_in_paint")
    ot_pts  = a("points_off_turnovers")

    players_js.append({
        "name": name, "team": team_name, "pos": pos, "is_import": imp,
        "pts": round(pts, 1), "reb": round(reb, 1), "ast": round(ast, 1),
        "stl": round(stl, 1), "blk": round(blk, 1), "to": round(to_, 1),
        "pm": round(pm, 1),
        "fg": round(fg, 3), "tsp": round(tsp, 3),
        "three_pct": round(t3, 3), "ft_pct": round(ft, 3),
        "min": round(mins, 1),
        "_shot_types": shot_types,
        "_orb": round(orb, 2), "_drb": round(drb, 2),
        "_fg_a": round(fg_a, 2), "_t3_m": round(t3_m, 2), "_t3_a": round(t3_a, 2),
        "_ft_a": round(ft_a, 2), "_tov": round(to_, 2),
        "_fb_pts": round(fb_pts, 2), "_sc_pts": round(sc_pts, 2),
        "_pip_pts": round(pip_pts, 2), "_ot_pts": round(ot_pts, 2),
    })

print(f"球員: {len(players_js)} 位（過濾後）")

# ── 球隊資料 ─────────────────────────────────────────────────────
standings_map = {}
for s in standings:
    tn = s["team"]["name"]
    standings_map[tn] = {
        "wins":   s.get("score_won_matches", s.get("won_matches", 0)),
        "losses": s.get("score_lost_matches", s.get("lost_matches", 0)),
    }

teams_js = []
for entry in team_stats:
    team  = entry.get("team", {})
    tname = team.get("name", "")
    if not tname:
        continue

    gc  = entry.get("game_count", 1) or 1
    acc = entry.get("accumulated_stats", {})

    def ta(key): return sv(acc.get(key)) / gc

    fg_m = ta("field_goals_made")
    fg_a = ta("field_goals_attempted")
    t3_m = ta("three_pointers_made")
    t3_a = ta("three_pointers_attempted")
    ft_m = ta("free_throws_made")
    ft_a = ta("free_throws_attempted")
    orb  = ta("offensive_rebounds")
    drb  = ta("defensive_rebounds")
    tov  = ta("turnovers")
    # won_score = 我方得分總計, lost_score = 對手得分總計
    pts_for   = sv(acc.get("won_score",  0)) / gc
    opp_score = sv(acc.get("lost_score", 0)) / gc

    efg_pct = (fg_m + 0.5 * t3_m) / fg_a if fg_a > 0 else 0
    ftr     = ft_a / fg_a if fg_a > 0 else 0
    orb_pct = orb / (orb + drb) * 100 if (orb + drb) > 0 else 0
    tov_denom = fg_a + 0.44 * ft_a + tov
    tov_pct = tov / tov_denom * 100 if tov_denom > 0 else 0

    # Possessions 估算
    poss = fg_a - orb + tov + 0.44 * ft_a
    ortg = pts_for / poss * 100 if poss > 0 else 100
    drtg = opp_score / poss * 100 if poss > 0 else 100

    # 情境得分（由該隊所有球員加總）
    tplayers = [p for p in players_js if p["team"] == tname]
    paint  = sum(p["_pip_pts"] for p in tplayers) / len(tplayers) * len(tplayers) / gc if tplayers else 0
    fb     = sum(p["_fb_pts"]  for p in tplayers)
    sc     = sum(p["_sc_pts"]  for p in tplayers)
    ot     = sum(p["_ot_pts"]  for p in tplayers)
    # 這些是場均加總（每個球員的場均）
    paint_pg = round(sum(p["_pip_pts"] * (p["min"]/40) for p in tplayers), 1)
    fb_pg    = round(sum(p["_fb_pts"]  * (p["min"]/40) for p in tplayers), 1)
    sc_pg    = round(sum(p["_sc_pts"]  * (p["min"]/40) for p in tplayers), 1)
    ot_pg    = round(sum(p["_ot_pts"]  * (p["min"]/40) for p in tplayers), 1)

    st = standings_map.get(tname, {"wins": 0, "losses": 0})

    teams_js.append({
        "name": tname, "wins": int(st["wins"]), "losses": int(st["losses"]),
        "color": COLORS.get(tname, "#888"),
        "ortg": round(ortg, 1), "drtg": round(drtg, 1),
        "efg_pct": round(efg_pct, 3), "tov_pct": round(tov_pct, 1),
        "orb_pct": round(orb_pct, 1), "ftr": round(ftr, 3),
        "three_att": round(t3_a, 1), "three_made": round(t3_m, 1),
        "three_pct": round(t3_m / t3_a if t3_a > 0 else 0, 3),
        "pts_for": round(pts_for, 1), "pts_against": round(opp_score, 1),
        "situation": {
            "paint": paint_pg, "fastbreak": fb_pg,
            "second_chance": sc_pg, "off_turnover": ot_pg,
        },
    })

print(f"球隊: {len(teams_js)} 支")

# 印出球隊數據確認
print("\n球隊戰績 & 攻防效率:")
for t in sorted(teams_js, key=lambda x: -(x["wins"])):
    net = t["ortg"] - t["drtg"]
    print(f"  {t['name']:12} {t['wins']}W{t['losses']}L  ORTG={t['ortg']}  DRTG={t['drtg']}  Net={net:+.1f}")

# 印出得分前 20 名
print("\n得分前 20 名:")
for p in sorted(players_js, key=lambda x: -x["pts"])[:20]:
    flag = "[外援]" if p["is_import"] else "      "
    print(f"  {p['name']:6} {p['team']:12} {p['pos']} {flag} {p['pts']}分 {p['reb']}板 {p['ast']}助")

# ── 計算聯盟均值 ─────────────────────────────────────────────────
def lavg(field):
    v = [p[field] for p in players_js if sv(p[field]) > 0]
    return round(sum(v) / len(v), 3) if v else 0

league_avg = {
    "pts": lavg("pts"), "reb": lavg("reb"), "ast": lavg("ast"),
    "stl": lavg("stl"), "blk": lavg("blk"), "to": lavg("to"),
    "fg": lavg("fg"), "three_pct": lavg("three_pct"),
    "ft_pct": lavg("ft_pct"), "tsp": lavg("tsp"),
    "efg": round(lavg("fg") + lavg("three_pct") * 0.15, 3),
    "tov_pct": 14.5, "usg": 22.5, "per": 15.0, "trb_pct": 19.0,
    "ortg": round(sum(t["ortg"] for t in teams_js) / len(teams_js), 1) if teams_js else 109.2,
    "drtg": round(sum(t["drtg"] for t in teams_js) / len(teams_js), 1) if teams_js else 109.2,
}

league_sit_avg = {
    "paint":         round(sum(t["situation"]["paint"]         for t in teams_js) / len(teams_js), 1),
    "fastbreak":     round(sum(t["situation"]["fastbreak"]     for t in teams_js) / len(teams_js), 1),
    "second_chance": round(sum(t["situation"]["second_chance"] for t in teams_js) / len(teams_js), 1),
    "off_turnover":  round(sum(t["situation"]["off_turnover"]  for t in teams_js) / len(teams_js), 1),
}

print(f"\n聯盟均值: pts={league_avg['pts']} reb={league_avg['reb']} ast={league_avg['ast']} fg={league_avg['fg']}")
print(f"情境均值: paint={league_sit_avg['paint']} fb={league_sit_avg['fastbreak']} sc={league_sit_avg['second_chance']} ot={league_sit_avg['off_turnover']}")

# ── 生成 data.js ─────────────────────────────────────────────────
SKIP = {"_shot_types","_orb","_drb","_fg_a","_t3_m","_t3_a","_ft_a","_tov","_fb_pts","_sc_pts","_pip_pts","_ot_pts"}

lines = ["const playersData = ["]
for p in players_js:
    pm_str = f"+{p['pm']}" if p["pm"] > 0 else str(p["pm"])
    st = p["_shot_types"]
    line = (
        f"    {{ name: '{p['name']}', team: '{p['team']}', pos: '{p['pos']}', "
        f"is_import: {'true' if p['is_import'] else 'false'}, "
        f"pts: {p['pts']}, reb: {p['reb']}, ast: {p['ast']}, "
        f"stl: {p['stl']}, blk: {p['blk']}, to: {p['to']}, pm: {pm_str}, "
        f"fg: {p['fg']}, tsp: {p['tsp']}, three_pct: {p['three_pct']}, "
        f"ft_pct: {p['ft_pct']}, min: {p['min']}, "
        f"shot_types: {{jump:{st['jump']}, layup:{st['layup']}, dunk:{st['dunk']}, hook:{st['hook']}}} }},"
    )
    lines.append(line)
lines.append("];\n")

lines.append("""
// ── 計算衍生指標 ─────────────────────────────────────────────────
playersData.forEach(p => {
    p.per     = ((p.pts + p.reb + p.ast + p.stl + p.blk) / p.min * 40).toFixed(1);
    p.efg     = (p.fg + p.three_pct * 0.15).toFixed(3);
    p.usg     = ((p.pts / p.min) * 2.5 * 100).toFixed(1);
    p.trb_pct = ((p.reb / p.min) * 35 * 100).toFixed(1);
    p.tov_pct = (p.to / (p.pts / p.min * 0.4 + p.to + p.ast * 0.15) * 100).toFixed(1);
    // shot_types 已由 API 真實數據提供，不再重新計算
});
""")

lines.append("// ── 球隊資料 ─────────────────────────────────────────────────────")
lines.append("const teamsData = [")
for t in teams_js:
    sit = t["situation"]
    lines.append(
        f"    {{ name: '{t['name']}', wins: {t['wins']}, losses: {t['losses']}, color: '{t['color']}',\n"
        f"      ortg: {t['ortg']}, drtg: {t['drtg']},\n"
        f"      efg_pct: {t['efg_pct']}, tov_pct: {t['tov_pct']}, orb_pct: {t['orb_pct']}, ftr: {t['ftr']},\n"
        f"      three_att: {t['three_att']}, three_made: {t['three_made']}, three_pct: {t['three_pct']},\n"
        f"      pts_for: {t['pts_for']}, pts_against: {t['pts_against']},\n"
        f"      situation: {{ paint: {sit['paint']}, fastbreak: {sit['fastbreak']}, second_chance: {sit['second_chance']}, off_turnover: {sit['off_turnover']} }},\n"
        f"    }},"
    )
lines.append("];\n")

lines.append("// ── 聯盟均值 ─────────────────────────────────────────────────────")
avg_str = ", ".join(f"{k}: {v}" for k, v in league_avg.items())
lines.append(f"const leagueAvg = {{ {avg_str} }};\n")
sit_str = ", ".join(f"{k}: {v}" for k, v in league_sit_avg.items())
lines.append(f"const leagueSitAvg = {{ {sit_str} }};")

with open("data.js", "w", encoding="utf-8") as f:
    f.write("\n".join(lines))

print("\n[完成] data.js 已寫入真實數據")
print(f"  球員 {len(players_js)} 位  球隊 {len(teams_js)} 支")

# 也輸出 data.json 供 Python / Streamlit 讀取
json_export = {
    "players": [
        {k: v for k, v in p.items() if not k.startswith("_")}
        for p in players_js
    ],
    "teams": teams_js,
    "league_avg": league_avg,
    "league_sit_avg": league_sit_avg,
}
with open("data.json", "w", encoding="utf-8") as f:
    json.dump(json_export, f, ensure_ascii=False, indent=2)
print("  data.json 也已寫入")
