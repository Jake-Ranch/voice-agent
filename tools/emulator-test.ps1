# Voice-agent 模拟器冒烟测试：每次提交前必跑。
# 用 PowerShell 7 运行（中文断言依赖 UTF-8）：
#   在 pwsh 里执行: & 'C:\MyDIY\58iosmaid\emulator-test.ps1'
$ErrorActionPreference = 'Continue'
$adb = 'C:\MyDIY\58iosmaid\android-sdk\platform-tools\adb.exe'
$apk = 'C:\MyDIY\58iosmaid\voice-agent\app\build\outputs\apk\debug\app-debug.apk'
$pkg = 'io.github.jakeranch.voiceagent'
$fail = 0
function Check($name, $ok) {
    if ($ok) { Write-Output "PASS: $name" } else { $script:fail++; Write-Output "FAIL: $name" }
}

# 0) 模拟器在线 + 唤醒解锁（锁屏状态下无障碍服务不会绑定）
$dev = & $adb devices | Select-String -Pattern 'emulator-\d+\s+device'
Check 'emulator online' ($null -ne $dev)
if ($null -eq $dev) { exit 2 }
& $adb shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
& $adb shell wm dismiss-keyguard 2>&1 | Out-Null
Start-Sleep -Seconds 2

# 1) 安装 + 权限 + 无障碍
& $adb install -r $apk 2>&1 | Out-Null
& $adb shell pm grant $pkg android.permission.RECORD_AUDIO 2>&1 | Out-Null
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>&1 | Out-Null
& $adb shell settings put secure enabled_accessibility_services "$pkg/$pkg.HandsService" 2>&1 | Out-Null
& $adb shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
Start-Sleep -Seconds 4
$bound = ((& $adb shell dumpsys accessibility 2>&1) -join '')
Check 'accessibility service bound' ($bound -match 'Bound services:\{Service')

# 2) 启动 App，进程存活
& $adb shell am force-stop $pkg 2>&1 | Out-Null
& $adb logcat -c 2>&1 | Out-Null
& $adb shell am start -n "$pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 6
$proc = (& $adb shell pidof $pkg).Trim()
Check 'app process alive' ($proc -ne '')

# 屏幕尺寸 → 坐标（齿轮实测 y=342：DeviceDefault 标题栏把布局整体压低了）
$sizeLine = ((& $adb shell wm size) -join '').Trim()
$m = [regex]::Match($sizeLine, '(\d+)x(\d+)')
$w = [int]$m.Groups[1].Value
$h = [int]$m.Groups[2].Value

# 3) 点齿轮 → 抽屉打开（用日志断言；先重置状态确保抽屉初始关闭）
& $adb shell am force-stop $pkg 2>&1 | Out-Null
& $adb logcat -c 2>&1 | Out-Null
& $adb shell am start -n "$pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 5
& $adb shell input tap ($w - 58) 342 2>&1 | Out-Null
Start-Sleep -Seconds 2
$log = ((& $adb logcat -d -s VoiceAgent:*) -join '')
Check 'settings drawer opens' ($log -match '设置抽屉已打开')

# 4) 面板可交互：点 key 框（不可被遮罩盖住）→ 点保存 → 服务启动
#    （专门抓"遮罩盖住面板"回归：若被盖住，点 key 框会点到遮罩导致抽屉收起）
& $adb shell input tap ([int]($w * 0.3)) 515 2>&1 | Out-Null
Start-Sleep -Seconds 2
$l1 = ((& $adb logcat -d -s VoiceAgent:*) -join '')
Check 'panel not covered by scrim' (-not ($l1 -match '设置抽屉已收起'))
& $adb shell input tap ([int]($w * 0.35)) 680 2>&1 | Out-Null  # 保存按钮（框里是已有的真实 key）
Start-Sleep -Seconds 5
$l2 = ((& $adb logcat -d -s VoiceAgent:*) -join '')
Check 'save button starts service' ($l2 -match '语音服务已启动')

# 5) 点遮罩空白处 → 抽屉收起（用日志断言）
& $adb shell input tap ($w - 40) 800 2>&1 | Out-Null
Start-Sleep -Seconds 2
$log2 = ((& $adb logcat -d -s VoiceAgent:*) -join '')
Check 'drawer closes on scrim tap' ($log2 -match '设置抽屉已收起')

# 6) 启动服务 → 本地模型就绪
& $adb shell am start-foreground-service -n "$pkg/.AgentService" 2>&1 | Out-Null
$ready = $false
for ($i = 0; $i -lt 24; $i++) {
    $l = ((& $adb logcat -d -s VoiceAgent:*) -join '')
    if ($l -match '本地识别模型就绪') { $ready = $true; break }
    Start-Sleep -Seconds 5
}
Check 'local ASR model ready' $ready

# 6.5) 重新确认无障碍绑定（前面的 force-stop/install 可能打断绑定）
& $adb shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
& $adb shell wm dismiss-keyguard 2>&1 | Out-Null
& $adb shell settings put secure enabled_accessibility_services "$pkg/$pkg.HandsService" 2>&1 | Out-Null
& $adb shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
Start-Sleep -Seconds 4
$bound2 = ((& $adb shell dumpsys accessibility 2>&1) -join '')
Check 'accessibility rebound before command' ($bound2 -match 'Bound services:\{Service')

# 7) 文本指令「打开Maps」→ agent 执行并真打开地图（模拟器上 Maps 标签确定匹配）
& $adb logcat -c 2>&1 | Out-Null
& $adb shell "am start-foreground-service -n $pkg/.AgentService --es text '打开Maps'" 2>&1 | Out-Null
$done = $false
for ($i = 0; $i -lt 12; $i++) {
    $l = ((& $adb logcat -d -s VoiceAgent:*) -join '')
    if ($l -match 'done|停下来了') { $done = $true; break }
    Start-Sleep -Seconds 5
}
Check 'text command processed' $done
$top = ((& $adb shell dumpsys activity activities | Select-String -Pattern 'topResumedActivity') -join '').Trim()
Check 'maps opened by agent' ($top -match 'com.google.android.apps.maps')

Write-Output "===== RESULT: $fail failure(s) ====="
exit $fail
