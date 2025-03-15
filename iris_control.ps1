<#
.SYNOPSIS
Manages Iris service on Android device via ADB.
.DESCRIPTION
This script provides functions to install, start, stop, and check the status of the Iris service on an Android device using ADB.
#>
param(
    [Parameter(Position=0, Mandatory=$false)]
    [ValidateSet('status', 'start', 'stop', 'install')]
    [string]$Action = 'status'
)

$IRIS_PROCESS_NAME = "qwer"
$IRIS_PROCESS_KEYWORD = "app_process"
$IRIS_START_COMMAND = "adb shell 'su root sh -c `"CLASSPATH=/data/local/tmp/Iris.apk app_process / party.qwer.iris.Main`"' 2>&1"
$IRIS_APK_URL = "https://github.com/dolidolih/Iris/releases/latest/download/Iris.apk"
$IRIS_APK_PATH = "/data/local/tmp/Iris.apk"
$IRIS_APK_LOCAL_FILE = "Iris.apk"
$IRIS_APK_MD5_LOCAL_FILE = "Iris.apk.md5"

function Check-AdbInstalled {
    if (!(Get-Command adb -ErrorAction SilentlyContinue)) {
        Write-Host "adb is not installed. Please install adb and add it to your PATH."
        Write-Host "You can usually install it with Android SDK Platform-Tools."
        return $false
    }
    return $true
}

function Check-AdbDevice {
    adb devices | Out-Null
    if (!(adb devices | Select-String -Pattern "`tdevice")) {
        Write-Host "No device found. Please ensure your Android device is connected via USB or network."
        $device_ip = Read-Host "If using network, enter device IP address (or press Enter to skip)"
        if ($device_ip) {
            adb connect $device_ip
            Start-Sleep -Seconds 3
            if (!(adb devices | Select-String -Pattern $device_ip)) {
                Write-Host "Failed to connect to device at $device_ip. Please check IP and device status."
                return $false
            } else {
                Write-Host "Successfully connected to device at $device_ip."
                return $true
            }
        } else {
            Write-Host "No device IP provided. Please connect a device."
            return $false
        }
    }
    return $true
}

function Get-IrisPid {
    if (!(Check-AdbDevice)) { return }
    $pid_iris = adb shell "ps -f" | Select-String -Pattern "$IRIS_PROCESS_NAME" | Where-Object { $_ -notmatch 'sh -c' } | ForEach-Object { $_.Line -split ' ' -ne '' } | Where-Object { $_ -match '^[0-9]+$' } | Select-Object -First 1
    return $pid_iris
}

function Iris-Status {
    if (!(Check-AdbDevice)) { return }
    $pid_iris = Get-IrisPid
    if ($pid_iris) {
        Write-Host "Iris is working. PID: $pid_iris"
    } else {
        Write-Host "Iris is not running."
    }
}

function Iris-Start {
    if (!(Check-AdbDevice)) { return }
    $pid_iris = Get-IrisPid
    if ($pid_iris) {
        Write-Host "Iris is already running."
    } else {
        Write-Host "Starting Iris service..."
        $job = Start-Job -ScriptBlock {
            adb shell "su root sh -c 'app_process -cp /data/local/tmp/Iris.apk / party.qwer.iris.Main'"
        }
        Start-Sleep -Seconds 1
        Get-Job -Id $job.Id | Out-Null
        Remove-Job -Id $job.Id -Force
        $pid_iris = Get-IrisPid
        if ($pid_iris) {
            Write-Host "Iris is working. PID: $pid_iris"
        } else {
            Write-Host "Failed to start Iris."
        }
    }
}
function Iris-Stop {
    if (!(Check-AdbDevice)) { return }
    $pid_iris = Get-IrisPid
    if ($pid_iris) {
        Write-Host "Stopping Iris service..."
        adb shell "su root sh -c 'kill -s SIGKILL $($pid_iris)'"
        Start-Sleep -Seconds 1
        $stopped_pid = Get-IrisPid
        if (!($stopped_pid)) {
            Write-Host "Iris service stopped."
        } else {
            Write-Host "Failed to stop Iris service (PID: $pid_iris) may still be running."
        }
    } else {
        Write-Host "Iris is not running."
    }
}

function Iris-Install {
    if (!(Check-AdbInstalled)) { return }
    if (!(Check-AdbDevice)) { return }

    Write-Host "Downloading Iris.apk..."
    try {
        Invoke-WebRequest -Uri $IRIS_APK_URL -OutFile $IRIS_APK_LOCAL_FILE
        Write-Host "Download completed."
    } catch {
        Write-Host "Failed to download Iris.apk. Please check the URL and your internet connection."
        return
    }

    Write-Host "Downloading MD5 checksum..."
    try {
        Invoke-WebRequest -Uri ($IRIS_APK_URL + ".MD5") -OutFile $IRIS_APK_MD5_LOCAL_FILE
        Write-Host "MD5 checksum downloaded."
    } catch {
        Write-Warning "Failed to download MD5 checksum. Skipping MD5 verification."
        Write-Warning "Installation will proceed without checksum verification."

    }

    Write-Host "Verifying MD5 checksum..."
    if (Test-Path $IRIS_APK_MD5_LOCAL_FILE) {
        $expected_md5 = Get-Content $IRIS_APK_MD5_LOCAL_FILE
        $calculated_md5 = Get-FileHash -Algorithm MD5 -Path $IRIS_APK_LOCAL_FILE | Select-Object -ExpandProperty Hash

        if ($expected_md5 -eq $calculated_md5) {
            Write-Host "MD5 checksum verification passed!"
        } else {
            Write-Host "MD5 checksum verification failed!"
            Write-Host "Expected MD5: $($expected_md5)"
            Write-Host "Calculated MD5: $($calculated_md5)"
            Write-Host "Downloaded file may be corrupted. Installation aborted."
            return
        }
    } else {
        Write-Host "MD5 checksum verification skipped due to download failure."
    }


    Write-Host "Pushing Iris.apk to device..."
    adb push $IRIS_APK_LOCAL_FILE $IRIS_APK_PATH
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Failed to push Iris.apk to /data/local/tmp. Check adb connection and permissions."
        return
    }

    Write-Host "Verifying installation..."
    $verify_output = adb shell "ls $IRIS_APK_PATH"
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Installation completed!"
    } else {
        Write-Host "Installation verification failed. File might not be in /data/local/tmp."
    }
}


switch ($Action) {
    "status" {
        Iris-Status
    }
    "start" {
        Iris-Start
    }
    "stop" {
        Iris-Stop
    }
    "install" {
        Iris-Install
    }
    default {
        Write-Host "Usage: $PSCommandPath {status|start|stop|install}"
        exit 1
    }
}

exit 0