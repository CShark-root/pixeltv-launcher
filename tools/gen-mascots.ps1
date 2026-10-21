# Генерирует листы спрайтов в app/src/main/assets/mascots/ и превью .fontpreview/mascots.png
# Запуск: powershell -ExecutionPolicy Bypass -File tools\gen-mascots.ps1
Add-Type -AssemblyName System.Drawing
Add-Type -Path (Join-Path $PSScriptRoot "MascotGen.cs") -ReferencedAssemblies System.Drawing

$root = Join-Path $PSScriptRoot ".."
$out = Join-Path $root "app\src\main\assets\mascots"
New-Item -ItemType Directory -Force $out | Out-Null

# id, вид, тело, тень, светлое, розовое, глаза, полоски, кончик хвоста, носочки
$defs = @(
    @("pinch",    "blob", "E08A66", "C46A4A", "FFD9C2", "F49A9A", "3A2430", "",       "",       ""),
    @("ember",    "fox",  "F58A3E", "D86A26", "FFF3E4", "F7A0A8", "3A2420", "",       "FFF6EA", "7A3A22"),
    @("sprocket", "cat",  "A9AAB8", "8A8B9C", "EEEEF4", "F4A3B4", "3A3448", "7C7D8E", "",       ""),
    @("nyx",      "cat",  "4D4860", "3C3850", "7A7590", "E59AB4", "B6E36A", "",       "",       ""),
    @("mango",    "cat",  "F7A856", "E08A3A", "FFEBD2", "F6A0A6", "4A2A1A", "E2862E", "",       ""),
    @("frost",    "cat",  "F6F5FA", "DCDBE8", "FFFFFF", "F8AABE", "4A8EDB", "",       "",       "")
)

$sheets = @()
foreach ($d in $defs) {
    $pal = [MascotGen]::MakePal($d[2], $d[3], $d[4], $d[5], $d[6], $d[7], $d[8], $d[9], ($d[1] -eq "fox"))
    $sheet = [MascotGen]::Sheet($d[1], $pal)
    $sheet.Save((Join-Path $out "$($d[0]).png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $sheets += ,@($d[0], $sheet)
}
Set-Content -Encoding ascii (Join-Path $out "frames.txt") ("{0} {1}`n{2}" -f [MascotGen]::W, [MascotGen]::H, ([MascotGen]::Frames -join " "))

# Превью: все листы друг под другом, увеличенные в 4 раза, на тёмном фоне.
$scale = 4
$pw = [MascotGen]::W * [MascotGen]::Frames.Length * $scale
$ph = [MascotGen]::H * $scale * $sheets.Count
$prev = New-Object System.Drawing.Bitmap $pw, $ph
$g = [System.Drawing.Graphics]::FromImage($prev)
$g.Clear([System.Drawing.Color]::FromArgb(20, 18, 26))
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
for ($i = 0; $i -lt $sheets.Count; $i++) {
    $s = $sheets[$i][1]
    $g.DrawImage($s, 0, $i * [MascotGen]::H * $scale, $s.Width * $scale, $s.Height * $scale)
}
$pp = Join-Path $root ".fontpreview\mascots.png"
$prev.Save($pp)
Write-Output $pp
