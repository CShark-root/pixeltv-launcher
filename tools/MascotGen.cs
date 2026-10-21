// Генератор спрайтов маскотов PixelDen — уютный чиби-стиль.
// Большая круглая голова, огромные глаза с бликами, румянец, короткие лапки, пушистый хвост.
// Холст 64x48 без сглаживания, контур 1 px — тёмный оттенок шерсти, а не чёрный.
// Все смотрят вправо; лаунчер отражает их сам, когда они идут влево.
using System;
using System.Drawing;
using System.Drawing.Drawing2D;

public static class MascotGen
{
    public const int W = 64, H = 48, Ground = 46;

    public static readonly string[] Frames = {
        "sit0", "sit1", "blink", "happy", "look", "lookblink",
        "walk0", "walk1", "walk2", "walk3", "walk4", "walk5", "walk6", "walk7",
        "jump", "sleep0", "sleep1",
    };

    public class Pal
    {
        public Color Body, Shade, Light, Accent, Eye, EyeHi, Outline, Stripe, Extra, Nose, Blush, Socks, Shine;
        public bool Stripes, Fox;
    }

    static Color C(string hex) { return ColorTranslator.FromHtml("#" + hex); }
    static bool Has(string s) { return !string.IsNullOrEmpty(s); }
    static Color Mix(Color a, Color b, float t)
    {
        return Color.FromArgb(255, (int)(a.R + (b.R - a.R) * t), (int)(a.G + (b.G - a.G) * t), (int)(a.B + (b.B - a.B) * t));
    }

    /// Палитра: тело, тень, светлое (грудь/щёки), розовое (уши/нос), глаза, [полоски], [кончик хвоста], [носочки].
    public static Pal MakePal(string body, string shade, string light, string pink, string eye,
                              string stripe = null, string tip = null, string socks = null, bool fox = false)
    {
        Color b = C(body), sh = C(shade);
        return new Pal {
            Body = b, Shade = sh, Light = C(light), Accent = C(pink), Eye = C(eye), EyeHi = Color.White,
            // Мягкий контур: тень, утянутая к тёмно-сливовому, а не к чёрному.
            Outline = Mix(sh, C("2A1E2E"), 0.55f),
            Stripe = Has(stripe) ? C(stripe) : sh, Stripes = Has(stripe),
            Extra = Has(tip) ? C(tip) : C(light), Socks = Has(socks) ? C(socks) : b,
            Nose = C(pink), Blush = Mix(C(pink), b, 0.25f), Shine = Mix(b, Color.White, 0.35f), Fox = fox,
        };
    }

    // ------------------------------------------------------------------ примитивы

    static void Ell(Graphics g, Color c, float cx, float cy, float rx, float ry)
    {
        using (var br = new SolidBrush(c)) g.FillEllipse(br, cx - rx, cy - ry, rx * 2, ry * 2);
    }

    static void Rect(Graphics g, Color c, float x, float y, float w, float h)
    {
        using (var br = new SolidBrush(c)) g.FillRectangle(br, x, y, w, h);
    }

    static void Poly(Graphics g, Color c, params float[] xy)
    {
        var pts = new PointF[xy.Length / 2];
        for (int i = 0; i < pts.Length; i++) pts[i] = new PointF(xy[i * 2], xy[i * 2 + 1]);
        using (var br = new SolidBrush(c)) g.FillPolygon(br, pts);
    }

    static void Curve(Graphics g, Color c, float width, params float[] xy)
    {
        var pts = new PointF[xy.Length / 2];
        for (int i = 0; i < pts.Length; i++) pts[i] = new PointF(xy[i * 2], xy[i * 2 + 1]);
        using (var pen = new Pen(c, width)) { pen.StartCap = LineCap.Round; pen.EndCap = LineCap.Round; pen.LineJoin = LineJoin.Round; g.DrawCurve(pen, pts, 0.6f); }
    }

    static void Px(Bitmap b, Color c, int x, int y) { if (x >= 0 && y >= 0 && x < W && y < H) b.SetPixel(x, y, c); }

    // ------------------------------------------------------------------ лицо

    enum EyeKind { Open, Closed, Happy }

    /// Большой блестящий глаз 4x5: два блика — главный сверху и маленький снизу.
    static void BigEye(Bitmap bmp, Graphics g, Pal p, int x, int y, EyeKind k)
    {
        switch (k)
        {
            case EyeKind.Open:
                Rect(g, p.Eye, x, y + 1, 4, 4);
                Rect(g, p.Eye, x + 1, y, 2, 6);
                Px(bmp, p.EyeHi, x + 1, y + 1); Px(bmp, p.EyeHi, x + 2, y + 1); Px(bmp, p.EyeHi, x + 1, y + 2);
                Px(bmp, p.EyeHi, x + 3, y + 4);
                break;
            case EyeKind.Closed: // спокойная дуга «︶»
                Px(bmp, p.Outline, x, y + 3); Px(bmp, p.Outline, x + 1, y + 4); Px(bmp, p.Outline, x + 2, y + 4); Px(bmp, p.Outline, x + 3, y + 3);
                break;
            case EyeKind.Happy:  // радостная дуга «^»
                Px(bmp, p.Outline, x, y + 3); Px(bmp, p.Outline, x + 1, y + 2); Px(bmp, p.Outline, x + 2, y + 2); Px(bmp, p.Outline, x + 3, y + 3);
                break;
        }
    }

    static void Blush(Graphics g, Pal p, float x, float y) { Ell(g, p.Blush, x, y, 2.5f, 1.5f); }

    /// Ротик «ω» в 1 px.
    static void Mouth(Bitmap bmp, Pal p, int x, int y, bool open)
    {
        Px(bmp, p.Outline, x, y); Px(bmp, p.Outline, x + 1, y + 1); Px(bmp, p.Outline, x + 2, y); Px(bmp, p.Outline, x + 3, y + 1); Px(bmp, p.Outline, x + 4, y);
        if (open) { Px(bmp, p.Accent, x + 2, y + 1); Px(bmp, p.Accent, x + 2, y + 2); }
    }

    // ------------------------------------------------------------------ голова в профиль

    static void Head(Bitmap bmp, Graphics g, Pal p, float hx, float hy, EyeKind eye, bool mouthOpen = false)
    {
        bool fox = p.Fox;
        float earH = fox ? 16 : 13;
        // дальнее ухо
        Poly(g, p.Shade, hx - 11, hy - 4, hx - 9, hy - earH + 2, hx - 3, hy - 9);
        // ближнее ухо
        Poly(g, p.Body, hx - 3, hy - 9, hx + 1, hy - earH, hx + 6, hy - 8);
        Poly(g, p.Accent, hx - 1, hy - 9, hx + 1, hy - earH + 4, hx + 4, hy - 9);
        // большая круглая голова
        Ell(g, p.Body, hx, hy, 14, 11.5f);
        Ell(g, p.Shine, hx - 5, hy - 6, 4, 2);
        if (fox)
        {
            // мордочка вперёд и белые щёки
            Ell(g, p.Light, hx + 6, hy + 5, 8, 5);
            Poly(g, p.Light, hx + 8, hy + 1, hx + 17, hy + 4, hx + 9, hy + 8);
            Rect(g, p.Nose, hx + 15, hy + 3, 3, 2);
        }
        else
        {
            Ell(g, p.Light, hx + 8, hy + 5, 5, 3.5f);
            Rect(g, p.Nose, hx + 12, hy + 2, 2, 2);
            if (p.Stripes) { Rect(g, p.Stripe, hx - 3, hy - 11, 1, 3); Rect(g, p.Stripe, hx, hy - 11, 1, 3); Rect(g, p.Stripe, hx - 7, hy - 9, 1, 3); }
        }
        BigEye(bmp, g, p, (int)hx + 5, (int)hy - 3, eye);
        Blush(g, p, hx + 4, hy + 5);
        Mouth(bmp, p, (int)hx + (fox ? 11 : 9), (int)hy + 6, mouthOpen);
    }

    /// Голова анфас — маскот поворачивается к зрителю.
    static void FrontHead(Bitmap bmp, Graphics g, Pal p, float hx, float hy, bool blink)
    {
        bool fox = p.Fox;
        float earH = fox ? 16 : 13;
        Poly(g, p.Body, hx - 13, hy - 3, hx - 10, hy - earH, hx - 3, hy - 9);
        Poly(g, p.Body, hx + 13, hy - 3, hx + 10, hy - earH, hx + 3, hy - 9);
        Poly(g, p.Accent, hx - 11, hy - 6, hx - 9, hy - earH + 4, hx - 6, hy - 8);
        Poly(g, p.Accent, hx + 11, hy - 6, hx + 9, hy - earH + 4, hx + 6, hy - 8);
        Ell(g, p.Body, hx, hy, 14, 11.5f);
        Ell(g, p.Shine, hx - 5, hy - 7, 4, 2);
        if (fox) { Ell(g, p.Light, hx - 6, hy + 5, 6, 4); Ell(g, p.Light, hx + 6, hy + 5, 6, 4); Ell(g, p.Light, hx, hy + 6, 4, 3); }
        else
        {
            Ell(g, p.Light, hx, hy + 5, 6, 3.5f);
            if (p.Stripes) { Rect(g, p.Stripe, hx - 1, hy - 11, 1, 3); Rect(g, p.Stripe, hx + 1, hy - 11, 1, 3); Rect(g, p.Stripe, hx - 4, hy - 10, 1, 2); Rect(g, p.Stripe, hx + 4, hy - 10, 1, 2); }
        }
        Rect(g, p.Nose, hx - 1, hy + 2, 3, 2);
        BigEye(bmp, g, p, (int)hx - 9, (int)hy - 4, blink ? EyeKind.Closed : EyeKind.Open);
        BigEye(bmp, g, p, (int)hx + 5, (int)hy - 4, blink ? EyeKind.Closed : EyeKind.Open);
        Blush(g, p, hx - 9, hy + 4);
        Blush(g, p, hx + 9, hy + 4);
        Mouth(bmp, p, (int)hx - 2, (int)hy + 5, false);
    }

    // ------------------------------------------------------------------ хвост

    static void Tail(Graphics g, Pal p, float bx, float by, float sway)
    {
        float w = p.Fox ? 9 : 6;
        float tx = bx - 6 + sway * 2, ty = by - 16;
        Curve(g, p.Body, w, bx, by, bx - 7, by - 5, bx - 8 + sway, by - 11, tx, ty);
        if (p.Fox) Ell(g, p.Extra, tx, ty - 1, 4, 4);
        else
        {
            Ell(g, p.Shine, bx - 7 + sway * 0.5f, by - 8, 1.2f, 2);
            if (p.Stripes) { Ell(g, p.Stripe, bx - 7, by - 6, 2.2f, 0.8f); Ell(g, p.Stripe, bx - 8 + sway, by - 11, 2.2f, 0.8f); }
        }
    }

    // ------------------------------------------------------------------ позы

    static void Sit(Bitmap bmp, Graphics g, Pal p, int breath, EyeKind eye, float sway, int look)
    {
        float b = breath;
        Tail(g, p, 20, 40, sway);
        // пухлое тельце
        Ell(g, p.Shade, 28, 40, 12, 7);
        Ell(g, p.Body, 28, 38 - b * 0.5f, 12, 8.5f);
        Ell(g, p.Light, 34, 40 - b * 0.5f, 6, 5);
        if (p.Stripes) { Rect(g, p.Stripe, 20, 33, 3, 1); Rect(g, p.Stripe, 19, 36, 3, 1); }
        // лапки-булочки
        Ell(g, p.Socks, 31, Ground - 1.5f, 3.5f, 2.5f);
        Ell(g, p.Fox ? p.Socks : p.Light, 37, Ground - 1.5f, 3.5f, 2.5f);
        Ell(g, p.Shade, 21, Ground - 1.5f, 4, 2);
        // голова
        if (look > 0) FrontHead(bmp, g, p, 33, 18 - b, look == 2);
        else Head(bmp, g, p, 35, 18 - b, eye, eye == EyeKind.Happy);
    }

    static void Walk(Bitmap bmp, Graphics g, Pal p, int phase, bool jump)
    {
        double a = phase * Math.PI / 4;
        float bob = jump ? -2 : (float)Math.Round(Math.Abs(Math.Sin(a)) * -1.4);
        float by = 35 + bob;
        float s = jump ? 0 : (float)Math.Sin(a);
        // дальние лапки (темнее)
        Ell(g, p.Shade, 18 - s * 2.5f, jump ? by + 7 : Ground - 2, 3, 3);
        Ell(g, p.Shade, 34 + s * 2.5f, jump ? by + 7 : Ground - 2, 3, 3);
        Tail(g, p, 14, by - 2, (float)Math.Sin(a * 0.5) * 1.6f);
        // тельце-фасолинка
        Ell(g, p.Shade, 26, by + 2, 13, 6.5f);
        Ell(g, p.Body, 26, by, 13, 7);
        Ell(g, p.Light, 28, by + 4, 9, 2.5f);
        if (p.Stripes) { Rect(g, p.Stripe, 19, by - 6, 1, 3); Rect(g, p.Stripe, 23, by - 7, 1, 3); Rect(g, p.Stripe, 27, by - 7, 1, 3); }
        // ближние лапки
        Ell(g, p.Socks, 20 + s * 2.5f, jump ? by + 8 : Ground - 2, 3, 3);
        Ell(g, p.Socks, 36 - s * 2.5f, jump ? by + 8 : Ground - 2, 3, 3);
        Head(bmp, g, p, 41, 19 + bob, jump ? EyeKind.Happy : EyeKind.Open, jump);
    }

    static void Sleep(Bitmap bmp, Graphics g, Pal p, int breath)
    {
        float b = breath;
        // калачик
        Ell(g, p.Shade, 28, 41, 17, 6);
        Ell(g, p.Body, 27, 39 - b * 0.5f, 16, 7 + b * 0.5f);
        Ell(g, p.Shine, 22, 35 - b, 5, 1.5f);
        if (p.Stripes) { Rect(g, p.Stripe, 18, 34 - b, 1, 3); Rect(g, p.Stripe, 23, 33 - b, 1, 3); Rect(g, p.Stripe, 28, 34 - b, 1, 3); }
        // хвост-одеялко
        Curve(g, p.Body, p.Fox ? 8 : 5, 12, 41, 20, Ground - 1, 34, Ground - 1, 44, Ground - 3);
        if (p.Fox) Ell(g, p.Extra, 45, Ground - 3, 4, 3);
        // голова на лапках
        float hx = 42, hy = 33 - b;
        Poly(g, p.Shade, hx - 10, hy - 3, hx - 8, hy - 12, hx - 3, hy - 7);
        Poly(g, p.Body, hx - 3, hy - 7, hx + 1, hy - 14, hx + 5, hy - 6);
        Poly(g, p.Accent, hx - 1, hy - 7, hx + 1, hy - 11, hx + 3, hy - 7);
        Ell(g, p.Body, hx, hy, 11, 9);
        Ell(g, p.Shine, hx - 4, hy - 5, 3, 1.5f);
        if (p.Fox) { Ell(g, p.Light, hx + 5, hy + 4, 6, 3.5f); Rect(g, p.Nose, hx + 10, hy + 2, 2, 2); }
        else { Ell(g, p.Light, hx + 6, hy + 4, 4, 3); Rect(g, p.Nose, hx + 9, hy + 1, 2, 2); }
        BigEye(bmp, g, p, (int)hx + 2, (int)hy - 3, EyeKind.Closed);
        Blush(g, p, hx + 3, hy + 4);
    }

    // ------------------------------------------------------------------ Pinch (кирпичик в духе Claude)

    static void Blob(Bitmap bmp, Graphics g, Pal p, string frame)
    {
        bool walk = frame.StartsWith("walk");
        int phase = walk ? int.Parse(frame.Substring(4)) : 0;
        int breath = frame == "sit1" ? 1 : 0;
        bool jump = frame == "jump";
        bool sleep = frame.StartsWith("sleep");
        double a = phase * Math.PI / 4;
        float lift = jump ? -5 : walk ? (float)Math.Round(Math.Abs(Math.Sin(a)) * -1.5) : 0;
        float top = (sleep ? 25 : 16) + lift + breath + (sleep && frame == "sleep1" ? 1 : 0);
        float bottom = sleep ? Ground - 1 : 39 + lift;

        if (!sleep)
        {
            float[] xs = { 19, 26, 38, 45 };
            for (int i = 0; i < 4; i++)
            {
                float sw = walk ? (float)Math.Sin(a + (i % 2 == 0 ? 0 : Math.PI)) * 2.5f : 0;
                Color c = i % 2 == 0 ? p.Shade : p.Body;
                float y2 = jump ? bottom + 5 : Ground - 1;
                Rect(g, c, xs[i] - 1.5f + sw, bottom - 1, 3, y2 - bottom + 1);
            }
        }
        float armY = top + 9 + (jump ? -8 : 0) + breath;
        Rect(g, p.Shade, 8, armY, 6, 5);
        Rect(g, p.Body, 50, armY, 6, 5);
        // тело со скруглёнными углами
        Rect(g, p.Body, 14, top, 36, bottom - top);
        Rect(g, p.Body, 12, top + 2, 40, bottom - top - 4);
        Rect(g, p.Shade, 14, bottom - 3, 36, 3);
        Rect(g, p.Shine, 16, top + 2, 10, 2);
        // лицо
        if (frame == "look" || frame == "lookblink")
        {
            if (frame == "look") { BigEye(bmp, g, p, 22, (int)top + 5, EyeKind.Open); BigEye(bmp, g, p, 38, (int)top + 5, EyeKind.Open); }
            else { BigEye(bmp, g, p, 22, (int)top + 5, EyeKind.Closed); BigEye(bmp, g, p, 38, (int)top + 5, EyeKind.Closed); }
            Blush(g, p, 21, top + 13); Blush(g, p, 43, top + 13);
            Mouth(bmp, p, 30, (int)top + 13, false);
        }
        else
        {
            EyeKind k = sleep || frame == "blink" ? EyeKind.Closed : (frame == "happy" || jump) ? EyeKind.Happy : EyeKind.Open;
            BigEye(bmp, g, p, 40, (int)top + 5, k);
            Blush(g, p, 45, top + 13);
        }
    }

    // ------------------------------------------------------------------ сборка

    public static Bitmap Frame(string species, Pal p, string frame)
    {
        var bmp = new Bitmap(W, H);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.None;
            g.PixelOffsetMode = PixelOffsetMode.Half;
            g.InterpolationMode = InterpolationMode.NearestNeighbor;
            g.Clear(Color.Transparent);
            if (species == "blob") Blob(bmp, g, p, frame);
            else if (frame == "sit0") Sit(bmp, g, p, 0, EyeKind.Open, 0, 0);
            else if (frame == "sit1") Sit(bmp, g, p, 1, EyeKind.Open, 1, 0);
            else if (frame == "blink") Sit(bmp, g, p, 0, EyeKind.Closed, 0, 0);
            else if (frame == "happy") Sit(bmp, g, p, 1, EyeKind.Happy, 1, 0);
            else if (frame == "look") Sit(bmp, g, p, 0, EyeKind.Open, 0, 1);
            else if (frame == "lookblink") Sit(bmp, g, p, 0, EyeKind.Open, 0, 2);
            else if (frame.StartsWith("walk")) Walk(bmp, g, p, int.Parse(frame.Substring(4)), false);
            else if (frame == "jump") Walk(bmp, g, p, 0, true);
            else if (frame == "sleep0") Sleep(bmp, g, p, 0);
            else if (frame == "sleep1") Sleep(bmp, g, p, 1);
        }
        return Outline(bmp, p.Outline);
    }

    /// Контур 1 px вокруг силуэта и чистка полупрозрачных пикселей.
    static Bitmap Outline(Bitmap src, Color outline)
    {
        var dst = new Bitmap(W, H);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
            {
                Color c = src.GetPixel(x, y);
                if (c.A > 127) { dst.SetPixel(x, y, Color.FromArgb(255, c.R, c.G, c.B)); continue; }
                bool edge = false;
                for (int d = 0; d < 4 && !edge; d++)
                {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (nx >= 0 && ny >= 0 && nx < W && ny < H && src.GetPixel(nx, ny).A > 127) edge = true;
                }
                dst.SetPixel(x, y, edge ? outline : Color.Transparent);
            }
        return dst;
    }

    public static Bitmap Sheet(string species, Pal p)
    {
        var sheet = new Bitmap(W * Frames.Length, H);
        using (var g = Graphics.FromImage(sheet))
        {
            g.InterpolationMode = InterpolationMode.NearestNeighbor;
            g.PixelOffsetMode = PixelOffsetMode.Half;
            for (int i = 0; i < Frames.Length; i++)
                using (var f = Frame(species, p, Frames[i])) g.DrawImage(f, i * W, 0, W, H);
        }
        return sheet;
    }
}
