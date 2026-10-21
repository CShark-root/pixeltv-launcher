// Генератор спрайтов маскотов PixelDen — «фасолинки» с чёрным контуром.
// Рисуется ЛЕВАЯ половина вручную (символьная сетка), правая — зеркало. Затем позы
// (ручки, лапки, хвост, глаза), авто-тень справа, контур 1 px и мягкое свечение для тёмного фона.
using System;
using System.Collections.Generic;
using System.Drawing;

public static class MascotGen
{
    public const int W = 32, H = 32;

    public static readonly string[] Frames = {
        "sit0", "sit1", "half", "blink", "crouch", "happy", "look", "lookblink",
        "walk0", "walk1", "walk2", "walk3", "walk4", "walk5", "walk6", "walk7",
        "jump", "sleep0", "sleep1",
    };

    // Левая половина, 11 колонок (колонки 0–1 — место под ручку, её рисует код).
    // o тело, w светлое, p розовое, e глаз (ставит код), m контурные детали, f лапки, z полоски, k тёмный акцент
    static readonly string[] CAT = {
        "....o......",
        "....oo.....",
        "....opo....",
        "....oppoooo",
        "...oooooozo",
        "..ooooooooz",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..oppooomom",
        "..ooooooomo",
        "...oooooooo",
        "....ooowwww",
        "....zoowwww",
        "....oowwwww",
        "....zowwwww",
        "....ooowwww",
        "....ooowwww",
        "....ooooooo",
        "....ooooooo",
        "....ooooooo",
        "....fff....",
        "....fff....",
        "....fff....",
    };

    static readonly string[] FOX = {
        "...k.......",
        "...ko......",
        "...ooo.....",
        "...opoo....",
        "...oppoo...",
        "...opppoooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..wwoooowww",
        "..wwwwwwwwm",
        "...wwwwwwww",
        "....oowwwww",
        "....oowwwww",
        "....oowwwww",
        "....ooowwww",
        "....ooowwww",
        "....ooowwww",
        "....ooooooo",
        "....ooooooo",
        "....ooooooo",
        "....fff....",
        "....fff....",
        "....fff....",
    };

    static readonly string[] BLOB = {
        "...oooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..oppoooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "..ooooooooo",
        "...oooooooo",
        "...ff..ff..",
        "...ff..ff..",
        "...ff..ff..",
    };

    class Species
    {
        public string[] Half;
        public int EyeRow, EyeX, EyeW, EyeH;   // левый глаз; правый — зеркало
        public int ArmRow;                     // плечо (верх ручки)
        public int LegRow;                     // первая строка лапок
        public int[] Legs;                     // пары (x, ширина) левых лапок
        public int TailRow;                    // строка основания хвоста
        public int BodyX;                      // левый край туловища (к нему крепятся ручки и хвост)
        public string Tail;                    // cat / fox / null
        public int BreathRow;                  // строка, которую «съедает» вдох
    }

    static readonly Dictionary<string, Species> SPECIES = new Dictionary<string, Species> {
        { "cat",  new Species { Half = CAT,  EyeRow = 7, EyeX = 5, EyeW = 2, EyeH = 2, ArmRow = 13, LegRow = 22, Legs = new[] { 4, 3 },       TailRow = 20, BodyX = 4, Tail = "cat", BreathRow = 18 } },
        { "fox",  new Species { Half = FOX,  EyeRow = 8, EyeX = 5, EyeW = 2, EyeH = 2, ArmRow = 14, LegRow = 23, Legs = new[] { 4, 3 },       TailRow = 21, BodyX = 4, Tail = "fox", BreathRow = 19 } },
        { "blob", new Species { Half = BLOB, EyeRow = 4, EyeX = 5, EyeW = 1, EyeH = 3, ArmRow = 8,  LegRow = 14, Legs = new[] { 3, 2, 7, 2 }, TailRow = 12, BodyX = 2, Tail = null,  BreathRow = 11 } },
    };

    // ------------------------------------------------------------------ палитра

    public class Pal
    {
        public Dictionary<char, Color> C = new Dictionary<char, Color>();
        public Color Outline;
    }

    static Color Hex(string h) { return ColorTranslator.FromHtml("#" + h); }

    /// тело, тень, светлое, розовое, глаза, полоски(""), лапки(""), кончик хвоста(""), контур
    public static Pal MakePal(string body, string shade, string light, string pink, string eye,
                              string stripe, string feet, string tip, string outline)
    {
        var p = new Pal();
        p.C['o'] = Hex(body); p.C['s'] = Hex(shade); p.C['w'] = Hex(light); p.C['p'] = Hex(pink);
        p.C['e'] = Hex(eye);
        p.C['z'] = string.IsNullOrEmpty(stripe) ? Hex(body) : Hex(stripe);
        p.C['f'] = string.IsNullOrEmpty(feet) ? Hex(body) : Hex(feet);
        p.C['x'] = string.IsNullOrEmpty(tip) ? Hex(body) : Hex(tip);
        p.C['t'] = Hex(body); p.C['a'] = Hex(body);
        p.Outline = Hex(outline);
        p.C['m'] = p.Outline; p.C['k'] = p.Outline;
        p.C['h'] = Color.White;
        return p;
    }

    // ------------------------------------------------------------------ сетка

    class Grid
    {
        public char[,] G = new char[H, W];
        public Grid() { for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) G[y, x] = '.'; }
        public char this[int x, int y]
        {
            get { return x >= 0 && y >= 0 && x < W && y < H ? G[y, x] : '.'; }
            set { if (x >= 0 && y >= 0 && x < W && y < H) G[y, x] = value; }
        }
    }

    const int OX = 4;
    static int Mir(int x) { return OX + 21 - (x - OX); }   // зеркальная колонка

    static int Top(Species sp) { return H - 2 - sp.Half.Length; }

    static Grid Build(Species sp, int top)
    {
        var g = new Grid();
        for (int r = 0; r < sp.Half.Length; r++)
            for (int c = 0; c < 11; c++)
            {
                char ch = c < sp.Half[r].Length ? sp.Half[r][c] : '.';
                g[OX + c, top + r] = ch;
                g[Mir(OX + c), top + r] = ch;
            }
        return g;
    }

    // ------------------------------------------------------------------ части

    static void Eyes(Grid g, Species sp, int top, string kind, int side = -1)
    {
        for (int s = 0; s < 2; s++)
        {
            if (side >= 0 && s != side) continue;
            int x0 = s == 0 ? OX + sp.EyeX : Mir(OX + sp.EyeX) - (sp.EyeW - 1);
            int y0 = top + sp.EyeRow;
            for (int dy = 0; dy < sp.EyeH; dy++) for (int dx = 0; dx < sp.EyeW; dx++) g[x0 + dx, y0 + dy] = 'o';
            int yl = y0 + sp.EyeH - 1;
            switch (kind)
            {
                case "open":
                    for (int dy = 0; dy < sp.EyeH; dy++) for (int dx = 0; dx < sp.EyeW; dx++) g[x0 + dx, y0 + dy] = 'e';
                    break;
                case "closed":
                    g[x0 - 1, yl] = 'm'; for (int dx = 0; dx < sp.EyeW; dx++) g[x0 + dx, yl] = 'm'; g[x0 + sp.EyeW, yl] = 'm';
                    break;
                case "half": // верх глаза прикрыт веком
                    for (int dx = 0; dx < sp.EyeW; dx++) g[x0 + dx, yl] = 'e';
                    for (int dx = 0; dx < sp.EyeW; dx++) g[x0 + dx, yl - 1] = 'm';
                    break;
                case "happy": // ^
                    g[x0 - 1, yl] = 'm'; for (int dx = 0; dx < sp.EyeW; dx++) g[x0 + dx, yl - 1] = 'm'; g[x0 + sp.EyeW, yl] = 'm';
                    break;
            }
        }
    }

    /// Ручка. pose: 0 — опущена вдоль тела, 1 — чуть вперёд (шаг), 2 — поднята, 3 — поднята выше (машет).
    static void Arm(Grid g, Species sp, int top, int side, int pose)
    {
        int y = top + sp.ArmRow;
        // клетки левой ручки (x относительно OX), правую получаем зеркалом
        int[,] cells;
        switch (pose)
        {
            case 1: cells = new[,] { { 1, 0 }, { 1, 1 }, { 0, 1 }, { 0, 2 }, { -1, 2 }, { -1, 3 } }; break;
            case 2: cells = new[,] { { 1, 0 }, { 1, -1 }, { 0, -1 }, { 0, -2 }, { -1, -2 }, { -1, -3 } }; break;
            case 3: cells = new[,] { { 1, 0 }, { 1, -1 }, { 1, -2 }, { 0, -2 }, { 0, -3 }, { 0, -4 } }; break;
            default: cells = new[,] { { 1, 0 }, { 1, 1 }, { 1, 2 }, { 0, 1 }, { 0, 2 }, { 0, 3 } }; break;
        }
        for (int i = 0; i < cells.GetLength(0); i++)
        {
            int x = OX + sp.BodyX - 2 + cells[i, 0], yy = y + cells[i, 1];
            g[side == 0 ? x : Mir(x), yy] = 'a';
        }
    }

    static void Legs(Grid g, Species sp, int top, int liftLeft, int liftRight)
    {
        int y0 = top + sp.LegRow;
        int rows = sp.Half.Length - sp.LegRow;
        for (int i = 0; i + 1 < sp.Legs.Length; i += 2)
            for (int s = 0; s < 2; s++)
            {
                int lift = s == 0 ? liftLeft : liftRight;
                if (lift == 0) continue;
                for (int dx = 0; dx < sp.Legs[i + 1]; dx++)
                {
                    int x = OX + sp.Legs[i] + dx;
                    if (s == 1) x = Mir(x);
                    for (int r = 0; r < rows; r++) g[x, y0 + r] = '.';
                    for (int r = 0; r < rows - lift; r++) g[x, y0 + r] = 'f';
                }
            }
    }

    /// Хвост-крючок справа от тела, толщиной 2 (у лиса — 3, с белым кончиком).
    static void Tail(Grid g, Species sp, int top, int wag, bool up)
    {
        if (sp.Tail == null) return;
        if (sp.Tail == "fox") { FoxTail(g, sp, top, wag, up); return; }
        bool fox = false;
        int t = fox ? 3 : 2;
        int bx = Mir(OX + sp.BodyX) + 1;   // сразу за правым боком туловища
        int by = top + sp.TailRow;
        int len = 3, rise = up ? 7 : 5;
        for (int dx = 0; dx < len; dx++) for (int k = 0; k < t; k++) g[bx + dx, by - k + (t - 2)] = 't';
        for (int dy = 1; dy <= rise; dy++)
        {
            int sh = wag == 1 && dy > rise - 3 ? 1 : 0;
            bool tip = dy > rise - (fox ? 3 : 2);
            for (int k = 0; k < t; k++) g[bx + len - 1 + k - (t - 2) + sh - (t > 2 ? 1 : 0), by - dy + (t - 2)] = tip ? 'x' : 't';
        }
    }

    /// Пушистый лисий хвост: выходит из-за бока, расширяется кверху, белый кончик.
    /// Строки — снизу вверх; пара (от, до) — колонки относительно правого бока туловища.
    static void FoxTail(Grid g, Species sp, int top, int wag, bool up)
    {
        int bx = Mir(OX + sp.BodyX) + 2;   // за опущенной ручкой, без шва
        int by = top + sp.TailRow + 1;
        int[,] rows = {
            { 0, 2 },   // основание у бока
            { 0, 3 },
            { 1, 4 },
            { 1, 5 },
            { 2, 5 },
            { 2, 5 },   // дальше — белый кончик
            { 2, 5 },
            { 3, 4 },
        };
        int n = rows.GetLength(0);
        int lift = up ? 1 : 0;
        for (int i = 0; i < n; i++)
        {
            int sh = wag == 1 && i >= 4 ? 1 : 0;    // верх хвоста качается
            bool tip = i >= n - 3;
            for (int dx = rows[i, 0]; dx <= rows[i, 1]; dx++)
                g[bx + dx + sh, by - i - lift] = tip ? 'x' : 't';
        }
    }

    static void OpenMouth(Grid g, Species sp, int top)
    {
        int y = top + sp.EyeRow + 4;
        g[OX + 10, y] = 'p'; g[Mir(OX + 10), y] = 'p';
    }

    static void ShiftDown(Grid g, int fromRow, int rows)
    {
        for (int n = 0; n < rows; n++)
        {
            for (int y = fromRow; y > 0; y--) for (int x = 0; x < W; x++) g[x, y] = g[x, y - 1];
            for (int x = 0; x < W; x++) g[x, 0] = '.';
        }
    }

    static void Hop(Grid g, int up)
    {
        for (int n = 0; n < up; n++)
        {
            for (int y = 0; y < H - 1; y++) for (int x = 0; x < W; x++) g[x, y] = g[x, y + 1];
            for (int x = 0; x < W; x++) g[x, H - 1] = '.';
        }
    }

    // ------------------------------------------------------------------ позы

    static Grid Pose(string species, string frame)
    {
        var sp = SPECIES[species];
        int top = Top(sp);
        var g = Build(sp, top);
        int armL = 0, armR = 0, wag = 0, squash = 0, hop = 0;
        bool tailUp = false;
        string eyes = "open";

        switch (frame)
        {
            case "sit1": wag = 1; break;                      // только хвост, тело не дёргается
            case "half": eyes = "half"; break;
            case "crouch": squash = 1; break;
            case "blink": eyes = "closed"; break;
            case "happy": eyes = "happy"; armL = 2; armR = 2; tailUp = true; wag = 1; OpenMouth(g, sp, top); break;
            case "look": armR = 3; tailUp = true; break;
            case "lookblink": armR = 2; tailUp = true; wag = 1; break;
            case "jump": eyes = "happy"; armL = 2; armR = 2; tailUp = true; hop = 1; Legs(g, sp, top, 1, 1); OpenMouth(g, sp, top); break;
            case "sleep0": eyes = "closed"; squash = 1; break;
            case "sleep1": eyes = "closed"; squash = 2; break;
        }
        if (frame.StartsWith("walk"))
        {
            int ph = int.Parse(frame.Substring(4)) % 4;
            // 0 — опора, 1 — шаг левой, 2 — опора, 3 — шаг правой
            if (ph == 1) { Legs(g, sp, top, 1, 0); armL = 0; armR = 1; hop = 1; wag = 1; }
            if (ph == 3) { Legs(g, sp, top, 0, 1); armL = 1; armR = 0; hop = 1; }
            if (ph == 0 || ph == 2) squash = 1;
            tailUp = ph % 2 == 1;
        }

        Eyes(g, sp, top, eyes);
        if (frame == "lookblink") Eyes(g, sp, top, "closed", 1);   // подмигивает правым глазом
        Arm(g, sp, top, 0, armL);
        Arm(g, sp, top, 1, armR);
        Tail(g, sp, top, wag, tailUp);
        if (squash > 0) ShiftDown(g, top + sp.BreathRow, squash);
        if (hop > 0) Hop(g, hop);
        return g;
    }

    // ------------------------------------------------------------------ отрисовка

    static bool Solid(Grid g, int x, int y) { return g[x, y] != '.'; }

    public static Bitmap Frame(string species, Pal p, string frame)
    {
        var g = Pose(species, frame);
        var bmp = new Bitmap(W, H);
        var outline = new bool[H, W];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
            {
                char c = g[x, y];
                if (c != '.')
                {
                    bool shadeable = c == 'o' || c == 't' || c == 'a' || c == 'z';
                    bool shade = shadeable && !Solid(g, x + 1, y) && x > OX + 10;
                    bmp.SetPixel(x, y, shade ? p.C['s'] : (p.C.ContainsKey(c) ? p.C[c] : p.C['o']));
                    continue;
                }
                bool edge = Solid(g, x + 1, y) || Solid(g, x - 1, y) || Solid(g, x, y + 1) || Solid(g, x, y - 1);
                if (edge) { bmp.SetPixel(x, y, p.Outline); outline[y, x] = true; }
                else bmp.SetPixel(x, y, Color.Transparent);
            }
        // мягкое свечение вокруг контура — силуэт читается на тёмном фоне
        var glow = Color.FromArgb(48, 255, 255, 255);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
            {
                if (bmp.GetPixel(x, y).A != 0) continue;
                bool near = (x + 1 < W && outline[y, x + 1]) || (x > 0 && outline[y, x - 1]) || (y + 1 < H && outline[y + 1, x]) || (y > 0 && outline[y - 1, x]);
                if (near) bmp.SetPixel(x, y, glow);
            }
        return bmp;
    }

    public static Bitmap Sheet(string species, Pal p)
    {
        var sheet = new Bitmap(W * Frames.Length, H);
        for (int i = 0; i < Frames.Length; i++)
            using (var f = Frame(species, p, Frames[i]))
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) sheet.SetPixel(i * W + x, y, f.GetPixel(x, y));
        return sheet;
    }
}
