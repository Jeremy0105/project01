import numpy as np
import plotly.graph_objects as go
import pandas as pd


def _hex_to_rgba(hex_color: str, alpha: float = 0.2) -> str:
    hex_color = hex_color.lstrip("#")
    r = int(hex_color[0:2], 16)
    g = int(hex_color[2:4], 16)
    b = int(hex_color[4:6], 16)
    return f"rgba({r},{g},{b},{alpha})"


def normalize_for_radar(df: pd.DataFrame, player_name: str, cols: list) -> list:
    row  = df[df["name"] == player_name].iloc[0]
    mins = df[cols].min()
    maxs = df[cols].max()
    result = []
    for c in cols:
        if maxs[c] == mins[c]:
            result.append(50.0)
        else:
            result.append(round((row[c] - mins[c]) / (maxs[c] - mins[c]) * 100, 1))
    return result


def polar_fig(labels: list, values: list, name: str, color: str, height: int = 300):
    fig = go.Figure(go.Scatterpolar(
        r=values + [values[0]],
        theta=labels + [labels[0]],
        fill="toself",
        name=name,
        line_color=color,
        fillcolor=_hex_to_rgba(color, 0.2),
    ))
    fig.update_layout(
        polar=dict(radialaxis=dict(visible=True, range=[0, 100])),
        showlegend=False,
        height=height,
        paper_bgcolor="rgba(0,0,0,0)",
        font_color="white",
        margin=dict(l=30, r=30, t=30, b=30),
    )
    return fig


def stat_bar(label: str, val: float, avg: float, color: str) -> str:
    pct     = min(int(val * 100), 100)
    avg_pct = min(int(avg * 100), 100)
    return f"""
    <div style="margin:8px 0">
      <div style="display:flex;justify-content:space-between;margin-bottom:3px">
        <span style="font-weight:600">{label}</span>
        <span style="color:{color};font-weight:bold">{val:.1%}</span>
      </div>
      <div style="background:#2a2a3e;border-radius:4px;height:10px;position:relative">
        <div style="background:{color};width:{pct}%;height:100%;border-radius:4px"></div>
        <div style="position:absolute;top:-3px;left:{avg_pct}%;width:2px;height:16px;
                    background:white;opacity:0.5"></div>
      </div>
      <div style="font-size:0.72rem;color:#888;margin-top:2px">聯盟均值: {avg:.1%}</div>
    </div>"""
