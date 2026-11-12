function Import-AiEnvironment([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "AI environment file was not found: $Path" }
    $allowed = '^(AI_MODEL_PROVIDER|AI_RUNTIME_SETTINGS_ENABLED|INTERNAL_SERVICE_TOKEN|MYSQL_(URL|USERNAME|PASSWORD)|EDUCATION_MYSQL_(URL|USERNAME|PASSWORD)|DEEPSEEK_[A-Z0-9_]+|OPENAI_COMPATIBLE_[A-Z0-9_]+|TUTOR_ANIMATION_[A-Z0-9_]+|OLLAMA_[A-Z0-9_]+|MEMORY_[A-Z0-9_]+|CHROMA_[A-Z0-9_]+|KNOWLEDGE_SERVICE_[A-Z0-9_]+|BUSINESS_SERVICE_[A-Z0-9_]+|ASSESSMENT_[A-Z0-9_]+|GRADING_[A-Z0-9_]+|SANDBOX_[A-Z0-9_]+|MODEL_[A-Z0-9_]+)$'
    $count = 0; $lineNumber = 0
    foreach ($raw in Get-Content -LiteralPath $Path) {
        $lineNumber++; $line = $raw.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        if ($line.StartsWith('export ')) { $line = $line.Substring(7).TrimStart() }
        if ($line -notmatch '^(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?<value>.*)$') { throw "Invalid .env entry at line $lineNumber; use KEY=VALUE." }
        $name = $Matches.name; $value = $Matches.value.Trim()
        if ($name -notmatch $allowed) { continue }
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
        Set-Item -LiteralPath "Env:$name" -Value $value; $count++
    }
    return $count
}
