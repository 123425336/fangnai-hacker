# Rebuild registry.json for Fangnai Hacker tool directory from README.md snapshot + recipes/docs.
# README.md is the last-good catalog snapshot written by ToolRegistry.writeRegistryAndReadme.
$ErrorActionPreference = 'Stop'

$toolDir = 'C:\Users\123\Desktop\mc\.minecraft\versions\1.20.1-Forge_47.4.16\tool'
$readmePath = Join-Path $toolDir 'README.md'
$registryPath = Join-Path $toolDir 'registry.json'

$readme = Get-Content $readmePath -Encoding UTF8 -Raw

# Split into per-tool sections by '## <id>' headings
$sections = [regex]::Split($readme, '(?m)^## ')
$tools = New-Object System.Collections.Generic.List[object]
$seen = @{}

foreach ($section in $sections) {
    if (-not $section -or $section.Trim() -eq '') { continue }
    $lines = $section -split "`r?`n"
    $id = $lines[0].Trim()
    if (-not $id) { continue }

    function Get-Field([string[]]$lns, [string]$name) {
        foreach ($l in $lns) {
            if ($l -match "^\- $([regex]::Escape($name)):\s*(.*)$") {
                $v = $Matches[1].Trim()
                if ($v.StartsWith('`') -and $v.EndsWith('`') -and $v.Length -ge 2) {
                    $v = $v.Substring(1, $v.Length - 2)
                }
                return $v
            }
        }
        return $null
    }

    $purpose = Get-Field $lines 'Purpose'
    $capability = Get-Field $lines 'Capability'
    $confirmation = Get-Field $lines 'Confirmation'
    $risk = Get-Field $lines 'Risk'
    $sha256 = Get-Field $lines 'SHA-256'
    $className = Get-Field $lines 'Class'
    $runtimeStatus = Get-Field $lines 'Runtime'
    $methodsRaw = Get-Field $lines 'Methods'
    $sourcePath = Get-Field $lines 'Source'
    $recipePath = Get-Field $lines 'Recipe'
    $docPath = Get-Field $lines 'Doc'

    if (-not $purpose -or -not $capability -or -not $recipePath) {
        Write-Warning "Section '$id' missing required fields; skipped"
        continue
    }

    # methods list
    $methods = @()
    if ($methodsRaw -and $methodsRaw -ne 'unknown') {
        $methods = @($methodsRaw -split ',\s*' | Where-Object { $_ })
    }

    $isGenerated = $capability -eq 'define_generated_class' -or $capability -eq 'compile_define_generated_class'
    $name = if ($isGenerated -and $className) { "generated class $className" } else { $purpose }

    $tool = [ordered]@{
        id = $id
        name = $name
        purpose = $purpose
        capability = $capability
        confirmationPolicy = $confirmation
        recipePath = $recipePath
        docPath = $docPath
        sha256 = $sha256
    }

    if ($isGenerated) {
        $tool.className = $className
        $tool.classSha256 = $sha256
        if ($sourcePath) { $tool.sourcePath = $sourcePath }
        if ($methods.Count -gt 0) { $tool.methods = $methods }
    }

    # dates from doc when available
    $docFile = Join-Path $toolDir $docPath
    if (Test-Path $docFile) {
        $doc = Get-Content $docFile -Encoding UTF8 -Raw
        if ($doc -match '(?m)^- Created:\s*(\S.*)$') { $tool.createdAt = $Matches[1].Trim() }
        if ($doc -match '(?m)^- Updated:\s*(\S.*)$') { $tool.updatedAt = $Matches[1].Trim() }
        if ($doc -match '(?m)^- Source SHA-256:\s*`([^`]+)`') { $tool.sourceSha256 = $Matches[1] }
    }

    if ($runtimeStatus) { $tool.runtimeStatus = $runtimeStatus }
    if ($risk) { $tool.riskLevel = $risk }

    # verify referenced files exist
    $recipeOk = Test-Path (Join-Path $toolDir $recipePath)
    if (-not $recipeOk) { Write-Warning "recipe missing for $id : $recipePath" }

    $tools.Add($tool)
    $seen[$id] = $true
}

Write-Host "Parsed $($tools.Count) tools from README"

# Keep ONLY generated-class tools whose .class file actually exists.
# (default-return-* entries are stale transform records; the mod cannot execute them.)
$classDir = Join-Path $toolDir 'classes\com\fangnai\hacker\generated'
$valid = New-Object System.Collections.Generic.List[object]
foreach ($t in $tools) {
    if ($t.capability -ne 'define_generated_class' -and $t.capability -ne 'compile_define_generated_class') {
        Write-Host "skip (not executable) $($t.id) [$($t.capability)]"
        continue
    }
    $simpleName = ($t.className -split '\.')[-1]
    $classFile = Join-Path $classDir "$simpleName.class"
    if (-not (Test-Path $classFile)) {
        Write-Warning "class file missing for $($t.id): $classFile"
        continue
    }
    $hash = (Get-FileHash $classFile -Algorithm SHA256).Hash.ToLower()
    if ($hash -ne $t.sha256) {
        Write-Warning "class hash mismatch for $($t.id): readme=$($t.sha256) actual=$hash"
    } else {
        Write-Host "ok $($t.id) ($simpleName) hash match"
    }
    $valid.Add($t)
}
Write-Host "Keeping $($valid.Count) tools"

$registry = @{ tools = $valid.ToArray() }
$json = ConvertTo-Json $registry -Depth 8

# backup the corrupt (empty) registry, then write
if (Test-Path $registryPath) {
    $bak = "$registryPath.corrupt-bak"
    Copy-Item $registryPath $bak -Force
    Write-Host "Backed up old registry -> $bak"
}
[System.IO.File]::WriteAllText($registryPath, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote $registryPath ($((Get-Item $registryPath).Length) bytes)"

# verify round-trip
$check = Get-Content $registryPath -Encoding UTF8 -Raw | ConvertFrom-Json
Write-Host "Verify: tools=$($check.tools.Count), ids unique=$((@($check.tools.id | Select-Object -Unique)).Count)"
