"""
TPBL 勝率預測模型
- 特徵：四大要素（eFG%, TOV%, ORB%, FTR）+ 節奏（Pace）
- 目標：勝率 (win_rate)
- 模型：Ridge Regression（sklearn）
- 輸出：model.json 供 JS 讀取進行 client-side 預測
"""
import sys, json, math
sys.stdout.reconfigure(encoding="utf-8")
import numpy as np
from sklearn.linear_model import Ridge
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import r2_score

with open("tpbl_raw.json", encoding="utf-8") as f:
    raw = json.load(f)

def sv(v, d=0.0):
    try: return float(v) if v is not None else d
    except: return d

# ── 計算各隊四大要素 ──────────────────────────────────────────────
team_features = []
for ts in raw["team_stats"]:
    gc  = ts.get("game_count", 36) or 36
    acc = ts["accumulated_stats"]
    nm  = ts["team"]["name"]

    def ta(k): return sv(acc.get(k)) / gc

    fg_m = ta("field_goals_made");    fg_a = ta("field_goals_attempted")
    t3_m = ta("three_pointers_made"); t3_a = ta("three_pointers_attempted")
    ft_m = ta("free_throws_made");    ft_a = ta("free_throws_attempted")
    orb  = ta("offensive_rebounds");  drb  = ta("defensive_rebounds")
    tov  = ta("turnovers")
    pts  = sv(acc.get("won_score",  0)) / gc
    opp  = sv(acc.get("lost_score", 0)) / gc

    efg     = (fg_m + 0.5 * t3_m) / fg_a if fg_a > 0 else 0
    ftr     = ft_a / fg_a if fg_a > 0 else 0
    tov_denom = fg_a + 0.44 * ft_a + tov
    tov_pct = tov / tov_denom * 100 if tov_denom > 0 else 0
    orb_pct = orb / (orb + drb) * 100 if (orb + drb) > 0 else 0

    poss    = fg_a - orb + tov + 0.44 * ft_a
    ortg    = pts / poss * 100 if poss > 0 else 100
    drtg    = opp / poss * 100 if poss > 0 else 100
    pace    = poss  # possessions per game

    t3_rate = t3_a / fg_a if fg_a > 0 else 0  # 三分出手佔比

    team_features.append({
        "name": nm,
        "efg": efg, "tov_pct": tov_pct, "orb_pct": orb_pct,
        "ftr": ftr, "ortg": ortg, "drtg": drtg,
        "net_rtg": ortg - drtg,
        "pace": pace, "t3_rate": t3_rate,
    })

# ── 從戰績取勝率 ──────────────────────────────────────────────────
win_rate_map = {}
for s in raw["standings"]:
    nm = s["team"]["name"]
    w  = s.get("score_won_matches", s.get("won_matches", 0))
    l  = s.get("score_lost_matches", s.get("lost_matches", 0))
    win_rate_map[nm] = w / (w + l) if (w + l) > 0 else 0

# ── 建立訓練矩陣 ─────────────────────────────────────────────────
FEATURES = ["efg", "tov_pct", "orb_pct", "ftr", "net_rtg"]
X_raw, y_raw, team_names = [], [], []

for t in team_features:
    if t["name"] in win_rate_map:
        X_raw.append([t[f] for f in FEATURES])
        y_raw.append(win_rate_map[t["name"]])
        team_names.append(t["name"])

X = np.array(X_raw)
y = np.array(y_raw)

# ── 訓練 Ridge Regression ─────────────────────────────────────────
scaler = StandardScaler()
X_sc   = scaler.fit_transform(X)

model = Ridge(alpha=0.5)
model.fit(X_sc, y)

y_pred = model.predict(X_sc)
r2     = r2_score(y, y_pred)

print("=" * 50)
print("TPBL 勝率預測模型 (Ridge Regression)")
print("=" * 50)
print(f"特徵: {FEATURES}")
print(f"係數: {[round(c, 4) for c in model.coef_]}")
print(f"截距: {round(float(model.intercept_), 4)}")
print(f"R²:   {round(r2, 4)}")
print()
print(f"{'球隊':15} {'實際勝率':>8} {'預測勝率':>8} {'誤差':>8}")
print("-" * 45)
for i, nm in enumerate(team_names):
    act  = y[i]
    pred = max(0, min(1, y_pred[i]))
    err  = pred - act
    print(f"{nm:15} {act:8.3f} {pred:8.3f} {err:+8.3f}")

# ── 計算四大要素係數（正規化後對勝率的影響）──────────────────────
coef_dict = {}
for i, f in enumerate(FEATURES):
    coef_dict[f] = round(float(model.coef_[i]), 6)

# ── 輸出 model.json ───────────────────────────────────────────────
# 各特徵的合理範圍（slider 用）
ranges = {
    "efg":     {"min": 0.44, "max": 0.56, "step": 0.001,  "unit": "%",    "label": "eFG% (有效命中率)",  "scale": 100},
    "tov_pct": {"min": 11.0, "max": 18.0, "step": 0.1,    "unit": "%",    "label": "TOV% (失誤率)",      "scale": 1},
    "orb_pct": {"min": 22.0, "max": 35.0, "step": 0.1,    "unit": "%",    "label": "ORB% (進攻籃板率)", "scale": 1},
    "ftr":     {"min": 0.18, "max": 0.38, "step": 0.001,  "unit": "",     "label": "FTR (罰球率)",       "scale": 1},
    "net_rtg": {"min": -12,  "max": 8.0,  "step": 0.1,    "unit": "pts",  "label": "淨效率值 (Net Rtg)", "scale": 1},
}

output = {
    "model": "Ridge Regression",
    "r2_score": round(r2, 4),
    "features": FEATURES,
    "coefficients": [round(float(c), 6) for c in model.coef_],
    "intercept": round(float(model.intercept_), 6),
    "scaler_mean": [round(float(m), 6) for m in scaler.mean_],
    "scaler_std":  [round(float(s), 6) for s in scaler.scale_],
    "ranges": ranges,
    "team_data": [
        {
            "name": team_names[i],
            "efg":     round(X_raw[i][0] * 100, 2),
            "tov_pct": round(X_raw[i][1], 2),
            "orb_pct": round(X_raw[i][2], 2),
            "ftr":     round(X_raw[i][3], 3),
            "net_rtg": round(team_features[j]["net_rtg"], 2),
            "win_rate": round(y[i], 3),
            "win_rate_pred": round(float(max(0, min(1, y_pred[i]))), 3),
        }
        for i, j in [(i, [t["name"] for t in team_features].index(team_names[i]))
                     for i in range(len(team_names))]
    ],
}

with open("model.json", "w", encoding="utf-8") as f:
    json.dump(output, f, ensure_ascii=False, indent=2)

# 也輸出 model.js（供靜態 HTML 直接讀取）
js_str = "const modelData = " + json.dumps(output, ensure_ascii=False, indent=2) + ";"
with open("model.js", "w", encoding="utf-8") as f:
    f.write(js_str)

print()
print("[完成] model.json + model.js 已儲存")
print(f"  模型 R² = {round(r2, 4)}（7支球隊資料）")
