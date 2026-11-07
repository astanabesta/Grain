package dev.grain.photo;

/**
 * Additional TextureView transform, AFTER the framework's sensor-orientation transform.
 * Never apply JPEG_ORIENTATION here: doing so rotates portrait preview twice.
 * https://developer.android.com/media/camera/camera2/camera-preview#textureview
 */
public final class PreviewMath {
  private PreviewMath() {}

  public static float[] destinationCorners(
      int viewWidth, int viewHeight, int bufferWidth, int bufferHeight,
      int sensorOrientation, int displayDegrees, double zoom) {
    if(viewWidth<=0||viewHeight<=0||bufferWidth<=0||bufferHeight<=0)
      throw new IllegalArgumentException("Preview dimensions must be positive");
    boolean sensorSwap = Math.floorMod(sensorOrientation,180)!=0;
    float naturalWidth = sensorSwap ? bufferHeight : bufferWidth;
    float naturalHeight = sensorSwap ? bufferWidth : bufferHeight;
    int extraRotation = Math.floorMod(-displayDegrees,360);
    boolean displaySwap = extraRotation==90||extraRotation==270;
    float width = displaySwap ? naturalHeight : naturalWidth;
    float height = displaySwap ? naturalWidth : naturalHeight;
    float scale = (float)(Math.max(viewWidth/width,viewHeight/height)*Framing.zoom(zoom));
    float[] x={0,1,1,0}, y={0,0,1,1}, out=new float[8];
    for(int i=0;i<4;i++){
      float rx=x[i],ry=y[i];
      if(extraRotation==90){rx=1-y[i];ry=x[i];}
      else if(extraRotation==180){rx=1-x[i];ry=1-y[i];}
      else if(extraRotation==270){rx=y[i];ry=1-x[i];}
      out[i*2]=viewWidth/2f+(rx-.5f)*width*scale;
      out[i*2+1]=viewHeight/2f+(ry-.5f)*height*scale;
    }
    return out;
  }

  /**
   * Whether the live preview may be read back right now.
   *
   * <p>Reading a TextureView back needs its hardware layer. The platform throws instead of
   * returning null when it cannot get one, and the window where that happens is right after the
   * view tree changes - closing the menu re-lays out the camera back and rebuilds the preview
   * surface. Every reader of the preview (the histogram, the loupe and the peaking) therefore asks
   * here first, and simply gets nothing on the frames it cannot read.
   *
   * @param nowMs the current uptime, in milliseconds
   * @param settleUntilMs the time the read-back is held off until, in uptime milliseconds
   * @param surfaceAvailable whether the TextureView has a surface yet
   * @param viewWidth the width of the TextureView
   * @param viewHeight the height of the TextureView
   */
  public static boolean canReadBack(
      long nowMs, long settleUntilMs, boolean surfaceAvailable, int viewWidth, int viewHeight) {
    if (!surfaceAvailable) return false;
    if (viewWidth <= 0 || viewHeight <= 0) return false;
    return nowMs >= settleUntilMs;
  }

  /**
   * Whether a bitmap that came back from a read-back is big enough for the pixels the caller is
   * about to read out of it. The platform can hand back a bitmap of a different size when the
   * preview was resized underneath the reader.
   */
  public static boolean fitsTarget(
      int bitmapWidth, int bitmapHeight, int targetWidth, int targetHeight) {
    if (targetWidth <= 0 || targetHeight <= 0) return false;
    return bitmapWidth >= targetWidth && bitmapHeight >= targetHeight;
  }
}
