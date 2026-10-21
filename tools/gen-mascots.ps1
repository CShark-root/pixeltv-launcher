# Генерирует листы спрайтов в app/src/main/assets/mascots/ и превью .fontpreview/mascots.png
# Запуск: powershell -ExecutionPolicy Bypass -File tools\gen-mascots.ps1
Add-Type -AssemblyName System.Drawing
Add-Type -Path (Join-Path $PSScriptRoot "MascotGen.cs") -ReferencedAssemblies System.Drawing

$root = Join-Path $PSScriptRoot ".."
$out = Join-Path $root "app\src\main\assets\mascots"
New-Item -ItemType Directory -Force $out | Out-Null

# id, вид, тело, тень, светлое, розовое, глаза, полоски, лапки, кончик хвоста, контур
$defs = @(
    @("pinch",    "blob", "DE8460", "BF6A48", "F6B898", "F2A0A0", "1B1A1F", "",       "",       "",       "1B1A1F"),
    @("ember",    "fox",  "F28A3A", "D06A22", "FFF3E4", "F7A0A8", "1B1A1F", "",       "4A2A1C", "FFF6EA", "1B1A1F"),
    @("sprocket", "cat",  "A9A9B4", "8A8A96", "ECECF2", "F2A7B5", "1B1A1F", "7A7A86", "",       "",       "1B1A1F"),
    @("nyx",      "cat",  "3E3D48", "2E2D36", "5A5966", "D88AA0", "F2C14E", "",       "",       "",       "0E0D12"),
    @("mango",    "cat",  "F2A250", "D8832E", "FFE9CF", "F4A0A6", "1B1A1F", "D4741E", "",       "",       "1B1A1F"),
    @("frost",    "cat",  "F7F7FA", "D9D9E3", "FFFFFF", "F6A9BC", "4A9BEA", "",       "",       "",       "1B1A1F")
)
$sheets = @()
foreach ($d in $defs) {
    $pal = [MascotGen]::MakePal($d[2], $d[3], $d[4], $d[5], $d[6], $d[7], $d[8], $d[9], $d[10])
    $sheet = [MascotGen]::Sheet($d[1], $pal)
    $sheet.Save((Join-Path $out "$($d[0]).png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $sheets += ,@($d[0], $sheet)
}
Set-Content -Encoding ascii (Join-Path $out "frames.txt") ("{0} {1}`n{2}" -f [MascotGen]::W, [MascotGen]::H, ([MascotGen]::Frames -join " "))

# Превью: все листы друг под другом, увеличенные в 4 раза, на тёмном фоне.
$scale = 8
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
