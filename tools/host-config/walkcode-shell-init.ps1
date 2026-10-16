# Walkcode pane shell for Windows hosts (psmux).
# Sessions started over Windows OpenSSH inherit "ignore Ctrl+C" from sshd, so neither the
# shell nor the agents it starts can be interrupted with Ctrl+C. Re-enable it for this
# process; programs started from this shell inherit the setting.
Add-Type -Namespace Walkcode -Name Console -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool SetConsoleCtrlHandler(System.IntPtr handler, bool add);
'@
[void][Walkcode.Console]::SetConsoleCtrlHandler([IntPtr]::Zero, $false)
