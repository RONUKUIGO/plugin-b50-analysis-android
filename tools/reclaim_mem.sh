#!/system/bin/sh
# ============================================================
# 回收内存 —— 设备内存紧张时先跑这个（比重启省事得多）
#
# 干什么：
#   1) 停 Gradle / Kotlin 编译守护进程（构建完不退出，实测常驻 1~3 GB，是最大的一笔）
#   2) 让系统回收后台缓存进程（am kill-all，只杀 cached 后台，前台和常驻服务不受影响）
#   3) 打印回收前后的内存对比 + 当前 RSS TOP 10，方便判断还有谁在吃
#
# 用法:
#   sh tools/reclaim_mem.sh            # 只回收 + 报告
#   sh tools/reclaim_mem.sh --dry-run  # 只看现在谁在吃内存，不动手
# ============================================================

report() {
    echo "--- $1 ---"
    awk '/MemTotal|MemFree|MemAvailable|SwapFree/ {printf "  %-14s %8.2f GB\n", $1, $2/1048576}' /proc/meminfo
}

echo "=== 现在谁在吃内存（RSS TOP 10）==="
ps -A -o pid,rss,args --sort=-rss 2>/dev/null | head -11 | \
    awk 'NR==1{print "  PID      RSS"} NR>1{printf "  %-8s %7.1f MB  ", $1, $2/1024; for(i=3;i<=NF&&i<=4;i++) printf "%s ", $i; print ""}'

[ "$1" = "--dry-run" ] && exit 0

report "回收前"
echo "=== 1/2 停编译守护进程 ==="
for n in GradleDaemon KotlinCompileDaemon; do
    pids=$(ps -A -o pid,args | grep "$n" | grep -v grep | awk '{print $1}')
    if [ -n "$pids" ]; then echo "  停 $n: $pids"; kill $pids 2>/dev/null; else echo "  $n: 没有在跑"; fi
done
sleep 2
pkill -9 -f GradleDaemon 2>/dev/null
pkill -9 -f KotlinCompileDaemon 2>/dev/null

echo "=== 2/2 回收后台缓存进程 ==="
/system/bin/am kill-all 2>/dev/null && echo "  已通知系统回收"
sleep 3

report "回收后"
echo "提示：page cache（可回收）不算真实占用，看 MemAvailable 才准。"
