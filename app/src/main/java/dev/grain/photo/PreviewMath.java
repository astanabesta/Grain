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
}
