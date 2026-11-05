package dev.grain.photo;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

/**
 * Lightweight, resolution-independent UI artwork. No Unicode/emoji substitutes for camera icons.
 */
public final class GrainStyle {
  private GrainStyle() {}

  public static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  public static void icon(TextView view, String name, int color, int size) {
    view.setText("");
    view.setCompoundDrawables(null, null, null, null);
    Symbol d = new Symbol(view.getContext(), name, color, size);
    view.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
    view.setPadding(
        Math.max(
                0,
                (view.getLayoutParams() == null
                        ? dp(view.getContext(), 40)
                        : view.getLayoutParams().width)
                    - dp(view.getContext(), size))
            / 2,
        0,
        0,
        0);
  }

  public static void leading(TextView view, String name, int color, int size) {
    view.setCompoundDrawablesWithIntrinsicBounds(
        new Symbol(view.getContext(), name, color, size), null, null, null);
    view.setCompoundDrawablePadding(dp(view.getContext(), 7));
  }

  public static ColorStateList tint(int checked, int unchecked) {
    return new ColorStateList(
        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
        new int[] {checked, unchecked});
  }

  public static final class Symbol extends Drawable {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String kind;
    private final int color, pixels;

    public Symbol(Context c, String kind, int color, int size) {
      this.kind = kind;
      this.color = color;
      pixels = dp(c, size);
    }

    @Override
    public int getIntrinsicWidth() {
      return pixels;
    }

    @Override
    public int getIntrinsicHeight() {
      return pixels;
    }

    @Override
    public void draw(Canvas canvas) {
      canvas.save();
      Rect b = getBounds();
      canvas.translate(b.left, b.top);
      canvas.scale(b.width() / 24f, b.height() / 24f);
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.55f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      switch (kind) {
        case "settings":
          line(canvas, 5, 3, 5, 8);
          line(canvas, 5, 12, 5, 21);
          line(canvas, 12, 3, 12, 13);
          line(canvas, 12, 17, 12, 21);
          line(canvas, 19, 3, 19, 5);
          line(canvas, 19, 9, 19, 21);
          line(canvas, 2, 8, 8, 8);
          line(canvas, 9, 17, 15, 17);
          line(canvas, 16, 5, 22, 5);
          break;
        case "timer":
          canvas.drawCircle(12, 14, 8, p);
          line(canvas, 9, 2, 15, 2);
          line(canvas, 12, 3, 12, 6);
          line(canvas, 18, 8, 20, 6);
          path(canvas, 12, 10, 12, 15, 15, 17);
          break;
        case "flip":
          path(canvas, 20, 7, 17, 4, 12, 3, 7, 4, 3, 8);
          path(canvas, 3, 3, 3, 8, 8, 8);
          path(canvas, 4, 17, 7, 20, 12, 21, 17, 20, 21, 16);
          path(canvas, 21, 21, 21, 16, 16, 16);
          break;
        case "grid":
          for (int x : new int[] {3, 14})
            for (int y : new int[] {3, 14}) canvas.drawRoundRect(x, y, x + 7, y + 7, 1, 1, p);
          break;
        case "thirds":
          line(canvas, 8, 3, 8, 21);
          line(canvas, 16, 3, 16, 21);
          line(canvas, 3, 8, 21, 8);
          line(canvas, 3, 16, 21, 16);
          break;
        case "compare":
          canvas.drawRoundRect(4, 4, 20, 20, 3, 3, p);
          line(canvas, 12, 4, 12, 20);
          line(canvas, 7, 8, 9, 8);
          line(canvas, 7, 12, 9, 12);
          line(canvas, 7, 16, 9, 16);
          break;
        case "sun":
          canvas.drawCircle(12, 12, 4, p);
          for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            line(
                canvas,
                12 + (float) Math.cos(a) * 7,
                12 + (float) Math.sin(a) * 7,
                12 + (float) Math.cos(a) * 9,
                12 + (float) Math.sin(a) * 9);
          }
          break;
        case "close":
          line(canvas, 7, 7, 17, 17);
          line(canvas, 17, 7, 7, 17);
          break;
        case "down":
          path(canvas, 8, 10, 12, 14, 16, 10);
          break;
        case "download":
          path(canvas, 12, 3, 12, 15);
          path(canvas, 7, 10, 12, 15, 17, 10);
          path(canvas, 4, 16, 4, 21, 20, 21, 20, 16);
          break;
        case "camera":
          path(canvas, 8, 5, 6, 8, 3, 8, 3, 20, 21, 20, 21, 8, 18, 8, 16, 5, 8, 5);
          canvas.drawCircle(12, 13, 4, p);
          break;
        case "check":
          path(canvas, 5, 12, 10, 17, 19, 7);
          break;
        case "aperture":
          canvas.drawCircle(12, 12, 7, p);
          line(canvas, 8, 6, 12, 12);
          line(canvas, 19, 11, 12, 12);
          line(canvas, 8, 18, 12, 12);
          break;
        default:
          canvas.drawCircle(12, 12, 7, p);
      }
      canvas.restore();
    }

    private void line(Canvas c, float a, float b, float x, float y) {
      c.drawLine(a, b, x, y, p);
    }

    private void path(Canvas c, float... xy) {
      Path path = new Path();
      path.moveTo(xy[0], xy[1]);
      for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
      c.drawPath(path, p);
    }

    @Override
    public void setAlpha(int alpha) {
      p.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
      p.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  /** The outlined orange shutter from the HTML, including pressed and countdown states. */
  public static final class Shutter extends TextView {
    private final Paint p = new Paint(3);

    public Shutter(Context c) {
      super(c);
      setIncludeFontPadding(false);
      setGravity(android.view.Gravity.CENTER);
      setContentDescription("Take photo");
      setClickable(true);
      setFocusable(true);
    }

    @Override
    protected void onDraw(Canvas c) {
      float x = getWidth() / 2f, y = getHeight() / 2f, r = Math.min(x, y) - dp(getContext(), 2);
      if (isPressed()) {
        c.save();
        c.scale(.95f, .95f, x, y);
      }
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(dp(getContext(), 1));
      p.setColor(0xffb9bcaa);
      c.drawCircle(x, y, r, p);
      p.setColor(0xffe76736);
      c.drawCircle(x, y, r - dp(getContext(), 6), p);
      p.setStyle(Paint.Style.FILL);
      c.drawCircle(x, y, r - dp(getContext(), 9), p);
      String state = getText().toString();
      if (state.matches("[0-9]+")) {
        p.setColor(0xfff4f1e8);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(getContext(), 29));
        p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        c.drawText(state, x, y + dp(getContext(), 10), p);
      } else if ("·".equals(state)) {
        p.setColor(0xfff4f1e8);
        c.drawCircle(x, y, dp(getContext(), 3), p);
      } else {
        Symbol icon = new Symbol(getContext(), "aperture", 0xfff6c2a2, 24);
        icon.setBounds(
            (int) x - dp(getContext(), 12),
            (int) y - dp(getContext(), 12),
            (int) x + dp(getContext(), 12),
            (int) y + dp(getContext(), 12));
        icon.draw(c);
      }
      if (isPressed()) c.restore();
    }

    @Override
    public void setPressed(boolean pressed) {
      super.setPressed(pressed);
      invalidate();
    }
  }

  /** A tiny paper-bordered print, drawn from the real last-shot bitmap. */
  public static final class PhotoThumb extends Drawable {
    private final Bitmap bitmap;
    private final Paint paint = new Paint(3);
    private final float border;

    public PhotoThumb(Context c, Bitmap bitmap) {
      this.bitmap = bitmap;
      border = dp(c, 3);
    }

    @Override
    public void draw(Canvas canvas) {
      Rect b = getBounds();
      paint.setColor(0xfff4f1e8);
      canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, border, border, paint);
      RectF target =
          new RectF(b.left + border, b.top + border, b.right - border, b.bottom - border);
      if (bitmap == null || bitmap.isRecycled()) return;
      float scale =
          Math.max(target.width() / bitmap.getWidth(), target.height() / bitmap.getHeight());
      float w = target.width() / scale, h = target.height() / scale;
      Rect source =
          new Rect(
              (int) ((bitmap.getWidth() - w) / 2),
              (int) ((bitmap.getHeight() - h) / 2),
              (int) ((bitmap.getWidth() + w) / 2),
              (int) ((bitmap.getHeight() + h) / 2));
      canvas.drawBitmap(bitmap, source, target, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1);
      paint.setColor(0xffb8b9aa);
      canvas.drawRoundRect(
          b.left + .5f, b.top + .5f, b.right - .5f, b.bottom - .5f, border, border, paint);
      paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void setAlpha(int a) {
      paint.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter f) {
      paint.setColorFilter(f);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  public static final class RatioShape extends View {
    private final Paint paint = new Paint(3);
    private final float ratio;
    private final int color;

    public RatioShape(Context c, String ratio, int color) {
      super(c);
      this.ratio = (float) Framing.portraitRatio(ratio);
      this.color = color;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas c) {
      float h = Math.min(getHeight() - dp(getContext(), 2), dp(getContext(), 30)),
          w = h * ratio,
          x = (getWidth() - w) / 2,
          y = (getHeight() - h) / 2;
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(getContext(), 1));
      paint.setColor(color);
      c.drawRoundRect(x, y, x + w, y + h, dp(getContext(), 2), dp(getContext(), 2), paint);
    }
  }
}
