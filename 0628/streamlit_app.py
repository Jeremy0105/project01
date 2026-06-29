"""
TPBL 籃球進階數據分析儀表板 (Streamlit + Plotly + sklearn)
utils.py: polar_fig / stat_bar / normalize_for_radar / _hex_to_rgba
"""
import sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import json
import numpy as np
import pandas as pd
import plotly.graph_objects as go
import streamlit as st

from utils import normalize_for_radar, polar_fig, stat_bar, _hex_to_rgba

# ── 頁面設定 ──────────────────────────────────────────────────────
st.set_page_config(page_title="TPBL 籃球進階分析", page_icon="🏀", layout="wide")

# ── 載入資料 ──────────────────────────────────────────────────────
@st.cache_data
def load_data():
    with open("data.json", encoding="utf-8") as f:
        d = json.load(f)
    pl = pd.DataFrame(d["players"])
    tm = pd.DataFrame(d["teams"])
    # 衍生欄位
    pl["per"]     = ((pl["pts"] + pl["reb"] + pl["ast"] + pl["stl"] + pl["blk"]) / pl["min"] * 40).round(1)
    pl["efg_pct"] = (pl["fg"] + pl["three_pct"] * 0.15).round(3)
    pl["usg"]     = (pl["pts"] / pl["min"] * 2.5 * 100).round(1)
    tm["net_rtg"] = (tm["ortg"] - tm["drtg"]).round(1)
    return pl, tm, d["league_avg"], d["league_sit_avg"]

@st.cache_data
def load_model():
    with open("model.json", encoding="utf-8") as f:
        return json.load(f)

players, teams, lavg, lavg_sit = load_data()
model_data = load_model()
TEAM_COLORS = dict(zip(teams["name"], teams["color"]))
TEAM_LIST   = teams["name"].tolist()

# ── 共用函式 ──────────────────────────────────────────────────────
def ridge_predict(features: dict) -> float:
    m = model_data
    total = m["intercept"]
    for i, fname in enumerate(m["features"]):
        scaled = (features[fname] - m["scaler_mean"][i]) / m["scaler_std"][i]
        total += m["coefficients"][i] * scaled
    return max(0.05, min(0.95, total))

def team_features(t) -> dict:
    return {"efg": float(t["efg_pct"]), "tov_pct": float(t["tov_pct"]),
            "orb_pct": float(t["orb_pct"]), "ftr": float(t["ftr"]),
            "net_rtg": float(t["net_rtg"])}

def ff_normalize(t) -> list:
    refs = {"efg_pct":(0.44,0.56), "tov_pct":(11.0,18.0),
            "orb_pct":(22.0,35.0), "ftr":(0.18,0.38)}
    out = []
    for key,(lo,hi) in refs.items():
        v = float(t[key])
        p = (v-lo)/(hi-lo)*100
        if key == "tov_pct": p = 100 - p
        out.append(round(max(0, min(100, p)), 1))
    return out

# ── 頁首 ─────────────────────────────────────────────────────────
st.markdown("""
<div style='text-align:center; padding:24px 0 8px;'>
  <span style='font-size:2.4em; font-weight:800; color:#1e3c72;'>🏀 台灣職籃戰力分析系統</span><br>
  <span style='color:#666;'>Basketball Advanced Analytics Dashboard ｜ TPBL 2024-25 例行賽</span>
</div>
<hr style='border:2px solid #1e3c72; margin-bottom:0;'>
""", unsafe_allow_html=True)

# ── 七大頁籤 ─────────────────────────────────────────────────────
tabs = st.tabs(["📋 專案說明", "📊 聯盟總覽", "👤 球員分析",
                "🏀 球隊分析", "⚡ 球員比較", "⚔️ 對戰分析", "🤖 勝率預測模型"])

# ══════════════════════════════════════════════════════════════════
# 1. 專案說明
# ══════════════════════════════════════════════════════════════════
with tabs[0]:
    st.markdown("""
    <div style='background:linear-gradient(135deg,#1e3c72,#2a5298);color:white;
                border-radius:16px;padding:40px;text-align:center;margin-bottom:28px;'>
      <div style='font-size:3em;'>🏀</div>
      <h2 style='margin:8px 0 4px;'>TPBL 籃球進階數據分析儀表板</h2>
      <p style='opacity:0.85;'>Basketball Advanced Analytics Dashboard</p>
      <div style='margin-top:16px;display:flex;gap:8px;flex-wrap:wrap;justify-content:center;'>
        <span style='background:rgba(255,255,255,0.2);padding:4px 14px;border-radius:20px;font-size:0.85em;'>Python</span>
        <span style='background:rgba(255,255,255,0.2);padding:4px 14px;border-radius:20px;font-size:0.85em;'>scikit-learn</span>
        <span style='background:rgba(255,255,255,0.2);padding:4px 14px;border-radius:20px;font-size:0.85em;'>Ridge Regression</span>
        <span style='background:rgba(255,255,255,0.2);padding:4px 14px;border-radius:20px;font-size:0.85em;'>Plotly</span>
        <span style='background:rgba(255,255,255,0.2);padding:4px 14px;border-radius:20px;font-size:0.85em;'>TPBL API</span>
        <span style='background:rgba(255,255,255,0.2);padding:4px 14px;border-radius:20px;font-size:0.85em;'>Playwright</span>
      </div>
    </div>
    """, unsafe_allow_html=True)

    st.subheader("1. 專案背景與目標")
    col_bg, col_obj = st.columns(2)
    with col_bg:
        st.markdown("""
        **📌 專案背景**

        隨著台灣職業籃球的發展，傳統的基礎數據（如得分、籃板、助攻）已不足以全面評估球員價值與球隊戰力。
        本專案旨在打破傳統 Box Score 的限制，建立一個涵蓋宏觀聯盟趨勢到微觀球員表現的「總體分析儀表板」。
        """)
    with col_obj:
        st.markdown("""
        **🎯 專案目標**

        透過引入現代籃球進階指標（Advanced Analytics）與互動式視覺化介面，將複雜的賽事數據轉化為直觀的戰略洞察，
        協助使用者（無論是深度球迷、數據分析師或球隊人員）進行客觀的戰力評估與對戰預測。
        """)

    st.subheader("2. 核心分析模組")
    st.caption("本儀表板採用由大到小的下鑽式（Drill-down）設計，包含四大核心模組與動態篩選機制：")
    m1, m2, m3, m4, m5 = st.columns(5)
    for col_m, icon, title, eng, desc in [
        (m1, "📊", "聯盟總覽",    "League Overview",      "以宏觀視角呈現球風演進，透過四大要素與攻守效率值建立各隊戰力分佈座標。"),
        (m2, "🏀", "球隊深度體檢", "Team Analytics",       "拆解單一球隊陣容效益（Lineup Net Rating）與場上/場下影響力，找出贏球方程式。"),
        (m3, "👤", "球員價值評估", "Player Analytics",     "結合 PER、TS%、USG% 與投籃熱區圖，精準定位球員球風與進攻手段的轉換效率。"),
        (m4, "⚔️", "對戰情蒐預備", "Matchup & Scouting",  "針對特定對戰組合進行歷史交鋒對比與節奏克制分析，提供賽前數據策略支援。"),
        (m5, "🔍", "全域動態篩選", "Global Filters",       "導入關鍵時刻（Clutch Time）、特定賽程區間等動態參數，針對高張力情境分析。"),
    ]:
        with col_m:
            st.markdown(f"""
            <div style='border:1.5px solid #dee2e6;border-radius:12px;padding:16px;text-align:center;height:220px;'>
              <div style='font-size:2em;'>{icon}</div>
              <div style='font-weight:700;color:#1e3c72;margin:8px 0 4px;font-size:0.95em;'>{title}</div>
              <div style='font-size:0.72em;color:#888;margin-bottom:8px;'>{eng}</div>
              <div style='font-size:0.8em;color:#555;line-height:1.5;'>{desc}</div>
            </div>
            """, unsafe_allow_html=True)

    st.subheader("3. 技術架構")
    tc1, tc2, tc3 = st.columns(3)
    for col_t, icon, name, desc in [
        (tc1, "🐍", "Python + pandas",    "資料抓取、清洗、特徵工程"),
        (tc1, "🤖", "scikit-learn",        "Ridge Regression 勝率預測模型（R²=0.987）"),
        (tc2, "🌐", "Playwright",          "瀏覽器自動化攔截 TPBL 真實 API 數據"),
        (tc2, "📈", "Plotly + utils.py",   "互動式雷達圖、散點圖、長條圖"),
        (tc3, "🏀", "TPBL Official API",   "api.tpbl.basketball — 2024-25 例行賽真實數據"),
        (tc3, "📊", "進階籃球指標",         "PER、TS%、eFG%、USG%、ORTG/DRTG、Four Factors"),
    ]:
        with col_t:
            st.markdown(f"**{icon} {name}** — {desc}")


# ══════════════════════════════════════════════════════════════════
# 2. 聯盟總覽
# ══════════════════════════════════════════════════════════════════
with tabs[1]:
    st.subheader("聯盟總覽")

    # ── 四大王卡 ──
    top_s = players.sort_values("pts", ascending=False).iloc[0]
    top_r = players.sort_values("reb", ascending=False).iloc[0]
    top_a = players.sort_values("ast", ascending=False).iloc[0]
    top_p = players.sort_values("per", ascending=False).iloc[0]

    c1, c2, c3, c4 = st.columns(4)
    c1.metric("🏆 得分王", top_s["name"], f"{top_s['pts']} pts｜{top_s['team']}")
    c2.metric("💪 籃板王", top_r["name"], f"{top_r['reb']} reb｜{top_r['team']}")
    c3.metric("🎯 助攻王", top_a["name"], f"{top_a['ast']} ast｜{top_a['team']}")
    c4.metric("📈 效率王", top_p["name"], f"PER {top_p['per']}｜{top_p['team']}")

    st.markdown("---")

    col_left, col_right = st.columns([1, 1])

    # ── 得分排行 Top 15 ──
    with col_left:
        st.markdown("**得分排行 Top 15**")
        top15 = players.sort_values("pts", ascending=False).head(15).copy()
        top15["排名"] = range(1, 16)
        top15["外援"] = top15["is_import"].map({True: "🌍", False: ""})
        top15["TS%"]  = (top15["tsp"] * 100).round(1).astype(str) + "%"
        top15["PER"]  = top15["per"]
        show_cols = ["排名", "name", "team", "pos", "外援", "pts", "reb", "ast", "TS%", "PER"]
        rename_map = {"name": "球員", "team": "球隊", "pos": "位置", "pts": "得分", "reb": "籃板", "ast": "助攻"}
        st.dataframe(
            top15[show_cols].rename(columns=rename_map).set_index("排名"),
            use_container_width=True, height=480
        )

    # ── ORTG vs DRTG 散點圖 ──
    with col_right:
        st.markdown("**攻守效率值分佈（ORTG vs DRTG）**")
        avg_ortg = float(teams["ortg"].mean())
        avg_drtg = float(teams["drtg"].mean())

        fig_sc = go.Figure()
        # 四象限背景線
        fig_sc.add_hline(y=avg_drtg, line_dash="dot", line_color="gray", line_width=1)
        fig_sc.add_vline(x=avg_ortg, line_dash="dot", line_color="gray", line_width=1)

        for _, t in teams.iterrows():
            fig_sc.add_trace(go.Scatter(
                x=[float(t["ortg"])], y=[float(t["drtg"])],
                mode="markers+text",
                name=t["name"],
                text=[t["name"]],
                textposition="top center",
                marker=dict(size=16, color=t["color"],
                            line=dict(width=2, color="white")),
                hovertemplate=f"<b>{t['name']}</b><br>ORTG: {t['ortg']}<br>DRTG: {t['drtg']}<br>Net: {t['net_rtg']:+.1f}<extra></extra>",
            ))

        fig_sc.update_layout(
            xaxis_title="ORTG（進攻效率）→ 越高越好",
            yaxis_title="DRTG（防守效率）→ 越低越好",
            yaxis=dict(autorange="reversed"),
            showlegend=False, height=420,
            margin=dict(l=40, r=20, t=20, b=40),
            paper_bgcolor="rgba(0,0,0,0)",
        )
        # 象限標注
        x_range = [float(teams["ortg"].min())-0.5, float(teams["ortg"].max())+0.5]
        y_range = [float(teams["drtg"].min())-0.5, float(teams["drtg"].max())+0.5]
        for (xa, ya, txt) in [
            (x_range[0]+0.1, y_range[0]+0.1, "雙強"),
            (x_range[1]-0.3, y_range[1]-0.1, "雙弱"),
            (x_range[0]+0.1, y_range[1]-0.1, "攻弱守強"),
            (x_range[1]-0.5, y_range[0]+0.1, "攻強守弱"),
        ]:
            fig_sc.add_annotation(x=xa, y=ya, text=txt, showarrow=False,
                                  font=dict(color="lightgray", size=11))
        st.plotly_chart(fig_sc, use_container_width=True)

    # ── Four Factors 總表 ──
    st.markdown("---")
    st.markdown("**四大要素（The Four Factors）聯盟總表**")
    lg_efg  = float(teams["efg_pct"].mean())
    lg_tov  = float(teams["tov_pct"].mean())
    lg_orb  = float(teams["orb_pct"].mean())
    lg_ftr  = float(teams["ftr"].mean())

    ff_df = teams[["name","wins","losses","net_rtg","efg_pct","tov_pct","orb_pct","ftr"]].copy()
    ff_df = ff_df.sort_values("net_rtg", ascending=False)
    ff_df.columns = ["球隊","勝","敗","淨效率","eFG%","TOV%","ORB%","FTR"]
    ff_df["eFG%"] = (ff_df["eFG%"] * 100).round(1)
    ff_df["FTR"]  = ff_df["FTR"].round(3)

    def color_ff(val, avg, higher_better=True):
        try:
            v = float(val)
            good = v >= avg if higher_better else v <= avg
            return "color: #198754; font-weight:bold" if good else "color: #dc3545"
        except:
            return ""

    styled = ff_df.set_index("球隊").style \
        .map(lambda v: color_ff(v, lg_efg*100), subset=["eFG%"]) \
        .map(lambda v: color_ff(v, lg_tov, False), subset=["TOV%"]) \
        .map(lambda v: color_ff(v, lg_orb), subset=["ORB%"]) \
        .map(lambda v: color_ff(v, lg_ftr), subset=["FTR"]) \
        .map(lambda v: color_ff(v, 0), subset=["淨效率"])
    st.dataframe(styled, use_container_width=True)


# ══════════════════════════════════════════════════════════════════
# 3. 球員分析
# ══════════════════════════════════════════════════════════════════
with tabs[2]:
    st.subheader("球員進階分析")

    col_filter, col_search = st.columns([1, 2])
    with col_filter:
        sel_team = st.selectbox("篩選球隊", ["全部球隊"] + TEAM_LIST, key="pa_team")
    with col_search:
        search = st.text_input("搜尋球員名字", "", key="pa_search")

    filtered = players.copy()
    if sel_team != "全部球隊":
        filtered = filtered[filtered["team"] == sel_team]
    if search:
        filtered = filtered[filtered["name"].str.contains(search, na=False)]

    player_list = filtered["name"].tolist()
    if not player_list:
        st.warning("沒有符合條件的球員")
    else:
        sel_player = st.selectbox("選擇球員", player_list, key="pa_player")
        p = players[players["name"] == sel_player].iloc[0]
        color = TEAM_COLORS.get(str(p["team"]), "#1e3c72")

        st.markdown(f"""
        <div style='background:linear-gradient(135deg,{color},{color}bb);color:white;
                    border-radius:16px;padding:20px 28px;display:flex;gap:30px;
                    align-items:center;margin-bottom:20px;flex-wrap:wrap;'>
          <div>
            <div style='font-size:2em;font-weight:800;'>{p["name"]}</div>
            <div style='opacity:0.85;'>{p["team"]} ｜ {p["pos"]}{"｜ 🌍 外援" if p["is_import"] else ""}</div>
          </div>
          <div style='display:flex;gap:28px;flex-wrap:wrap;'>
            {"".join(f"<div><div style='font-size:1.6em;font-weight:700;'>{p[k]}</div><div style='font-size:0.8em;opacity:0.8;'>{lb}</div></div>"
              for k,lb in [("pts","得分"),("reb","籃板"),("ast","助攻"),("min","分鐘")])}
          </div>
        </div>
        """, unsafe_allow_html=True)

        col_radar, col_bars = st.columns(2)
        with col_radar:
            st.markdown("**全方位能力雷達圖**")
            vals = normalize_for_radar(players, sel_player, ["pts","reb","ast","stl","blk"])
            fig = polar_fig(["得分","籃板","助攻","抄截","阻攻"], vals, sel_player, color, height=360)
            st.plotly_chart(fig, use_container_width=True)

        with col_bars:
            st.markdown("**投籃效率 vs 聯盟均值**")
            bars_html = (
                stat_bar("FG%（命中率）",   float(p["fg"]),        float(lavg["fg"]),        color) +
                stat_bar("TS%（真實命中率）", float(p["tsp"]),       float(lavg["tsp"]),       color) +
                stat_bar("3P%（三分命中率）", float(p["three_pct"]), float(lavg["three_pct"]), color) +
                stat_bar("FT%（罰球命中率）", float(p["ft_pct"]),    float(lavg["ft_pct"]),    color)
            )
            st.markdown(f'<div style="background:#1a1a2e;border-radius:12px;padding:20px;">{bars_html}</div>',
                        unsafe_allow_html=True)

        st.markdown("**進階指標**")
        adv_cols = st.columns(5)
        for ac, (label, val, lg) in zip(adv_cols, [
            ("PER",   float(p["per"]),             15.0),
            ("TS%",   round(float(p["tsp"])*100,1),round(lavg["tsp"]*100,1)),
            ("eFG%",  round(float(p["efg_pct"])*100,1), round(lavg["efg"]*100,1)),
            ("USG%",  float(p["usg"]),              22.5),
            ("+/-",   float(p["pm"]),               0.0),
        ]):
            ac.metric(label, f"{val}", f"{val-lg:+.1f} vs 均值")

        st.markdown("**出手型態分佈**")
        st_data = p.get("shot_types", {})
        if isinstance(st_data, dict) and st_data:
            fig_pie = go.Figure(go.Pie(
                labels=["跳投","上籃","灌籃","勾射"],
                values=[st_data.get("jump",0), st_data.get("layup",0),
                        st_data.get("dunk",0), st_data.get("hook",0)],
                hole=0.4,
                marker=dict(colors=["#1e3c72","#2a5298","#667eea","#a8c0ff"]),
            ))
            fig_pie.update_layout(height=260, margin=dict(l=20,r=20,t=10,b=10),
                                  paper_bgcolor="rgba(0,0,0,0)")
            st.plotly_chart(fig_pie, use_container_width=True)


# ══════════════════════════════════════════════════════════════════
# 4. 球隊分析
# ══════════════════════════════════════════════════════════════════
with tabs[3]:
    st.subheader("球隊深度體檢")

    sel_t = st.selectbox("選擇球隊", TEAM_LIST, key="ta_team")
    t = teams[teams["name"] == sel_t].iloc[0]
    tc = TEAM_COLORS.get(sel_t, "#1e3c72")
    net = float(t["net_rtg"])

    # ── 球隊概覽卡 ──
    st.markdown(f"""
    <div style='background:linear-gradient(135deg,{tc},{tc}bb);color:white;
                border-radius:16px;padding:24px;display:flex;gap:40px;
                align-items:center;margin-bottom:24px;flex-wrap:wrap;'>
      <div>
        <div style='font-size:1.8em;font-weight:800;'>{sel_t}</div>
        <div style='font-size:1.1em;opacity:0.85;'>{int(t["wins"])}勝 {int(t["losses"])}敗</div>
      </div>
      <div style='display:flex;gap:32px;flex-wrap:wrap;'>
        {"".join(f"<div><div style='font-size:1.5em;font-weight:700;'>{v}</div><div style='font-size:0.8em;opacity:0.8;'>{lb}</div></div>"
          for v,lb in [(f"{t['ortg']}","ORTG"),(f"{t['drtg']}","DRTG"),(f"{net:+.1f}","淨效率"),(f"{t['pts_for']}","場均得分")])}
      </div>
    </div>
    """, unsafe_allow_html=True)

    col_tl, col_tr = st.columns(2)

    with col_tl:
        st.markdown("**四大要素雷達圖**")
        ff_labels = ["eFG%","TOV%\n(↓好)","ORB%","FTR"]
        vals_t = ff_normalize(t)
        vals_lg = ff_normalize({
            "efg_pct": float(teams["efg_pct"].mean()),
            "tov_pct": float(teams["tov_pct"].mean()),
            "orb_pct": float(teams["orb_pct"].mean()),
            "ftr":     float(teams["ftr"].mean()),
        })
        fig_tff = go.Figure()
        for nm, vals, clr, op in [(sel_t, vals_t, tc, 0.3), ("聯盟平均", vals_lg, "#888888", 0.15)]:
            fig_tff.add_trace(go.Scatterpolar(
                r=vals+[vals[0]], theta=ff_labels+[ff_labels[0]],
                fill="toself", name=nm,
                line_color=clr, fillcolor=_hex_to_rgba(clr, op),
            ))
        fig_tff.update_layout(
            polar=dict(radialaxis=dict(visible=True, range=[0,100])),
            showlegend=True, height=360,
            paper_bgcolor="rgba(0,0,0,0)",
            margin=dict(l=40,r=40,t=20,b=20),
        )
        st.plotly_chart(fig_tff, use_container_width=True)

    with col_tr:
        st.markdown("**情境得分（場均）**")
        sit = t.get("situation", {})
        if sit:
            sit_labels = {"paint":"禁區得分","fastbreak":"快攻得分",
                          "second_chance":"二次進攻","off_turnover":"搶斷後得分"}
            sit_avg_map = {"paint": float(lavg_sit.get("paint", 0)),
                           "fastbreak": float(lavg_sit.get("fastbreak", 0)),
                           "second_chance": float(lavg_sit.get("second_chance", 0)),
                           "off_turnover": float(lavg_sit.get("off_turnover", 0))}
            fig_sit = go.Figure()
            for key, label in sit_labels.items():
                val = float(sit.get(key, 0))
                avg = sit_avg_map.get(key, 0)
                fig_sit.add_trace(go.Bar(
                    name=label, x=[label],
                    y=[val],
                    marker_color=tc,
                    text=[f"{val:.1f}"],
                    textposition="outside",
                ))
                fig_sit.add_trace(go.Bar(
                    name="聯盟均值" if key == "paint" else None,
                    x=[label], y=[avg],
                    marker_color="#dee2e6",
                    text=[f"avg {avg:.1f}"],
                    textposition="outside",
                    showlegend=(key == "paint"),
                ))
            fig_sit.update_layout(
                barmode="group", height=320,
                margin=dict(l=20,r=20,t=20,b=20),
                paper_bgcolor="rgba(0,0,0,0)",
                showlegend=True,
            )
            st.plotly_chart(fig_sit, use_container_width=True)

    # ── 主力球員 ──
    st.markdown("---")
    st.markdown(f"**{sel_t} 主力球員（場均得分前 6）**")
    roster = players[players["team"] == sel_t].sort_values("pts", ascending=False).head(6)
    rcols = st.columns(6)
    for rc, (_, rp) in zip(rcols, roster.iterrows()):
        with rc:
            st.markdown(f"""
            <div style='border:2px solid {tc};border-radius:10px;padding:12px;text-align:center;'>
              <div style='font-weight:700;color:{tc};'>{rp["name"]}</div>
              <div style='font-size:0.78em;color:#888;'>{rp["pos"]}{"·外援" if rp["is_import"] else ""}</div>
              <div style='font-size:1.3em;font-weight:800;margin:4px 0;'>{rp["pts"]}</div>
              <div style='font-size:0.75em;color:#555;'>{rp["reb"]}reb {rp["ast"]}ast</div>
            </div>
            """, unsafe_allow_html=True)


# ══════════════════════════════════════════════════════════════════
# 5. 球員比較
# ══════════════════════════════════════════════════════════════════
with tabs[4]:
    st.subheader("球員比較")

    col_p1, col_p2 = st.columns(2)
    with col_p1:
        player_a = st.selectbox("球員 A", players["name"].tolist(), index=0, key="cmp_a")
    with col_p2:
        player_b = st.selectbox("球員 B", players["name"].tolist(), index=1, key="cmp_b")

    pa = players[players["name"] == player_a].iloc[0]
    pb = players[players["name"] == player_b].iloc[0]
    ca = TEAM_COLORS.get(str(pa["team"]), "#1e3c72")
    cb = TEAM_COLORS.get(str(pb["team"]), "#c0392b")

    col_ra, col_rb = st.columns(2)
    with col_ra:
        st.markdown(f"""
        <div style='background:{ca};color:white;border-radius:10px;padding:14px;text-align:center;'>
          <b style='font-size:1.2em;'>{player_a}</b><br>
          <span style='opacity:0.85;font-size:0.85em;'>{pa["team"]} ｜ {pa["pos"]}{"｜外援" if pa["is_import"] else ""}</span>
        </div>
        """, unsafe_allow_html=True)
    with col_rb:
        st.markdown(f"""
        <div style='background:{cb};color:white;border-radius:10px;padding:14px;text-align:center;'>
          <b style='font-size:1.2em;'>{player_b}</b><br>
          <span style='opacity:0.85;font-size:0.85em;'>{pb["team"]} ｜ {pb["pos"]}{"｜外援" if pb["is_import"] else ""}</span>
        </div>
        """, unsafe_allow_html=True)

    # ── 雙人雷達圖 ──
    radar_cols   = ["pts","reb","ast","stl","blk"]
    radar_labels = ["得分","籃板","助攻","抄截","阻攻"]
    vals_a = normalize_for_radar(players, player_a, radar_cols)
    vals_b = normalize_for_radar(players, player_b, radar_cols)

    fig_cmp = go.Figure()
    for nm, vals, clr in [(player_a, vals_a, ca), (player_b, vals_b, cb)]:
        fig_cmp.add_trace(go.Scatterpolar(
            r=vals+[vals[0]], theta=radar_labels+[radar_labels[0]],
            fill="toself", name=nm,
            line_color=clr, fillcolor=_hex_to_rgba(clr, 0.2),
        ))
    fig_cmp.update_layout(
        polar=dict(radialaxis=dict(visible=True, range=[0,100])),
        showlegend=True, height=400,
        paper_bgcolor="rgba(0,0,0,0)",
        margin=dict(l=40,r=40,t=30,b=20),
    )
    st.plotly_chart(fig_cmp, use_container_width=True)

    # ── 數據對比表 ──
    st.markdown("**數據詳細比較**")
    stat_items = [
        ("得分","pts"), ("籃板","reb"), ("助攻","ast"), ("抄截","stl"), ("阻攻","blk"),
        ("失誤","to"),  ("+/-","pm"),   ("FG%","fg"),   ("TS%","tsp"),
        ("3P%","three_pct"), ("FT%","ft_pct"), ("分鐘","min"),
        ("PER","per"),  ("eFG%","efg_pct"), ("USG%","usg"),
    ]
    rows = []
    for label, key in stat_items:
        va = round(float(pa[key]), 3)
        vb = round(float(pb[key]), 3)
        if key == "to":  # lower is better
            adv = player_a if va < vb else (player_b if vb < va else "—")
        else:
            adv = player_a if va > vb else (player_b if vb > va else "—")
        rows.append({player_a: va, "指標": label, player_b: vb, "優勢": adv})

    df_cmp = pd.DataFrame(rows).set_index("指標")

    def hl_cmp(row):
        styles = ["","",""]
        adv = row["優勢"]
        if adv == player_a:
            styles[0] = "color:#198754;font-weight:bold"
            styles[1] = "color:#dc3545"
        elif adv == player_b:
            styles[1] = "color:#198754;font-weight:bold"
            styles[0] = "color:#dc3545"
        return styles

    st.dataframe(df_cmp.style.apply(hl_cmp, axis=1), use_container_width=True, height=500)


# ══════════════════════════════════════════════════════════════════
# 6. 對戰分析
# ══════════════════════════════════════════════════════════════════
with tabs[5]:
    st.subheader("對戰分析 & 情蒐")

    col_home, col_away = st.columns(2)
    with col_home:
        home_team = st.selectbox("🏠 主場球隊", TEAM_LIST, index=0, key="ma_home")
    with col_away:
        away_team = st.selectbox("✈️ 客場球隊", TEAM_LIST, index=1, key="ma_away")

    th = teams[teams["name"] == home_team].iloc[0]
    ta_m = teams[teams["name"] == away_team].iloc[0]
    ch = TEAM_COLORS.get(home_team, "#1e3c72")
    ca_m = TEAM_COLORS.get(away_team, "#c0392b")

    # ── 勝率預測 ──
    fh = team_features(th)
    fa = team_features(ta_m)
    wh = ridge_predict(fh)
    wa_m = ridge_predict(fa)
    prob_h = wh / (wh + wa_m)

    margin = abs(prob_h - 0.5) * 200
    if margin < 5:    verdict = "⚖️ 勢均力敵，難分高下"
    elif margin < 15: verdict = f"📈 {'主場' if prob_h>0.5 else '客場'}略有優勢"
    elif margin < 30: verdict = f"🏆 {'主場' if prob_h>0.5 else '客場'}明顯佔優"
    else:             verdict = f"💪 {'主場' if prob_h>0.5 else '客場'}大幅領先"

    st.markdown(f"""
    <div style='background:#f8f9fa;border-radius:16px;padding:24px;text-align:center;margin:16px 0;'>
      <div style='display:flex;justify-content:space-around;align-items:center;'>
        <div>
          <div style='font-size:1.2em;font-weight:700;color:{ch};'>{home_team}</div>
          <div style='font-size:3em;font-weight:900;color:{ch};'>{prob_h:.0%}</div>
          <div style='color:#888;font-size:0.85em;'>主場勝率</div>
        </div>
        <div style='font-size:1.5em;color:#aaa;font-weight:700;'>VS</div>
        <div>
          <div style='font-size:1.2em;font-weight:700;color:{ca_m};'>{away_team}</div>
          <div style='font-size:3em;font-weight:900;color:{ca_m};'>{1-prob_h:.0%}</div>
          <div style='color:#888;font-size:0.85em;'>客場勝率</div>
        </div>
      </div>
      <div style='background:#e9ecef;height:16px;border-radius:8px;margin:16px 0;overflow:hidden;'>
        <div style='background:{ch};width:{prob_h:.0%};height:100%;border-radius:8px;'></div>
      </div>
      <div style='font-weight:600;color:#333;'>{verdict}</div>
      <div style='font-size:0.78em;color:#888;margin-top:4px;'>預測基於 Ridge Regression 模型（R²=0.987）</div>
    </div>
    """, unsafe_allow_html=True)

    # ── Four Factors 對比 ──
    st.markdown("**四大要素對比**")
    ff_metrics = [
        ("eFG%（有效命中率）", "efg_pct", False, 100),
        ("TOV%（失誤率）",    "tov_pct", True,  1),
        ("ORB%（進攻籃板）",  "orb_pct", False, 1),
        ("FTR（罰球率）",     "ftr",     False, 1),
        ("ORTG（進攻效率）",  "ortg",    False, 1),
        ("DRTG（防守效率）",  "drtg",    True,  1),
    ]
    ff_rows = []
    for label, key, lower_better, scale in ff_metrics:
        vh = round(float(th[key]) * scale, 2)
        va = round(float(ta_m[key]) * scale, 2)
        adv = home_team if (vh < va if lower_better else vh > va) else (away_team if (va < vh if lower_better else va > vh) else "平手")
        ff_rows.append({"指標": label, home_team: vh, away_team: va, "優勢": adv})

    ff_df_m = pd.DataFrame(ff_rows).set_index("指標")

    def hl_ff(row):
        styles = ["","",""]
        adv = row["優勢"]
        if adv == home_team:   styles[0] = f"color:{ch};font-weight:bold"
        elif adv == away_team: styles[1] = f"color:{ca_m};font-weight:bold"
        return styles

    st.dataframe(ff_df_m.style.apply(hl_ff, axis=1), use_container_width=True)

    # ── 關鍵位置對位 ──
    st.markdown("---")
    st.markdown("**關鍵位置對位**")
    pos_cols = st.columns(3)
    for pc, pos, pos_name in zip(pos_cols, ["G","F","C"], ["後衛","前鋒","中鋒"]):
        ph_list = players[(players["team"]==home_team) & (players["pos"]==pos)].sort_values("pts", ascending=False)
        pa_list = players[(players["team"]==away_team) & (players["pos"]==pos)].sort_values("pts", ascending=False)
        ph_p = ph_list.iloc[0] if len(ph_list) > 0 else None
        pa_p = pa_list.iloc[0] if len(pa_list) > 0 else None
        with pc:
            adv_side = "平手"
            if ph_p is not None and pa_p is not None:
                adv_side = home_team if float(ph_p["per"]) > float(pa_p["per"]) else away_team
            st.markdown(f"""
            <div style='border:1.5px solid #dee2e6;border-radius:10px;padding:14px;'>
              <div style='text-align:center;font-weight:700;color:#1e3c72;margin-bottom:10px;'>{pos_name}（{pos}）</div>
              <div style='display:flex;justify-content:space-between;gap:8px;'>
                <div style='text-align:center;flex:1;border-right:1px solid #eee;padding-right:8px;'>
                  <div style='font-size:0.85em;color:{ch};font-weight:600;'>{"🏆 " if adv_side==home_team else ""}{ph_p["name"] if ph_p is not None else "—"}</div>
                  <div style='font-size:0.75em;color:#888;'>{"外援｜" if ph_p is not None and ph_p["is_import"] else ""}{f"{ph_p['pts']}分 {ph_p['reb']}板" if ph_p is not None else ""}</div>
                </div>
                <div style='text-align:center;flex:1;padding-left:8px;'>
                  <div style='font-size:0.85em;color:{ca_m};font-weight:600;'>{"🏆 " if adv_side==away_team else ""}{pa_p["name"] if pa_p is not None else "—"}</div>
                  <div style='font-size:0.75em;color:#888;'>{"外援｜" if pa_p is not None and pa_p["is_import"] else ""}{f"{pa_p['pts']}分 {pa_p['reb']}板" if pa_p is not None else ""}</div>
                </div>
              </div>
            </div>
            """, unsafe_allow_html=True)


# ══════════════════════════════════════════════════════════════════
# 7. 勝率預測模型
# ══════════════════════════════════════════════════════════════════
with tabs[6]:
    st.subheader("🤖 勝率預測模型（Ridge Regression）")
    st.caption(f"訓練數據：2024-25 例行賽 7 支球隊｜sklearn Ridge Regression｜模型 R² = {model_data['r2_score']}")

    st.markdown("**快速載入球隊數據**")
    col_la, col_lb = st.columns(2)
    with col_la:
        load_a = st.selectbox("主場球隊", ["（自訂）"] + [t["name"] for t in model_data["team_data"]], key="ml_a")
    with col_lb:
        load_b = st.selectbox("客場球隊", ["（自訂）"] + [t["name"] for t in model_data["team_data"]], key="ml_b")

    def get_defaults(name):
        if name == "（自訂）":
            return {"efg":50.0,"tov_pct":14.5,"orb_pct":29.0,"ftr":28.0,"net_rtg":0.0}
        t = next(x for x in model_data["team_data"] if x["name"] == name)
        return {"efg":t["efg"],"tov_pct":t["tov_pct"],"orb_pct":t["orb_pct"],
                "ftr":t["ftr"]*100,"net_rtg":t["net_rtg"]}

    def_a = get_defaults(load_a)
    def_b = get_defaults(load_b)

    st.markdown("---")
    col_slA, col_mid, col_slB = st.columns([2,1,2])

    with col_slA:
        st.markdown(f"**🏠 主場 — {load_a}**")
        efg_a = st.slider("eFG% (有效命中率)", 44.0, 56.0, float(def_a["efg"]),     0.1, key="s_efga")
        tov_a = st.slider("TOV% (失誤率) ↓",  11.0, 18.0, float(def_a["tov_pct"]), 0.1, key="s_tova")
        orb_a = st.slider("ORB% (進攻籃板)",   22.0, 35.0, float(def_a["orb_pct"]), 0.1, key="s_orba")
        ftr_a = st.slider("FTR ×100",          18.0, 38.0, float(def_a["ftr"]),     0.5, key="s_ftra")
        net_a = st.slider("淨效率值",          -12.0, 8.0,  float(def_a["net_rtg"]), 0.1, key="s_neta")

    with col_slB:
        st.markdown(f"**✈️ 客場 — {load_b}**")
        efg_b = st.slider("eFG% (有效命中率)", 44.0, 56.0, float(def_b["efg"]),     0.1, key="s_efgb")
        tov_b = st.slider("TOV% (失誤率) ↓",  11.0, 18.0, float(def_b["tov_pct"]), 0.1, key="s_tovb")
        orb_b = st.slider("ORB% (進攻籃板)",   22.0, 35.0, float(def_b["orb_pct"]), 0.1, key="s_orbb")
        ftr_b = st.slider("FTR ×100",          18.0, 38.0, float(def_b["ftr"]),     0.5, key="s_ftrb")
        net_b = st.slider("淨效率值",          -12.0, 8.0,  float(def_b["net_rtg"]), 0.1, key="s_netb")

    feat_a2 = {"efg":efg_a/100,"tov_pct":tov_a,"orb_pct":orb_a,"ftr":ftr_a/100,"net_rtg":net_a}
    feat_b2 = {"efg":efg_b/100,"tov_pct":tov_b,"orb_pct":orb_b,"ftr":ftr_b/100,"net_rtg":net_b}
    wa2 = ridge_predict(feat_a2)
    wb2 = ridge_predict(feat_b2)
    prob_a2 = wa2 / (wa2 + wb2)

    with col_mid:
        st.markdown("<br><br><br>", unsafe_allow_html=True)
        margin2 = abs(prob_a2 - 0.5) * 200
        v2 = ("⚖️ 勢均力敵" if margin2 < 5 else
              ("📈 略有優勢" if margin2 < 15 else
               ("🏆 明顯佔優" if margin2 < 30 else "💪 大幅領先")) if prob_a2 > 0.5 else
              ("📉 略有劣勢" if margin2 < 15 else
               ("📉 明顯劣勢" if margin2 < 30 else "❌ 大幅落後")))
        st.markdown(f"""
        <div style='text-align:center;padding:10px;'>
          <div style='font-size:2.8em;font-weight:900;color:#1e3c72;'>{prob_a2:.0%}</div>
          <div style='color:#888;font-size:0.85em;margin-bottom:10px;'>主場勝率</div>
          <div style='font-size:1.3em;color:#aaa;font-weight:700;'>VS</div>
          <div style='font-size:2.8em;font-weight:900;color:#c0392b;margin-top:10px;'>{1-prob_a2:.0%}</div>
          <div style='color:#888;font-size:0.85em;margin-bottom:12px;'>客場勝率</div>
          <div style='font-weight:600;'>{v2}</div>
        </div>
        """, unsafe_allow_html=True)

    # ── 特徵影響圖 ──
    st.markdown("---")
    st.markdown("**各指標對主場勝率的影響**")
    feat_labels = {"efg":"eFG%","tov_pct":"TOV%","orb_pct":"ORB%","ftr":"FTR","net_rtg":"淨效率值"}
    impacts_x = [
        (feat_a2[f] - feat_b2[f]) * model_data["coefficients"][i] / model_data["scaler_std"][i]
        for i, f in enumerate(model_data["features"])
    ]
    fig_imp = go.Figure(go.Bar(
        y=[feat_labels[f] for f in model_data["features"]],
        x=impacts_x, orientation="h",
        marker_color=["#198754" if x >= 0 else "#dc3545" for x in impacts_x],
        text=[f"{x:+.4f}" for x in impacts_x], textposition="outside",
    ))
    fig_imp.update_layout(
        xaxis_title="影響值（正 = 有利主場）",
        height=280, margin=dict(l=20,r=60,t=10,b=30),
        paper_bgcolor="rgba(0,0,0,0)",
    )
    st.plotly_chart(fig_imp, use_container_width=True)
    st.caption("* sklearn Ridge Regression（α=0.5）｜特徵：eFG%, TOV%, ORB%, FTR, 淨效率值｜TPBL 2024-25 例行賽 7 支球隊訓練")
