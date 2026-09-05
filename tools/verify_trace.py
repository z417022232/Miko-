#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真机轨迹验证工具（第四阶段测试工具）。

用法：
  python tools/verify_trace.py timeline [db_path]   # 从本地 db（或先 pull）渲染因果链时间线
  python tools/verify_trace.py pull [out_dir]       # 从手机拉取 db+wal+shm 三件套
  python tools/verify_trace.py overnight            # 息屏恢复检查：服务/WorkState/采样/崩溃

依赖：adb（默认路径，可用环境变量 ADB 覆盖）；python 标准库。
"""
import os
import sqlite3
import subprocess
import sys
import datetime
import tempfile

ADB = os.environ.get("ADB", r"C:\Users\Administrator\Documents\Codex\2026-07-22\referenced-chatgpt-conversation-this-is-untrusted\work\android-env\android-sdk\platform-tools\adb.exe")
PKG = "com.example.worktimetracker"
DB_NAME = "databases/work_time_tracker.db"
HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)
DIAG = os.path.join(os.path.dirname(os.path.dirname(PROJ)), "diagnostics")


def adb(*args, timeout=30):
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)


def pull_db(out_dir=None):
    """拉取 db+wal+shm 三件套（Room 是 WAL 模式，只拉主库会漏数据）。"""
    out_dir = out_dir or os.path.join(DIAG, datetime.datetime.now().strftime("%Y-%m-%d-trace"))
    db_dir = os.path.join(out_dir, "databases")
    os.makedirs(db_dir, exist_ok=True)
    main = os.path.join(db_dir, "work_time_tracker.db")
    with open(main, "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "run-as", PKG, "cat", DB_NAME], capture_output=True).stdout)
    for suffix in ("-wal", "-shm"):
        p = os.path.join(db_dir, "work_time_tracker.db" + suffix)
        data = subprocess.run([ADB, "exec-out", "run-as", PKG, "cat", DB_NAME + suffix], capture_output=True).stdout
        if data:
            with open(p, "wb") as f:
                f.write(data)
    print("已拉取:", db_dir)
    return main


def connect(main):
    db = sqlite3.connect(main)
    # 打开即自动合并 WAL
    db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    return db


def ts(ms):
    return datetime.datetime.fromtimestamp(ms / 1000).strftime("%m-%d %H:%M:%S") if ms else "None"


def render_timeline(main, day=None):
    db = connect(main)
    cur = db.cursor()
    where = "WHERE type IN ('TRACE','STATE','FUSION','MOTION_BURST','SAMPLING','RECORD','SERVICE')"
    args = []
    if day:
        where += " AND time >= ? AND time < ?"
        d = datetime.datetime.strptime(day, "%Y-%m-%d")
        args = [int(d.timestamp() * 1000), int((d + datetime.timedelta(days=1)).timestamp() * 1000)]
    print("=" * 100)
    print("工时事件时间线" + (f"（{day}）" if day else ""))
    print("=" * 100)
    cur.execute(f"SELECT time, type, content FROM app_logs {where} ORDER BY time", args)
    for t, typ, content in cur.fetchall():
        print(f"{ts(t)} [{typ:<12}] {content}")
    print("\n---- 状态机原始日志（STATE）与位置日志已包含在上表；以下是当日四时间核对 ----")
    q = "SELECT workDate, startTime, endTime, homeDepartureTime, homeArrivalTime, needsReview, isManual FROM work_records"
    if day:
        q += " WHERE workDate = ?"
        cur.execute(q, (day,))
    else:
        cur.execute(q + " ORDER BY id DESC LIMIT 5")
    for wd, st, et, hd, ha, nr, im in cur.fetchall():
        print(f"{wd}: 到岗={ts(st)} 离岗={ts(et)} 离家={ts(hd)} 到家={ts(ha)} needsReview={nr} 手动={im}")


def overnight_check():
    print("== 1. 前台服务状态 ==")
    r = adb("shell", f"dumpsys activity services {PKG}")
    svc = [l for l in r.stdout.splitlines() if "ServiceRecord" in l]
    print("服务运行中" if svc else "!! 服务未运行")
    print("\n== 2. 数据库快照 ==")
    main = pull_db()
    db = connect(main)
    cur = db.cursor()
    now_ms = int(datetime.datetime.now().timestamp() * 1000)
    cur.execute("SELECT currentState, lastLocationTime, sessionId, homeDepartureTime, homeArrivalTime, confirmedDepartureTime, lastGpsFixTime, lastNetworkFixTime FROM work_state")
    row = cur.fetchone()
    if row:
        state, last_loc, session, hd, ha, cd, gps, net = row
        age_min = (now_ms - last_loc) / 60000 if last_loc else -1
        print(f"WorkState: {state} | 最后定位 {ts(last_loc)}（{age_min:.0f} 分钟前）| sessionId={session}")
        print(f"离家={ts(hd)} 到家={ts(ha)} 离公司={ts(cd)} | GPS={ts(gps)} NETWORK={ts(net)}")
        if age_min > 30:
            print(f"!! 最后定位已 {age_min:.0f} 分钟前——采样可能停在长间隔档或服务异常")
    print("\n== 3. 最近采样/唤醒日志 ==")
    cur.execute("SELECT time, type, content FROM app_logs WHERE type IN ('SAMPLING','MOTION_BURST','SERVICE','FUSION') ORDER BY time DESC LIMIT 15")
    for t, typ, content in reversed(cur.fetchall()):
        print(f"{ts(t)} [{typ}] {content}")
    print("\n== 4. 崩溃检查（dropbox）==")
    r = adb("shell", "dumpsys dropbox --print data_app_crash", timeout=60)
    lines = [l for l in r.stdout.splitlines()
             if ("Process: " in l or "FATAL" in l or "java.lang" in l or "AndroidRuntime" in l)]
    print("\n".join(lines[-30:]) if lines else "无应用崩溃记录")
    print("\n== 5. 耗电（自上次充满/拔电）==")
    r = adb("shell", "dumpsys batterystats com.example.worktimetracker", timeout=60)
    for line in r.stdout.splitlines():
        if any(k in line for k in ("Foreground services", "Wake lock", "gps", "Total")):
            print(line.strip()[:120])


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "timeline"
    if cmd == "pull":
        pull_db(sys.argv[2] if len(sys.argv) > 2 else None)
    elif cmd == "overnight":
        overnight_check()
    else:
        day = sys.argv[3] if len(sys.argv) > 3 else None
        if len(sys.argv) > 2:
            main = sys.argv[2]
        else:
            main = pull_db()
        render_timeline(main, day)
