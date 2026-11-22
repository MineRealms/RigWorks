param(
    [string]$Pack = 'G:/DOWNLOAD-EDGE/CustomSteve-neoforge-1.21.11-1.0.0-rc-hf4-FullPack.zip',
    [string]$EngineJar = 'H:/MinecraftMods/Reverse/customsteve-1.21.11-rc-hf4-analysis/extracted/META-INF/jarjar/com.mmd.java_ngengine-1.0.0-rc-hf2.jar',
    [string]$NativeBridge = 'H:/MinecraftMods/Reverse/customsteve-1.21.11-rc-hf4-analysis/extracted/customsteve-natives/windows-x86_64/df_java_bridge.dll',
    [string]$Java = 'C:/Program Files/Java/jdk-21/bin/java.exe',
    [string]$Python = 'python',
    [string]$Manifest = (Join-Path $PSScriptRoot 'source_clips.tsv'),
    [switch]$SkipSampling,
    [switch]$SkipExport
)

$ErrorActionPreference = 'Stop'
$previewRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location -LiteralPath $previewRepo
try {
    if (-not $SkipSampling) {
        foreach ($requiredPath in @($Pack, $EngineJar, $NativeBridge, $Java)) {
            if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
                throw "Missing local preview dependency: $requiredPath"
            }
        }
        & $Java "-Dmmd.jni.path=$NativeBridge" --class-path $EngineJar `
            (Join-Path $PSScriptRoot 'SampleCustomSteveMotion.java') `
            --pack $Pack --manifest $Manifest --output (Join-Path $previewRepo 'build/vmd-motion-preview/source-samples.json')
        if ($LASTEXITCODE -ne 0) { throw "MMD sampling failed: $LASTEXITCODE" }
    }
    & $Python (Join-Path $PSScriptRoot 'blender_bridge.py') --script (Join-Path $PSScriptRoot 'prepare_blender_preview.py')
    if ($LASTEXITCODE -ne 0) { throw "Blender preparation failed: $LASTEXITCODE" }
    & $Python (Join-Path $PSScriptRoot 'blender_bridge.py') --script (Join-Path $PSScriptRoot 'retarget_blender_preview.py') --timeout 600
    if ($LASTEXITCODE -ne 0) { throw "Blender baking failed: $LASTEXITCODE" }
    & $Python (Join-Path $PSScriptRoot 'blender_bridge.py') --script (Join-Path $PSScriptRoot 'verify_blender_preview.py') --timeout 600
    if ($LASTEXITCODE -ne 0) { throw "Blender verification failed: $LASTEXITCODE" }
    if (-not $SkipExport) {
        & $Python (Join-Path $PSScriptRoot 'blender_bridge.py') --script (Join-Path $PSScriptRoot 'export_blender_preview.py') --timeout 600
        if ($LASTEXITCODE -ne 0) { throw "FBX export failed: $LASTEXITCODE" }
        Write-Host "Independent FBX takes: $(Join-Path $previewRepo 'build/vmd-motion-preview/exports')"
    }
    & $Python (Join-Path $PSScriptRoot 'blender_bridge.py') --code "import bpy; bpy.context.scene.frame_set(108); bpy.context.scene.sync_mode = 'FRAME_DROP'; bpy.ops.screen.animation_play() if not bpy.context.screen.is_animation_playing else None"
    if ($LASTEXITCODE -ne 0) { throw "Preview playback failed: $LASTEXITCODE" }
    Write-Host "Blender preview: $(Join-Path $previewRepo 'build/vmd-motion-preview/customsteve_clips_preview.blend')"
} finally {
    Pop-Location
}
