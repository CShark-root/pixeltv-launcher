#!/bin/sh
# Дымовой тест PixelDen на ТВ: обходит все экраны нажатиями и проверяет, что лаунчер не упал.
# Запуск из папки platform-tools:  sh tvlauncher/tools/smoke-test.sh [ip:port] [папка-для-скриншотов]
export MSYS_NO_PATHCONV=1
S=${1:-192.168.1.51:5555}
OUT=${2:-.}
ADB="./adb.exe -s $S"
PKG=com.pixelden.launcher

k() { $ADB shell input keyevent "$@"; sleep 0.5; }
shot() { $ADB shell screencap -p /sdcard/smoke.png; $ADB pull /sdcard/smoke.png "$OUT/smoke_$1.png" >/dev/null 2>&1; }
alive() {
    pid=$($ADB shell pidof $PKG | tr -d '\r')
    top=$($ADB shell dumpsys activity activities | grep mResumedActivity | grep -c $PKG)
    if [ -z "$pid" ] || [ "$top" = "0" ]; then echo "FAIL после шага: $1"; $ADB shell logcat -d -b crash | grep -A6 "FATAL EXCEPTION" | tail -8; exit 1; fi
    echo "ok: $1"
}

$ADB logcat -b crash -c
k KEYCODE_HOME; sleep 2; alive "главный экран"
# шапка: вверх к кнопкам, влево до упора = «Сценарии»
k 19; k 19; k 21; k 21; k 21
k 23; sleep 1.2; alive "открыть Сценарии"; shot scenarios
k 20; k 23; sleep 1.2; alive "редактор сценария"; shot editor
k 4; sleep 1; alive "назад к списку"
k 4; sleep 1; alive "назад на главный"
# «Настройки» — вторая кнопка шапки
k 19; k 19; k 21; k 21; k 21; k 22
k 23; sleep 1.2; alive "открыть Настройки"; shot settings
# открыть каждую плитку хаба: OK → страница → BACK → следующая плитка
i=0
while [ $i -lt 13 ]; do
    k 23; sleep 1.0; alive "страница раздела $i"
    [ $i -eq 0 ] && shot settings_page
    k 4; sleep 1.0; alive "назад на хаб ($i)"
    if [ $((i % 3)) -eq 2 ]; then k 20; k 21; k 21; else k 22; fi
    i=$((i + 1))
done
shot settings_end
k 4; sleep 1; alive "назад из Настроек"
echo "ВСЕ ЭКРАНЫ ОК"
