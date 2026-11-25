// مراقب النوافذ والعمليات على ويندوز — يطبع سطر JSON كل 3 ثوانٍ على stdout
// يُشغَّل بواسطة agent.js كعملية فرعية طويلة الأمد
$seen = @{}
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class Win32Fg {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
}
"@

while ($true) {
  try {
    $hwnd = [Win32Fg]::GetForegroundWindow()
    $sb = New-Object System.Text.StringBuilder 512
    [void][Win32Fg]::GetWindowText($hwnd, $sb, 512)
    $title = $sb.ToString()
    $pidUint = 0
    [void][Win32Fg]::GetWindowThreadProcessId($hwnd, [ref]$pidUint)
    $procName = ""
    if ($pidUint -gt 0) {
      $p = Get-Process -Id $pidUint -ErrorAction SilentlyContinue
      if ($p) { $procName = $p.ProcessName + ".exe" }
    }
    $procList = @(Get-Process -ErrorAction SilentlyContinue | ForEach-Object { $_.ProcessName + ".exe" } | Sort-Object -Unique)
    @{ t = $title; p = $procName; a = $procList } | ConvertTo-Json -Compress -Depth 2
  } catch { }
  Start-Sleep -Seconds 3
}
