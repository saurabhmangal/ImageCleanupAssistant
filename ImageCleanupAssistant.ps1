param(
    [string]$InitialLibraryPath,
    [switch]$SelfTest
)

Add-Type -AssemblyName PresentationFramework
Add-Type -AssemblyName PresentationCore
Add-Type -AssemblyName WindowsBase
Add-Type -AssemblyName System.Xaml
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName Microsoft.VisualBasic

$script:State = @{
    LibraryRoot    = $null
    ImageItems     = @()
    ExactDuplicatePairs = @()
    DuplicatePairs = @()
    BlurryItems    = @()
    ForwardItems   = @()
    ScreenshotItems = @()
    TextHeavyItems  = @()
    CurrentQueueId = ""
    CurrentEntries = @()
}

$script:UI = @{}

function Show-Message {
    param(
        [string]$Message,
        [string]$Title = "Image Cleanup Assistant",
        [System.Windows.MessageBoxImage]$Icon = [System.Windows.MessageBoxImage]::Information
    )

    [System.Windows.MessageBox]::Show(
        $Message,
        $Title,
        [System.Windows.MessageBoxButton]::OK,
        $Icon
    ) | Out-Null
}

function Test-SupportedImage {
    param([string]$Path)

    $extensions = ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".tif", ".tiff"
    return $extensions -contains ([System.IO.Path]::GetExtension($Path).ToLowerInvariant())
}

function Convert-ToDisplaySize {
    param([double]$Bytes)

    if ($Bytes -ge 1GB) { return "$([Math]::Round($Bytes / 1GB, 2)) GB" }
    if ($Bytes -ge 1MB) { return "$([Math]::Round($Bytes / 1MB, 2)) MB" }
    if ($Bytes -ge 1KB) { return "$([Math]::Round($Bytes / 1KB, 1)) KB" }
    return "$([Math]::Round($Bytes, 0)) bytes"
}

function Get-FileContentHash {
    param([string]$Path)

    $stream = $null
    $algorithm = [System.Security.Cryptography.MD5]::Create()
    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $hashBytes = $algorithm.ComputeHash($stream)
        return -join ($hashBytes | ForEach-Object { $_.ToString("x2") })
    }
    finally {
        if ($stream) { $stream.Dispose() }
        if ($algorithm) { $algorithm.Dispose() }
    }
}

function Get-HammingDistance {
    param(
        [string]$First,
        [string]$Second
    )

    $distance = 0
    $length = [Math]::Min($First.Length, $Second.Length)
    for ($i = 0; $i -lt $length; $i++) {
        if ($First[$i] -ne $Second[$i]) {
            $distance++
        }
    }

    return $distance + [Math]::Abs($First.Length - $Second.Length)
}

function Get-MeanAbsoluteDifference {
    param(
        [int[]]$First,
        [int[]]$Second
    )

    if (-not $First -or -not $Second) {
        return [double]::PositiveInfinity
    }

    $length = [Math]::Min($First.Count, $Second.Count)
    if ($length -le 0) {
        return [double]::PositiveInfinity
    }

    $differenceTotal = 0.0
    for ($i = 0; $i -lt $length; $i++) {
        $differenceTotal += [Math]::Abs($First[$i] - $Second[$i])
    }

    $differenceTotal += ([Math]::Abs($First.Count - $Second.Count) * 16)
    return [Math]::Round(($differenceTotal / $length), 2)
}

function Get-NameCore {
    param([string]$Name)

    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($Name)
    $baseName = $baseName -replace '(?i)(copy|edited|duplicate)', ' '
    $baseName = $baseName -replace '[_\-\(\)\[\]\.]+', ' '
    $baseName = $baseName -replace '\s+', ' '
    return $baseName.Trim().ToLowerInvariant()
}

function Get-NameAffinityScore {
    param(
        [pscustomobject]$First,
        [pscustomobject]$Second
    )

    $firstCore = Get-NameCore -Name $First.Name
    $secondCore = Get-NameCore -Name $Second.Name

    if ([string]::IsNullOrWhiteSpace($firstCore) -or [string]::IsNullOrWhiteSpace($secondCore)) {
        return 0
    }

    if ($firstCore -eq $secondCore) {
        return 16
    }

    if ($firstCore.Contains($secondCore) -or $secondCore.Contains($firstCore)) {
        return 10
    }

    $firstWords = @($firstCore -split ' ' | Where-Object { $_.Length -ge 3 })
    $secondWords = @($secondCore -split ' ' | Where-Object { $_.Length -ge 3 })
    if ($firstWords.Count -eq 0 -or $secondWords.Count -eq 0) {
        return 0
    }

    $commonWords = @($firstWords | Where-Object { $secondWords -contains $_ } | Select-Object -Unique)
    if ($commonWords.Count -eq 0) {
        return 0
    }

    return [Math]::Min(12, ($commonWords.Count * 4))
}

function Get-WpfBitmap {
    param(
        [string]$Path,
        [int]$DecodePixelWidth = 0
    )

    $stream = $null
    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $bitmap = New-Object System.Windows.Media.Imaging.BitmapImage
        $bitmap.BeginInit()
        $bitmap.CacheOption = [System.Windows.Media.Imaging.BitmapCacheOption]::OnLoad
        if ($DecodePixelWidth -gt 0) {
            $bitmap.DecodePixelWidth = $DecodePixelWidth
        }
        $bitmap.StreamSource = $stream
        $bitmap.EndInit()
        $bitmap.Freeze()
        return $bitmap
    }
    finally {
        if ($stream) {
            $stream.Dispose()
        }
    }
}

function Get-ImageAnalysis {
    param([string]$Path)

    $stream = $null
    $image = $null
    $sample = $null
    $graphics = $null
    $differenceSample = $null
    $differenceGraphics = $null
    $descriptorSample = $null
    $descriptorGraphics = $null
    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $image = [System.Drawing.Image]::FromStream($stream, $false, $false)

        $sample = New-Object System.Drawing.Bitmap 8, 8
        $graphics = [System.Drawing.Graphics]::FromImage($sample)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.DrawImage($image, 0, 0, 8, 8)

        $values = New-Object System.Collections.Generic.List[double]
        $brightnessTotal = 0.0
        $contrastTotal = 0.0

        for ($y = 0; $y -lt 8; $y++) {
            for ($x = 0; $x -lt 8; $x++) {
                $pixel = $sample.GetPixel($x, $y)
                $gray = (0.299 * $pixel.R) + (0.587 * $pixel.G) + (0.114 * $pixel.B)
                $values.Add($gray)
                $brightnessTotal += $gray

                if ($x -gt 0) {
                    $left = $sample.GetPixel($x - 1, $y)
                    $leftGray = (0.299 * $left.R) + (0.587 * $left.G) + (0.114 * $left.B)
                    $contrastTotal += [Math]::Abs($gray - $leftGray)
                }

                if ($y -gt 0) {
                    $up = $sample.GetPixel($x, $y - 1)
                    $upGray = (0.299 * $up.R) + (0.587 * $up.G) + (0.114 * $up.B)
                    $contrastTotal += [Math]::Abs($gray - $upGray)
                }
            }
        }

        $average = ($values | Measure-Object -Average).Average
        $hashBits = foreach ($value in $values) {
            if ($value -ge $average) { "1" } else { "0" }
        }
        $hash = -join $hashBits

        $differenceSample = New-Object System.Drawing.Bitmap 9, 8
        $differenceGraphics = [System.Drawing.Graphics]::FromImage($differenceSample)
        $differenceGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $differenceGraphics.DrawImage($image, 0, 0, 9, 8)

        $gradientBits = New-Object System.Collections.Generic.List[string]
        for ($y = 0; $y -lt 8; $y++) {
            for ($x = 0; $x -lt 8; $x++) {
                $leftPixel = $differenceSample.GetPixel($x, $y)
                $rightPixel = $differenceSample.GetPixel($x + 1, $y)
                $leftGray = (0.299 * $leftPixel.R) + (0.587 * $leftPixel.G) + (0.114 * $leftPixel.B)
                $rightGray = (0.299 * $rightPixel.R) + (0.587 * $rightPixel.G) + (0.114 * $rightPixel.B)
                $bit = if ($leftGray -ge $rightGray) { "1" } else { "0" }
                $gradientBits.Add($bit) | Out-Null
            }
        }
        $gradientHash = -join $gradientBits

        $descriptorSample = New-Object System.Drawing.Bitmap 24, 24
        $descriptorGraphics = [System.Drawing.Graphics]::FromImage($descriptorSample)
        $descriptorGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $descriptorGraphics.DrawImage($image, 0, 0, 24, 24)

        $colorMap = @{}
        $whitePixels = 0
        $saturationTotal = 0.0
        $strongEdges = 0
        $toneSignature = New-Object System.Collections.Generic.List[int]

        for ($y = 0; $y -lt 24; $y++) {
            for ($x = 0; $x -lt 24; $x++) {
                $pixel = $descriptorSample.GetPixel($x, $y)
                $gray = (0.299 * $pixel.R) + (0.587 * $pixel.G) + (0.114 * $pixel.B)
                $maxChannel = [Math]::Max([Math]::Max($pixel.R, $pixel.G), $pixel.B)
                $minChannel = [Math]::Min([Math]::Min($pixel.R, $pixel.G), $pixel.B)
                $saturationTotal += if ($maxChannel -eq 0) { 0.0 } else { ($maxChannel - $minChannel) / [double]$maxChannel }
                $colorMap["$([int]($pixel.R / 32))-$([int]($pixel.G / 32))-$([int]($pixel.B / 32))"] = $true

                if ($gray -ge 235) { $whitePixels++ }

                if ($x -gt 0) {
                    $left = $descriptorSample.GetPixel($x - 1, $y)
                    $leftGray = (0.299 * $left.R) + (0.587 * $left.G) + (0.114 * $left.B)
                    if ([Math]::Abs($gray - $leftGray) -ge 55) { $strongEdges++ }
                }

                if ($y -gt 0) {
                    $up = $descriptorSample.GetPixel($x, $y - 1)
                    $upGray = (0.299 * $up.R) + (0.587 * $up.G) + (0.114 * $up.B)
                    if ([Math]::Abs($gray - $upGray) -ge 55) { $strongEdges++ }
                }
            }
        }

        for ($blockY = 0; $blockY -lt 6; $blockY++) {
            for ($blockX = 0; $blockX -lt 6; $blockX++) {
                $blockTotal = 0.0
                for ($innerY = 0; $innerY -lt 4; $innerY++) {
                    for ($innerX = 0; $innerX -lt 4; $innerX++) {
                        $pixel = $descriptorSample.GetPixel(($blockX * 4) + $innerX, ($blockY * 4) + $innerY)
                        $blockTotal += (0.299 * $pixel.R) + (0.587 * $pixel.G) + (0.114 * $pixel.B)
                    }
                }

                $toneSignature.Add([int][Math]::Round($blockTotal / 16.0)) | Out-Null
            }
        }

        return [pscustomobject]@{
            Width             = $image.Width
            Height            = $image.Height
            Hash              = $hash
            GradientHash      = $gradientHash
            ToneSignature     = $toneSignature.ToArray()
            SharpnessEstimate = [Math]::Round(($contrastTotal / 112.0), 2)
            Brightness        = [Math]::Round(($brightnessTotal / 64.0), 2)
            EdgeDensity       = [Math]::Round(($strongEdges / 1104.0), 3)
            AverageSaturation = [Math]::Round(($saturationTotal / 576.0), 3)
            WhitePixelRatio   = [Math]::Round(($whitePixels / 576.0), 3)
            UniqueColorCount  = $colorMap.Count
        }
    }
    finally {
        if ($descriptorGraphics) { $descriptorGraphics.Dispose() }
        if ($descriptorSample) { $descriptorSample.Dispose() }
        if ($differenceGraphics) { $differenceGraphics.Dispose() }
        if ($differenceSample) { $differenceSample.Dispose() }
        if ($graphics) { $graphics.Dispose() }
        if ($sample) { $sample.Dispose() }
        if ($image) { $image.Dispose() }
        if ($stream) { $stream.Dispose() }
    }
}

function Get-QualityScore {
    param([pscustomobject]$Item)

    return [Math]::Round(
        (($Item.Width * $Item.Height) / 1000000.0 * 3.8) +
        ($Item.SharpnessEstimate * 1.8) +
        (($Item.SizeBytes / 1000000.0) * 0.7),
        2
    )
}

function Get-ForwardProfile {
    param([pscustomobject]$Item)

    $nameBonus = if ($Item.Name -match '(?i)(diwali|holi|eid|christmas|xmas|new.?year|good.?morning|good.?night|quote|motivational|motivation|blessing|blessings|shayari|status|suvichar|thought|wish|greeting|festival|birthday|anniversary|invitation)') { 3 } else { 0 }
    $score = $nameBonus
    if ($Item.AverageSaturation -ge 0.24) { $score += 1 }
    if ($Item.UniqueColorCount -le 110) { $score += 1 }
    if ($Item.EdgeDensity -ge 0.12) { $score += 1 }
    if ($Item.WhitePixelRatio -le 0.45) { $score += 1 }
    if ($Item.Height -gt 0 -and ($Item.Width / [double]$Item.Height) -le 1.4) { $score += 1 }

    return [pscustomobject]@{
        IsForward = $score -ge 4
        Score     = $score
    }
}

function Get-ScreenshotProfile {
    param([pscustomobject]$Item)

    $score = 0
    $pathText = "$($Item.Name) $($Item.Folder)"
    if ($pathText -match '(?i)(screenshot|screen.?shot|screen_capture|capture|screenshots)') {
        $score += 3
    }

    $maxRatio = if ($Item.Height -eq 0 -or $Item.Width -eq 0) { 1.0 } else { [Math]::Max(($Item.Width / [double]$Item.Height), ($Item.Height / [double]$Item.Width)) }
    if ($maxRatio -ge 1.6 -and $maxRatio -le 2.4) { $score += 1 }
    if ($Item.EdgeDensity -ge 0.12) { $score += 1 }
    if ($Item.WhitePixelRatio -ge 0.18) { $score += 1 }
    if ($Item.AverageSaturation -le 0.38) { $score += 1 }

    return [pscustomobject]@{
        IsScreenshot = $score -ge 4
        Score        = $score
    }
}

function Get-TextHeavyProfile {
    param([pscustomobject]$Item)

    $score = 0
    if ($Item.Name -match '(?i)(quote|motivational|motivation|shayari|suvichar|thought|wish|greeting|invitation|poster|banner|flyer|notice|good.?morning|good.?night|festival|diwali|holi|new.?year)') {
        $score += 2
    }

    if ($Item.EdgeDensity -ge 0.16) { $score += 1 }
    if ($Item.WhitePixelRatio -ge 0.24) { $score += 1 }
    if ($Item.UniqueColorCount -le 95) { $score += 1 }
    if ($Item.AverageSaturation -le 0.32) { $score += 1 }
    if ($Item.Width -le 1800 -and $Item.Height -le 1800) { $score += 1 }

    return [pscustomobject]@{
        IsTextHeavy = $score -ge 4
        Score       = $score
    }
}

function Get-BlurProfile {
    param([pscustomobject]$Item)

    $score = 0
    if ($Item.SharpnessEstimate -le 8) { $score += 4 }
    elseif ($Item.SharpnessEstimate -le 12) { $score += 3 }
    elseif ($Item.SharpnessEstimate -le 16) { $score += 2 }
    elseif ($Item.SharpnessEstimate -le 20) { $score += 1 }

    if ($Item.EdgeDensity -le 0.05) { $score += 2 }
    elseif ($Item.EdgeDensity -le 0.08) { $score += 1 }

    return [pscustomobject]@{
        IsBlurry  = ($score -ge 4) -or ($Item.SharpnessEstimate -le 10 -and $Item.EdgeDensity -le 0.10)
        Score     = $score
        LabelText = "Sharpness $($Item.SharpnessEstimate) | edge detail $($Item.EdgeDensity)"
    }
}

function New-ImageItem {
    param([System.IO.FileInfo]$File)

    $analysis = Get-ImageAnalysis -Path $File.FullName
    $item = [pscustomobject]@{
        Path              = $File.FullName
        Name              = $File.Name
        Folder            = $File.DirectoryName
        SizeBytes         = [double]$File.Length
        SizeText          = Convert-ToDisplaySize -Bytes $File.Length
        Width             = $analysis.Width
        Height            = $analysis.Height
        DimensionsText    = "$($analysis.Width) x $($analysis.Height)"
        Hash              = $analysis.Hash
        GradientHash      = $analysis.GradientHash
        ToneSignature     = $analysis.ToneSignature
        AspectRatio       = if ($analysis.Height -eq 0) { 1.0 } else { [Math]::Round(($analysis.Width / [double]$analysis.Height), 4) }
        SharpnessEstimate = $analysis.SharpnessEstimate
        Brightness        = $analysis.Brightness
        EdgeDensity       = $analysis.EdgeDensity
        AverageSaturation = $analysis.AverageSaturation
        WhitePixelRatio   = $analysis.WhitePixelRatio
        UniqueColorCount  = $analysis.UniqueColorCount
        ModifiedAt        = $File.LastWriteTime
        ModifiedText      = $File.LastWriteTime.ToString("dd MMM yyyy HH:mm")
        ContentHash       = ""
        QualityScore      = 0.0
        LikelyBlurry      = $false
        BlurText          = ""
        LikelyForward     = $false
        LikelyScreenshot  = $false
        LikelyTextHeavy   = $false
    }

    $item.ContentHash = Get-FileContentHash -Path $File.FullName
    $item.QualityScore = Get-QualityScore -Item $item
    $forwardProfile = Get-ForwardProfile -Item $item
    $blurProfile = Get-BlurProfile -Item $item
    $screenshotProfile = Get-ScreenshotProfile -Item $item
    $textHeavyProfile = Get-TextHeavyProfile -Item $item
    $item.LikelyForward = $forwardProfile.IsForward
    $item.LikelyBlurry = $blurProfile.IsBlurry
    $item.LikelyScreenshot = $screenshotProfile.IsScreenshot
    $item.LikelyTextHeavy = $textHeavyProfile.IsTextHeavy
    $item.BlurText = $blurProfile.LabelText
    return $item
}

function Get-DeleteNamePenalty {
    param([pscustomobject]$Item)

    if ($Item.Name -match '(?i)(copy|edited|duplicate|_copy|-copy|\(\d+\))') {
        return 3
    }

    return 0
}

function Select-DeleteCandidate {
    param(
        [pscustomobject]$First,
        [pscustomobject]$Second
    )

    if ($First.QualityScore -lt $Second.QualityScore) { return $First }
    if ($Second.QualityScore -lt $First.QualityScore) { return $Second }

    $firstPenalty = Get-DeleteNamePenalty -Item $First
    $secondPenalty = Get-DeleteNamePenalty -Item $Second
    if ($firstPenalty -gt $secondPenalty) { return $First }
    if ($secondPenalty -gt $firstPenalty) { return $Second }
    if ($First.SizeBytes -lt $Second.SizeBytes) { return $First }
    if ($Second.SizeBytes -lt $First.SizeBytes) { return $Second }
    return $Second
}

function Get-SimilarPairCandidate {
    param(
        [pscustomobject]$First,
        [pscustomobject]$Second
    )

    if ($First.ContentHash -eq $Second.ContentHash) {
        return $null
    }

    $aspectDifference = [Math]::Abs($First.AspectRatio - $Second.AspectRatio)
    if ($aspectDifference -gt 0.03) {
        return $null
    }

    $firstLongSide = [Math]::Max($First.Width, $First.Height)
    $secondLongSide = [Math]::Max($Second.Width, $Second.Height)
    $dimensionRatio = [Math]::Max($firstLongSide, $secondLongSide) / [double][Math]::Max(1, [Math]::Min($firstLongSide, $secondLongSide))
    if ($dimensionRatio -gt 1.8) {
        return $null
    }

    $brightnessDifference = [Math]::Abs($First.Brightness - $Second.Brightness)
    if ($brightnessDifference -gt 28) {
        return $null
    }

    $saturationDifference = [Math]::Abs($First.AverageSaturation - $Second.AverageSaturation)
    if ($saturationDifference -gt 0.22) {
        return $null
    }

    $whiteDifference = [Math]::Abs($First.WhitePixelRatio - $Second.WhitePixelRatio)
    if ($whiteDifference -gt 0.20) {
        return $null
    }

    $edgeDifference = [Math]::Abs($First.EdgeDensity - $Second.EdgeDensity)
    if ($edgeDifference -gt 0.11) {
        return $null
    }

    $averageHashDistance = Get-HammingDistance -First $First.Hash -Second $Second.Hash
    if ($averageHashDistance -gt 5) {
        return $null
    }

    $gradientHashDistance = Get-HammingDistance -First $First.GradientHash -Second $Second.GradientHash
    if ($gradientHashDistance -gt 9) {
        return $null
    }

    $toneDifference = Get-MeanAbsoluteDifference -First $First.ToneSignature -Second $Second.ToneSignature
    if ($toneDifference -gt 16) {
        return $null
    }

    $nameAffinity = Get-NameAffinityScore -First $First -Second $Second
    $sameFolderBonus = if ($First.Folder -eq $Second.Folder) { 4 } else { 0 }

    $score =
        (120 - ($averageHashDistance * 11)) +
        (90 - ($gradientHashDistance * 6)) +
        [Math]::Max(0, (40 - ($toneDifference * 2.2))) +
        [Math]::Max(0, (24 - ($brightnessDifference * 0.8))) +
        [Math]::Max(0, (18 - ($saturationDifference * 80))) +
        [Math]::Max(0, (16 - ($edgeDifference * 120))) +
        [Math]::Max(0, (12 - ($aspectDifference * 400))) +
        $nameAffinity +
        $sameFolderBonus

    if ($score -lt 175) {
        return $null
    }

    return [pscustomobject]@{
        Score                 = [Math]::Round($score, 2)
        AverageHashDistance   = $averageHashDistance
        GradientHashDistance  = $gradientHashDistance
        ToneDifference        = $toneDifference
        BrightnessDifference  = [Math]::Round($brightnessDifference, 2)
        AspectDifference      = [Math]::Round($aspectDifference, 4)
        SameFolderBonus       = $sameFolderBonus
        NameAffinity          = $nameAffinity
    }
}

function Get-DuplicatePairs {
    param([object[]]$Items)

    $candidates = New-Object System.Collections.Generic.List[object]
    $ratioBuckets = $Items | Group-Object { [Math]::Round($_.AspectRatio, 1).ToString("0.0") }

    foreach ($bucket in $ratioBuckets) {
        $groupItems = @($bucket.Group | Sort-Object Brightness, Width, Name)
        for ($i = 0; $i -lt $groupItems.Count; $i++) {
            for ($j = $i + 1; $j -lt $groupItems.Count; $j++) {
                $first = $groupItems[$i]
                $second = $groupItems[$j]

                $candidateMatch = Get-SimilarPairCandidate -First $first -Second $second
                if (-not $candidateMatch) { continue }

                $candidate = Select-DeleteCandidate -First $first -Second $second
                $keeper = if ($candidate.Path -eq $first.Path) { $second } else { $first }
                $confidence = [Math]::Min(99, [Math]::Max(65, [int][Math]::Round($candidateMatch.Score / 3.15)))
                $candidates.Add([pscustomobject]@{
                    First              = $first
                    Second             = $second
                    Label              = "$($first.Name) vs $($second.Name)"
                    SuggestedDelete    = $candidate
                    Confidence         = $confidence
                    MatchScore         = $candidateMatch.Score
                    RecommendationText = "Strong visual match ($confidence%). Suggested delete: $($candidate.Name). Keep: $($keeper.Name)."
                }) | Out-Null
            }
        }
    }

    $usedPaths = @{}
    $pairs = New-Object System.Collections.Generic.List[object]
    foreach ($candidate in @($candidates | Sort-Object @{
            Expression = "MatchScore"
            Descending = $true
        }, @{
            Expression = { [Math]::Max($_.First.ModifiedAt.Ticks, $_.Second.ModifiedAt.Ticks) }
            Descending = $true
        }, @{
            Expression = "Label"
            Descending = $false
        })) {
        if ($usedPaths.ContainsKey($candidate.First.Path) -or $usedPaths.ContainsKey($candidate.Second.Path)) {
            continue
        }

        $usedPaths[$candidate.First.Path] = $true
        $usedPaths[$candidate.Second.Path] = $true
        $pairs.Add($candidate) | Out-Null
    }

    return $pairs.ToArray()
}

function Get-ExactDuplicatePairs {
    param([object[]]$Items)

    $pairs = New-Object System.Collections.Generic.List[object]
    $groups = $Items | Group-Object ContentHash | Where-Object { $_.Count -gt 1 -and -not [string]::IsNullOrWhiteSpace($_.Name) }

    foreach ($group in $groups) {
        $groupItems = @(
            $group.Group |
                Sort-Object @{
                    Expression = "QualityScore"
                    Descending = $true
                }, @{
                    Expression = "SizeBytes"
                    Descending = $true
                }, @{
                    Expression = "Name"
                    Ascending  = $true
                }
        )
        $keeper = $groupItems[0]

        foreach ($duplicate in @($groupItems | Select-Object -Skip 1)) {
            $pairs.Add([pscustomobject]@{
                First              = $keeper
                Second             = $duplicate
                Label              = "$($keeper.Name) vs $($duplicate.Name)"
                SuggestedDelete    = $duplicate
                Confidence         = 100
                MatchScore         = 1000
                RecommendationText = "Exact duplicate match. Suggested delete: $($duplicate.Name). Keep: $($keeper.Name)."
            }) | Out-Null
        }
    }

    return @($pairs | Sort-Object Label)
}

function Scan-Library {
    param(
        [string]$RootPath,
        [scriptblock]$ProgressCallback
    )

    $root = (Get-Item -LiteralPath $RootPath -ErrorAction Stop).FullName
    $files = @(
        Get-ChildItem -LiteralPath $root -Recurse -File |
            Where-Object { Test-SupportedImage -Path $_.FullName } |
            Sort-Object FullName
    )

    $items = New-Object System.Collections.Generic.List[object]
    $skipped = New-Object System.Collections.Generic.List[string]
    $index = 0

    foreach ($file in $files) {
        $index++
        if ($ProgressCallback) {
            & $ProgressCallback $index $files.Count "Scanning $index of $($files.Count): $($file.Name)"
        }

        try {
            $items.Add((New-ImageItem -File $file)) | Out-Null
        }
        catch {
            $skipped.Add($file.FullName) | Out-Null
        }
    }

    $imageItems = @($items.ToArray() | Sort-Object ModifiedAt -Descending)
    return [pscustomobject]@{
        RootPath       = $root
        ImageItems     = $imageItems
        ExactDuplicatePairs = @(Get-ExactDuplicatePairs -Items $imageItems)
        DuplicatePairs = @(Get-DuplicatePairs -Items $imageItems)
        BlurryItems    = @($imageItems | Where-Object { $_.LikelyBlurry } | Sort-Object BlurText, Name)
        ForwardItems   = @($imageItems | Where-Object { $_.LikelyForward } | Sort-Object ModifiedAt -Descending)
        ScreenshotItems = @($imageItems | Where-Object { $_.LikelyScreenshot } | Sort-Object ModifiedAt -Descending)
        TextHeavyItems  = @($imageItems | Where-Object { $_.LikelyTextHeavy } | Sort-Object ModifiedAt -Descending)
        SkippedFiles   = $skipped.ToArray()
    }
}

function Set-ProgressState {
    param(
        [bool]$Visible,
        [double]$Value = 0,
        [double]$Maximum = 1
    )

    $script:UI.ScanProgress.Visibility = if ($Visible) { "Visible" } else { "Collapsed" }
    $script:UI.ScanProgress.Maximum = [Math]::Max(1, $Maximum)
    $script:UI.ScanProgress.Value = [Math]::Min($Value, $script:UI.ScanProgress.Maximum)
}

function Set-Status {
    param([string]$Message)
    $script:UI.StatusText.Text = $Message
}

function Set-PreviewImage {
    param(
        $ImageControl,
        $Placeholder,
        [string]$Path,
        [int]$DecodePixelWidth
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        $ImageControl.Source = $null
        $Placeholder.Visibility = "Visible"
        return
    }

    try {
        $ImageControl.Source = Get-WpfBitmap -Path $Path -DecodePixelWidth $DecodePixelWidth
        $Placeholder.Visibility = "Collapsed"
    }
    catch {
        $ImageControl.Source = $null
        $Placeholder.Visibility = "Visible"
    }
}

function Update-Overview {
    $exactDuplicateCount = @($script:State.ExactDuplicatePairs).Count
    $duplicateCount = @($script:State.DuplicatePairs).Count
    $blurryCount = @($script:State.BlurryItems).Count
    $forwardCount = @($script:State.ForwardItems).Count
    $screenshotCount = @($script:State.ScreenshotItems).Count
    $textHeavyCount = @($script:State.TextHeavyItems).Count

    $script:UI.ExactDuplicateTab.Header = "Exact Duplicates ($exactDuplicateCount)"
    $script:UI.DuplicateTab.Header = "Duplicate Images ($duplicateCount)"
    $script:UI.BlurryTab.Header = "Blurry Photos ($blurryCount)"
    $script:UI.ForwardTab.Header = "Likely Forwards ($forwardCount)"
    $script:UI.ScreenshotTab.Header = "Screenshots ($screenshotCount)"
    $script:UI.TextHeavyTab.Header = "Text-Heavy Images ($textHeavyCount)"

    $script:UI.OverviewExactDuplicateCount.Text = "$exactDuplicateCount pair(s)"
    $script:UI.OverviewDuplicateCount.Text = "$duplicateCount pair(s)"
    $script:UI.OverviewBlurryCount.Text = "$blurryCount photo(s)"
    $script:UI.OverviewForwardCount.Text = "$forwardCount photo(s)"
    $script:UI.OverviewScreenshotCount.Text = "$screenshotCount photo(s)"
    $script:UI.OverviewTextHeavyCount.Text = "$textHeavyCount photo(s)"
    $script:UI.OpenExactDuplicatesButton.IsEnabled = $exactDuplicateCount -gt 0
    $script:UI.OpenDuplicatesButton.IsEnabled = $duplicateCount -gt 0
    $script:UI.OpenBlurryButton.IsEnabled = $blurryCount -gt 0
    $script:UI.OpenForwardButton.IsEnabled = $forwardCount -gt 0
    $script:UI.OpenScreenshotsButton.IsEnabled = $screenshotCount -gt 0
    $script:UI.OpenTextHeavyButton.IsEnabled = $textHeavyCount -gt 0

    if (-not $script:State.LibraryRoot) {
        $script:UI.SummaryText.Text = "Choose a folder to begin."
    }
    else {
        $script:UI.SummaryText.Text = "$($script:State.ImageItems.Count) image(s) scanned | $exactDuplicateCount exact duplicate pair(s) | $duplicateCount similar pair(s) | $blurryCount blurry photo(s) | $forwardCount likely forward(s) | $screenshotCount screenshot(s) | $textHeavyCount text-heavy image(s)"
    }
}

function Bind-ExactDuplicateList {
    $script:UI.ExactDuplicateListBox.ItemsSource = $null
    $script:UI.ExactDuplicateListBox.ItemsSource = $script:State.ExactDuplicatePairs
    if (-not $script:UI.ExactDuplicateListBox.SelectedItem -and @($script:State.ExactDuplicatePairs).Count -gt 0) {
        $script:UI.ExactDuplicateListBox.SelectedIndex = 0
    }
}

function Bind-DuplicateList {
    $script:UI.DuplicateListBox.ItemsSource = $null
    $script:UI.DuplicateListBox.ItemsSource = $script:State.DuplicatePairs
    if (-not $script:UI.DuplicateListBox.SelectedItem -and @($script:State.DuplicatePairs).Count -gt 0) {
        $script:UI.DuplicateListBox.SelectedIndex = 0
    }
}

function Bind-BlurryList {
    $script:UI.BlurryListBox.ItemsSource = $null
    $script:UI.BlurryListBox.ItemsSource = $script:State.BlurryItems
    if (-not $script:UI.BlurryListBox.SelectedItem -and @($script:State.BlurryItems).Count -gt 0) {
        $script:UI.BlurryListBox.SelectedIndex = 0
    }
}

function Bind-ForwardList {
    $script:UI.ForwardListBox.ItemsSource = $null
    $script:UI.ForwardListBox.ItemsSource = $script:State.ForwardItems
    if (-not $script:UI.ForwardListBox.SelectedItem -and @($script:State.ForwardItems).Count -gt 0) {
        $script:UI.ForwardListBox.SelectedIndex = 0
    }
}

function Bind-ScreenshotList {
    $script:UI.ScreenshotListBox.ItemsSource = $null
    $script:UI.ScreenshotListBox.ItemsSource = $script:State.ScreenshotItems
    if (-not $script:UI.ScreenshotListBox.SelectedItem -and @($script:State.ScreenshotItems).Count -gt 0) {
        $script:UI.ScreenshotListBox.SelectedIndex = 0
    }
}

function Bind-TextHeavyList {
    $script:UI.TextHeavyListBox.ItemsSource = $null
    $script:UI.TextHeavyListBox.ItemsSource = $script:State.TextHeavyItems
    if (-not $script:UI.TextHeavyListBox.SelectedItem -and @($script:State.TextHeavyItems).Count -gt 0) {
        $script:UI.TextHeavyListBox.SelectedIndex = 0
    }
}

function Update-ExactDuplicatePreview {
    $pair = $script:UI.ExactDuplicateListBox.SelectedItem
    if (-not $pair) {
        $script:UI.ExactDuplicateInfo.Text = "No exact duplicate pair selected."
        Set-PreviewImage -ImageControl $script:UI.ExactDuplicateLeftImage -Placeholder $script:UI.ExactDuplicateLeftPlaceholder -Path $null -DecodePixelWidth 640
        Set-PreviewImage -ImageControl $script:UI.ExactDuplicateRightImage -Placeholder $script:UI.ExactDuplicateRightPlaceholder -Path $null -DecodePixelWidth 640
        $script:UI.DeleteExactLeftButton.IsEnabled = $false
        $script:UI.DeleteExactRightButton.IsEnabled = $false
        $script:UI.DeleteExactBothButton.IsEnabled = $false
        $script:UI.KeepExactDuplicateButton.IsEnabled = $false
        $script:UI.NextExactDuplicateButton.IsEnabled = $false
        return
    }

    $script:UI.ExactDuplicateInfo.Text = $pair.RecommendationText
    Set-PreviewImage -ImageControl $script:UI.ExactDuplicateLeftImage -Placeholder $script:UI.ExactDuplicateLeftPlaceholder -Path $pair.First.Path -DecodePixelWidth 640
    Set-PreviewImage -ImageControl $script:UI.ExactDuplicateRightImage -Placeholder $script:UI.ExactDuplicateRightPlaceholder -Path $pair.Second.Path -DecodePixelWidth 640
    $script:UI.DeleteExactLeftButton.IsEnabled = $true
    $script:UI.DeleteExactRightButton.IsEnabled = $true
    $script:UI.DeleteExactBothButton.IsEnabled = $true
    $script:UI.KeepExactDuplicateButton.IsEnabled = $true
    $script:UI.NextExactDuplicateButton.IsEnabled = ($script:UI.ExactDuplicateListBox.SelectedIndex + 1) -lt @($script:State.ExactDuplicatePairs).Count
}

function Update-DuplicatePreview {
    $pair = $script:UI.DuplicateListBox.SelectedItem
    if (-not $pair) {
        $script:UI.DuplicateInfo.Text = "No duplicate pair selected."
        Set-PreviewImage -ImageControl $script:UI.DuplicateLeftImage -Placeholder $script:UI.DuplicateLeftPlaceholder -Path $null -DecodePixelWidth 640
        Set-PreviewImage -ImageControl $script:UI.DuplicateRightImage -Placeholder $script:UI.DuplicateRightPlaceholder -Path $null -DecodePixelWidth 640
        $script:UI.DeleteLeftButton.IsEnabled = $false
        $script:UI.DeleteRightButton.IsEnabled = $false
        $script:UI.DeleteBothButton.IsEnabled = $false
        $script:UI.KeepDuplicateButton.IsEnabled = $false
        $script:UI.NextDuplicateButton.IsEnabled = $false
        return
    }

    $script:UI.DuplicateInfo.Text = $pair.RecommendationText
    Set-PreviewImage -ImageControl $script:UI.DuplicateLeftImage -Placeholder $script:UI.DuplicateLeftPlaceholder -Path $pair.First.Path -DecodePixelWidth 640
    Set-PreviewImage -ImageControl $script:UI.DuplicateRightImage -Placeholder $script:UI.DuplicateRightPlaceholder -Path $pair.Second.Path -DecodePixelWidth 640
    $script:UI.DeleteLeftButton.IsEnabled = $true
    $script:UI.DeleteRightButton.IsEnabled = $true
    $script:UI.DeleteBothButton.IsEnabled = $true
    $script:UI.KeepDuplicateButton.IsEnabled = $true
    $script:UI.NextDuplicateButton.IsEnabled = ($script:UI.DuplicateListBox.SelectedIndex + 1) -lt @($script:State.DuplicatePairs).Count
}

function Update-BlurryPreview {
    $item = $script:UI.BlurryListBox.SelectedItem
    if (-not $item) {
        $script:UI.BlurryInfo.Text = "No blurry photo selected."
        Set-PreviewImage -ImageControl $script:UI.BlurryPreviewImage -Placeholder $script:UI.BlurryPreviewPlaceholder -Path $null -DecodePixelWidth 1200
        $script:UI.DeleteBlurryButton.IsEnabled = $false
        $script:UI.KeepBlurryButton.IsEnabled = $false
        $script:UI.NextBlurryButton.IsEnabled = $false
        $script:UI.OpenBlurryPreviewButton.IsEnabled = $false
        return
    }

    $script:UI.BlurryInfo.Text = "$($item.Name)`n$($item.DimensionsText) | $($item.SizeText)`n$($item.BlurText)"
    Set-PreviewImage -ImageControl $script:UI.BlurryPreviewImage -Placeholder $script:UI.BlurryPreviewPlaceholder -Path $item.Path -DecodePixelWidth 1200
    $script:UI.DeleteBlurryButton.IsEnabled = $true
    $script:UI.KeepBlurryButton.IsEnabled = $true
    $script:UI.NextBlurryButton.IsEnabled = ($script:UI.BlurryListBox.SelectedIndex + 1) -lt @($script:State.BlurryItems).Count
    $script:UI.OpenBlurryPreviewButton.IsEnabled = $true
}

function Update-ForwardPreview {
    $item = $script:UI.ForwardListBox.SelectedItem
    if (-not $item) {
        $script:UI.ForwardInfo.Text = "No likely forward selected."
        Set-PreviewImage -ImageControl $script:UI.ForwardPreviewImage -Placeholder $script:UI.ForwardPreviewPlaceholder -Path $null -DecodePixelWidth 1200
        $script:UI.DeleteForwardButton.IsEnabled = $false
        $script:UI.KeepForwardButton.IsEnabled = $false
        $script:UI.NextForwardButton.IsEnabled = $false
        $script:UI.OpenForwardPreviewButton.IsEnabled = $false
        return
    }

    $script:UI.ForwardInfo.Text = "$($item.Name)`n$($item.DimensionsText) | $($item.SizeText)`nLikely greeting, quote, or shareable forward image."
    Set-PreviewImage -ImageControl $script:UI.ForwardPreviewImage -Placeholder $script:UI.ForwardPreviewPlaceholder -Path $item.Path -DecodePixelWidth 1200
    $script:UI.DeleteForwardButton.IsEnabled = $true
    $script:UI.KeepForwardButton.IsEnabled = $true
    $script:UI.NextForwardButton.IsEnabled = ($script:UI.ForwardListBox.SelectedIndex + 1) -lt @($script:State.ForwardItems).Count
    $script:UI.OpenForwardPreviewButton.IsEnabled = $true
}

function Update-ScreenshotPreview {
    $item = $script:UI.ScreenshotListBox.SelectedItem
    if (-not $item) {
        $script:UI.ScreenshotInfo.Text = "No screenshot selected."
        Set-PreviewImage -ImageControl $script:UI.ScreenshotPreviewImage -Placeholder $script:UI.ScreenshotPreviewPlaceholder -Path $null -DecodePixelWidth 1200
        $script:UI.DeleteScreenshotButton.IsEnabled = $false
        $script:UI.KeepScreenshotButton.IsEnabled = $false
        $script:UI.NextScreenshotButton.IsEnabled = $false
        $script:UI.OpenScreenshotPreviewButton.IsEnabled = $false
        return
    }

    $script:UI.ScreenshotInfo.Text = "$($item.Name)`n$($item.DimensionsText) | $($item.SizeText)`nLikely screenshot or captured screen image."
    Set-PreviewImage -ImageControl $script:UI.ScreenshotPreviewImage -Placeholder $script:UI.ScreenshotPreviewPlaceholder -Path $item.Path -DecodePixelWidth 1200
    $script:UI.DeleteScreenshotButton.IsEnabled = $true
    $script:UI.KeepScreenshotButton.IsEnabled = $true
    $script:UI.NextScreenshotButton.IsEnabled = ($script:UI.ScreenshotListBox.SelectedIndex + 1) -lt @($script:State.ScreenshotItems).Count
    $script:UI.OpenScreenshotPreviewButton.IsEnabled = $true
}

function Update-TextHeavyPreview {
    $item = $script:UI.TextHeavyListBox.SelectedItem
    if (-not $item) {
        $script:UI.TextHeavyInfo.Text = "No text-heavy image selected."
        Set-PreviewImage -ImageControl $script:UI.TextHeavyPreviewImage -Placeholder $script:UI.TextHeavyPreviewPlaceholder -Path $null -DecodePixelWidth 1200
        $script:UI.DeleteTextHeavyButton.IsEnabled = $false
        $script:UI.KeepTextHeavyButton.IsEnabled = $false
        $script:UI.NextTextHeavyButton.IsEnabled = $false
        $script:UI.OpenTextHeavyPreviewButton.IsEnabled = $false
        return
    }

    $script:UI.TextHeavyInfo.Text = "$($item.Name)`n$($item.DimensionsText) | $($item.SizeText)`nLikely poster, quote card, greeting card, or other text-heavy image."
    Set-PreviewImage -ImageControl $script:UI.TextHeavyPreviewImage -Placeholder $script:UI.TextHeavyPreviewPlaceholder -Path $item.Path -DecodePixelWidth 1200
    $script:UI.DeleteTextHeavyButton.IsEnabled = $true
    $script:UI.KeepTextHeavyButton.IsEnabled = $true
    $script:UI.NextTextHeavyButton.IsEnabled = ($script:UI.TextHeavyListBox.SelectedIndex + 1) -lt @($script:State.TextHeavyItems).Count
    $script:UI.OpenTextHeavyPreviewButton.IsEnabled = $true
}

function Refresh-Ui {
    Update-Overview
    Bind-ExactDuplicateList
    Bind-DuplicateList
    Bind-BlurryList
    Bind-ForwardList
    Bind-ScreenshotList
    Bind-TextHeavyList
    Update-ExactDuplicatePreview
    Update-DuplicatePreview
    Update-BlurryPreview
    Update-ForwardPreview
    Update-ScreenshotPreview
    Update-TextHeavyPreview
}

function Remove-PathsFromState {
    param([string[]]$Paths)

    $lookup = @{}
    foreach ($path in $Paths) { if ($path) { $lookup[$path] = $true } }
    if ($lookup.Count -eq 0) { return }

    $script:State.ImageItems = @($script:State.ImageItems | Where-Object { -not $lookup.ContainsKey($_.Path) })
    $script:State.ExactDuplicatePairs = @($script:State.ExactDuplicatePairs | Where-Object { -not $lookup.ContainsKey($_.First.Path) -and -not $lookup.ContainsKey($_.Second.Path) })
    $script:State.DuplicatePairs = @($script:State.DuplicatePairs | Where-Object { -not $lookup.ContainsKey($_.First.Path) -and -not $lookup.ContainsKey($_.Second.Path) })
    $script:State.BlurryItems = @($script:State.BlurryItems | Where-Object { -not $lookup.ContainsKey($_.Path) })
    $script:State.ForwardItems = @($script:State.ForwardItems | Where-Object { -not $lookup.ContainsKey($_.Path) })
    $script:State.ScreenshotItems = @($script:State.ScreenshotItems | Where-Object { -not $lookup.ContainsKey($_.Path) })
    $script:State.TextHeavyItems = @($script:State.TextHeavyItems | Where-Object { -not $lookup.ContainsKey($_.Path) })
}

function Delete-SelectedPaths {
    param(
        [string[]]$Paths,
        [string]$Title,
        [string]$Message
    )

    $confirm = [System.Windows.MessageBox]::Show(
        $Message,
        $Title,
        [System.Windows.MessageBoxButton]::YesNo,
        [System.Windows.MessageBoxImage]::Warning
    )

    if ($confirm -ne [System.Windows.MessageBoxResult]::Yes) {
        return
    }

    foreach ($path in $Paths) {
        if (Test-Path -LiteralPath $path) {
            [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile(
                $path,
                [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
                [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin
            )
        }
    }

    Remove-PathsFromState -Paths $Paths
    Refresh-Ui
    Set-Status "Moved $(@($Paths).Count) image(s) to the Recycle Bin."
}

function Select-SafeListIndex {
    param(
        [System.Windows.Controls.ListBox]$ListBox,
        [int]$Index
    )

    if (-not $ListBox -or $ListBox.Items.Count -eq 0) {
        return
    }

    $safeIndex = [Math]::Min([Math]::Max($Index, 0), $ListBox.Items.Count - 1)
    $ListBox.SelectedIndex = $safeIndex
}

function Invoke-DirectSinglePhotoDeletion {
    param(
        [pscustomobject]$Item,
        [System.Windows.Controls.ListBox]$ListBox,
        [string]$StatusMessage
    )

    if (-not $Item -or [string]::IsNullOrWhiteSpace($Item.Path)) {
        return $false
    }

    $currentIndex = if ($ListBox) { $ListBox.SelectedIndex } else { 0 }

    try {
        if (Test-Path -LiteralPath $Item.Path) {
            [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile(
                $Item.Path,
                [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
                [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin
            )
        }
    }
    catch {
        Show-Message -Message $_.Exception.Message -Title "Delete Failed" -Icon Error
        return $false
    }

    Remove-PathsFromState -Paths @($Item.Path)
    Refresh-Ui
    Select-SafeListIndex -ListBox $ListBox -Index $currentIndex
    Set-Status $StatusMessage
    return $true
}

function Invoke-ActiveSinglePhotoDelete {
    if ($script:UI.MainTabs.SelectedItem -eq $script:UI.BlurryTab) {
        return Invoke-DirectSinglePhotoDeletion -Item $script:UI.BlurryListBox.SelectedItem -ListBox $script:UI.BlurryListBox -StatusMessage "Moved one blurry photo to the Recycle Bin."
    }

    if ($script:UI.MainTabs.SelectedItem -eq $script:UI.ForwardTab) {
        return Invoke-DirectSinglePhotoDeletion -Item $script:UI.ForwardListBox.SelectedItem -ListBox $script:UI.ForwardListBox -StatusMessage "Moved one likely forward image to the Recycle Bin."
    }

    if ($script:UI.MainTabs.SelectedItem -eq $script:UI.ScreenshotTab) {
        return Invoke-DirectSinglePhotoDeletion -Item $script:UI.ScreenshotListBox.SelectedItem -ListBox $script:UI.ScreenshotListBox -StatusMessage "Moved one screenshot to the Recycle Bin."
    }

    if ($script:UI.MainTabs.SelectedItem -eq $script:UI.TextHeavyTab) {
        return Invoke-DirectSinglePhotoDeletion -Item $script:UI.TextHeavyListBox.SelectedItem -ListBox $script:UI.TextHeavyListBox -StatusMessage "Moved one text-heavy image to the Recycle Bin."
    }

    return $false
}

function Show-PreviewWindow {
    param([pscustomobject]$Item)

    if (-not $Item) {
        return
    }

    $previewXaml = @'
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="Image Preview"
        Width="1100"
        Height="820"
        MinWidth="900"
        MinHeight="700"
        Background="#1E1D1B"
        WindowStartupLocation="CenterOwner">
    <Grid Margin="18">
        <Grid.RowDefinitions>
            <RowDefinition Height="*"/>
            <RowDefinition Height="Auto"/>
        </Grid.RowDefinitions>
        <Border Grid.Row="0" CornerRadius="18" Background="#151515" Padding="12">
            <Grid>
                <Image x:Name="PreviewImage" Stretch="Uniform"/>
                <TextBlock x:Name="PreviewPlaceholder"
                           Text="Preview unavailable"
                           Foreground="#A7A7A7"
                           FontSize="20"
                           HorizontalAlignment="Center"
                           VerticalAlignment="Center"/>
            </Grid>
        </Border>
        <Border Grid.Row="1" Margin="0,16,0,0" Background="#26231F" CornerRadius="16" Padding="18">
            <StackPanel>
                <TextBlock x:Name="NameText" FontSize="22" FontWeight="SemiBold" Foreground="White"/>
                <TextBlock x:Name="InfoText" Margin="0,6,0,0" FontSize="14" Foreground="#E9E0D2"/>
                <TextBlock x:Name="PathText" Margin="0,6,0,0" FontSize="13" TextWrapping="Wrap" Foreground="#CDBEA8"/>
            </StackPanel>
        </Border>
    </Grid>
</Window>
'@

    [xml]$previewXml = $previewXaml
    $reader = New-Object System.Xml.XmlNodeReader $previewXml
    $window = [System.Windows.Markup.XamlReader]::Load($reader)
    $previewImage = $window.FindName("PreviewImage")
    $previewPlaceholder = $window.FindName("PreviewPlaceholder")
    $nameText = $window.FindName("NameText")
    $infoText = $window.FindName("InfoText")
    $pathText = $window.FindName("PathText")

    $nameText.Text = $Item.Name
    $infoText.Text = "$($Item.DimensionsText) | $($Item.SizeText) | $($Item.ModifiedText)"
    $pathText.Text = $Item.Path

    Set-PreviewImage -ImageControl $previewImage -Placeholder $previewPlaceholder -Path $Item.Path -DecodePixelWidth 1600
    [void]$window.ShowDialog()
}

function Load-LibraryIntoUi {
    param([string]$RootPath)

    try {
        Set-ProgressState -Visible $true -Value 0 -Maximum 1
        Set-Status "Preparing scan..."

        $snapshot = Scan-Library -RootPath $RootPath -ProgressCallback {
            param($Current, $Total, $Message)
            Set-ProgressState -Visible $true -Value $Current -Maximum $Total
            Set-Status $Message
            [System.Windows.Forms.Application]::DoEvents()
        }

        $script:State.LibraryRoot = $snapshot.RootPath
        $script:State.ImageItems = @($snapshot.ImageItems)
        $script:State.ExactDuplicatePairs = @($snapshot.ExactDuplicatePairs)
        $script:State.DuplicatePairs = @($snapshot.DuplicatePairs)
        $script:State.BlurryItems = @($snapshot.BlurryItems)
        $script:State.ForwardItems = @($snapshot.ForwardItems)
        $script:State.ScreenshotItems = @($snapshot.ScreenshotItems)
        $script:State.TextHeavyItems = @($snapshot.TextHeavyItems)
        $script:UI.LibraryPathText.Text = $snapshot.RootPath
        Refresh-Ui
        Set-Status "Scan complete. Review the cleanup queues tab by tab."
    }
    catch {
        Set-Status "Scan failed."
        Show-Message -Message $_.Exception.Message -Title "Scan Failed" -Icon Error
    }
    finally {
        Set-ProgressState -Visible $false
    }
}

function Move-ToNextEntry {
    if (-not $script:UI.ReviewListBox) {
        return $false
    }

    $nextIndex = $script:UI.ReviewListBox.SelectedIndex + 1
    if ($nextIndex -lt $script:UI.ReviewListBox.Items.Count) {
        $script:UI.ReviewListBox.SelectedIndex = $nextIndex
        return $true
    }

    return $false
}

function Run-SelfTest {
    if (-not $InitialLibraryPath) {
        throw "Use -InitialLibraryPath with -SelfTest."
    }

    $snapshot = Scan-Library -RootPath $InitialLibraryPath
    [pscustomobject]@{
        RootPath          = $snapshot.RootPath
        ImageCount        = @($snapshot.ImageItems).Count
        ExactDuplicatePairs = @($snapshot.ExactDuplicatePairs).Count
        SimilarPairs      = @($snapshot.DuplicatePairs).Count
        BlurryCount       = @($snapshot.BlurryItems).Count
        ForwardImageCount = @($snapshot.ForwardItems).Count
        ScreenshotCount   = @($snapshot.ScreenshotItems).Count
        TextHeavyCount    = @($snapshot.TextHeavyItems).Count
        SkippedFiles      = @($snapshot.SkippedFiles).Count
    } | ConvertTo-Json -Depth 3
}

if ($SelfTest) {
    Run-SelfTest
    return
}

function Move-PathsToRecycleBin {
    param(
        [string[]]$Paths,
        [switch]$Confirm,
        [string]$Title = "Delete Photos",
        [string]$Message = ""
    )

    $validPaths = @($Paths | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($validPaths.Count -eq 0) {
        return $false
    }

    if ($Confirm) {
        $result = [System.Windows.MessageBox]::Show(
            $Message,
            $Title,
            [System.Windows.MessageBoxButton]::YesNo,
            [System.Windows.MessageBoxImage]::Warning
        )

        if ($result -ne [System.Windows.MessageBoxResult]::Yes) {
            return $false
        }
    }

    try {
        foreach ($path in $validPaths) {
            if (Test-Path -LiteralPath $path) {
                [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile(
                    $path,
                    [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
                    [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin
                )
            }
        }
    }
    catch {
        Show-Message -Message $_.Exception.Message -Title "Delete Failed" -Icon Error
        return $false
    }

    return $true
}

function Get-QueueDefinitions {
    return @(
        [pscustomobject]@{
            Id          = "exact"
            Kind        = "pair"
            Title       = "Exact Duplicates"
            Count       = @($script:State.ExactDuplicatePairs).Count
            Description = "Byte-identical files. Safest queue to clear first."
            EmptyText   = "No exact duplicates are waiting right now."
        },
        [pscustomobject]@{
            Id          = "similar"
            Kind        = "pair"
            Title       = "Similar Photos"
            Count       = @($script:State.DuplicatePairs).Count
            Description = "Stricter side-by-side matches for resized or lightly edited copies."
            EmptyText   = "No strong similar-photo pairs were found."
        },
        [pscustomobject]@{
            Id          = "blurry"
            Kind        = "single"
            Title       = "Blurry Photos"
            Count       = @($script:State.BlurryItems).Count
            Description = "Photos with weak sharpness or poor edge detail."
            EmptyText   = "No blurry photos are waiting in this queue."
        },
        [pscustomobject]@{
            Id          = "forward"
            Kind        = "single"
            Title       = "Likely Forwards"
            Count       = @($script:State.ForwardItems).Count
            Description = "WhatsApp forwards, greetings, festival wishes, quotes, and good-morning cards."
            EmptyText   = "No likely forwards are waiting in this queue."
        },
        [pscustomobject]@{
            Id          = "screenshot"
            Kind        = "single"
            Title       = "Screenshots"
            Count       = @($script:State.ScreenshotItems).Count
            Description = "Captured screens and UI images."
            EmptyText   = "No screenshots are waiting in this queue."
        },
        [pscustomobject]@{
            Id          = "textheavy"
            Kind        = "single"
            Title       = "Text-Heavy Images"
            Count       = @($script:State.TextHeavyItems).Count
            Description = "Quote cards, flyers, posters, and other graphics with a lot of text."
            EmptyText   = "No text-heavy images are waiting in this queue."
        }
    )
}

function Get-SingleQueueReasonText {
    param(
        [string]$QueueId,
        [pscustomobject]$Item
    )

    switch ($QueueId) {
        "blurry" { return $Item.BlurText }
        "forward" { return "Likely greeting, quote, motivational, festival, or WhatsApp-style forward." }
        "screenshot" { return "Likely screenshot or captured screen image." }
        "textheavy" { return "Likely poster, greeting card, quote image, or other text-heavy graphic." }
        default { return "" }
    }
}

function Get-ReviewEntries {
    param([string]$QueueId)

    switch ($QueueId) {
        "exact" {
            return @(
                $script:State.ExactDuplicatePairs | ForEach-Object {
                    [pscustomobject]@{
                        EntryId    = "exact|$($_.First.Path)|$($_.Second.Path)"
                        QueueId    = "exact"
                        Kind       = "pair"
                        Pair       = $_
                        Item       = $null
                        Title      = $_.Label
                        Subtitle   = $_.RecommendationText
                        MetaText   = "Exact file match | $($_.First.DimensionsText)"
                        SearchText = "$($_.First.Name) $($_.Second.Name) $($_.First.Folder) $($_.Second.Folder)"
                    }
                }
            )
        }
        "similar" {
            return @(
                $script:State.DuplicatePairs | ForEach-Object {
                    [pscustomobject]@{
                        EntryId    = "similar|$($_.First.Path)|$($_.Second.Path)"
                        QueueId    = "similar"
                        Kind       = "pair"
                        Pair       = $_
                        Item       = $null
                        Title      = $_.Label
                        Subtitle   = $_.RecommendationText
                        MetaText   = "$($_.Confidence)% match | $($_.First.DimensionsText) and $($_.Second.DimensionsText)"
                        SearchText = "$($_.First.Name) $($_.Second.Name) $($_.First.Folder) $($_.Second.Folder)"
                    }
                }
            )
        }
        "blurry" {
            return @(
                $script:State.BlurryItems | ForEach-Object {
                    [pscustomobject]@{
                        EntryId    = "blurry|$($_.Path)"
                        QueueId    = "blurry"
                        Kind       = "single"
                        Pair       = $null
                        Item       = $_
                        Title      = $_.Name
                        Subtitle   = Get-SingleQueueReasonText -QueueId "blurry" -Item $_
                        MetaText   = "$($_.DimensionsText) | $($_.SizeText) | $($_.ModifiedText)"
                        SearchText = "$($_.Name) $($_.Folder) $($_.BlurText)"
                    }
                }
            )
        }
        "forward" {
            return @(
                $script:State.ForwardItems | ForEach-Object {
                    [pscustomobject]@{
                        EntryId    = "forward|$($_.Path)"
                        QueueId    = "forward"
                        Kind       = "single"
                        Pair       = $null
                        Item       = $_
                        Title      = $_.Name
                        Subtitle   = Get-SingleQueueReasonText -QueueId "forward" -Item $_
                        MetaText   = "$($_.DimensionsText) | $($_.SizeText) | $($_.ModifiedText)"
                        SearchText = "$($_.Name) $($_.Folder)"
                    }
                }
            )
        }
        "screenshot" {
            return @(
                $script:State.ScreenshotItems | ForEach-Object {
                    [pscustomobject]@{
                        EntryId    = "screenshot|$($_.Path)"
                        QueueId    = "screenshot"
                        Kind       = "single"
                        Pair       = $null
                        Item       = $_
                        Title      = $_.Name
                        Subtitle   = Get-SingleQueueReasonText -QueueId "screenshot" -Item $_
                        MetaText   = "$($_.DimensionsText) | $($_.SizeText) | $($_.ModifiedText)"
                        SearchText = "$($_.Name) $($_.Folder)"
                    }
                }
            )
        }
        "textheavy" {
            return @(
                $script:State.TextHeavyItems | ForEach-Object {
                    [pscustomobject]@{
                        EntryId    = "textheavy|$($_.Path)"
                        QueueId    = "textheavy"
                        Kind       = "single"
                        Pair       = $null
                        Item       = $_
                        Title      = $_.Name
                        Subtitle   = Get-SingleQueueReasonText -QueueId "textheavy" -Item $_
                        MetaText   = "$($_.DimensionsText) | $($_.SizeText) | $($_.ModifiedText)"
                        SearchText = "$($_.Name) $($_.Folder)"
                    }
                }
            )
        }
        default {
            return @()
        }
    }
}

function Get-EntryKey {
    param([pscustomobject]$Entry)

    if (-not $Entry) {
        return ""
    }

    if ($Entry.Kind -eq "pair" -and $Entry.Pair) {
        return "$($Entry.Pair.First.Path)|$($Entry.Pair.Second.Path)"
    }

    if ($Entry.Item) {
        return $Entry.Item.Path
    }

    return $Entry.EntryId
}

function Select-QueueById {
    param([string]$QueueId)

    if (-not $script:UI.QueueListBox) {
        return
    }

    foreach ($item in $script:UI.QueueListBox.Items) {
        if ($item.Id -eq $QueueId) {
            $script:UI.QueueListBox.SelectedItem = $item
            return
        }
    }

    if ($script:UI.QueueListBox.Items.Count -gt 0) {
        $script:UI.QueueListBox.SelectedIndex = 0
    }
}

function Update-SummaryBanner {
    if (-not $script:UI.SummaryText) {
        return
    }

    if (-not $script:State.LibraryRoot) {
        $script:UI.LibraryPathText.Text = "No folder selected yet."
        $script:UI.SummaryText.Text = "Choose a folder to build cleanup queues."
        $script:UI.SummaryHintText.Text = "This app groups exact duplicates, stronger similar-photo matches, blurry shots, forwards, screenshots, and text-heavy images into one simpler review flow."
        return
    }

    $exactCount = @($script:State.ExactDuplicatePairs).Count
    $similarCount = @($script:State.DuplicatePairs).Count
    $blurryCount = @($script:State.BlurryItems).Count
    $forwardCount = @($script:State.ForwardItems).Count
    $screenshotCount = @($script:State.ScreenshotItems).Count
    $textHeavyCount = @($script:State.TextHeavyItems).Count

    $script:UI.LibraryPathText.Text = $script:State.LibraryRoot
    $script:UI.SummaryText.Text = "$($script:State.ImageItems.Count) image(s) scanned | $exactCount exact duplicate pair(s) | $similarCount similar pair(s) | $blurryCount blurry photo(s) | $forwardCount likely forward(s) | $screenshotCount screenshot(s) | $textHeavyCount text-heavy image(s)"
    $script:UI.SummaryHintText.Text = "Use the queue list on the left, filter the current queue in the middle, and review one item at a time on the right."
}

function Refresh-QueueList {
    $definitions = @(Get-QueueDefinitions)
    $preferredQueueId = if ([string]::IsNullOrWhiteSpace($script:State.CurrentQueueId)) {
        @($definitions | Where-Object { $_.Count -gt 0 } | Select-Object -First 1).Id
    }
    else {
        $script:State.CurrentQueueId
    }

    if ([string]::IsNullOrWhiteSpace($preferredQueueId)) {
        $preferredQueueId = "exact"
    }

    if (-not (@($definitions | Where-Object { $_.Id -eq $preferredQueueId }).Count -gt 0)) {
        $preferredQueueId = "exact"
    }

    $script:UI.QueueListBox.ItemsSource = $null
    $script:UI.QueueListBox.ItemsSource = $definitions
    $script:State.CurrentQueueId = $preferredQueueId
    Select-QueueById -QueueId $preferredQueueId
}

function Refresh-ReviewList {
    $queueDefinition = @((Get-QueueDefinitions | Where-Object { $_.Id -eq $script:State.CurrentQueueId }) | Select-Object -First 1)
    if (-not $queueDefinition) {
        $queueDefinition = @((Get-QueueDefinitions | Select-Object -First 1))
        if ($queueDefinition) {
            $script:State.CurrentQueueId = $queueDefinition[0].Id
        }
    }

    $previousEntryKey = Get-EntryKey -Entry $script:UI.ReviewListBox.SelectedItem
    $entries = @(Get-ReviewEntries -QueueId $script:State.CurrentQueueId)

    $filterText = ""
    if ($script:UI.ReviewSearchTextBox) {
        $filterText = $script:UI.ReviewSearchTextBox.Text.Trim()
    }

    if (-not [string]::IsNullOrWhiteSpace($filterText)) {
        $escaped = [regex]::Escape($filterText)
        $entries = @($entries | Where-Object { $_.SearchText -match $escaped })
    }

    $script:State.CurrentEntries = $entries
    $script:UI.ReviewListBox.ItemsSource = $null
    $script:UI.ReviewListBox.ItemsSource = $entries

    if ($queueDefinition) {
        $script:UI.ReviewPaneTitleText.Text = $queueDefinition[0].Title
        $script:UI.ReviewPaneSubtitleText.Text = $queueDefinition[0].Description
    }

    if ($entries.Count -gt 0) {
        $targetEntry = @($entries | Where-Object { (Get-EntryKey -Entry $_) -eq $previousEntryKey } | Select-Object -First 1)
        if (-not $targetEntry) {
            $targetEntry = @($entries | Select-Object -First 1)
        }

        $script:UI.ReviewListEmptyState.Visibility = "Collapsed"
        $script:UI.ReviewFooterText.Text = "$($entries.Count) item(s) shown."
        $script:UI.ReviewListBox.SelectedItem = $targetEntry[0]
    }
    else {
        $script:UI.ReviewListBox.SelectedItem = $null
        $script:UI.ReviewListEmptyState.Visibility = "Visible"
        if (-not $script:State.LibraryRoot) {
            $script:UI.ReviewListEmptyText.Text = "Choose a folder to start scanning."
        }
        elseif (-not [string]::IsNullOrWhiteSpace($filterText)) {
            $script:UI.ReviewListEmptyText.Text = "No photos match the current filter."
        }
        elseif ($queueDefinition) {
            $script:UI.ReviewListEmptyText.Text = $queueDefinition[0].EmptyText
        }
        else {
            $script:UI.ReviewListEmptyText.Text = "No items to review."
        }

        $script:UI.ReviewFooterText.Text = "0 item(s) shown."
    }
}

function Update-ReviewWorkspace {
    $definition = @((Get-QueueDefinitions | Where-Object { $_.Id -eq $script:State.CurrentQueueId }) | Select-Object -First 1)
    $entry = $script:UI.ReviewListBox.SelectedItem

    if (-not $script:State.LibraryRoot) {
        $script:UI.WorkspaceTitleText.Text = "Start With One Folder"
        $script:UI.WorkspaceHelperText.Text = "Pick a folder, scan it, and then review one cleanup queue at a time."
        $script:UI.WorkspaceEmptyTitleText.Text = "Nothing to review yet"
        $script:UI.WorkspaceEmptyText.Text = "Choose your library folder to build duplicate, blurry, forward, screenshot, and text-heavy queues."
        $script:UI.WorkspaceEmptyState.Visibility = "Visible"
        $script:UI.PairReviewPanel.Visibility = "Collapsed"
        $script:UI.SingleReviewPanel.Visibility = "Collapsed"
        $script:UI.PairActionPanel.Visibility = "Collapsed"
        $script:UI.SingleActionPanel.Visibility = "Collapsed"
        return
    }

    if ($definition) {
        $script:UI.WorkspaceTitleText.Text = $definition[0].Title
        $script:UI.WorkspaceHelperText.Text = $definition[0].Description
    }

    if (-not $entry) {
        $script:UI.WorkspaceEmptyTitleText.Text = if ($definition) { $definition[0].Title } else { "Nothing selected" }
        $script:UI.WorkspaceEmptyText.Text = if ($definition) { $definition[0].EmptyText } else { "Choose a queue to start reviewing." }
        $script:UI.WorkspaceEmptyState.Visibility = "Visible"
        $script:UI.PairReviewPanel.Visibility = "Collapsed"
        $script:UI.SingleReviewPanel.Visibility = "Collapsed"
        $script:UI.PairActionPanel.Visibility = "Collapsed"
        $script:UI.SingleActionPanel.Visibility = "Collapsed"
        return
    }

    $script:UI.WorkspaceEmptyState.Visibility = "Collapsed"

    if ($entry.Kind -eq "pair") {
        $pair = $entry.Pair
        $script:UI.PairReviewPanel.Visibility = "Visible"
        $script:UI.SingleReviewPanel.Visibility = "Collapsed"
        $script:UI.PairActionPanel.Visibility = "Visible"
        $script:UI.SingleActionPanel.Visibility = "Collapsed"

        $script:UI.PairRecommendationText.Text = $pair.RecommendationText
        $script:UI.PairLeftNameText.Text = $pair.First.Name
        $script:UI.PairLeftMetaText.Text = "$($pair.First.DimensionsText) | $($pair.First.SizeText) | $($pair.First.ModifiedText)"
        $script:UI.PairLeftPathText.Text = $pair.First.Path
        $script:UI.PairRightNameText.Text = $pair.Second.Name
        $script:UI.PairRightMetaText.Text = "$($pair.Second.DimensionsText) | $($pair.Second.SizeText) | $($pair.Second.ModifiedText)"
        $script:UI.PairRightPathText.Text = $pair.Second.Path

        if ($pair.SuggestedDelete.Path -eq $pair.First.Path) {
            $script:UI.PairLeftTagText.Text = "Suggested delete"
            $script:UI.PairRightTagText.Text = "Suggested keep"
        }
        else {
            $script:UI.PairLeftTagText.Text = "Suggested keep"
            $script:UI.PairRightTagText.Text = "Suggested delete"
        }

        Set-PreviewImage -ImageControl $script:UI.PairLeftImage -Placeholder $script:UI.PairLeftPlaceholder -Path $pair.First.Path -DecodePixelWidth 1100
        Set-PreviewImage -ImageControl $script:UI.PairRightImage -Placeholder $script:UI.PairRightPlaceholder -Path $pair.Second.Path -DecodePixelWidth 1100

        $script:UI.DeletePairLeftButton.IsEnabled = $true
        $script:UI.DeletePairRightButton.IsEnabled = $true
        $script:UI.DeletePairBothButton.IsEnabled = $true
        $script:UI.KeepPairButton.IsEnabled = $true
        $script:UI.NextPairButton.IsEnabled = ($script:UI.ReviewListBox.SelectedIndex + 1) -lt $script:UI.ReviewListBox.Items.Count
        $script:UI.OpenPairCompareButton.IsEnabled = $true
        return
    }

    $item = $entry.Item
    $script:UI.PairReviewPanel.Visibility = "Collapsed"
    $script:UI.SingleReviewPanel.Visibility = "Visible"
    $script:UI.PairActionPanel.Visibility = "Collapsed"
    $script:UI.SingleActionPanel.Visibility = "Visible"

    $script:UI.SingleNameText.Text = $item.Name
    $script:UI.SingleMetaText.Text = "$($item.DimensionsText) | $($item.SizeText) | $($item.ModifiedText)"
    $script:UI.SingleReasonText.Text = Get-SingleQueueReasonText -QueueId $entry.QueueId -Item $item
    $script:UI.SinglePathText.Text = $item.Path
    Set-PreviewImage -ImageControl $script:UI.SinglePreviewImage -Placeholder $script:UI.SinglePreviewPlaceholder -Path $item.Path -DecodePixelWidth 1600

    $script:UI.DeleteSingleButton.IsEnabled = $true
    $script:UI.KeepSingleButton.IsEnabled = $true
    $script:UI.NextSingleButton.IsEnabled = ($script:UI.ReviewListBox.SelectedIndex + 1) -lt $script:UI.ReviewListBox.Items.Count
    $script:UI.OpenSinglePreviewButton.IsEnabled = $true
}

function Refresh-Ui {
    Update-SummaryBanner
    Refresh-QueueList
    Refresh-ReviewList
    Update-ReviewWorkspace
}

function Invoke-KeepCurrentEntry {
    $entry = $script:UI.ReviewListBox.SelectedItem
    if (-not $entry) {
        return $false
    }

    $currentIndex = $script:UI.ReviewListBox.SelectedIndex
    switch ($entry.QueueId) {
        "exact" {
            $key = Get-EntryKey -Entry $entry
            $script:State.ExactDuplicatePairs = @($script:State.ExactDuplicatePairs | Where-Object { "$($_.First.Path)|$($_.Second.Path)" -ne $key })
            Refresh-Ui
            Select-SafeListIndex -ListBox $script:UI.ReviewListBox -Index $currentIndex
            Set-Status "Kept both files and removed the pair from Exact Duplicates."
            return $true
        }
        "similar" {
            $key = Get-EntryKey -Entry $entry
            $script:State.DuplicatePairs = @($script:State.DuplicatePairs | Where-Object { "$($_.First.Path)|$($_.Second.Path)" -ne $key })
            Refresh-Ui
            Select-SafeListIndex -ListBox $script:UI.ReviewListBox -Index $currentIndex
            Set-Status "Kept both files and removed the pair from Similar Photos."
            return $true
        }
        "blurry" {
            $script:State.BlurryItems = @($script:State.BlurryItems | Where-Object { $_.Path -ne $entry.Item.Path })
            Refresh-Ui
            Select-SafeListIndex -ListBox $script:UI.ReviewListBox -Index $currentIndex
            Set-Status "Kept the photo and removed it from Blurry Photos."
            return $true
        }
        "forward" {
            $script:State.ForwardItems = @($script:State.ForwardItems | Where-Object { $_.Path -ne $entry.Item.Path })
            Refresh-Ui
            Select-SafeListIndex -ListBox $script:UI.ReviewListBox -Index $currentIndex
            Set-Status "Kept the photo and removed it from Likely Forwards."
            return $true
        }
        "screenshot" {
            $script:State.ScreenshotItems = @($script:State.ScreenshotItems | Where-Object { $_.Path -ne $entry.Item.Path })
            Refresh-Ui
            Select-SafeListIndex -ListBox $script:UI.ReviewListBox -Index $currentIndex
            Set-Status "Kept the image and removed it from Screenshots."
            return $true
        }
        "textheavy" {
            $script:State.TextHeavyItems = @($script:State.TextHeavyItems | Where-Object { $_.Path -ne $entry.Item.Path })
            Refresh-Ui
            Select-SafeListIndex -ListBox $script:UI.ReviewListBox -Index $currentIndex
            Set-Status "Kept the image and removed it from Text-Heavy Images."
            return $true
        }
    }

    return $false
}

function Invoke-ConfirmedPairDeletion {
    param(
        [string[]]$Paths,
        [string]$Title,
        [string]$Message,
        [string]$StatusMessage
    )

    $currentIndex = $script:UI.ReviewListBox.SelectedIndex
    if (-not (Move-PathsToRecycleBin -Paths $Paths -Confirm -Title $Title -Message $Message)) {
        return $false
    }

    Remove-PathsFromState -Paths $Paths
    Refresh-Ui
    Select-SafeListIndex -ListBox $script:UI.ReviewListBox -Index $currentIndex
    Set-Status $StatusMessage
    return $true
}

function Invoke-DeleteCurrentPair {
    param(
        [ValidateSet("Left", "Right", "Both")]
        [string]$Mode
    )

    $entry = $script:UI.ReviewListBox.SelectedItem
    if (-not $entry -or $entry.Kind -ne "pair" -or -not $entry.Pair) {
        return $false
    }

    switch ($Mode) {
        "Left" {
            return Invoke-ConfirmedPairDeletion `
                -Paths @($entry.Pair.First.Path) `
                -Title "Delete Left Photo" `
                -Message "Send this photo to the Recycle Bin?`n`n$($entry.Pair.First.Path)" `
                -StatusMessage "Moved the left photo to the Recycle Bin."
        }
        "Right" {
            return Invoke-ConfirmedPairDeletion `
                -Paths @($entry.Pair.Second.Path) `
                -Title "Delete Right Photo" `
                -Message "Send this photo to the Recycle Bin?`n`n$($entry.Pair.Second.Path)" `
                -StatusMessage "Moved the right photo to the Recycle Bin."
        }
        "Both" {
            return Invoke-ConfirmedPairDeletion `
                -Paths @($entry.Pair.First.Path, $entry.Pair.Second.Path) `
                -Title "Delete Both Photos" `
                -Message "Send both photos to the Recycle Bin?`n`n$($entry.Pair.First.Path)`n$($entry.Pair.Second.Path)" `
                -StatusMessage "Moved both photos to the Recycle Bin."
        }
    }

    return $false
}

function Invoke-ActiveSinglePhotoDelete {
    $entry = $script:UI.ReviewListBox.SelectedItem
    if (-not $entry -or $entry.Kind -ne "single" -or -not $entry.Item) {
        return $false
    }

    if ($entry.QueueId -notin @("blurry", "forward", "screenshot", "textheavy")) {
        return $false
    }

    $statusMessage = switch ($entry.QueueId) {
        "blurry" { "Moved one blurry photo to the Recycle Bin." }
        "forward" { "Moved one likely forward image to the Recycle Bin." }
        "screenshot" { "Moved one screenshot to the Recycle Bin." }
        "textheavy" { "Moved one text-heavy image to the Recycle Bin." }
    }

    $currentIndex = $script:UI.ReviewListBox.SelectedIndex
    if (-not (Move-PathsToRecycleBin -Paths @($entry.Item.Path))) {
        return $false
    }

    Remove-PathsFromState -Paths @($entry.Item.Path)
    Refresh-Ui
    Select-SafeListIndex -ListBox $script:UI.ReviewListBox -Index $currentIndex
    Set-Status $statusMessage
    return $true
}

function Invoke-OpenCurrentPreview {
    $entry = $script:UI.ReviewListBox.SelectedItem
    if (-not $entry) {
        return $false
    }

    if ($entry.Kind -eq "pair" -and $entry.Pair) {
        Show-PairPreviewWindow -Pair $entry.Pair
        return $true
    }

    if ($entry.Item) {
        Show-PreviewWindow -Item $entry.Item
        return $true
    }

    return $false
}

function Show-PairPreviewWindow {
    param([pscustomobject]$Pair)

    if (-not $Pair) {
        return
    }

    $compareXaml = @'
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="Compare Similar Photos"
        Width="1320"
        Height="860"
        MinWidth="1080"
        MinHeight="760"
        Background="#191817"
        WindowStartupLocation="CenterOwner">
    <Grid Margin="18">
        <Grid.RowDefinitions>
            <RowDefinition Height="Auto"/>
            <RowDefinition Height="*"/>
            <RowDefinition Height="Auto"/>
        </Grid.RowDefinitions>
        <Border Grid.Row="0" Background="#26211D" CornerRadius="16" Padding="16">
            <TextBlock x:Name="RecommendationText" FontSize="16" FontWeight="SemiBold" Foreground="#F5E7D8" TextWrapping="Wrap"/>
        </Border>
        <Grid Grid.Row="1" Margin="0,16,0,16">
            <Grid.ColumnDefinitions>
                <ColumnDefinition Width="*"/>
                <ColumnDefinition Width="18"/>
                <ColumnDefinition Width="*"/>
            </Grid.ColumnDefinitions>
            <Border Grid.Column="0" Background="#111111" CornerRadius="18" Padding="14">
                <Grid>
                    <Grid.RowDefinitions>
                        <RowDefinition Height="Auto"/>
                        <RowDefinition Height="*"/>
                        <RowDefinition Height="Auto"/>
                        <RowDefinition Height="Auto"/>
                    </Grid.RowDefinitions>
                    <TextBlock x:Name="LeftTagText" FontSize="12" FontWeight="SemiBold" Foreground="#E5814A"/>
                    <Grid Grid.Row="1" Margin="0,10,0,14">
                        <Image x:Name="LeftImage" Stretch="Uniform"/>
                        <TextBlock x:Name="LeftPlaceholder" Text="Left preview unavailable" Foreground="#A7A7A7" HorizontalAlignment="Center" VerticalAlignment="Center"/>
                    </Grid>
                    <TextBlock x:Name="LeftNameText" Grid.Row="2" FontSize="15" FontWeight="SemiBold" Foreground="White" TextWrapping="Wrap"/>
                    <TextBlock x:Name="LeftInfoText" Grid.Row="3" Margin="0,6,0,0" FontSize="12" Foreground="#CFC3B5" TextWrapping="Wrap"/>
                </Grid>
            </Border>
            <Border Grid.Column="2" Background="#111111" CornerRadius="18" Padding="14">
                <Grid>
                    <Grid.RowDefinitions>
                        <RowDefinition Height="Auto"/>
                        <RowDefinition Height="*"/>
                        <RowDefinition Height="Auto"/>
                        <RowDefinition Height="Auto"/>
                    </Grid.RowDefinitions>
                    <TextBlock x:Name="RightTagText" FontSize="12" FontWeight="SemiBold" Foreground="#E5814A"/>
                    <Grid Grid.Row="1" Margin="0,10,0,14">
                        <Image x:Name="RightImage" Stretch="Uniform"/>
                        <TextBlock x:Name="RightPlaceholder" Text="Right preview unavailable" Foreground="#A7A7A7" HorizontalAlignment="Center" VerticalAlignment="Center"/>
                    </Grid>
                    <TextBlock x:Name="RightNameText" Grid.Row="2" FontSize="15" FontWeight="SemiBold" Foreground="White" TextWrapping="Wrap"/>
                    <TextBlock x:Name="RightInfoText" Grid.Row="3" Margin="0,6,0,0" FontSize="12" Foreground="#CFC3B5" TextWrapping="Wrap"/>
                </Grid>
            </Border>
        </Grid>
        <Border Grid.Row="2" Background="#26211D" CornerRadius="16" Padding="16">
            <TextBlock x:Name="PathText" FontSize="12" Foreground="#C8B7A1" TextWrapping="Wrap"/>
        </Border>
    </Grid>
</Window>
'@

    [xml]$compareXml = $compareXaml
    $reader = New-Object System.Xml.XmlNodeReader $compareXml
    $compareWindow = [System.Windows.Markup.XamlReader]::Load($reader)

    $recommendationText = $compareWindow.FindName("RecommendationText")
    $leftImage = $compareWindow.FindName("LeftImage")
    $leftPlaceholder = $compareWindow.FindName("LeftPlaceholder")
    $leftTagText = $compareWindow.FindName("LeftTagText")
    $leftNameText = $compareWindow.FindName("LeftNameText")
    $leftInfoText = $compareWindow.FindName("LeftInfoText")
    $rightImage = $compareWindow.FindName("RightImage")
    $rightPlaceholder = $compareWindow.FindName("RightPlaceholder")
    $rightTagText = $compareWindow.FindName("RightTagText")
    $rightNameText = $compareWindow.FindName("RightNameText")
    $rightInfoText = $compareWindow.FindName("RightInfoText")
    $pathText = $compareWindow.FindName("PathText")

    $recommendationText.Text = $Pair.RecommendationText
    $leftNameText.Text = $Pair.First.Name
    $leftInfoText.Text = "$($Pair.First.DimensionsText) | $($Pair.First.SizeText) | $($Pair.First.ModifiedText)"
    $rightNameText.Text = $Pair.Second.Name
    $rightInfoText.Text = "$($Pair.Second.DimensionsText) | $($Pair.Second.SizeText) | $($Pair.Second.ModifiedText)"
    $pathText.Text = "$($Pair.First.Path)`n$($Pair.Second.Path)"

    if ($Pair.SuggestedDelete.Path -eq $Pair.First.Path) {
        $leftTagText.Text = "Suggested delete"
        $rightTagText.Text = "Suggested keep"
    }
    else {
        $leftTagText.Text = "Suggested keep"
        $rightTagText.Text = "Suggested delete"
    }

    Set-PreviewImage -ImageControl $leftImage -Placeholder $leftPlaceholder -Path $Pair.First.Path -DecodePixelWidth 1600
    Set-PreviewImage -ImageControl $rightImage -Placeholder $rightPlaceholder -Path $Pair.Second.Path -DecodePixelWidth 1600
    [void]$compareWindow.ShowDialog()
}

function Load-LibraryIntoUi {
    param([string]$RootPath)

    try {
        Set-ProgressState -Visible $true -Value 0 -Maximum 1
        Set-Status "Preparing scan..."

        $snapshot = Scan-Library -RootPath $RootPath -ProgressCallback {
            param($Current, $Total, $Message)
            Set-ProgressState -Visible $true -Value $Current -Maximum $Total
            Set-Status $Message
            [System.Windows.Forms.Application]::DoEvents()
        }

        $script:State.LibraryRoot = $snapshot.RootPath
        $script:State.ImageItems = @($snapshot.ImageItems)
        $script:State.ExactDuplicatePairs = @($snapshot.ExactDuplicatePairs)
        $script:State.DuplicatePairs = @($snapshot.DuplicatePairs)
        $script:State.BlurryItems = @($snapshot.BlurryItems)
        $script:State.ForwardItems = @($snapshot.ForwardItems)
        $script:State.ScreenshotItems = @($snapshot.ScreenshotItems)
        $script:State.TextHeavyItems = @($snapshot.TextHeavyItems)
        $script:State.CurrentQueueId = ""
        if ($script:UI.ReviewSearchTextBox) {
            $script:UI.ReviewSearchTextBox.Text = ""
        }

        Refresh-Ui
        Set-Status "Scan complete. Start with the highlighted queue and work straight through it."
    }
    catch {
        Set-Status "Scan failed."
        Show-Message -Message $_.Exception.Message -Title "Scan Failed" -Icon Error
    }
    finally {
        Set-ProgressState -Visible $false
    }
}

$modernXaml = @'
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="Image Cleanup Assistant"
        Width="1520"
        Height="940"
        MinWidth="1280"
        MinHeight="820"
        Background="#F4EFE8"
        FontFamily="Segoe UI"
        WindowStartupLocation="CenterScreen">
    <Grid Margin="18">
        <Grid.RowDefinitions>
            <RowDefinition Height="Auto"/>
            <RowDefinition Height="Auto"/>
            <RowDefinition Height="*"/>
            <RowDefinition Height="Auto"/>
        </Grid.RowDefinitions>
        <Border Grid.Row="0" Background="#FFFCF6" CornerRadius="22" Padding="22">
            <Grid>
                <Grid.ColumnDefinitions>
                    <ColumnDefinition Width="*"/>
                    <ColumnDefinition Width="Auto"/>
                    <ColumnDefinition Width="Auto"/>
                </Grid.ColumnDefinitions>
                <StackPanel Margin="0,0,20,0">
                    <TextBlock Text="Image Cleanup Assistant" FontSize="30" FontWeight="SemiBold" Foreground="#1E1E1E"/>
                    <TextBlock Text="Clean a large mixed photo library one queue at a time: exact duplicates, stronger similar-photo matches, blurry shots, forwards, screenshots, and text-heavy graphics." Margin="0,8,0,0" FontSize="14" TextWrapping="Wrap" Foreground="#5D5148"/>
                    <TextBlock x:Name="LibraryPathText" Margin="0,12,0,0" FontSize="13" TextWrapping="Wrap" Foreground="#8B6E57" Text="No folder selected yet."/>
                </StackPanel>
                <Button x:Name="ChooseFolderButton" Grid.Column="1" Width="170" Height="42" Margin="0,0,12,0" Content="Choose Folder" Background="#D96E3C" Foreground="White" BorderBrush="#D96E3C"/>
                <Button x:Name="RescanButton" Grid.Column="2" Width="110" Height="42" Content="Rescan"/>
            </Grid>
        </Border>
        <Border Grid.Row="1" Margin="0,16,0,16" Background="White" CornerRadius="18" Padding="16">
            <Grid>
                <Grid.ColumnDefinitions>
                    <ColumnDefinition Width="*"/>
                    <ColumnDefinition Width="240"/>
                </Grid.ColumnDefinitions>
                <StackPanel>
                    <TextBlock x:Name="SummaryText" FontSize="13" FontWeight="SemiBold" Foreground="#454545" TextWrapping="Wrap" Text="Choose a folder to begin."/>
                    <TextBlock x:Name="SummaryHintText" Margin="0,6,0,0" FontSize="12" Foreground="#6A6157" TextWrapping="Wrap" Text="This app will build cleanup queues after the scan finishes."/>
                </StackPanel>
                <ProgressBar x:Name="ScanProgress" Grid.Column="1" Height="12" VerticalAlignment="Center" Visibility="Collapsed" Minimum="0" Maximum="1" Value="0"/>
            </Grid>
        </Border>
        <Grid Grid.Row="2">
            <Grid.ColumnDefinitions>
                <ColumnDefinition Width="290"/>
                <ColumnDefinition Width="360"/>
                <ColumnDefinition Width="*"/>
            </Grid.ColumnDefinitions>
            <Border Grid.Column="0" Background="#FFFCF7" CornerRadius="20" Padding="18" Margin="0,0,16,0">
                <Grid>
                    <Grid.RowDefinitions>
                        <RowDefinition Height="Auto"/>
                        <RowDefinition Height="*"/>
                        <RowDefinition Height="Auto"/>
                    </Grid.RowDefinitions>
                    <StackPanel>
                        <TextBlock Text="Cleanup Queues" FontSize="22" FontWeight="SemiBold" Foreground="#1E1E1E"/>
                        <TextBlock Text="Pick one queue and clear it from top to bottom." Margin="0,8,0,0" FontSize="13" Foreground="#665E55" TextWrapping="Wrap"/>
                    </StackPanel>
                    <ListBox x:Name="QueueListBox" Grid.Row="1" Margin="0,16,0,16" BorderThickness="0" Background="Transparent" HorizontalContentAlignment="Stretch"/>
                    <Border Grid.Row="2" Background="#F5EEE2" CornerRadius="14" Padding="12">
                        <TextBlock Text="Delete works directly in Blurry Photos, Likely Forwards, Screenshots, and Text-Heavy Images. Use K to keep, N to move next, and Enter to open a larger preview." FontSize="12" Foreground="#675B4E" TextWrapping="Wrap"/>
                    </Border>
                </Grid>
            </Border>
            <Border Grid.Column="1" Background="White" CornerRadius="20" Padding="18" Margin="0,0,16,0">
                <Grid>
                    <Grid.RowDefinitions>
                        <RowDefinition Height="Auto"/>
                        <RowDefinition Height="Auto"/>
                        <RowDefinition Height="*"/>
                        <RowDefinition Height="Auto"/>
                    </Grid.RowDefinitions>
                    <StackPanel>
                        <TextBlock x:Name="ReviewPaneTitleText" Text="Review Queue" FontSize="22" FontWeight="SemiBold" Foreground="#1E1E1E"/>
                        <TextBlock x:Name="ReviewPaneSubtitleText" Margin="0,8,0,0" FontSize="13" Foreground="#665E55" TextWrapping="Wrap" Text="Choose a queue on the left to populate this list."/>
                    </StackPanel>
                    <TextBox x:Name="ReviewSearchTextBox" Grid.Row="1" Margin="0,16,0,16" Height="38" Padding="10,8" FontSize="13" VerticalContentAlignment="Center"/>
                    <Grid Grid.Row="2">
                        <ListBox x:Name="ReviewListBox" BorderThickness="0" Background="Transparent" HorizontalContentAlignment="Stretch"/>
                        <Border x:Name="ReviewListEmptyState" Background="#F8F4ED" CornerRadius="16" Padding="18" Visibility="Collapsed">
                            <TextBlock x:Name="ReviewListEmptyText" FontSize="13" Foreground="#655B51" TextWrapping="Wrap" HorizontalAlignment="Center" VerticalAlignment="Center" TextAlignment="Center" Text="Nothing to review in this queue yet."/>
                        </Border>
                    </Grid>
                    <TextBlock x:Name="ReviewFooterText" Grid.Row="3" Margin="0,14,0,0" FontSize="12" Foreground="#7B736B" Text="0 item(s) shown."/>
                </Grid>
            </Border>
            <Border Grid.Column="2" Background="White" CornerRadius="20" Padding="18">
                <Grid>
                    <Grid.RowDefinitions>
                        <RowDefinition Height="Auto"/>
                        <RowDefinition Height="*"/>
                        <RowDefinition Height="Auto"/>
                    </Grid.RowDefinitions>
                    <StackPanel>
                        <TextBlock x:Name="WorkspaceTitleText" Text="Start With One Folder" FontSize="24" FontWeight="SemiBold" Foreground="#1E1E1E"/>
                        <TextBlock x:Name="WorkspaceHelperText" Margin="0,8,0,0" FontSize="13" Foreground="#665E55" TextWrapping="Wrap" Text="Pick a queue on the left, review the current item here, then delete, keep, or move next."/>
                    </StackPanel>
                    <Grid Grid.Row="1" Margin="0,18,0,18">
                        <Border x:Name="WorkspaceEmptyState" Background="#F8F3EA" CornerRadius="18" Padding="24">
                            <StackPanel HorizontalAlignment="Center" VerticalAlignment="Center" Width="420">
                                <TextBlock x:Name="WorkspaceEmptyTitleText" FontSize="22" FontWeight="SemiBold" Foreground="#2A2927" TextAlignment="Center" Text="Nothing to review yet"/>
                                <TextBlock x:Name="WorkspaceEmptyText" Margin="0,12,0,0" FontSize="13" Foreground="#6A6157" TextWrapping="Wrap" TextAlignment="Center" Text="Choose your library folder to build cleanup queues, then review one item at a time."/>
                            </StackPanel>
                        </Border>
                        <Grid x:Name="PairReviewPanel" Visibility="Collapsed">
                            <Grid.RowDefinitions>
                                <RowDefinition Height="Auto"/>
                                <RowDefinition Height="*"/>
                            </Grid.RowDefinitions>
                            <Border Background="#FFF4E9" CornerRadius="14" Padding="14">
                                <TextBlock x:Name="PairRecommendationText" FontSize="14" FontWeight="SemiBold" Foreground="#5E412E" TextWrapping="Wrap"/>
                            </Border>
                            <Grid Grid.Row="1" Margin="0,16,0,0">
                                <Grid.ColumnDefinitions>
                                    <ColumnDefinition Width="*"/>
                                    <ColumnDefinition Width="16"/>
                                    <ColumnDefinition Width="*"/>
                                </Grid.ColumnDefinitions>
                                <Border Grid.Column="0" Background="#FBFBFB" CornerRadius="18" Padding="14" BorderBrush="#E5E5E5" BorderThickness="1">
                                    <Grid>
                                        <Grid.RowDefinitions>
                                            <RowDefinition Height="Auto"/>
                                            <RowDefinition Height="*"/>
                                            <RowDefinition Height="Auto"/>
                                            <RowDefinition Height="Auto"/>
                                            <RowDefinition Height="Auto"/>
                                        </Grid.RowDefinitions>
                                        <TextBlock x:Name="PairLeftTagText" FontSize="12" FontWeight="SemiBold" Foreground="#D96E3C"/>
                                        <Grid Grid.Row="1" Margin="0,10,0,14" Background="#F6F3EE">
                                            <Image x:Name="PairLeftImage" Stretch="Uniform"/>
                                            <TextBlock x:Name="PairLeftPlaceholder" Text="Left preview unavailable" Foreground="#8A8A8A" HorizontalAlignment="Center" VerticalAlignment="Center"/>
                                        </Grid>
                                        <TextBlock x:Name="PairLeftNameText" Grid.Row="2" FontSize="16" FontWeight="SemiBold" Foreground="#202020" TextWrapping="Wrap"/>
                                        <TextBlock x:Name="PairLeftMetaText" Grid.Row="3" Margin="0,8,0,0" FontSize="12" Foreground="#5F5B55" TextWrapping="Wrap"/>
                                        <TextBlock x:Name="PairLeftPathText" Grid.Row="4" Margin="0,8,0,0" FontSize="11" Foreground="#7C756D" TextWrapping="Wrap"/>
                                    </Grid>
                                </Border>
                                <Border Grid.Column="2" Background="#FBFBFB" CornerRadius="18" Padding="14" BorderBrush="#E5E5E5" BorderThickness="1">
                                    <Grid>
                                        <Grid.RowDefinitions>
                                            <RowDefinition Height="Auto"/>
                                            <RowDefinition Height="*"/>
                                            <RowDefinition Height="Auto"/>
                                            <RowDefinition Height="Auto"/>
                                            <RowDefinition Height="Auto"/>
                                        </Grid.RowDefinitions>
                                        <TextBlock x:Name="PairRightTagText" FontSize="12" FontWeight="SemiBold" Foreground="#D96E3C"/>
                                        <Grid Grid.Row="1" Margin="0,10,0,14" Background="#F6F3EE">
                                            <Image x:Name="PairRightImage" Stretch="Uniform"/>
                                            <TextBlock x:Name="PairRightPlaceholder" Text="Right preview unavailable" Foreground="#8A8A8A" HorizontalAlignment="Center" VerticalAlignment="Center"/>
                                        </Grid>
                                        <TextBlock x:Name="PairRightNameText" Grid.Row="2" FontSize="16" FontWeight="SemiBold" Foreground="#202020" TextWrapping="Wrap"/>
                                        <TextBlock x:Name="PairRightMetaText" Grid.Row="3" Margin="0,8,0,0" FontSize="12" Foreground="#5F5B55" TextWrapping="Wrap"/>
                                        <TextBlock x:Name="PairRightPathText" Grid.Row="4" Margin="0,8,0,0" FontSize="11" Foreground="#7C756D" TextWrapping="Wrap"/>
                                    </Grid>
                                </Border>
                            </Grid>
                        </Grid>
                        <Grid x:Name="SingleReviewPanel" Visibility="Collapsed">
                            <Grid.ColumnDefinitions>
                                <ColumnDefinition Width="2.15*"/>
                                <ColumnDefinition Width="1.05*"/>
                            </Grid.ColumnDefinitions>
                            <Border Background="#F7F3EC" CornerRadius="18" Padding="14" Margin="0,0,16,0">
                                <Grid Background="#F6F3EE">
                                    <Image x:Name="SinglePreviewImage" Stretch="Uniform"/>
                                    <TextBlock x:Name="SinglePreviewPlaceholder" Text="Preview unavailable" Foreground="#8A8A8A" HorizontalAlignment="Center" VerticalAlignment="Center"/>
                                </Grid>
                            </Border>
                            <Border Grid.Column="1" Background="#FAFAFA" CornerRadius="18" Padding="18" BorderBrush="#E5E5E5" BorderThickness="1">
                                <StackPanel>
                                    <TextBlock x:Name="SingleNameText" FontSize="22" FontWeight="SemiBold" Foreground="#202020" TextWrapping="Wrap"/>
                                    <TextBlock x:Name="SingleMetaText" Margin="0,10,0,0" FontSize="12" Foreground="#5F5B55" TextWrapping="Wrap"/>
                                    <TextBlock x:Name="SingleReasonText" Margin="0,16,0,0" FontSize="13" Foreground="#5E412E" TextWrapping="Wrap"/>
                                    <TextBlock x:Name="SinglePathText" Margin="0,18,0,0" FontSize="12" Foreground="#767068" TextWrapping="Wrap"/>
                                </StackPanel>
                            </Border>
                        </Grid>
                    </Grid>
                    <Grid Grid.Row="2">
                        <UniformGrid x:Name="PairActionPanel" Columns="6" Visibility="Collapsed">
                            <Button x:Name="DeletePairLeftButton" Margin="0,0,8,0" Height="42" Content="Delete Left" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="DeletePairRightButton" Margin="0,0,8,0" Height="42" Content="Delete Right" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="DeletePairBothButton" Margin="0,0,8,0" Height="42" Content="Delete Both" Background="#8E3B28" Foreground="White" BorderBrush="#8E3B28"/>
                            <Button x:Name="KeepPairButton" Margin="0,0,8,0" Height="42" Content="Keep Pair"/>
                            <Button x:Name="NextPairButton" Margin="0,0,8,0" Height="42" Content="Next Pair"/>
                            <Button x:Name="OpenPairCompareButton" Height="42" Content="Open Full Compare"/>
                        </UniformGrid>
                        <UniformGrid x:Name="SingleActionPanel" Columns="4" Visibility="Collapsed">
                            <Button x:Name="DeleteSingleButton" Margin="0,0,8,0" Height="42" Content="Delete Photo (Del)" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="KeepSingleButton" Margin="0,0,8,0" Height="42" Content="Keep Photo (K)"/>
                            <Button x:Name="NextSingleButton" Margin="0,0,8,0" Height="42" Content="Next Photo (N)"/>
                            <Button x:Name="OpenSinglePreviewButton" Height="42" Content="Open Large Preview"/>
                        </UniformGrid>
                    </Grid>
                </Grid>
            </Border>
        </Grid>
        <Border Grid.Row="3" Margin="0,16,0,0" Background="#201E1B" CornerRadius="16" Padding="14">
            <TextBlock x:Name="StatusText" Foreground="#F8F3EA" FontSize="13" Text="Ready. Choose a folder to begin."/>
        </Border>
    </Grid>
</Window>
'@

[xml]$modernXml = $modernXaml
$modernReader = New-Object System.Xml.XmlNodeReader $modernXml
$modernWindow = [System.Windows.Markup.XamlReader]::Load($modernReader)

$modernControlNames = @(
    "ChooseFolderButton","RescanButton","LibraryPathText","SummaryText","SummaryHintText","ScanProgress","StatusText",
    "QueueListBox",
    "ReviewPaneTitleText","ReviewPaneSubtitleText","ReviewSearchTextBox","ReviewListBox","ReviewListEmptyState","ReviewListEmptyText","ReviewFooterText",
    "WorkspaceTitleText","WorkspaceHelperText","WorkspaceEmptyState","WorkspaceEmptyTitleText","WorkspaceEmptyText",
    "PairReviewPanel","PairRecommendationText","PairLeftImage","PairLeftPlaceholder","PairLeftTagText","PairLeftNameText","PairLeftMetaText","PairLeftPathText",
    "PairRightImage","PairRightPlaceholder","PairRightTagText","PairRightNameText","PairRightMetaText","PairRightPathText",
    "SingleReviewPanel","SinglePreviewImage","SinglePreviewPlaceholder","SingleNameText","SingleMetaText","SingleReasonText","SinglePathText",
    "PairActionPanel","DeletePairLeftButton","DeletePairRightButton","DeletePairBothButton","KeepPairButton","NextPairButton","OpenPairCompareButton",
    "SingleActionPanel","DeleteSingleButton","KeepSingleButton","NextSingleButton","OpenSinglePreviewButton"
)

foreach ($name in $modernControlNames) {
    $script:UI[$name] = $modernWindow.FindName($name)
}

$script:UI.MainWindow = $modernWindow
$queueTemplateXaml = @'
<DataTemplate xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
              xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <Border x:Name="QueueCard" Padding="14" Margin="0,0,0,12" CornerRadius="16" Background="#FAF7F1" BorderBrush="#E4DDD0" BorderThickness="1">
        <Grid>
            <Grid.ColumnDefinitions>
                <ColumnDefinition Width="*"/>
                <ColumnDefinition Width="Auto"/>
            </Grid.ColumnDefinitions>
            <StackPanel Margin="0,0,12,0">
                <TextBlock Text="{Binding Title}" FontSize="15" FontWeight="SemiBold" Foreground="#1F1F1F"/>
                <TextBlock Text="{Binding Description}" Margin="0,6,0,0" FontSize="12" Foreground="#685E55" TextWrapping="Wrap"/>
            </StackPanel>
            <Border Grid.Column="1" Background="#EEE6DA" CornerRadius="12" Padding="10,5" VerticalAlignment="Top">
                <TextBlock Text="{Binding Count}" FontSize="13" FontWeight="SemiBold" Foreground="#3B322A"/>
            </Border>
        </Grid>
    </Border>
    <DataTemplate.Triggers>
        <DataTrigger Binding="{Binding RelativeSource={RelativeSource AncestorType=ListBoxItem}, Path=IsSelected}" Value="True">
            <Setter TargetName="QueueCard" Property="BorderBrush" Value="#D96E3C"/>
            <Setter TargetName="QueueCard" Property="BorderThickness" Value="2"/>
            <Setter TargetName="QueueCard" Property="Background" Value="#FFF4E8"/>
        </DataTrigger>
    </DataTemplate.Triggers>
</DataTemplate>
'@
$reviewTemplateXaml = @'
<DataTemplate xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
              xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <Border x:Name="ReviewCard" Padding="14" Margin="0,0,0,12" CornerRadius="16" Background="#FAFBFD" BorderBrush="#E0E6EF" BorderThickness="1">
        <StackPanel>
            <TextBlock Text="{Binding Title}" FontSize="14" FontWeight="SemiBold" Foreground="#1F1F1F" TextWrapping="Wrap"/>
            <TextBlock Text="{Binding Subtitle}" Margin="0,8,0,0" FontSize="12" Foreground="#6A5B4D" TextWrapping="Wrap"/>
            <TextBlock Text="{Binding MetaText}" Margin="0,8,0,0" FontSize="11" Foreground="#7A746D" TextWrapping="Wrap"/>
        </StackPanel>
    </Border>
    <DataTemplate.Triggers>
        <DataTrigger Binding="{Binding RelativeSource={RelativeSource AncestorType=ListBoxItem}, Path=IsSelected}" Value="True">
            <Setter TargetName="ReviewCard" Property="BorderBrush" Value="#D96E3C"/>
            <Setter TargetName="ReviewCard" Property="BorderThickness" Value="2"/>
            <Setter TargetName="ReviewCard" Property="Background" Value="#FFF7EE"/>
        </DataTrigger>
    </DataTemplate.Triggers>
</DataTemplate>
'@
$script:UI.QueueListBox.ItemTemplate = [System.Windows.Markup.XamlReader]::Parse($queueTemplateXaml)
$script:UI.ReviewListBox.ItemTemplate = [System.Windows.Markup.XamlReader]::Parse($reviewTemplateXaml)
$modernFolderDialog = New-Object System.Windows.Forms.FolderBrowserDialog
$modernFolderDialog.Description = "Choose the root folder that contains the images you want to clean"

Refresh-Ui

$script:UI.ChooseFolderButton.Add_Click({
    if ($modernFolderDialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        Load-LibraryIntoUi -RootPath $modernFolderDialog.SelectedPath
    }
})

$script:UI.RescanButton.Add_Click({
    if (-not $script:State.LibraryRoot) {
        Show-Message -Message "Choose a folder first." -Title "No Folder Selected" -Icon Warning
        return
    }

    Load-LibraryIntoUi -RootPath $script:State.LibraryRoot
})

$script:UI.QueueListBox.Add_SelectionChanged({
    $selectedQueue = $script:UI.QueueListBox.SelectedItem
    if ($selectedQueue) {
        $script:State.CurrentQueueId = $selectedQueue.Id
        Refresh-ReviewList
        Update-ReviewWorkspace
    }
})

$script:UI.ReviewSearchTextBox.Add_TextChanged({
    Refresh-ReviewList
    Update-ReviewWorkspace
})

$script:UI.ReviewListBox.Add_SelectionChanged({
    Update-ReviewWorkspace
})

$script:UI.ReviewListBox.Add_MouseDoubleClick({
    [void](Invoke-OpenCurrentPreview)
})

$modernWindow.Add_PreviewKeyDown({
    param($sender, $eventArgs)

    if ($eventArgs.OriginalSource -is [System.Windows.Controls.Primitives.TextBoxBase]) {
        return
    }

    switch ($eventArgs.Key) {
        ([System.Windows.Input.Key]::Delete) {
            if (Invoke-ActiveSinglePhotoDelete) {
                $eventArgs.Handled = $true
            }
        }
        ([System.Windows.Input.Key]::N) {
            if (Move-ToNextEntry) {
                $eventArgs.Handled = $true
            }
        }
        ([System.Windows.Input.Key]::Right) {
            if (Move-ToNextEntry) {
                $eventArgs.Handled = $true
            }
        }
        ([System.Windows.Input.Key]::K) {
            if (Invoke-KeepCurrentEntry) {
                $eventArgs.Handled = $true
            }
        }
        ([System.Windows.Input.Key]::Enter) {
            if (Invoke-OpenCurrentPreview) {
                $eventArgs.Handled = $true
            }
        }
    }
})

$script:UI.DeletePairLeftButton.Add_Click({ [void](Invoke-DeleteCurrentPair -Mode Left) })
$script:UI.DeletePairRightButton.Add_Click({ [void](Invoke-DeleteCurrentPair -Mode Right) })
$script:UI.DeletePairBothButton.Add_Click({ [void](Invoke-DeleteCurrentPair -Mode Both) })
$script:UI.KeepPairButton.Add_Click({ [void](Invoke-KeepCurrentEntry) })
$script:UI.NextPairButton.Add_Click({ [void](Move-ToNextEntry) })
$script:UI.OpenPairCompareButton.Add_Click({ [void](Invoke-OpenCurrentPreview) })
$script:UI.DeleteSingleButton.Add_Click({ [void](Invoke-ActiveSinglePhotoDelete) })
$script:UI.KeepSingleButton.Add_Click({ [void](Invoke-KeepCurrentEntry) })
$script:UI.NextSingleButton.Add_Click({ [void](Move-ToNextEntry) })
$script:UI.OpenSinglePreviewButton.Add_Click({ [void](Invoke-OpenCurrentPreview) })

if ($InitialLibraryPath -and (Test-Path -LiteralPath $InitialLibraryPath -PathType Container)) {
    Load-LibraryIntoUi -RootPath $InitialLibraryPath
}

[void]$modernWindow.ShowDialog()
return

$mainXaml = @'
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="Image Cleanup Assistant"
        Width="1400"
        Height="900"
        MinWidth="1180"
        MinHeight="780"
        Background="#F3EFE8"
        FontFamily="Segoe UI"
        WindowStartupLocation="CenterScreen">
    <Grid Margin="20">
        <Grid.RowDefinitions>
            <RowDefinition Height="Auto"/>
            <RowDefinition Height="Auto"/>
            <RowDefinition Height="*"/>
            <RowDefinition Height="Auto"/>
        </Grid.RowDefinitions>

        <Border Grid.Row="0" Background="#FFFBF4" CornerRadius="22" Padding="22">
            <Grid>
                <Grid.ColumnDefinitions>
                    <ColumnDefinition Width="*"/>
                    <ColumnDefinition Width="Auto"/>
                    <ColumnDefinition Width="Auto"/>
                </Grid.ColumnDefinitions>
                <StackPanel Margin="0,0,20,0">
                    <TextBlock Text="Image Cleanup Assistant" FontSize="30" FontWeight="SemiBold" Foreground="#1E1E1E"/>
                    <TextBlock Text="Remove unwanted local images by reviewing focused cleanup queues like exact duplicates, similar images, blurry photos, forwards, screenshots, and text-heavy graphics." Margin="0,8,0,0" FontSize="14" TextWrapping="Wrap" Foreground="#5D5148"/>
                    <TextBlock x:Name="LibraryPathText" Margin="0,12,0,0" FontSize="13" TextWrapping="Wrap" Foreground="#8B6E57" Text="No folder selected yet."/>
                </StackPanel>
                <Button x:Name="ChooseFolderButton" Grid.Column="1" Width="170" Height="42" Margin="0,0,12,0" Content="Choose Folder" Background="#D96E3C" Foreground="White" BorderBrush="#D96E3C"/>
                <Button x:Name="RescanButton" Grid.Column="2" Width="110" Height="42" Content="Rescan"/>
            </Grid>
        </Border>

        <Border Grid.Row="1" Margin="0,16,0,16" Background="White" CornerRadius="18" Padding="16">
            <Grid>
                <Grid.ColumnDefinitions>
                    <ColumnDefinition Width="*"/>
                    <ColumnDefinition Width="220"/>
                </Grid.ColumnDefinitions>
                <TextBlock x:Name="SummaryText" FontSize="13" FontWeight="SemiBold" Foreground="#454545" TextWrapping="Wrap" Text="Choose a folder to begin."/>
                <ProgressBar x:Name="ScanProgress" Grid.Column="1" Height="12" VerticalAlignment="Center" Visibility="Collapsed" Minimum="0" Maximum="1" Value="0"/>
            </Grid>
        </Border>

        <TabControl x:Name="MainTabs" Grid.Row="2">
            <TabItem x:Name="OverviewTab" Header="Overview">
                <UniformGrid Columns="3" Rows="2" Margin="20">
                    <Border Background="#F4F8FF" CornerRadius="18" Padding="18" Margin="0,0,12,12">
                        <StackPanel>
                            <TextBlock Text="Exact Duplicates" FontSize="18" FontWeight="SemiBold" Foreground="#2E4A67"/>
                            <TextBlock x:Name="OverviewExactDuplicateCount" Margin="0,14,0,0" FontSize="30" FontWeight="SemiBold" Text="0 pair(s)"/>
                            <TextBlock Margin="0,8,0,0" FontSize="12" Foreground="#5B6672" TextWrapping="Wrap" Text="Byte-identical files so you can safely remove extra copies."/>
                            <Button x:Name="OpenExactDuplicatesButton" Margin="0,18,0,0" Height="40" Content="Review Exact Duplicates" IsEnabled="False"/>
                        </StackPanel>
                    </Border>
                    <Border Background="#FFF8EE" CornerRadius="18" Padding="18" Margin="0,0,12,0">
                        <StackPanel>
                            <TextBlock Text="Duplicate Images" FontSize="18" FontWeight="SemiBold" Foreground="#5C3E28"/>
                            <TextBlock x:Name="OverviewDuplicateCount" Margin="0,14,0,0" FontSize="30" FontWeight="SemiBold" Text="0 pair(s)"/>
                            <TextBlock Margin="0,8,0,0" FontSize="12" Foreground="#6A584A" TextWrapping="Wrap" Text="Compare both copies side by side and delete the weaker one."/>
                            <Button x:Name="OpenDuplicatesButton" Margin="0,18,0,0" Height="40" Content="Review Duplicates" IsEnabled="False"/>
                        </StackPanel>
                    </Border>
                    <Border Background="#F3F8FF" CornerRadius="18" Padding="18" Margin="6,0,6,0">
                        <StackPanel>
                            <TextBlock Text="Blurry Photos" FontSize="18" FontWeight="SemiBold" Foreground="#344A67"/>
                            <TextBlock x:Name="OverviewBlurryCount" Margin="0,14,0,0" FontSize="30" FontWeight="SemiBold" Text="0 photo(s)"/>
                            <TextBlock Margin="0,8,0,0" FontSize="12" Foreground="#5B6672" TextWrapping="Wrap" Text="Review images flagged for low sharpness and weak edge detail."/>
                            <Button x:Name="OpenBlurryButton" Margin="0,18,0,0" Height="40" Content="Review Blurry Photos" IsEnabled="False"/>
                        </StackPanel>
                    </Border>
                    <Border Background="#FFF5F1" CornerRadius="18" Padding="18" Margin="12,0,0,0">
                        <StackPanel>
                            <TextBlock Text="Likely Forwards" FontSize="18" FontWeight="SemiBold" Foreground="#6A3B2B"/>
                            <TextBlock x:Name="OverviewForwardCount" Margin="0,14,0,0" FontSize="30" FontWeight="SemiBold" Text="0 photo(s)"/>
                            <TextBlock Margin="0,8,0,0" FontSize="12" Foreground="#6B594F" TextWrapping="Wrap" Text="Holiday greetings, quote cards, good-morning images, and similar forwards."/>
                            <Button x:Name="OpenForwardButton" Margin="0,18,0,0" Height="40" Content="Review Likely Forwards" IsEnabled="False"/>
                        </StackPanel>
                    </Border>
                    <Border Background="#F5FBF2" CornerRadius="18" Padding="18" Margin="0,12,12,0">
                        <StackPanel>
                            <TextBlock Text="Screenshots" FontSize="18" FontWeight="SemiBold" Foreground="#416040"/>
                            <TextBlock x:Name="OverviewScreenshotCount" Margin="0,14,0,0" FontSize="30" FontWeight="SemiBold" Text="0 photo(s)"/>
                            <TextBlock Margin="0,8,0,0" FontSize="12" Foreground="#5C6B5A" TextWrapping="Wrap" Text="Phone and desktop screenshots, captured screens, and similar UI images."/>
                            <Button x:Name="OpenScreenshotsButton" Margin="0,18,0,0" Height="40" Content="Review Screenshots" IsEnabled="False"/>
                        </StackPanel>
                    </Border>
                    <Border Background="#FFF9F1" CornerRadius="18" Padding="18" Margin="6,12,6,0">
                        <StackPanel>
                            <TextBlock Text="Text-Heavy Images" FontSize="18" FontWeight="SemiBold" Foreground="#6D4F2D"/>
                            <TextBlock x:Name="OverviewTextHeavyCount" Margin="0,14,0,0" FontSize="30" FontWeight="SemiBold" Text="0 photo(s)"/>
                            <TextBlock Margin="0,8,0,0" FontSize="12" Foreground="#6A5C4A" TextWrapping="Wrap" Text="Quote cards, posters, greetings, flyers, and other image-based text content."/>
                            <Button x:Name="OpenTextHeavyButton" Margin="0,18,0,0" Height="40" Content="Review Text-Heavy Images" IsEnabled="False"/>
                        </StackPanel>
                    </Border>
                    <Border Background="#F7F7F7" CornerRadius="18" Padding="18" Margin="12,12,0,0">
                        <StackPanel>
                            <TextBlock Text="How To Use" FontSize="18" FontWeight="SemiBold" Foreground="#3F3F3F"/>
                            <TextBlock Margin="0,14,0,0" FontSize="13" Foreground="#5E5E5E" TextWrapping="Wrap" Text="Pick one queue, review each image or pair, delete what you do not want, and keep moving forward. Deleted files go to the Recycle Bin."/>
                        </StackPanel>
                    </Border>
                </UniformGrid>
            </TabItem>

            <TabItem x:Name="ExactDuplicateTab" Header="Exact Duplicates (0)">
                <Grid Margin="20">
                    <Grid.ColumnDefinitions>
                        <ColumnDefinition Width="360"/>
                        <ColumnDefinition Width="18"/>
                        <ColumnDefinition Width="*"/>
                    </Grid.ColumnDefinitions>
                    <ListBox x:Name="ExactDuplicateListBox" Grid.Column="0" BorderThickness="0">
                        <ListBox.ItemTemplate>
                            <DataTemplate>
                                <Border Padding="12" Margin="0,0,0,10" CornerRadius="14" Background="#EEF4FB" BorderBrush="#D5E2F0" BorderThickness="1">
                                    <StackPanel>
                                        <TextBlock FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="{Binding Label}"/>
                                        <TextBlock Margin="0,6,0,0" FontSize="12" Foreground="#49627D" TextWrapping="Wrap" Text="{Binding RecommendationText}"/>
                                    </StackPanel>
                                </Border>
                            </DataTemplate>
                        </ListBox.ItemTemplate>
                    </ListBox>
                    <Grid Grid.Column="2">
                        <Grid.RowDefinitions>
                            <RowDefinition Height="Auto"/>
                            <RowDefinition Height="*"/>
                            <RowDefinition Height="Auto"/>
                        </Grid.RowDefinitions>
                        <TextBlock x:Name="ExactDuplicateInfo" FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="No exact duplicate pair selected."/>
                        <Grid Grid.Row="1" Margin="0,16,0,16">
                            <Grid.ColumnDefinitions>
                                <ColumnDefinition Width="*"/>
                                <ColumnDefinition Width="16"/>
                                <ColumnDefinition Width="*"/>
                            </Grid.ColumnDefinitions>
                            <Grid Grid.Column="0" Background="#F0F5FA">
                                <Image x:Name="ExactDuplicateLeftImage" Stretch="Uniform"/>
                                <TextBlock x:Name="ExactDuplicateLeftPlaceholder" Text="Left preview" HorizontalAlignment="Center" VerticalAlignment="Center" Foreground="#8A8A8A"/>
                            </Grid>
                            <Grid Grid.Column="2" Background="#F0F5FA">
                                <Image x:Name="ExactDuplicateRightImage" Stretch="Uniform"/>
                                <TextBlock x:Name="ExactDuplicateRightPlaceholder" Text="Right preview" HorizontalAlignment="Center" VerticalAlignment="Center" Foreground="#8A8A8A"/>
                            </Grid>
                        </Grid>
                        <UniformGrid Grid.Row="2" Columns="5">
                            <Button x:Name="DeleteExactLeftButton" Margin="0,0,8,0" Height="40" Content="Delete Left" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="DeleteExactRightButton" Margin="0,0,8,0" Height="40" Content="Delete Right" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="DeleteExactBothButton" Margin="0,0,8,0" Height="40" Content="Delete Both" Background="#8E3B28" Foreground="White" BorderBrush="#8E3B28"/>
                            <Button x:Name="KeepExactDuplicateButton" Margin="0,0,8,0" Height="40" Content="Keep Pair"/>
                            <Button x:Name="NextExactDuplicateButton" Height="40" Content="Next Pair"/>
                        </UniformGrid>
                    </Grid>
                </Grid>
            </TabItem>

            <TabItem x:Name="DuplicateTab" Header="Duplicate Images (0)">
                <Grid Margin="20">
                    <Grid.ColumnDefinitions>
                        <ColumnDefinition Width="360"/>
                        <ColumnDefinition Width="18"/>
                        <ColumnDefinition Width="*"/>
                    </Grid.ColumnDefinitions>
                    <ListBox x:Name="DuplicateListBox" Grid.Column="0" BorderThickness="0">
                        <ListBox.ItemTemplate>
                            <DataTemplate>
                                <Border Padding="12" Margin="0,0,0,10" CornerRadius="14" Background="#FFF7EE" BorderBrush="#EDD9C0" BorderThickness="1">
                                    <StackPanel>
                                        <TextBlock FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="{Binding Label}"/>
                                        <TextBlock Margin="0,6,0,0" FontSize="12" Foreground="#7A5336" TextWrapping="Wrap" Text="{Binding RecommendationText}"/>
                                    </StackPanel>
                                </Border>
                            </DataTemplate>
                        </ListBox.ItemTemplate>
                    </ListBox>
                    <Grid Grid.Column="2">
                        <Grid.RowDefinitions>
                            <RowDefinition Height="Auto"/>
                            <RowDefinition Height="*"/>
                            <RowDefinition Height="Auto"/>
                        </Grid.RowDefinitions>
                        <TextBlock x:Name="DuplicateInfo" FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="No duplicate pair selected."/>
                        <Grid Grid.Row="1" Margin="0,16,0,16">
                            <Grid.ColumnDefinitions>
                                <ColumnDefinition Width="*"/>
                                <ColumnDefinition Width="16"/>
                                <ColumnDefinition Width="*"/>
                            </Grid.ColumnDefinitions>
                            <Grid Grid.Column="0" Background="#F8F4EC">
                                <Image x:Name="DuplicateLeftImage" Stretch="Uniform"/>
                                <TextBlock x:Name="DuplicateLeftPlaceholder" Text="Left preview" HorizontalAlignment="Center" VerticalAlignment="Center" Foreground="#8A8A8A"/>
                            </Grid>
                            <Grid Grid.Column="2" Background="#F8F4EC">
                                <Image x:Name="DuplicateRightImage" Stretch="Uniform"/>
                                <TextBlock x:Name="DuplicateRightPlaceholder" Text="Right preview" HorizontalAlignment="Center" VerticalAlignment="Center" Foreground="#8A8A8A"/>
                            </Grid>
                        </Grid>
                        <UniformGrid Grid.Row="2" Columns="5">
                            <Button x:Name="DeleteLeftButton" Margin="0,0,8,0" Height="40" Content="Delete Left" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="DeleteRightButton" Margin="0,0,8,0" Height="40" Content="Delete Right" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="DeleteBothButton" Margin="0,0,8,0" Height="40" Content="Delete Both" Background="#8E3B28" Foreground="White" BorderBrush="#8E3B28"/>
                            <Button x:Name="KeepDuplicateButton" Margin="0,0,8,0" Height="40" Content="Keep Pair"/>
                            <Button x:Name="NextDuplicateButton" Height="40" Content="Next Pair"/>
                        </UniformGrid>
                    </Grid>
                </Grid>
            </TabItem>

            <TabItem x:Name="BlurryTab" Header="Blurry Photos (0)">
                <Grid Margin="20">
                    <Grid.ColumnDefinitions>
                        <ColumnDefinition Width="360"/>
                        <ColumnDefinition Width="18"/>
                        <ColumnDefinition Width="*"/>
                    </Grid.ColumnDefinitions>
                    <ListBox x:Name="BlurryListBox" Grid.Column="0" BorderThickness="0">
                        <ListBox.ItemTemplate>
                            <DataTemplate>
                                <Border Padding="12" Margin="0,0,0,10" CornerRadius="14" Background="#F1F6FF" BorderBrush="#D7E2F3" BorderThickness="1">
                                    <StackPanel>
                                        <TextBlock FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="{Binding Name}"/>
                                        <TextBlock Margin="0,6,0,0" FontSize="12" Foreground="#4F6178" TextWrapping="Wrap" Text="{Binding BlurText}"/>
                                    </StackPanel>
                                </Border>
                            </DataTemplate>
                        </ListBox.ItemTemplate>
                    </ListBox>
                    <Grid Grid.Column="2">
                        <Grid.RowDefinitions>
                            <RowDefinition Height="Auto"/>
                            <RowDefinition Height="*"/>
                            <RowDefinition Height="Auto"/>
                        </Grid.RowDefinitions>
                        <TextBlock x:Name="BlurryInfo" FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="No blurry photo selected."/>
                        <Grid Grid.Row="1" Margin="0,16,0,16" Background="#EEF3FA">
                            <Image x:Name="BlurryPreviewImage" Stretch="Uniform"/>
                            <TextBlock x:Name="BlurryPreviewPlaceholder" Text="Preview unavailable" HorizontalAlignment="Center" VerticalAlignment="Center" Foreground="#8A8A8A"/>
                        </Grid>
                        <UniformGrid Grid.Row="2" Columns="4">
                            <Button x:Name="DeleteBlurryButton" Margin="0,0,8,0" Height="40" Content="Delete Photo" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="KeepBlurryButton" Margin="0,0,8,0" Height="40" Content="Keep Photo"/>
                            <Button x:Name="NextBlurryButton" Margin="0,0,8,0" Height="40" Content="Next Photo"/>
                            <Button x:Name="OpenBlurryPreviewButton" Height="40" Content="Open Large Preview"/>
                        </UniformGrid>
                    </Grid>
                </Grid>
            </TabItem>

            <TabItem x:Name="ForwardTab" Header="Likely Forwards (0)">
                <Grid Margin="20">
                    <Grid.ColumnDefinitions>
                        <ColumnDefinition Width="360"/>
                        <ColumnDefinition Width="18"/>
                        <ColumnDefinition Width="*"/>
                    </Grid.ColumnDefinitions>
                    <ListBox x:Name="ForwardListBox" Grid.Column="0" BorderThickness="0">
                        <ListBox.ItemTemplate>
                            <DataTemplate>
                                <Border Padding="12" Margin="0,0,0,10" CornerRadius="14" Background="#FFF1E8" BorderBrush="#F0D6C7" BorderThickness="1">
                                    <StackPanel>
                                        <TextBlock FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="{Binding Name}"/>
                                        <TextBlock Margin="0,6,0,0" FontSize="12" Foreground="#6B4F3A" TextWrapping="Wrap" Text="Likely greeting, quote, or shareable forward image"/>
                                    </StackPanel>
                                </Border>
                            </DataTemplate>
                        </ListBox.ItemTemplate>
                    </ListBox>
                    <Grid Grid.Column="2">
                        <Grid.RowDefinitions>
                            <RowDefinition Height="Auto"/>
                            <RowDefinition Height="*"/>
                            <RowDefinition Height="Auto"/>
                        </Grid.RowDefinitions>
                        <TextBlock x:Name="ForwardInfo" FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="No likely forward selected."/>
                        <Grid Grid.Row="1" Margin="0,16,0,16" Background="#FFF4EC">
                            <Image x:Name="ForwardPreviewImage" Stretch="Uniform"/>
                            <TextBlock x:Name="ForwardPreviewPlaceholder" Text="Preview unavailable" HorizontalAlignment="Center" VerticalAlignment="Center" Foreground="#8A8A8A"/>
                        </Grid>
                        <UniformGrid Grid.Row="2" Columns="4">
                            <Button x:Name="DeleteForwardButton" Margin="0,0,8,0" Height="40" Content="Delete Photo" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="KeepForwardButton" Margin="0,0,8,0" Height="40" Content="Keep Photo"/>
                            <Button x:Name="NextForwardButton" Margin="0,0,8,0" Height="40" Content="Next Photo"/>
                            <Button x:Name="OpenForwardPreviewButton" Height="40" Content="Open Large Preview"/>
                        </UniformGrid>
                    </Grid>
                </Grid>
            </TabItem>

            <TabItem x:Name="ScreenshotTab" Header="Screenshots (0)">
                <Grid Margin="20">
                    <Grid.ColumnDefinitions>
                        <ColumnDefinition Width="360"/>
                        <ColumnDefinition Width="18"/>
                        <ColumnDefinition Width="*"/>
                    </Grid.ColumnDefinitions>
                    <ListBox x:Name="ScreenshotListBox" Grid.Column="0" BorderThickness="0">
                        <ListBox.ItemTemplate>
                            <DataTemplate>
                                <Border Padding="12" Margin="0,0,0,10" CornerRadius="14" Background="#EEF8F0" BorderBrush="#D4E5D6" BorderThickness="1">
                                    <StackPanel>
                                        <TextBlock FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="{Binding Name}"/>
                                        <TextBlock Margin="0,6,0,0" FontSize="12" Foreground="#547055" TextWrapping="Wrap" Text="Likely screenshot or captured screen image"/>
                                    </StackPanel>
                                </Border>
                            </DataTemplate>
                        </ListBox.ItemTemplate>
                    </ListBox>
                    <Grid Grid.Column="2">
                        <Grid.RowDefinitions>
                            <RowDefinition Height="Auto"/>
                            <RowDefinition Height="*"/>
                            <RowDefinition Height="Auto"/>
                        </Grid.RowDefinitions>
                        <TextBlock x:Name="ScreenshotInfo" FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="No screenshot selected."/>
                        <Grid Grid.Row="1" Margin="0,16,0,16" Background="#F4FAF3">
                            <Image x:Name="ScreenshotPreviewImage" Stretch="Uniform"/>
                            <TextBlock x:Name="ScreenshotPreviewPlaceholder" Text="Preview unavailable" HorizontalAlignment="Center" VerticalAlignment="Center" Foreground="#8A8A8A"/>
                        </Grid>
                        <UniformGrid Grid.Row="2" Columns="4">
                            <Button x:Name="DeleteScreenshotButton" Margin="0,0,8,0" Height="40" Content="Delete Photo" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="KeepScreenshotButton" Margin="0,0,8,0" Height="40" Content="Keep Photo"/>
                            <Button x:Name="NextScreenshotButton" Margin="0,0,8,0" Height="40" Content="Next Photo"/>
                            <Button x:Name="OpenScreenshotPreviewButton" Height="40" Content="Open Large Preview"/>
                        </UniformGrid>
                    </Grid>
                </Grid>
            </TabItem>

            <TabItem x:Name="TextHeavyTab" Header="Text-Heavy Images (0)">
                <Grid Margin="20">
                    <Grid.ColumnDefinitions>
                        <ColumnDefinition Width="360"/>
                        <ColumnDefinition Width="18"/>
                        <ColumnDefinition Width="*"/>
                    </Grid.ColumnDefinitions>
                    <ListBox x:Name="TextHeavyListBox" Grid.Column="0" BorderThickness="0">
                        <ListBox.ItemTemplate>
                            <DataTemplate>
                                <Border Padding="12" Margin="0,0,0,10" CornerRadius="14" Background="#FFF6ED" BorderBrush="#E8D9C8" BorderThickness="1">
                                    <StackPanel>
                                        <TextBlock FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="{Binding Name}"/>
                                        <TextBlock Margin="0,6,0,0" FontSize="12" Foreground="#7A5A3E" TextWrapping="Wrap" Text="Likely poster, quote card, greeting, or other text-heavy graphic"/>
                                    </StackPanel>
                                </Border>
                            </DataTemplate>
                        </ListBox.ItemTemplate>
                    </ListBox>
                    <Grid Grid.Column="2">
                        <Grid.RowDefinitions>
                            <RowDefinition Height="Auto"/>
                            <RowDefinition Height="*"/>
                            <RowDefinition Height="Auto"/>
                        </Grid.RowDefinitions>
                        <TextBlock x:Name="TextHeavyInfo" FontSize="14" FontWeight="SemiBold" TextWrapping="Wrap" Text="No text-heavy image selected."/>
                        <Grid Grid.Row="1" Margin="0,16,0,16" Background="#FFF8F0">
                            <Image x:Name="TextHeavyPreviewImage" Stretch="Uniform"/>
                            <TextBlock x:Name="TextHeavyPreviewPlaceholder" Text="Preview unavailable" HorizontalAlignment="Center" VerticalAlignment="Center" Foreground="#8A8A8A"/>
                        </Grid>
                        <UniformGrid Grid.Row="2" Columns="4">
                            <Button x:Name="DeleteTextHeavyButton" Margin="0,0,8,0" Height="40" Content="Delete Photo" Background="#B84C2E" Foreground="White" BorderBrush="#B84C2E"/>
                            <Button x:Name="KeepTextHeavyButton" Margin="0,0,8,0" Height="40" Content="Keep Photo"/>
                            <Button x:Name="NextTextHeavyButton" Margin="0,0,8,0" Height="40" Content="Next Photo"/>
                            <Button x:Name="OpenTextHeavyPreviewButton" Height="40" Content="Open Large Preview"/>
                        </UniformGrid>
                    </Grid>
                </Grid>
            </TabItem>
        </TabControl>

        <Border Grid.Row="3" Margin="0,16,0,0" Background="#201E1B" CornerRadius="16" Padding="14">
            <TextBlock x:Name="StatusText" Foreground="#F8F3EA" FontSize="13" Text="Ready. Choose a folder to begin."/>
        </Border>
    </Grid>
</Window>
'@

[xml]$mainXml = $mainXaml
$reader = New-Object System.Xml.XmlNodeReader $mainXml
$window = [System.Windows.Markup.XamlReader]::Load($reader)

$controlNames = @(
    "ChooseFolderButton","RescanButton","LibraryPathText","SummaryText","ScanProgress","MainTabs",
    "OverviewTab","ExactDuplicateTab","DuplicateTab","BlurryTab","ForwardTab","ScreenshotTab","TextHeavyTab",
    "OverviewExactDuplicateCount","OverviewDuplicateCount","OverviewBlurryCount","OverviewForwardCount","OverviewScreenshotCount","OverviewTextHeavyCount",
    "OpenExactDuplicatesButton","OpenDuplicatesButton","OpenBlurryButton","OpenForwardButton","OpenScreenshotsButton","OpenTextHeavyButton",
    "ExactDuplicateListBox","ExactDuplicateInfo","ExactDuplicateLeftImage","ExactDuplicateLeftPlaceholder","ExactDuplicateRightImage","ExactDuplicateRightPlaceholder",
    "DeleteExactLeftButton","DeleteExactRightButton","DeleteExactBothButton","KeepExactDuplicateButton","NextExactDuplicateButton",
    "DuplicateListBox","DuplicateInfo","DuplicateLeftImage","DuplicateLeftPlaceholder","DuplicateRightImage","DuplicateRightPlaceholder",
    "DeleteLeftButton","DeleteRightButton","DeleteBothButton","KeepDuplicateButton","NextDuplicateButton",
    "BlurryListBox","BlurryInfo","BlurryPreviewImage","BlurryPreviewPlaceholder","DeleteBlurryButton","KeepBlurryButton","NextBlurryButton","OpenBlurryPreviewButton",
    "ForwardListBox","ForwardInfo","ForwardPreviewImage","ForwardPreviewPlaceholder","DeleteForwardButton","KeepForwardButton","NextForwardButton","OpenForwardPreviewButton",
    "ScreenshotListBox","ScreenshotInfo","ScreenshotPreviewImage","ScreenshotPreviewPlaceholder","DeleteScreenshotButton","KeepScreenshotButton","NextScreenshotButton","OpenScreenshotPreviewButton",
    "TextHeavyListBox","TextHeavyInfo","TextHeavyPreviewImage","TextHeavyPreviewPlaceholder","DeleteTextHeavyButton","KeepTextHeavyButton","NextTextHeavyButton","OpenTextHeavyPreviewButton",
    "StatusText"
)

foreach ($name in $controlNames) {
    $script:UI[$name] = $window.FindName($name)
}

$folderDialog = New-Object System.Windows.Forms.FolderBrowserDialog
$folderDialog.Description = "Choose the root folder that contains the images you want to clean"

Refresh-Ui

$script:UI.ChooseFolderButton.Add_Click({
    if ($folderDialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        Load-LibraryIntoUi -RootPath $folderDialog.SelectedPath
    }
})

$script:UI.RescanButton.Add_Click({
    if (-not $script:State.LibraryRoot) {
        Show-Message -Message "Choose a folder first." -Title "No Folder Selected" -Icon Warning
        return
    }

    Load-LibraryIntoUi -RootPath $script:State.LibraryRoot
})

$script:UI.OpenExactDuplicatesButton.Add_Click({ $script:UI.MainTabs.SelectedItem = $script:UI.ExactDuplicateTab })
$script:UI.OpenDuplicatesButton.Add_Click({ $script:UI.MainTabs.SelectedItem = $script:UI.DuplicateTab })
$script:UI.OpenBlurryButton.Add_Click({ $script:UI.MainTabs.SelectedItem = $script:UI.BlurryTab })
$script:UI.OpenForwardButton.Add_Click({ $script:UI.MainTabs.SelectedItem = $script:UI.ForwardTab })
$script:UI.OpenScreenshotsButton.Add_Click({ $script:UI.MainTabs.SelectedItem = $script:UI.ScreenshotTab })
$script:UI.OpenTextHeavyButton.Add_Click({ $script:UI.MainTabs.SelectedItem = $script:UI.TextHeavyTab })

$script:UI.ExactDuplicateListBox.Add_SelectionChanged({ Update-ExactDuplicatePreview })
$script:UI.DuplicateListBox.Add_SelectionChanged({ Update-DuplicatePreview })
$script:UI.BlurryListBox.Add_SelectionChanged({ Update-BlurryPreview })
$script:UI.ForwardListBox.Add_SelectionChanged({ Update-ForwardPreview })
$script:UI.ScreenshotListBox.Add_SelectionChanged({ Update-ScreenshotPreview })
$script:UI.TextHeavyListBox.Add_SelectionChanged({ Update-TextHeavyPreview })

$window.Add_PreviewKeyDown({
    param($sender, $eventArgs)

    if ($eventArgs.Key -ne [System.Windows.Input.Key]::Delete) {
        return
    }

    if (Invoke-ActiveSinglePhotoDelete) {
        $eventArgs.Handled = $true
    }
})

$script:UI.DeleteExactLeftButton.Add_Click({
    $pair = $script:UI.ExactDuplicateListBox.SelectedItem
    if ($pair) { Delete-SelectedPaths -Paths @($pair.First.Path) -Title "Delete Left Photo" -Message "Send this image to the Recycle Bin?`n`n$($pair.First.Path)" }
})

$script:UI.DeleteExactRightButton.Add_Click({
    $pair = $script:UI.ExactDuplicateListBox.SelectedItem
    if ($pair) { Delete-SelectedPaths -Paths @($pair.Second.Path) -Title "Delete Right Photo" -Message "Send this image to the Recycle Bin?`n`n$($pair.Second.Path)" }
})

$script:UI.DeleteExactBothButton.Add_Click({
    $pair = $script:UI.ExactDuplicateListBox.SelectedItem
    if ($pair) { Delete-SelectedPaths -Paths @($pair.First.Path, $pair.Second.Path) -Title "Delete Both Photos" -Message "Send both images to the Recycle Bin?`n`n$($pair.First.Path)`n$($pair.Second.Path)" }
})

$script:UI.KeepExactDuplicateButton.Add_Click({
    $pair = $script:UI.ExactDuplicateListBox.SelectedItem
    if ($pair) {
        $script:State.ExactDuplicatePairs = @($script:State.ExactDuplicatePairs | Where-Object { $_ -ne $pair })
        Refresh-Ui
        Set-Status "Kept both files and removed the pair from the exact-duplicate queue."
    }
})

$script:UI.NextExactDuplicateButton.Add_Click({
    if (($script:UI.ExactDuplicateListBox.SelectedIndex + 1) -lt @($script:State.ExactDuplicatePairs).Count) {
        $script:UI.ExactDuplicateListBox.SelectedIndex++
    }
})

$script:UI.DeleteLeftButton.Add_Click({
    $pair = $script:UI.DuplicateListBox.SelectedItem
    if ($pair) { Delete-SelectedPaths -Paths @($pair.First.Path) -Title "Delete Left Photo" -Message "Send this image to the Recycle Bin?`n`n$($pair.First.Path)" }
})

$script:UI.DeleteRightButton.Add_Click({
    $pair = $script:UI.DuplicateListBox.SelectedItem
    if ($pair) { Delete-SelectedPaths -Paths @($pair.Second.Path) -Title "Delete Right Photo" -Message "Send this image to the Recycle Bin?`n`n$($pair.Second.Path)" }
})

$script:UI.DeleteBothButton.Add_Click({
    $pair = $script:UI.DuplicateListBox.SelectedItem
    if ($pair) { Delete-SelectedPaths -Paths @($pair.First.Path, $pair.Second.Path) -Title "Delete Both Photos" -Message "Send both images to the Recycle Bin?`n`n$($pair.First.Path)`n$($pair.Second.Path)" }
})

$script:UI.KeepDuplicateButton.Add_Click({
    $pair = $script:UI.DuplicateListBox.SelectedItem
    if ($pair) {
        $script:State.DuplicatePairs = @($script:State.DuplicatePairs | Where-Object { $_ -ne $pair })
        Refresh-Ui
        Set-Status "Kept both images and removed the pair from the duplicate queue."
    }
})

$script:UI.NextDuplicateButton.Add_Click({
    if (($script:UI.DuplicateListBox.SelectedIndex + 1) -lt @($script:State.DuplicatePairs).Count) {
        $script:UI.DuplicateListBox.SelectedIndex++
    }
})

$script:UI.DeleteBlurryButton.Add_Click({
    $item = $script:UI.BlurryListBox.SelectedItem
    if ($item) {
        [void](Invoke-DirectSinglePhotoDeletion -Item $item -ListBox $script:UI.BlurryListBox -StatusMessage "Moved one blurry photo to the Recycle Bin.")
    }
})

$script:UI.KeepBlurryButton.Add_Click({
    $item = $script:UI.BlurryListBox.SelectedItem
    if ($item) {
        $script:State.BlurryItems = @($script:State.BlurryItems | Where-Object { $_.Path -ne $item.Path })
        Refresh-Ui
        Set-Status "Kept the photo and removed it from the blurry queue."
    }
})

$script:UI.NextBlurryButton.Add_Click({
    if (($script:UI.BlurryListBox.SelectedIndex + 1) -lt @($script:State.BlurryItems).Count) {
        $script:UI.BlurryListBox.SelectedIndex++
    }
})

$script:UI.OpenBlurryPreviewButton.Add_Click({
    if ($script:UI.BlurryListBox.SelectedItem) { Show-PreviewWindow -Item $script:UI.BlurryListBox.SelectedItem }
})

$script:UI.DeleteForwardButton.Add_Click({
    $item = $script:UI.ForwardListBox.SelectedItem
    if ($item) {
        [void](Invoke-DirectSinglePhotoDeletion -Item $item -ListBox $script:UI.ForwardListBox -StatusMessage "Moved one likely forward image to the Recycle Bin.")
    }
})

$script:UI.KeepForwardButton.Add_Click({
    $item = $script:UI.ForwardListBox.SelectedItem
    if ($item) {
        $script:State.ForwardItems = @($script:State.ForwardItems | Where-Object { $_.Path -ne $item.Path })
        Refresh-Ui
        Set-Status "Kept the photo and removed it from the likely-forward queue."
    }
})

$script:UI.NextForwardButton.Add_Click({
    if (($script:UI.ForwardListBox.SelectedIndex + 1) -lt @($script:State.ForwardItems).Count) {
        $script:UI.ForwardListBox.SelectedIndex++
    }
})

$script:UI.OpenForwardPreviewButton.Add_Click({
    if ($script:UI.ForwardListBox.SelectedItem) { Show-PreviewWindow -Item $script:UI.ForwardListBox.SelectedItem }
})

$script:UI.DeleteScreenshotButton.Add_Click({
    $item = $script:UI.ScreenshotListBox.SelectedItem
    if ($item) {
        [void](Invoke-DirectSinglePhotoDeletion -Item $item -ListBox $script:UI.ScreenshotListBox -StatusMessage "Moved one screenshot to the Recycle Bin.")
    }
})

$script:UI.KeepScreenshotButton.Add_Click({
    $item = $script:UI.ScreenshotListBox.SelectedItem
    if ($item) {
        $script:State.ScreenshotItems = @($script:State.ScreenshotItems | Where-Object { $_.Path -ne $item.Path })
        Refresh-Ui
        Set-Status "Kept the image and removed it from the screenshot queue."
    }
})

$script:UI.NextScreenshotButton.Add_Click({
    if (($script:UI.ScreenshotListBox.SelectedIndex + 1) -lt @($script:State.ScreenshotItems).Count) {
        $script:UI.ScreenshotListBox.SelectedIndex++
    }
})

$script:UI.OpenScreenshotPreviewButton.Add_Click({
    if ($script:UI.ScreenshotListBox.SelectedItem) { Show-PreviewWindow -Item $script:UI.ScreenshotListBox.SelectedItem }
})

$script:UI.DeleteTextHeavyButton.Add_Click({
    $item = $script:UI.TextHeavyListBox.SelectedItem
    if ($item) {
        [void](Invoke-DirectSinglePhotoDeletion -Item $item -ListBox $script:UI.TextHeavyListBox -StatusMessage "Moved one text-heavy image to the Recycle Bin.")
    }
})

$script:UI.KeepTextHeavyButton.Add_Click({
    $item = $script:UI.TextHeavyListBox.SelectedItem
    if ($item) {
        $script:State.TextHeavyItems = @($script:State.TextHeavyItems | Where-Object { $_.Path -ne $item.Path })
        Refresh-Ui
        Set-Status "Kept the image and removed it from the text-heavy queue."
    }
})

$script:UI.NextTextHeavyButton.Add_Click({
    if (($script:UI.TextHeavyListBox.SelectedIndex + 1) -lt @($script:State.TextHeavyItems).Count) {
        $script:UI.TextHeavyListBox.SelectedIndex++
    }
})

$script:UI.OpenTextHeavyPreviewButton.Add_Click({
    if ($script:UI.TextHeavyListBox.SelectedItem) { Show-PreviewWindow -Item $script:UI.TextHeavyListBox.SelectedItem }
})

if ($InitialLibraryPath -and (Test-Path -LiteralPath $InitialLibraryPath -PathType Container)) {
    Load-LibraryIntoUi -RootPath $InitialLibraryPath
}

[void]$window.ShowDialog()
